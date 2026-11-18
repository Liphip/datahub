#!/bin/sh
set -eu
umask 077
mkdir -p /keys /grafana-secrets /idp-import
secret() { test -s "/keys/$1" || openssl rand -hex 32 > "/keys/$1"; }
for name in db-password pipeline-password grafana-password oidc-client-secret idp-admin-password idp-user-password; do secret "$name"; done
if ! test -s /keys/connector.key; then openssl rand -base64 32 > /keys/connector.key; fi
if ! test -s /keys/signing.pem; then openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /keys/signing.pem 2>/dev/null; fi
openssl pkey -in /keys/signing.pem -pubout -out /keys/public.pem 2>/dev/null
chmod 600 /keys/*
chmod 644 /keys/public.pem
cp /keys/grafana-password /grafana-secrets/admin-password
chmod 755 /grafana-secrets
chmod 644 /grafana-secrets/admin-password
cp /keys/idp-admin-password /idp-import/admin-password
cp /keys/idp-user-password /idp-import/user-password
client_secret=$(cat /keys/oidc-client-secret)
user_password=$(cat /keys/idp-user-password)
cat > /idp-import/dataturntable-realm.json <<JSON
{
  "realm": "dataturntable", "enabled": true, "sslRequired": "none",
  "registrationAllowed": false,
  "clients": [{"clientId": "dataturntable", "enabled": true, "protocol": "openid-connect",
    "publicClient": false, "secret": "$client_secret", "standardFlowEnabled": true,
    "directAccessGrantsEnabled": false,
    "redirectUris": ["http://localhost:${DESKTOP_PORT:-8088}/api/internal/v1/auth/oidc/login"],
    "webOrigins": ["http://localhost:${DESKTOP_PORT:-8088}"],
    "attributes": {"pkce.code.challenge.method": "S256"}}],
  "users": [{"id": "2f437af0-07f5-49cd-a58a-bfbe728cd5ef", "username": "desktop-user",
    "enabled": true, "email": "desktop-user@example.invalid", "emailVerified": true,
    "firstName": "Desktop", "lastName": "User",
    "credentials": [{"type": "password", "value": "$user_password", "temporary": false}]}]
}
JSON
chown -R 1000:1000 /idp-import
chmod 700 /idp-import
chmod 600 /idp-import/*
echo 'Desktop keys and service credentials are ready (existing values preserved).'

# Stock monitoring images run as non-root. Copy public configuration with portable read modes.
if [ -d /monitoring-source ]; then
  mkdir -p /monitoring-config /grafana-config
  cp -R /monitoring-source/. /monitoring-config/
  cp -R /monitoring-source/grafana/provisioning/. /grafana-config/
  find /monitoring-config /grafana-config -type d -exec chmod 755 {} \;
  find /monitoring-config /grafana-config -type f -exec chmod 644 {} \;
fi
