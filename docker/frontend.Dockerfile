# Build stage
FROM node:24.21.0-alpine3.24 AS builder

WORKDIR /app

# Copy package files
COPY frontend/package*.json ./

# Install exactly the dependency tree recorded in the committed lockfile
RUN npm ci

# Copy source code
COPY frontend/ ./

# Build the application
RUN npm run build

# Production stage
FROM nginx:1.30.5-alpine3.24

# Same libexpat CVE-2026-93990 gap as backend/Dockerfile's base image (Alpine 3.24) — see that
# file's comment for the full explanation. Upgrading here closes it in the nginx-based image too.
RUN apk upgrade --no-cache

# Copy built assets from builder
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx config for frontend (serves static files)
COPY docker/frontend-nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1/health || exit 1

CMD ["nginx", "-g", "daemon off;"]
