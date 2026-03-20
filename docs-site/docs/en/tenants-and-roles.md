# Tenants and Roles

## Why tenants matter

Tenants isolate data, permissions, and operations. Always verify your active tenant before making changes.

## Recommended tenant model

- Root tenant for organization-level ownership
- Child tenants for environments (`dev`, `staging`, `prod`)
- Optional domain tenants for teams (`iot`, `finance`, `ops`)

## Role strategy

- Viewer: read-only
- Maintainer: data/module/pipeline changes
- Tenant admin: user/role governance
- Platform admin: restricted high-privilege tasks

## Admin checklist

1. Grant minimum rights required.
2. Verify effective rights with test login.
3. Review privileged accounts regularly.
4. Remove stale memberships immediately.
