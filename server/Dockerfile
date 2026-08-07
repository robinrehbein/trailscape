FROM node:22-alpine

WORKDIR /app

COPY server.mjs .

VOLUME /app/data
ENV DATA_DIR=/app/data

EXPOSE 8080

CMD ["node", "server.mjs"]
