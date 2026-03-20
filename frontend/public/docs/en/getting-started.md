# Getting Started

## 1. First-Time Setup

1. Deploy backend and frontend.
2. Run initial setup at `/setup`.
3. Create the first superadmin account.
4. Sign in and verify tenant context.

## 2. First 30 Minutes Checklist

- Confirm API health (`/q/health`) and metrics (`/q/metrics`).
- Open Dashboard and verify no critical failure spikes.
- Create a root tenant and one child tenant.
- Create one test module and one test pipeline.
- Run the pipeline once and inspect run details.

## 3. Safe Defaults for New Environments

- keep production write access limited to approved roles
- enforce per-tenant memberships (no shared admin login)
- enable monitoring and logs from day one
- define on-call owner per tenant

## 4. Deployment Paths

- Compose: single-host local/dev
- Swarm: multi-node service replicas
- Kubernetes: multi-instance autoscaling and namespace boundaries

## 5. Common Early Mistakes

- working in the wrong tenant context
- editing modules without version tags
- testing in production tenant first
- skipping log + metric correlation during incident response

## Tips

- Pin a browser tab to `/docs` for operator runbooks during incidents.
- Keep a shared release checklist in your team wiki and link it from this docs site.
