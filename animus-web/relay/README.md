# Animus relay

The Animus relay connects the Animus web editor to Minecraft servers that accept no inbound connections. A server connects out to `/agent`; a browser connects to `/app` and subscribes to one server by its fingerprint. The relay forwards the browser's `/animus/v1` requests to that server and the answers back. `GET /healthz` reports connection counts.

Everything is in memory. The relay stores no documents, tokens or frames, and logs no payloads. A restart drops every connection; servers and browsers reconnect.

## What the relay can see

The relay is a trusted pass-through. TLS (Cloud Run's) protects traffic on the wire, but the relay process reads every frame it forwards, including the editor's bearer tokens and the documents being edited. End-to-end encryption between browser and server is planned for a later release. Until then, whoever operates the relay can read the traffic it forwards. Tokens are scoped, expire after 7 days by default and can be revoked with `/animus editor revoke`.

Over the relay the editor checks that it reached the paired server before it sends a token or pairing secret. It sends a random 32-byte `X-Animus-Challenge` (base64url) with `GET /v1/hello`; the server answers with `X-Animus-Server-Key` and `X-Animus-Proof`, an Ed25519 signature over the UTF-8 text `animus-editor-proof/1 ` followed by the challenge. The editor needs the key's SHA-256 to equal the pinned fingerprint and the signature to verify. The prefix differs from the registration prefix `animus-relay/1 `, so neither signature can stand in for the other. This stops a relay from posing as a server that is not connected to it. It does not stop a relay that forwards to the real server from reading the traffic.

The relay does not check tokens. The server checks every forwarded request the same way as a direct one (token, scope, `X-Animus-Counter`, origin).

## Protocol

Frames are JSON text messages, one object each. Their definitions are the `relay-*` entries in `schema/editor-v1.schema.json`, and `schema/editor-v1.endpoints.json` lists which side sends which (`relay`). The relay closes a connection that breaks a rule with a `relay-error` frame and close code 4008. A message whose frame headers announce more than `MAX_FRAME_BYTES` (one frame, or continuation frames together) ends the TCP connection at once, before the payload is read.

### Addresses

The caller's address comes from `X-Forwarded-For` only when `TRUSTED_PROXY_HOPS` says proxies sit in front of the relay. With the default of 1 (Cloud Run's front end), it is the last entry, the one that front end appends. With N hops it is the Nth entry from the end. With 0 it is the socket peer, and the header is ignored. When the header has fewer entries than trusted hops, or that entry is not an IP address, the socket peer is used. Earlier entries are set by the client and ignored. The address is used for the per-address limits, which count an IPv6 address by its /64, and forwarded to the server, which uses it for pairing rate limits and its audit log.

A self-hosted relay that browsers reach directly must set `TRUSTED_PROXY_HOPS=0`; otherwise any client can choose its own address. Behind a reverse proxy that appends to `X-Forwarded-For`, set it to the number of such proxies.

Each address may hold `MAX_SERVER_CONNECTIONS_PER_ADDRESS` `/agent` connections and `MAX_APP_CONNECTIONS_PER_ADDRESS` `/app` connections, registered or not. Past that the upgrade gets 503.

### Server, `/agent`

1. The relay sends `{"type":"challenge","protocol":1,"nonce":"<32 bytes, base64url>"}`.
2. The server answers within 10 s with `{"type":"register","protocol":1,"serverKey":"<raw 32-byte Ed25519 key, base64url>","signature":"<base64url>"}`. The signature is Ed25519 over the UTF-8 text `animus-relay/1 ` followed by the nonce. `schema/fixtures/editor/relay-handshake.json` is a golden for this.
3. The relay replies `{"type":"registered","fingerprint":"sha256:<hex>"}`, the SHA-256 of the raw key. This is the same fingerprint as in pairing codes and `GET /v1/hello`. A newer registration with the same key replaces the older connection.
4. The relay sends `request` frames with its own ids. The server answers each with one `response` frame. For `GET /v1/events` a 2xx response opens a stream: the server sends `{"type":"event","id":N,"event":{...}}` frames until it sends `{"type":"close","id":N}` or the relay sends `close` for that id (the browser stopped the stream or left, or the request timed out).

The server's handshake must carry no `Origin` header.

### Browser, `/app`

1. The handshake's `Origin` must equal one of `ALLOWED_APP_ORIGINS` exactly. Otherwise the relay answers 403.
2. Within 10 s the browser sends `{"type":"subscribe","protocol":1,"fingerprint":"sha256:<hex>"}`. The relay answers `{"type":"subscribed","fingerprint":...,"online":true|false}`, and later `{"type":"status","online":...}` when the server connects or disconnects. One address may hold at most `MAX_APPS_PER_ADDRESS_PER_SERVER` subscriptions to one server.
3. Within 30 s of subscribing the browser must have an event stream the server accepted (a 2xx answer to `GET /v1/events`, which needs a valid token). Otherwise the relay closes it with a `timeout` error. Pairing (`hello`, then `POST /v1/pair`) fits in that time and then closes.
4. The browser sends `request` frames: `{"type":"request","id":N,"method":"GET|POST|DELETE","path":"/v1/...","headers":{...},"body":...}`. Ids are positive integers unique among the browser's open requests. The relay answers with the same id. Before forwarding, the relay replaces `Origin` with the handshake's origin and `X-Forwarded-For` with the browser's address, and removes `Forwarded` and `Host`.
5. Event streams use `GET /v1/events` with the token in `Authorization` (the relay path has no `event-auth` frame). The browser sends `{"type":"close","id":N}` to end a stream.

The relay answers some requests itself, with an `error-response` body:

| Status | Code | When |
| ---: | --- | --- |
| 400 | `invalid-request` | The id is already in use on this connection |
| 413 | `too-large` | The request with the relay's headers would exceed the frame limit |
| 429 | `rate-limited` | Too many pending requests or streams on this connection |
| 503 | `busy` | The relay's total pending-request limit is reached |
| 503 | `server-offline` | The server is not connected, or disconnected before it answered |
| 504 | `timeout` | The server did not answer within the request timeout |

Imports (`POST /v1/imports`, up to 16 MiB) do not fit in a relay frame. Use the direct connection for them.

## Running locally

```sh
cd animus-web/relay
dart pub get
PORT=8080 BIND_ADDRESS=127.0.0.1 TRUSTED_PROXY_HOPS=0 ALLOWED_APP_ORIGINS=http://127.0.0.1:8098 dart run bin/relay.dart
```

Point a test server at it with `relayUrl = "ws://127.0.0.1:8080"` in `[editor]` of `config.toml`. Plain `ws://` is accepted only on loopback. Servers in production use `wss://`.

Checks:

```sh
dart analyze
dart test
```

## Settings

All settings are environment variables.

| Variable | Default | Purpose |
| --- | ---: | --- |
| `PORT` | `8080` | Listener port |
| `BIND_ADDRESS` | `0.0.0.0` | Listener address |
| `TRUSTED_PROXY_HOPS` | `1` | Proxies in front of the relay that append to `X-Forwarded-For`; 0 when browsers connect directly |
| `ALLOWED_APP_ORIGINS` | `https://animus.volmitsoftware.com,https://animus-editor.web.app` | Comma-separated exact browser origins (`scheme://host[:port]`, no wildcards) |
| `MAX_FRAME_BYTES` | `1048576` | Largest text frame in UTF-8 bytes |
| `MAX_APP_MESSAGES_PER_SECOND` | `120` | Frames per second from one browser |
| `MAX_AGENT_MESSAGES_PER_SECOND` | `2000` | Frames per second from one server |
| `MAX_PENDING_PER_APP` | `64` | Unanswered requests per browser |
| `MAX_STREAMS_PER_APP` | `4` | Event streams per browser |
| `MAX_PENDING_GLOBAL` | `4096` | Unanswered requests in total |
| `MAX_APPS_PER_SERVER` | `64` | Browsers subscribed to one server |
| `MAX_APPS_PER_ADDRESS_PER_SERVER` | `8` | Browsers from one address subscribed to one server |
| `MAX_SERVER_CONNECTIONS_PER_ADDRESS` | `32` | `/agent` connections from one address |
| `MAX_APP_CONNECTIONS_PER_ADDRESS` | `64` | `/app` connections from one address |
| `MAX_SERVERS` | `1000` | Registered servers |
| `MAX_APPS` | `4096` | Browser connections |
| `HANDSHAKE_TIMEOUT_SECONDS` | `10` | Time for a server to register |
| `SUBSCRIPTION_TIMEOUT_SECONDS` | `10` | Time for a browser to subscribe |
| `SIGN_IN_TIMEOUT_SECONDS` | `30` | Time after subscribing for a browser to open an accepted event stream |
| `REQUEST_TIMEOUT_SECONDS` | `10` | Time for a server to answer |
| `PING_INTERVAL_SECONDS` | `30` | WebSocket ping interval; a peer that misses a pong is closed |

## Cloud Run

The image is a compiled Dart binary on distroless, running as the non-root user.

```sh
cd animus-web/relay
docker build -t animus-relay .
docker run --rm -p 8080:8080 -e ALLOWED_APP_ORIGINS=https://animus.volmitsoftware.com animus-relay
```

To deploy (replace the project, region and repository):

```sh
gcloud artifacts repositories create animus --repository-format=docker --location=us-central1
gcloud builds submit --tag us-central1-docker.pkg.dev/PROJECT/animus/animus-relay:1 .
gcloud run deploy animus-relay \
  --image us-central1-docker.pkg.dev/PROJECT/animus/animus-relay:1 \
  --region us-central1 \
  --allow-unauthenticated \
  --min-instances 1 --max-instances 1 \
  --timeout 3600 \
  --concurrency 1000 \
  --cpu 1 --memory 512Mi \
  --no-cpu-throttling \
  --set-env-vars ALLOWED_APP_ORIGINS=https://animus.volmitsoftware.com
```

- Keep exactly one instance. Routing is in memory, so a second instance would not see the first one's servers.
- Cloud Run ends WebSocket connections at the request timeout (at most 60 minutes). Both the plugin and the editor reconnect.
- `--no-cpu-throttling` keeps pings and timeouts running between frames.
- Serve the relay on its own hostname (a domain mapping or the `run.app` URL). Do not route it through a Firebase Hosting rewrite.
- Put the `wss://` URL in each server's `relayUrl`. Pairing codes carry it to the browser.
