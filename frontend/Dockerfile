# syntax=docker/dockerfile:1
FROM node:24.11.1-bookworm-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY . .
RUN npm run build

# nginx serves the SPA and reverse-proxies /api. The site config is mounted per environment
# (infra/nginx/local.conf.template or prod.conf.template).
FROM nginx:1.30.6-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx-security-headers.conf /etc/nginx/snippets/security-headers.conf
EXPOSE 80 443
