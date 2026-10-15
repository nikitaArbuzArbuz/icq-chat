FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# Полный конфиг вместо стандартного: в нём лимиты соединений, keepalive к бэкенду и лимит на вход.
COPY nginx.conf /etc/nginx/nginx.conf
# Стандартный server-блок из образа наш конфиг не подключает, удаляем его, чтобы не путал.
RUN rm /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
