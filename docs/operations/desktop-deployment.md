# Desktop test deployment

This is a running, persistent evaluation installation of DataTurntable on Docker Desktop or a
local Docker Engine. It includes the UI, management/data/ingest APIs, Kafka, MQTT, PostgreSQL,
isolated Python execution, Grafana dashboards, Prometheus metrics, Loki/Alloy container logs and documentation. Kubernetes, a host JDK, Maven,
Node and Python are not required. Automated checks remain separate from this deployment.

## Start

Use Docker Desktop in **Linux container** mode (Windows/macOS), or Docker Engine with Compose on
Linux. Use Compose v2.24 or newer. Allow about 4 CPUs, 8 GB RAM and 15 GB free disk for images and
data; the optional identity provider uses additional memory. Images support amd64 and arm64.
The first build downloads dependencies and takes longer than subsequent starts.

From the repository root:

```sh
docker compose -f docker/compose.desktop.yml up --build -d --wait
```

Open **http://localhost:8088** on your desktop. Follow “Run initial setup” to create the installation
administrator and choose your own password. Nothing is seeded into the application automatically.
The same gateway serves APIs, `/docs/`, `/dashboard/`, `/monitoring/` and `/q/metrics`.

The init service generates a signing key pair, connector encryption key and service passwords into
Docker volumes. Existing values are preserved when restarting or rebuilding. The databases,
Kafka log, MQTT state, ingestion spool and Grafana state also persist in project-specific volumes.
No credentials are committed or placed in a host `.env` file.

Only local ports are published:

| Address | Use |
|---|---|
| `http://localhost:8088` | Application, APIs, docs and dashboard gateway |
| `mqtt://localhost:1884` | MQTT input/output from desktop tools |
| `ws://localhost:9002` | MQTT over WebSocket |

Set `DESKTOP_PORT`, `DESKTOP_MQTT_PORT`, or `DESKTOP_MQTT_WS_PORT` before running Compose to choose
other ports. Configure the HTTP port before first importing the optional identity-provider realm.

Both Grafana instances (`/monitoring/` for application monitoring and `/dashboard/` for tenant dashboards) still have their own login until common component sessions are implemented. Its username is
`admin`; retrieve its generated password locally when needed:

```sh
docker compose -f docker/compose.desktop.yml exec backend cat /keys/grafana-password
```

The broker accepts anonymous local clients in this evaluation deployment. Module execution mounts
the Docker socket; use this deployment on a trusted development machine. It is a single-node test
installation, without high availability, TLS termination or production identity/session policies.

## Try the product

1. Create a tenant and select it in the UI.
2. Create a PROCESSING module with no requirements:

   ```python
   def process(input_data):
       return {**input_data, "value": input_data["value"] * 2}
   ```

3. Use the pipeline editor to connect an IMPORT node, this PROCESSING module and a DB_SAVE or
   EXPORT node. Activate the pipeline. The existing [energy example](../examples/energy-14a/README.md)
   and [model example](../examples/model-forecast/README.md) show fuller workflows.
4. Create an ingest API key scoped to that pipeline. Send a record through the gateway's
   `/api/ingest/v1/ingest/{pipelineId}` endpoint with `X-API-Key` and optionally `Idempotency-Key`.
   See the [API guide](../api/README.md) for the request contract.
5. Inspect the pipeline run, node results and logs in the UI. The Python code runs in a separate,
   restricted container without network; it does not run inside the backend. A module's
   requirements are installed once, by a separate container on the `bridge` network that must
   reach PyPI (or set `dataturntable.docker.dependency-install.index-url`/`.proxy`), so the first
   message after a requirements change takes longer. Only wheels are accepted by default.
6. Edit tenant limits/retention and inspect the Activity tabs on tenants, connectors and roles.

Connections to databases/brokers inside this deployment use their Compose names (`postgresql`,
`pipeline-postgresql`, `mosquitto`, `kafka`), not the desktop's `localhost`. Private-network
connector destinations are enabled for this local evaluation deployment. Desktop programs reach
services through the published local ports listed above.

## Optional local identity provider

To test interactive OIDC login with a real Keycloak instance, use both files:

```sh
docker compose -f docker/compose.desktop.yml -f docker/compose.desktop-oidc.yml up --build -d --wait
```

The overlay enables the provider on the backend and starts Keycloak at
**http://idp.localhost:8180**. The `.localhost` name reaches loopback in desktop browsers and is also
a Compose network alias, so the browser and backend use the same issuer. Port 8180 is fixed to
keep the issuer identical inside and outside Docker. Proxy configurations must bypass localhost
and `.localhost` destinations.

As the application administrator, open **Users → External identities** for the local account that
should be allowed to sign in, and add:

- Issuer: `http://idp.localhost:8180/realms/dataturntable`
- Subject: `2f437af0-07f5-49cd-a58a-bfbe728cd5ef`

Then sign out of the application, choose **Sign in with your identity provider**, and sign into
Keycloak as `desktop-user`. Retrieve its generated password locally:

```sh
docker compose -f docker/compose.desktop.yml exec backend cat /keys/idp-user-password
```

This explicit link grants that provider subject access to the chosen local account and its roles;
an equal email address alone grants nothing. Choose the local target account deliberately.
The seeded realm is for desktop evaluation only. Provider admin username: `desktop-admin`;
its generated password is in `/keys/idp-admin-password` in the backend container.

Keycloak stores its realm in a persistent volume. Its startup import does not overwrite an
existing realm. After changing the public HTTP port, update the client's redirect URI in the
Keycloak admin console. A clean deployment imports the current port automatically. To return to
password-only operation, run `down` with both Compose files and then start with the base file.
Volumes are retained. The overlay enables OIDC only when used.
Provider logout, JIT provisioning, claim-to-role mappings and shared sessions for embedded
components remain open product features; this deployment does not claim to implement them.

## Stop, resume and rebuild

If using Keycloak, include `-f docker/compose.desktop-oidc.yml` on **all** commands below,
including `down`, so Compose manages the provider as part of the same project.

```sh
# Status and logs
docker compose -f docker/compose.desktop.yml ps
docker compose -f docker/compose.desktop.yml logs -f backend

# Stop containers and retain all data/keys
docker compose -f docker/compose.desktop.yml down

# Resume; include the OIDC overlay again if you use it
docker compose -f docker/compose.desktop.yml up -d --wait

# Rebuild after changing application source
docker compose -f docker/compose.desktop.yml up --build -d --wait
```

`down` preserves data. Adding `--volumes` permanently deletes this installation's data and keys;
use that only when intentionally starting over. Keep the keys with the databases: replacing the
connector encryption key loses access to encrypted secrets and model artifacts.

For rootless Docker with a nonstandard socket, set `DESKTOP_DOCKER_SOCKET` to the daemon's Unix
socket path before startup. On Docker Desktop the standard `/var/run/docker.sock` mount works in
Linux-container mode. The desktop stack has its own Compose project and volumes, separate from
`docker/docker-compose.dev.yml`.

## Build and startup troubleshooting

- A source build needs Maven Central, npm, PyPI, image registries and Alpine package mirrors.
  A corporate/cloud proxy must allow those destinations and trust its CA through supported build
  configuration. Do not disable TLS or package verification.
- `init` and `module-runtime` are expected to exit successfully. Running services must become
  healthy. Inspect the failing service's logs if `up --wait` returns an error.
- Health probes explicitly bypass HTTP proxies for their loopback requests. This does not change
  the services' outbound proxy configuration.
- The docs image needs `docs/` and Markdown sources in its build context. They must not be excluded
  by `.dockerignore`.
- The test deployment uses one backend process with runtime role `all`, rather than four backend
  JVMs. It includes application/JVM/pipeline metrics and container logs. Host, PostgreSQL, Kafka
  and nginx exporter panels require the larger developer/operations Compose stack.


### Restrict password login after testing OIDC

To evaluate break-glass-only access, first create the administrator and test the provider flow.
Set `DATATURNTABLE_LOCAL_LOGIN_MODE=break-glass-only` and
`DATATURNTABLE_BREAK_GLASS_USER_IDS` to comma-separated existing account UUIDs, then restart using
the same Compose files. Only those accounts may start a password login. Active linked accounts
can still use OIDC. A missing/invalid allowlist causes startup to fail. The default remains `all`
for a new desktop deployment. See the configuration reference for token/session limits.

## Clean local retest

To remove the complete testing installation and rebuild it from the updated source:

```sh
docker compose -f docker/compose.desktop.yml down --volumes --remove-orphans
docker compose -f docker/compose.desktop.yml up --build -d --wait
```

Include the OIDC overlay on both commands when using it. This intentionally removes accounts,
tenants, pipelines, records, broker state and keys; run initial setup again after starting.
Images are rebuilt from source; deleting downloaded base images is unnecessary.

## Monitoring and connector diagnostics

- `/q/metrics` returns Prometheus text; it is routed to the backend rather than the SPA.
- Open `/monitoring/`, sign in with the generated Grafana administrator password above, and select
  the provisioned backend or pipeline dashboards. Traffic-dependent series appear after requests
  and pipeline executions; rate graphs need multiple scrapes.
- In Grafana Explore select Loki and query `{compose_service="backend"}`. Narrow module output with
  `|= "MODULE_STDOUT"` or connector diagnostics with `|= "<connector UUID>"`. In the split developer
  stack use `{compose_service=~"control-api|public-api|ingest-edge|pipeline-executor"}`.
- Successful Python `print()` output is available in the run's node logs and mirrored into backend
  logs for Alloy. A module that prints nothing has no stdout. Captures are bounded and redacted.
- Connector **Enabled** means scheduling is enabled. The broker status and its last error show the
  actual connection; **Test** checks reachability/credentials. Inside Docker use
  `mqtt://mosquitto:1883` for the bundled broker, rather than `localhost`.
- The desktop init service copies public monitoring configuration into readable volumes for
  non-root monitoring containers. Internal monitoring requests bypass configured HTTP proxies.
- The docs build context includes `docs/` and Markdown files. The tenant-schema fix reuses existing
  schemas under restricted roles; granting broad database CREATE privileges is unnecessary.

### MQTT credential-reference errors

`Invalid secret reference for 0` can occur on connectors edited with the older UI/API: the API
masked `secretRefs` as a string and the editor saved its characters as numbered entries. Updated
versions return reference IDs/versions (never secret values) and reject malformed references before
saving. For an already affected connector, edit it and re-enter all credential fields, then save;
the editor creates replacement secret references. A clean test deployment also removes the affected
configuration. A blank credential field preserves valid references; it cannot repair corrupted ones.

`has no pipeline topic subscriptions yet` is informational: connecting without subscriptions is
allowed. Configure an active pipeline IMPORT node with an MQTT Topic to receive messages.
`MqttException` indicates a separate broker connection failure. Updated logs, connector status and
the Test action include Paho's reason code and underlying cause, with credentials redacted. A broker
URL without a port uses the protocol default: `mqtt://host` uses plain TCP on 1883; use
`mqtts://host:8883` when the broker requires TLS. Choose the URL/port and credentials required by
your broker; HTTP proxy settings do not provide a TCP MQTT tunnel.

### Escaped module configuration

Module versions now return config as a JSON object instead of the database's JSON text. The editor
also recognizes older JSON-text responses, including an extra encoding layer, when opening the
latest version or loading history. Existing valid config does not need to be recreated. Reopen the
module after updating; edits are saved as objects. New API writes reject arrays/scalars/JSON strings
as config rather than creating another encoding layer.
