# Imagen del frontend (Angular compilado + nginx). Contexto de build: ./frontend
# Requiere frontend/package.json y package-lock.json (T4). El default.conf vive
# en docker/nginx y llega como contexto adicional `nginx_conf` (ver docker-compose.yml).

# ---- Etapa 1: build ------------------------------------------------------
# Angular 22 exige Node ^22.22.3 || ^24.15.0 (ADR 0001); node:24-alpine lo cumple.
FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration production

# ---- Etapa 2: servidor ---------------------------------------------------
# Imagen sin privilegios: corre como usuario `nginx` y escucha en 8080.
FROM nginxinc/nginx-unprivileged:stable-alpine
COPY --from=nginx_conf default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
EXPOSE 8080
