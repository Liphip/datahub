# DataTurntable

A high-performance, scalable data hub for IoT/sensor data processing with modular import/export interfaces, processing layer with Python user modules, and multi-tenant architecture.

## Features

- **Modular Import/Export**: Pluggable connectors for MQTT, HTTP/REST, WebSocket
- **Processing Layer**: User-defined Python modules for data transformation
- **ML Integration**: Run Python ML models (PyTorch, scikit-learn) in processing modules
- **TimescaleDB**: Optimized for time-series data storage
- **Multi-Tenant**: Hierarchical tenant model with fine-grained RBAC
- **Alerting**: Configurable alerts via MQTT, webhook, REST, email
- **High Performance**: Handles 1B+ readings/day (~11,574 reads/sec)
- **Horizontal Scaling**: Kubernetes-ready with auto-scaling

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    DataTurntable                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │  Frontend │  │   API    │  │  Engine  │  │  Docker  │       │
│  │  (React) │  │ (Quarkus) │  │  (Kafka) │  │ Modules  │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
│        │            │             │              │              │
│        └────────────┴─────────────┴──────────────┘              │
│                            │                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │  Kafka   │  │TimescaleDB│  │PostgreSQL│  │   MQTT   │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21+ (for backend development)
- Node.js 18+ (for frontend development)
- Maven 3.9+ (for backend build)

### Development Setup

1. Clone the repository:
```bash
git clone https://github.com/your-repo/dataturntable.git
cd dataturntable
```

2. Start the development environment:
```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```

3. Access the services:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- PostgreSQL: localhost:5432
- Kafka: localhost:9092

### Building from Source

Backend:
```bash
cd backend
mvn package
```

Frontend:
```bash
cd frontend
npm install
npm run build
```

## Module Contract

Users can write Python modules for import, processing, and export:

```python
# Processing Module Example
class ProcessingModule:
    def process(self, data, alert_fn=None):
        # Transform data (dict or DataFrame)
        if data['value'] > threshold:
            alert_fn(severity='critical', message='Threshold exceeded', payload=data)
        return data
```

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| POST /auth/login | User authentication |
| GET /tenants | List tenants |
| GET /modules | List code modules |
| GET /pipelines | List pipelines |
| POST /pipelines/{id}/run | Trigger pipeline |
| GET /data/{tenantId}/query | Query time-series data |
| GET /alerts/history | Alert history |

See [API Documentation](docs/api/README.md) for full details.

## Configuration

Environment variables for the backend:

| Variable | Description | Default |
|----------|-------------|---------|
| DB_HOST | PostgreSQL host | localhost |
| DB_PORT | PostgreSQL port | 5432 |
| DB_NAME | Database name | dataturntable |
| KAFKA_BOOTSTRAP_SERVERS | Kafka servers | localhost:9092 |
| REDIS_HOSTS | Redis hosts | localhost:6379 |

## Deployment

### Docker Compose

```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```

### Kubernetes

Using Kustomize:
```bash
kubectl apply -k docker/k8s/
```

Using Helm (coming soon):
```bash
helm install dataturntable ./docker/helm/dataturntable
```

## Development

### Running Tests

Backend:
```bash
cd backend
mvn test
```

Frontend:
```bash
cd frontend
npm test
```

### Code Style

- Backend: Follow Quarkus/Kotlin conventions
- Frontend: ESLint + Prettier (run `npm run lint`)

## Documentation

- [Architecture](docs/architecture/README.md)
- [Development Plan](docs/development/plan.md)
- [API Reference](docs/api/README.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contact

For questions or suggestions, contact: contact@dataturntable.io
