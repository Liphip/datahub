# Security Basics

## Access control

- no shared admin credentials
- least-privilege roles
- periodic access review

## Secrets handling

- store secrets outside git
- rotate on schedule
- revoke immediately after exposure

## Endpoint exposure

- publish only required routes
- guard admin endpoints behind trusted ingress
- monitor auth failures and suspicious spikes
