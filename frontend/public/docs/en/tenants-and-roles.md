# Tenants and Roles

## Tenant Design Strategy

Recommended hierarchy:

- Root tenant: organization-level control plane
- Child tenant: environment (`dev`, `staging`, `prod`)
- Optional sub-tenant: domain/team (`payments`, `iot`, `analytics`)

## Role Model Recommendations

- Viewer: read-only operational visibility
- Maintainer: module/pipeline/data write operations
- Tenant Admin: user and tenant governance
- Platform Admin: restricted high-privilege operations

## Least-Privilege Checklist

- Grant `data:write` only where table mutation is required.
- Grant `manage:tenant` to a minimal admin group.
- Remove wildcard permissions from regular users.
- Audit effective permissions after every role change.

## Operational Guide: Switching Tenant Context

1. Use header tenant selector.
2. Confirm current tenant name before changes.
3. Re-open page if data looks inconsistent.
4. Record the tenant ID in incident notes.

## Access Troubleshooting

- Symptom: empty page -> check tenant + permission ability.
- Symptom: forbidden error -> verify membership exists for selected tenant.
- Symptom: missing features -> compare role abilities to required action.

## Tips

- Keep role names explicit (`tenant-prod-maintainer`) to avoid mistakes.
- Use a separate emergency role with strict break-glass policy.
