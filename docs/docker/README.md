# Docker Setup

This document describes the Docker infrastructure for DataTurntable.

## Services

The development environment consists of the following services:

| Service | Image | Port | Description |
|---------|-------|------|-------------|
| postgresql | timescale/timescaledb:latest-pg16 | 5432 | TimescaleDB for time-series data |
| kafka | confluentinc/cp-kafka:7.5.0 | 9092 | Message broker for data streaming |
| mosquitto | eclipse-mosquitto:2 | 1883 | MQTT broker for IoT data |
| backend | custom (Quarkus) | 8080 | REST API server |
| frontend | custom (React + Nginx) | 3000 | Web UI |
| nginx | nginx:alpine | 80 | Reverse proxy |

## Quick Start

```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```

Wait for all services to become healthy:

```bash
docker-compose -f docker-compose.dev.yml ps
```

## Accessing Services

| Service | URL |
|---------|-----|
| Frontend (direct) | http://localhost:3000 |
| Frontend (via nginx) | http://localhost |
| Backend API | http://localhost:8080 |
| API (via nginx) | http://localhost/api |

## Health Checks

All services have health checks configured:

- **PostgreSQL**: `pg_isready -U dataturntable`
- **Kafka**: `kafka-broker-api-versions --bootstrap-server localhost:9092`
- **Mosquitto**: `mosquitto_sub -t $$SYS/#`
- **Backend**: `http://localhost:8080/q/health/ready`
- **Frontend**: `http://localhost/health`

## Environment Variables

### Backend

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | postgresql | Database host |
| DB_PORT | 5432 | Database port |
| DB_NAME | dataturntable | Database name |
| DB_USER | dataturntable | Database user |
| DB_PASSWORD | dataturntable | Database password |
| KAFKA_BOOTSTRAP_SERVERS | kafka:9092 | Kafka servers |

## Troubleshooting

### View logs

```bash
# All services
docker-compose -f docker-compose.dev.yml logs

# Specific service
docker-compose -f docker-compose.dev.yml logs backend
docker-compose -f docker-compose.dev.yml logs -f backend
```

### Reset volumes

```bash
docker-compose -f docker-compose.dev.yml down -v
```

### Rebuild services

```bash
docker-compose -f docker-compose.dev.yml up -d --build
```
