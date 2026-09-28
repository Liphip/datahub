#!/usr/bin/env bash
# Generates the throwaway RSA keypair the backend needs to sign and verify its own JWTs.
#
# Why this exists: the backend requires DATATURNTABLE_JWT_PUBLIC_KEY_LOCATION and
# DATATURNTABLE_JWT_SIGNING_KEY_LOCATION with no default, so a backend that has not been told
# where its keys are refuses to start. Nothing in docker/docker-compose.dev.yml provided them,
# which meant `docker compose -f docker/docker-compose.dev.yml up -d` could never bring the
# backend up. The Kubernetes manifests have the same gap (see docs/development/handoff.md).
#
# The keys are written to secrets/dev-keys/, which is gitignored. They are DEVELOPMENT KEYS ONLY:
# they are stored unencrypted on this machine and not protected by a secret store. Never use them
# for anything but a local stack. Real deployments must supply their own keypair from their
# secret store.
#
# Re-running overwrites the existing pair, which invalidates every JWT already issued.
set -euo pipefail

KEY_DIR="${KEY_DIR:-./secrets/dev-keys}"
KEY_BITS="${KEY_BITS:-2048}"

if [ -f "${KEY_DIR}/signing.pem" ] && [ -f "${KEY_DIR}/public.pem" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "==> Keys already present in ${KEY_DIR}; nothing to do."
  echo "    Set FORCE=1 to regenerate (this invalidates all issued tokens)."
  exit 0
fi

command -v openssl >/dev/null 2>&1 || {
  echo "openssl is required but was not found on PATH." >&2
  exit 1
}

mkdir -p "${KEY_DIR}"
umask 077

# PKCS#8 private key: what smallrye-jwt expects for signing.
openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${KEY_BITS}" -out "${KEY_DIR}/signing.pem"
# Public key in SubjectPublicKeyInfo form: what smallrye-jwt expects for verification.
openssl rsa -in "${KEY_DIR}/signing.pem" -pubout -out "${KEY_DIR}/public.pem" 2>/dev/null

chmod 600 "${KEY_DIR}/signing.pem"
chmod 644 "${KEY_DIR}/public.pem"

echo "==> Wrote development JWT keypair to ${KEY_DIR}"
echo "    signing.pem (private, gitignored) and public.pem."
echo "    These are throwaway development keys - do not use them anywhere real."
