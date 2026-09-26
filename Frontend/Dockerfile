FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /app

COPY Frontend/package.json Frontend/yarn.lock ./
RUN test "$(yarn --version)" = "1.22.22" && yarn install --frozen-lockfile

COPY Frontend/ ./
RUN yarn build

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
RUN apk add --no-cache jq=1.7.1-r0 oniguruma=6.9.9-r0
COPY Frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY Frontend/docker-entrypoint.d/20-runtime-config.sh /docker-entrypoint.d/20-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/20-runtime-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
COPY Frontend/logo.png /usr/share/nginx/html/logo.png
COPY Frontend/favicon.png /usr/share/nginx/html/favicon.png
COPY Frontend/default_pic.png /usr/share/nginx/html/default_pic.png

EXPOSE 80
