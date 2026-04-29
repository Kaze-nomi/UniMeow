FROM node:20-alpine AS build
WORKDIR /app

COPY Frontend/package.json Frontend/yarn.lock ./
RUN yarn install

COPY Frontend/ ./
RUN yarn build

FROM nginx:1.27-alpine
COPY Frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY Frontend/logo.png /usr/share/nginx/html/logo.png
COPY Frontend/favicon.png /usr/share/nginx/html/favicon.png
COPY Frontend/default_pic.png /usr/share/nginx/html/default_pic.png

EXPOSE 80
