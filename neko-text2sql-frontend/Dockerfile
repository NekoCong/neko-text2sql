# text2sql-frontend — build the Vite bundle, then serve it with nginx.
#
# The API base URL stays "/api" (see src/api/http.ts), and nginx proxies /api to the backend. That
# keeps the bundle host-independent: the same image works whatever IP or domain the server has, so
# nothing needs rebuilding when a spot instance comes back on a different address.
#
# The base images are build args so a deployment can point them at a registry mirror without
# editing this file (see deploy/.env).

ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginx:alpine

# --- Build stage --------------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- Runtime stage ------------------------------------------------------------------------------
FROM ${NGINX_IMAGE}

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
