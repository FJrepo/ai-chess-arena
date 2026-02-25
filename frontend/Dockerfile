FROM node:24-alpine@sha256:7fddd9ddeae8196abf4a3ef2de34e11f7b1a722119f91f28ddf1e99dcafdf114 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration=production

FROM nginxinc/nginx-unprivileged:alpine@sha256:07ac04b4a727a38e7360f3bd8bbe49a7433a8e2a3259dd403d2c982e5f4c7a1c
COPY --from=build /app/dist/ai-chess-arena/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
