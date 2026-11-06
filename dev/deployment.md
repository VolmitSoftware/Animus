# Deploying the editor and relay

The web editor runs at https://animus-editor.web.app (Firebase Hosting, project `animus-editor`). The relay is a separate service on Cloud Run. Servers reach the editor directly through their own listener, through the relay, or both. Deployments are run by hand; there is no CI workflow for them.

## Editor (Firebase Hosting)

Run these from `animus-web/`. Dart 3.13 or newer and Node.js (for `npx`) are needed. See [animus-web/README.md](../animus-web/README.md#building) for linking arcane_jaspr.

1. Fetch dependencies and link arcane_jaspr:

   ```sh
   tool/link_arcane_jaspr.sh
   dart pub get
   ```

2. Extract the 3D preview assets from a Minecraft 26.3 client jar. The output, `web/assets/mc/26.3/`, is Mojang's art and is gitignored, so every checkout generates it again. Any 26.3 client jar works, for example the one Loom or NeoForm keeps in `~/.gradle/caches`.

   ```sh
   dart run tool/extract_minecraft_world_assets.dart --jar=/path/to/minecraft-26.3-client.jar --version=26.3
   ```

   `--output=<folder>` changes the base folder (default `web/assets/mc`). Without the assets the editor still works and the stage reports that the pack is missing.

3. Build the static site into `build/jaspr`:

   ```sh
   dart run jaspr_cli:jaspr build
   ```

4. Deploy with a Google service account that may deploy to Firebase Hosting in `animus-editor`. Point the variable at the key file; never paste, print or commit the key's contents.

   ```sh
   GOOGLE_APPLICATION_CREDENTIALS=<service account path> npx -y firebase-tools deploy --only hosting --project animus-editor --non-interactive
   ```

`firebase.json` sets the cache rules, the single rewrite to `/index.html` and the security headers. Open https://animus-editor.web.app afterwards and check that the home page and an NPC preview load.

## Relay (Cloud Run)

The relay lets the editor reach servers that accept no inbound connections. The Volmit relay runs at `wss://animus-relay-823521865655.us-central1.run.app` (Cloud Run, project `animus-editor`, region us-central1, one instance). To run your own, build and deploy it from `animus-web/relay` with the `gcloud` commands in [the relay README](../animus-web/relay/README.md#cloud-run). Keep exactly one instance, give it its own hostname (not a Firebase Hosting rewrite) and set `ALLOWED_APP_ORIGINS=https://animus-editor.web.app`. The relay operator can read the traffic it forwards, so run it yourself or use one you trust.

## Server settings

The editor keys live in `[editor]` of `config.toml` (`plugins/Animus/config.toml`, or `config/animus/config.toml` on mod servers). Changes apply live when the file is saved. A change to `listenerEnabled`, `listenAddress`, `port`, `tlsCertificate`, `tlsPrivateKey` or `openConnections` restarts only the listener, and a change to a relay key restarts only the relay client; paired editors and their tokens stay valid through both. If the new port cannot bind, the listener stays down, `/animus editor status` says why, and the next change retries. Other keys apply in place. Replacing the TLS certificate files at the same paths is not noticed; change a path or restart.

To use the relay, set its `wss://` URL (the Cloud Run `run.app` URL or your domain mapping) and turn it on:

```toml
[editor]
relayEnabled = true
relayUrl = "wss://animus-relay-823521865655.us-central1.run.app"
```

`relayUrl` must be `wss://host[:port][/path]`; plain `ws://` is accepted only on `127.0.0.1` or `localhost`. The server connects to `<relayUrl>/agent`. `relayEnabled` is true by default and points at the Volmit relay; set it to false to use only the direct listener.

The direct listener is on by default (`listenerEnabled = true`) on `127.0.0.1:9797`, beside the relay; set it to false to use only the relay. The listener serves plain HTTP only on a loopback `listenAddress`; on any other address set `tlsCertificate` and `tlsPrivateKey`, or put a TLS proxy in front of a loopback listener and set `advertisedUrl` to the public `https://` address. `allowedOrigins` must include `https://animus-editor.web.app`, as it does by default.

After the restart, `/animus editor status` shows the listener and whether the relay is registered. `/animus editor pair` prints a pairing link that carries every enabled path; `/animus editor tokens` and `/animus editor revoke <token-id|all>` manage paired browsers.
