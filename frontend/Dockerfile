# Service Scheduler web app image: the Vite build served by nginx, which also forwards /api
# (and the API docs) to the backend named by API_UPSTREAM, so the browser sees one origin.
#   docker build -t service-scheduler-web frontend
#   docker run -e API_UPSTREAM=api:8081 -p 8080:8080 service-scheduler-web

FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ENV API_UPSTREAM=api:8081 PORT=8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
