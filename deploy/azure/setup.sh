#!/usr/bin/env bash
# One-off Azure set-up for the Service Scheduler. Run it in Azure Cloud Shell (Bash):
#
#   curl -fsSL https://raw.githubusercontent.com/Buithienphuoc/keyloop-challenge-case-a/master/deploy/azure/setup.sh -o setup.sh
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
GITHUB_REPO="${GITHUB_REPO:-Buithienphuoc/keyloop-challenge-case-a}"
PG_VERSION="${PG_VERSION:-16}"

STATE="$HOME/.service-scheduler-azure.env"
# shellcheck disable=SC1090
[ -f "$STATE" ] && source "$STATE"
SUFFIX="${SUFFIX:-$(openssl rand -hex 3)}"
# Hex plus a capital letter meets PostgreSQL's complexity rule and needs no URL escaping.
DB_PASSWORD="${DB_PASSWORD:-Pg$(openssl rand -hex 16)}"
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

step "PostgreSQL $PG (takes 5-10 minutes the first time)"
if ! exists az postgres flexible-server show -g "$RG" -n "$PG"; then
  # 0.0.0.0 = "allow Azure services", which is how Container Apps reach it; TLS is required.
  az postgres flexible-server create -g "$RG" -n "$PG" -l "$LOCATION" \
    --tier Burstable --sku-name Standard_B1ms --storage-size 32 --version "$PG_VERSION" \
    --admin-user scheduler --admin-password "$DB_PASSWORD" \
    --database-name scheduler --public-access 0.0.0.0 --yes -o none
fi
# The schema uses btree_gist for its no-double-booking constraints; Azure needs it allow-listed.
az postgres flexible-server parameter set -g "$RG" -s "$PG" --name azure.extensions --value BTREE_GIST,PGCRYPTO -o none
DATABASE_URL="jdbc:postgresql://${PG}.postgres.database.azure.com:5432/scheduler?user=scheduler&password=${DB_PASSWORD}&sslmode=require"

step "Container Apps environment $CAE"
exists az containerapp env show -g "$RG" -n "$CAE" || az containerapp env create -g "$RG" -n "$CAE" -l "$LOCATION" -o none

step "API app scheduler-api (internal, 1-5 replicas)"
if ! exists az containerapp show -g "$RG" -n scheduler-api; then
  # B1ms allows ~50 connections: 5 replicas x 8 stays under it.
  az containerapp create -g "$RG" -n scheduler-api --environment "$CAE" --image "$PLACEHOLDER" \
    --ingress internal --target-port 80 \
    --cpu 0.5 --memory 1.0Gi --min-replicas 1 --max-replicas 5 \
    --scale-rule-name http-load --scale-rule-type http --scale-rule-http-concurrency 30 \
    --registry-server "$ACR_SERVER" --registry-identity system \
    --secrets "database-url=$DATABASE_URL" "app-secret=$APP_SECRET" \
    --env-vars DATABASE_URL=secretref:database-url APPLICATION_SECRET=secretref:app-secret DB_POOL_SIZE=8 LOG_FORMAT=json \
    -o none
fi

step "Web app scheduler-web (public HTTPS)"
if ! exists az containerapp show -g "$RG" -n scheduler-web; then
  az containerapp create -g "$RG" -n scheduler-web --environment "$CAE" --image "$PLACEHOLDER" \
    --ingress external --target-port 80 \
    --cpu 0.25 --memory 0.5Gi --min-replicas 1 --max-replicas 3 \
    --scale-rule-name http-load --scale-rule-type http --scale-rule-http-concurrency 100 \
    --registry-server "$ACR_SERVER" --registry-identity system \
    --env-vars API_UPSTREAM=scheduler-api \
    -o none
fi

step "Identity for GitHub Actions ($GITHUB_REPO)"
exists az identity show -g "$RG" -n "$IDENTITY" || az identity create -g "$RG" -n "$IDENTITY" -l "$LOCATION" -o none
CLIENT_ID=$(az identity show -g "$RG" -n "$IDENTITY" --query clientId -o tsv)
PRINCIPAL_ID=$(az identity show -g "$RG" -n "$IDENTITY" --query principalId -o tsv)
# One credential per kind of job: building on master, and the "production" deployment job.
for pair in "github-master:repo:${GITHUB_REPO}:ref:refs/heads/master" "github-production:repo:${GITHUB_REPO}:environment:production"; do
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
