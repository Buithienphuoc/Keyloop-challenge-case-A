#!/usr/bin/env bash
# One-off Azure set-up for the Service Scheduler. Run it in Azure Cloud Shell (Bash):
#
#   curl -fsSL https://raw.githubusercontent.com/Buithienphuoc/Keyloop-challenge-case-A/master/deploy/azure/setup.sh -o setup.sh
#   bash setup.sh
#
# It creates, in one resource group:
#   - Azure Container Registry (Basic)              images pushed by GitHub Actions
#   - PostgreSQL Flexible Server (Burstable B1ms)   the one shared database
#   - Container Apps environment + two apps:
#       scheduler-api  internal only, 1-5 replicas, scales on concurrent HTTP requests
#       scheduler-web  public HTTPS, nginx serving the web app and forwarding /api to scheduler-api
#   - a managed identity that GitHub Actions signs in as (OpenID Connect: no password stored)
#
# Both apps start on a placeholder image; the first push to master deploys the real ones.
# Safe to run again: existing resources are kept, and generated names and passwords are reused
# from ~/.service-scheduler-azure.env. Delete everything with:
#   az group delete -n rg-service-scheduler --yes --no-wait
set -euo pipefail

LOCATION="${LOCATION:-japaneast}" # Azure for Students only allows a few regions
RG="${RG:-rg-service-scheduler}"
GITHUB_REPO="${GITHUB_REPO:-Buithienphuoc/Keyloop-challenge-case-A}"
PG_VERSION="${PG_VERSION:-16}"

STATE="$HOME/.service-scheduler-azure.env"
# shellcheck disable=SC1090
[ -f "$STATE" ] && source "$STATE"
# Cloud Shell may be ephemeral (no home folder kept between sessions), so the state file can be
# gone on a re-run. The suffix is then recovered from the registry already in the resource group,
# and a new database password is generated and applied to the existing server and API (below).
if [ -z "${SUFFIX:-}" ]; then
  existing=$(az acr list -g "$RG" --query "[?starts_with(name, 'scheduler')].name | [0]" -o tsv 2>/dev/null || true)
  SUFFIX="${existing#scheduler}"
  [ -n "$SUFFIX" ] || SUFFIX=$(openssl rand -hex 3)
fi
NEW_PASSWORD=false
if [ -z "${DB_PASSWORD:-}" ]; then
  # Hex plus a capital letter meets PostgreSQL's complexity rule and needs no URL escaping.
  DB_PASSWORD="Pg$(openssl rand -hex 16)"
  NEW_PASSWORD=true
fi
APP_SECRET="${APP_SECRET:-$(openssl rand -hex 32)}"
printf 'SUFFIX=%s\nDB_PASSWORD=%s\nAPP_SECRET=%s\n' "$SUFFIX" "$DB_PASSWORD" "$APP_SECRET" > "$STATE"
chmod 600 "$STATE"

ACR="scheduler${SUFFIX}"
PG="scheduler-db-${SUFFIX}"
CAE="scheduler-env"
IDENTITY="scheduler-github"
PLACEHOLDER="mcr.microsoft.com/k8se/quickstart:latest"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
exists() { "$@" --only-show-errors -o none >/dev/null 2>&1; }

step "Azure CLI extension and resource providers"
az extension add --name containerapp --upgrade --yes --only-show-errors
for ns in Microsoft.App Microsoft.OperationalInsights Microsoft.DBforPostgreSQL Microsoft.ContainerRegistry Microsoft.ManagedIdentity; do
  az provider register --namespace "$ns" --wait
done

step "Resource group $RG ($LOCATION)"
az group create -n "$RG" -l "$LOCATION" -o none

step "Container registry $ACR"
exists az acr show -g "$RG" -n "$ACR" || az acr create -g "$RG" -n "$ACR" -l "$LOCATION" --sku Basic -o none
ACR_SERVER=$(az acr show -g "$RG" -n "$ACR" --query loginServer -o tsv)
# The apps pull with the registry's admin user. Newer Container Apps environments ("express")
# reject a managed identity for registry sign-in, and a user name and password work on every kind.
az acr update -n "$ACR" --admin-enabled true -o none
ACR_USER=$(az acr credential show -n "$ACR" --query username -o tsv)
ACR_PASSWORD=$(az acr credential show -n "$ACR" --query "passwords[0].value" -o tsv)
REGISTRY=(--registry-server "$ACR_SERVER" --registry-username "$ACR_USER" --registry-password "$ACR_PASSWORD")

step "PostgreSQL $PG (takes 5-10 minutes the first time)"
if ! exists az postgres flexible-server show -g "$RG" -n "$PG"; then
  # 0.0.0.0 = "allow Azure services", which is how Container Apps reach it; TLS is required.
  az postgres flexible-server create -g "$RG" -n "$PG" -l "$LOCATION" \
    --tier Burstable --sku-name Standard_B1ms --storage-size 32 --version "$PG_VERSION" \
    --admin-user scheduler --admin-password "$DB_PASSWORD" \
    --public-access 0.0.0.0 --yes -o none
elif [ "$NEW_PASSWORD" = true ]; then
  echo "    (no saved password from an earlier run: setting a new one)"
  az postgres flexible-server update -g "$RG" -n "$PG" --admin-password "$DB_PASSWORD" -o none
fi
# Newer Azure CLI versions only accept --database-name for elastic clusters, so the database is
# created on its own.
exists az postgres flexible-server db show -g "$RG" -s "$PG" -n scheduler ||
  az postgres flexible-server db create -g "$RG" -s "$PG" -n scheduler -o none
# The schema uses btree_gist for its no-double-booking constraints; Azure needs it allow-listed.
az postgres flexible-server parameter set -g "$RG" -s "$PG" --name azure.extensions --value BTREE_GIST,PGCRYPTO -o none
DATABASE_URL="jdbc:postgresql://${PG}.postgres.database.azure.com:5432/scheduler?user=scheduler&password=${DB_PASSWORD}&sslmode=require"

step "Container Apps environment $CAE"
# The environment must be a standard (Consumption) one. Azure can hand out an "express"
# environment instead, and express has no internal service discovery (nginx could not reach
# scheduler-api by name) and fails image pulls after app updates. An express environment left by
# an earlier run is replaced: its apps only hold the placeholder or a re-deployable image, and
# the database lives outside it.
mode=$(az containerapp env show -g "$RG" -n "$CAE" --query properties.environmentMode -o tsv 2>/dev/null || true)
if [ "$(printf '%s' "$mode" | tr '[:upper:]' '[:lower:]')" = express ]; then
  echo "    ($CAE is an express environment: replacing it with a standard one, ~5 minutes)"
  for app in scheduler-api scheduler-web; do
    ! exists az containerapp show -g "$RG" -n "$app" || az containerapp delete -g "$RG" -n "$app" --yes -o none
  done
  az containerapp env delete -g "$RG" -n "$CAE" --yes -o none
fi
exists az containerapp env show -g "$RG" -n "$CAE" ||
  az containerapp env create -g "$RG" -n "$CAE" -l "$LOCATION" --environment-mode ConsumptionOnly -o none

step "API app scheduler-api (internal, 1-5 replicas)"
# Express environments refuse any change to an app that has a managed identity, and the first
# version of this script gave each app one. Such an app still runs the placeholder image, so it
# is simply made again below.
for app in scheduler-api scheduler-web; do
  if [ "$(az containerapp show -g "$RG" -n "$app" --query "identity.type" -o tsv 2>/dev/null || true)" = SystemAssigned ]; then
    echo "    ($app has a managed identity, which express environments reject: making it again)"
    az containerapp delete -g "$RG" -n "$app" --yes -o none
  fi
done
if ! exists az containerapp show -g "$RG" -n scheduler-api; then
  # B1ms allows ~50 connections: 5 replicas x 8 stays under it.
  az containerapp create -g "$RG" -n scheduler-api --environment "$CAE" --image "$PLACEHOLDER" \
    --ingress internal --target-port 80 \
    --cpu 0.5 --memory 1.0Gi --min-replicas 1 --max-replicas 5 \
    --scale-rule-name http-load --scale-rule-type http --scale-rule-http-concurrency 30 \
    "${REGISTRY[@]}" \
    --secrets "database-url=$DATABASE_URL" "app-secret=$APP_SECRET" \
    --env-vars DATABASE_URL=secretref:database-url APPLICATION_SECRET=secretref:app-secret DB_POOL_SIZE=8 LOG_FORMAT=json \
    -o none
elif [ "$NEW_PASSWORD" = true ]; then
  # The database password was just reset: give the API the new connection string and restart it.
  az containerapp secret set -g "$RG" -n scheduler-api --secrets "database-url=$DATABASE_URL" "app-secret=$APP_SECRET" -o none
  az containerapp revision restart -g "$RG" -n scheduler-api \
    --revision "$(az containerapp show -g "$RG" -n scheduler-api --query properties.latestRevisionName -o tsv)" -o none
fi

step "Web app scheduler-web (public HTTPS)"
if ! exists az containerapp show -g "$RG" -n scheduler-web; then
  az containerapp create -g "$RG" -n scheduler-web --environment "$CAE" --image "$PLACEHOLDER" \
    --ingress external --target-port 80 \
    --cpu 0.25 --memory 0.5Gi --min-replicas 1 --max-replicas 3 \
    --scale-rule-name http-load --scale-rule-type http --scale-rule-http-concurrency 100 \
    "${REGISTRY[@]}" \
    --env-vars API_UPSTREAM=scheduler-api \
    -o none
fi

step "Registry sign-in for both apps"
# An app made by an earlier run of this script may still name a managed identity: switch it over.
for app in scheduler-api scheduler-web; do
  [ -n "$(az containerapp show -g "$RG" -n "$app" --query "properties.configuration.registries[0].username" -o tsv)" ] ||
    az containerapp registry set -g "$RG" -n "$app" "${REGISTRY[@]/--registry-/--}" -o none
done

step "Identity for GitHub Actions ($GITHUB_REPO)"
exists az identity show -g "$RG" -n "$IDENTITY" || az identity create -g "$RG" -n "$IDENTITY" -l "$LOCATION" -o none
CLIENT_ID=$(az identity show -g "$RG" -n "$IDENTITY" --query clientId -o tsv)
PRINCIPAL_ID=$(az identity show -g "$RG" -n "$IDENTITY" --query principalId -o tsv)
# One credential per kind of job: building on master, and the "production" deployment job.
# GitHub may name the repository in its token as "owner/repo" or, for newer repositories, with
# their numeric ids ("owner@123/repo@456"), so both forms are registered.
GITHUB_REPO_IDS=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}" |
  jq -r '"\(.owner.login)@\(.owner.id)/\(.name)@\(.id)"')
for pair in "github-master:repo:${GITHUB_REPO}:ref:refs/heads/master" "github-production:repo:${GITHUB_REPO}:environment:production" \
  "github-master-ids:repo:${GITHUB_REPO_IDS}:ref:refs/heads/master" "github-production-ids:repo:${GITHUB_REPO_IDS}:environment:production"; do
  name="${pair%%:*}"
  exists az identity federated-credential show -g "$RG" --identity-name "$IDENTITY" -n "$name" ||
    az identity federated-credential create -g "$RG" --identity-name "$IDENTITY" -n "$name" \
      --issuer https://token.actions.githubusercontent.com --subject "${pair#*:}" \
      --audiences api://AzureADTokenExchange -o none
done
RG_ID=$(az group show -n "$RG" --query id -o tsv)
ACR_ID=$(az acr show -g "$RG" -n "$ACR" --query id -o tsv)
for assignment in "Contributor|$RG_ID" "AcrPush|$ACR_ID"; do
  role="${assignment%%|*}"
  scope="${assignment#*|}"
  [ -n "$(az role assignment list --assignee "$PRINCIPAL_ID" --role "$role" --scope "$scope" --query '[0].id' -o tsv)" ] ||
    az role assignment create --assignee-object-id "$PRINCIPAL_ID" --assignee-principal-type ServicePrincipal \
      --role "$role" --scope "$scope" -o none
done

WEB_URL="https://$(az containerapp show -g "$RG" -n scheduler-web --query properties.configuration.ingress.fqdn -o tsv)"

step "Allow the web app's origin on the API"
# nginx forwards /api under the API's internal host name, so Play sees the browser's requests as
# cross-origin; the public web address must be on its CORS list.
[ "$(az containerapp show -g "$RG" -n scheduler-api --query "properties.template.containers[0].env[?name=='CORS_ORIGIN'].value | [0]" -o tsv)" = "$WEB_URL" ] ||
  az containerapp update -g "$RG" -n scheduler-api --set-env-vars "CORS_ORIGIN=$WEB_URL" -o none
cat <<DONE

$(printf '\033[1;32m')Azure is ready.$(printf '\033[0m')
Add these as GitHub repository VARIABLES (not secrets): https://github.com/${GITHUB_REPO}/settings/variables/actions

  AZURE_CLIENT_ID        ${CLIENT_ID}
  AZURE_TENANT_ID        $(az account show --query tenantId -o tsv)
  AZURE_SUBSCRIPTION_ID  $(az account show --query id -o tsv)
  AZURE_RESOURCE_GROUP   ${RG}
  AZURE_ACR              ${ACR}

Then push to master (or re-run the CI/CD workflow on master). The site will be at:
  ${WEB_URL}
DONE
