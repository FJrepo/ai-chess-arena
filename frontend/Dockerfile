FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration=production

FROM nginxinc/nginx-unprivileged:alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
# Host names the UI may be opened on besides localhost, 127.0.0.1 and [::1] (space-separated); see nginx/.
# The filter makes the template step substitute only this variable, never nginx's own $variables.
ENV ALLOWED_HOSTS="" \
    NGINX_ENVSUBST_FILTER="^ALLOWED_HOSTS\$"
COPY --from=build /app/dist/ai-chess-arena/browser /usr/share/nginx/html
# The config is rendered from the template at container start; drop the base image's default server.
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=755 nginx/15-check-allowed-hosts.sh /docker-entrypoint.d/15-check-allowed-hosts.sh
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
