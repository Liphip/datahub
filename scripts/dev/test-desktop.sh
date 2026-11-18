#!/usr/bin/env bash
# Representative functional checks with an embedded DB and a loopback OIDC provider.
# Requires JDK 25, Maven 3.9+, Node 22.12+. No Docker or Kubernetes required.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_dir/backend"
mvn --batch-mode test -Dquarkus.http.test-port=0 \
  -Dtest=LocalLoginPolicyTest,LocalLoginPolicyResourceTest,AuthResourceTest,OidcFlowTest,ExternalIdentityResourceTest,TenantResourceTest,RetentionCleanupServiceTest,IngestRateLimiterTest
cd "$repo_dir/frontend"
npm ci
npx --no-install tsc --noEmit -p .
npm run lint
npm test
npm run build
