FROM node:22-alpine

WORKDIR /app

COPY server.mjs .

# Datenverzeichnis dem unprivilegierten node-Nutzer geben, BEVOR es als
# Volume deklariert wird – benannte Volumes uebernehmen die Rechte aus dem
# Image. Der Server laeuft damit nicht als root.
RUN mkdir -p /app/data && chown -R node:node /app/data

VOLUME /app/data
ENV DATA_DIR=/app/data

USER node

EXPOSE 8080

CMD ["node", "server.mjs"]
