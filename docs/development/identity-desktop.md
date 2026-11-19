# Identity checks on a desktop

This page describes automated identity checks. For an interactive test with a real provider,
run the [developer Compose stack](setup.md) and configure OIDC as described there.

Run `bash scripts/dev/test-desktop.sh` with JDK 25, Maven 3.9+ and Node 22.12+ on PATH.
No Kubernetes cluster, Docker daemon or external identity provider is required for this smoke suite.

The backend test fixture `DesktopOidcProvider` binds an ephemeral loopback port and provides real
OIDC discovery, JWKS, authorization and token endpoints. It requires PKCE S256 and a nonce,
signs ID tokens with an ephemeral RSA key and consumes each authorization code once. The test
client follows HTTP redirects and keeps cookies as a browser would. The application verifies the
provider token and maps its issuer and subject to an administrator-approved local user.

The suite checks valid sign-in, wrong nonce/audience/issuer/signature, expired provider tokens,
browser-bound handoff, replay refusal, expiry, explicit links and revocation. It also runs normal
password-authentication and tenant-settings checks so adding OIDC cannot silently replace the
existing authentication mechanism. This is protocol and API validation; a manual UI pass with
a real identity provider is still useful and is configured in [setup.md](setup.md).

For PostgreSQL schema validation, use the migration test in the setup guide against a dedicated
empty database. It cleans that database. Never supply a production or shared development database.

The Docker sandbox's hostile-module resource tests are separate from this smoke suite. They are
not required to test identity locally. Run them only in an isolated Docker-capable test environment,
as described in the standard test guide.
