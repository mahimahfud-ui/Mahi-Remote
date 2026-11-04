FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src/ ./src/
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY --from=builder /app/dist ./dist
# The Express app serves the browser controller and release bundles from /app/public.
COPY public ./public
# Release manifests and APK binaries are persisted through the compose data volume.
RUN mkdir -p /app/data
EXPOSE 8080
CMD ["node", "dist/index.js"]
