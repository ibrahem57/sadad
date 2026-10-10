FROM node:24-alpine
WORKDIR /app
COPY --chown=node:node server.js ./server.js
COPY --chown=node:node public ./public
RUN mkdir -p /app/data && chown -R node:node /app
ENV NODE_ENV=production
USER node
EXPOSE 8080
CMD ["node", "server.js"]
