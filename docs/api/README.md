# DataTurntable API Documentation

## Overview

The DataTurntable API provides REST endpoints for managing tenants, users, modules, pipelines, and data.

## Base URL

```
Development: http://localhost:8080
Production: https://api.dataturntable.io
```

## Authentication

All API endpoints (except `/auth/*`) require authentication using JWT Bearer tokens.

### Login

```http
POST /auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "your-password",
  "tenantId": "optional-tenant-id"
}
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "abilities": ["module:read", "pipeline:execute"],
    "isSuperadmin": false
  }
}
```

### Register

```http
POST /auth/register
Content-Type: application/json

{
  "email": "newuser@example.com",
  "password": "secure-password",
  "tenantName": "My Organization"
}
```

## Headers

Include the JWT token in the Authorization header:

```
Authorization: Bearer <your-token>
```

## Endpoints

### Tenants

#### List Tenants
```http
GET /tenants
```

#### Get Tenant
```http
GET /tenants/{id}
```

#### Create Tenant
```http
POST /tenants
Content-Type: application/json

{
  "name": "New Tenant",
  "parentId": "optional-parent-uuid",
  "quota": {
    "max_users": 100,
    "max_pipelines": 50
  }
}
```

#### Update Tenant
```http
PATCH /tenants/{id}
Content-Type: application/json

{
  "name": "Updated Name"
}
```

#### Delete Tenant
```http
DELETE /tenants/{id}
```

### Users

#### List Users
```http
GET /users?tenantId={tenant-uuid}
```

#### Create User
```http
POST /users?tenantId={tenant-uuid}&password={password}
Content-Type: application/json

{
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "roleId": "optional-role-uuid"
}
```

#### Update User
```http
PATCH /users/{id}
Content-Type: application/json

{
  "firstName": "Jane",
  "abilities": ["module:read", "module:write"]
}
```

#### Delete User
```http
DELETE /users/{id}
```

### Modules

#### List Modules
```http
GET /modules?tenantId={tenant-uuid}&type=PROCESSING
```

#### Get Module
```http
GET /modules/{id}
```

#### Create Module
```http
POST /modules?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "My Module",
  "moduleType": "PROCESSING",
  "description": "Processes sensor data"
}
```

#### Get Versions
```http
GET /modules/{id}/versions
```

#### Create Version
```http
POST /modules/{id}/versions
Content-Type: application/json

{
  "code": "def process(data): return data",
  "requirements": "pandas\nnumpy",
  "versionTag": "v1.0"
}
```

### Pipelines

#### List Pipelines
```http
GET /pipelines?tenantId={tenant-uuid}
```

#### Get Pipeline
```http
GET /pipelines/{id}
```

#### Create Pipeline
```http
POST /pipelines?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "Data Processing Pipeline",
  "importModuleVersionId": "uuid",
  "processingModuleVersionIds": ["uuid1", "uuid2"],
  "exportModuleVersionIds": ["uuid3"]
}
```

#### Trigger Pipeline
```http
POST /pipelines/{id}/run?triggerType=MANUAL
```

#### Get Pipeline Runs
```http
GET /pipelines/{id}/runs
```

### Data

#### Query Data
```http
GET /data/{tenantId}/query?table=measurements&start=2024-01-01&end=2024-01-02&limit=100
```

#### Insert Data
```http
POST /data/{tenantId}/insert?table=measurements
Content-Type: application/json

[
  {
    "time": "2024-01-01T00:00:00Z",
    "device_id": "sensor-001",
    "value": 23.5,
    "tags": {"location": "warehouse"}
  }
]
```

### Alerts

#### List Alert Channels
```http
GET /alerts/channels?tenantId={tenant-uuid}
```

#### Create Alert Channel
```http
POST /alerts/channels?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "Slack Alerts",
  "channelType": "WEBHOOK",
  "config": {
    "url": "https://hooks.slack.com/..."
  }
}
```

#### List Alert Rules
```http
GET /alerts/rules?tenantId={tenant-uuid}
```

#### Create Alert Rule
```http
POST /alerts/rules?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "High Temperature Alert",
  "channelId": "channel-uuid",
  "pipelineId": "pipeline-uuid",
  "condition": {
    "field": "temperature",
    "operator": ">",
    "value": 100
  }
}
```

#### Get Alert History
```http
GET /alerts/history?tenantId={tenant-uuid}&limit=100
```

## Error Responses

All errors follow this format:

```json
{
  "error": "Error message describing what went wrong"
}
```

Common status codes:
- 400: Bad Request
- 401: Unauthorized
- 403: Forbidden
- 404: Not Found
- 500: Internal Server Error

## Rate Limiting

API requests are rate-limited to 100 requests per minute per user.

## Pagination

List endpoints support pagination with query parameters:

```
GET /modules?tenantId=...&page=0&size=20
```

Response includes:
```json
{
  "content": [...],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20
}
```
