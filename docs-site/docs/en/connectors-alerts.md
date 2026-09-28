# Connectors, API Keys and Alerts

## Connectors

A connector is a named, reusable connection to an external system. INPUT connectors bring data in,
OUTPUT connectors deliver it. Pipeline nodes reference connectors; credentials are never shown
again after saving.

| Type | Direction | What the platform does |
|---|---|---|
| MQTT | input / output | Subscribes to or publishes on topics (bundled or external broker) |
| REST | input / output | Polls an endpoint (input) or sends requests (output) |
| WebSocket | input / output | Streams frames |
| UDP | output | Sends datagrams |
| FILE | input | Reads files from a directory on the server |
| FTP / FTPS, SFTP | input | Reads files from a remote server (SFTP needs the server's host key fingerprint) |
| EMAIL (IMAP) | input | Reads mails, their attachments or body |

**Pull inputs** (FILE, FTP, SFTP, EMAIL) are polled by the platform: nothing is needed from the
sender except dropping the file or sending the mail. Set a *minimum age* so half-written files are
skipped, and an *after read* action (move, delete, keep). The connector's **Inbox** shows every item
it has seen, with its state; a failed item can be retried individually.

**Credentials** (passwords, keys, tokens) are stored as encrypted secrets. When editing a connector,
leave a secret field empty to keep the stored value.

**Internal networks:** connectors cannot reach private or local addresses unless the system
administrator allowed that network. If saving fails with "loopback, private, link-local or
metadata network", ask them to add the network.

Best practices: separate credentials per environment, test after rotating a secret, keep
timeouts explicit.

## API keys

Devices and systems that push data (REST ingestion API, WebSocket) authenticate with an API key.
Open **API keys** (needs the *secret:manage* permission), choose the pipelines and input connectors
the key may feed and optionally an expiry date. The key is shown **once**: copy it into the sending
system right away. Revoke a key when a device is retired; it stops working immediately.

A sender only has to send: a `202` answer means the record is safely accepted. Adding an
`Idempotency-Key` header (for example device id plus timestamp) guarantees the record is processed
once even if the sender repeats it.

## Alerts

Alert **rules** are ALERT nodes in a pipeline: threshold, rate of change, time window, absence of
data, or your own Python logic. The **Alerts** page manages the **channels** (MQTT, webhook, REST,
e-mail, Slack) and shows the **history** of every delivery.

Recommended pattern:

1. configure a channel and test it
2. add an ALERT node to the pipeline and choose the channel as its default
3. set a cooldown so one condition does not fire repeatedly
4. check the delivery history

Delivery states to watch: `PENDING`, `SENT`, `FAILED`, `RETRY`.
