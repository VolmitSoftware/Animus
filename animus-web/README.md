# Animus Editor

The browser editor for Animus. It is a client-mode Jaspr app on arcane_jaspr, hosted on Firebase as `animus-editor.web.app` (project `animus-editor`). The design is in [docs/editor-design.md](../docs/editor-design.md) and the shared contracts in [docs/editor-contracts.md](../docs/editor-contracts.md).

The first screen opens a data folder (`plugins/Animus`, or `config/animus` on mod servers) from a directory pick or a zip. It lists NPCs, behaviors, packs, shops and chest GUIs, checks each against the schemas in `../schema` in the browser, and keeps the result as a local workspace in IndexedDB. Each document has Visual, Preview, Code and Split views. Nothing is uploaded.

## Building

Needs Dart 3.13 or newer. arcane_jaspr is a path dependency at `.deps/arcane_jaspr`:

```sh
cd animus-web
tool/link_arcane_jaspr.sh            # symlink ~/Developer/RemoteGit/ArcaneArts/arcane_jaspr; or pass a path
dart pub get
dart run tool/embed_schemas.dart     # after any change under ../schema
dart run tool/extract_minecraft_world_assets.dart --jar=<26.3 client jar> --version=26.3
dart analyze
dart test
dart run jaspr_cli:jaspr serve       # development server on :8080
dart run jaspr_cli:jaspr build       # static site in build/jaspr
python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
```

CI clones arcane_jaspr into `.deps/arcane_jaspr` at a pinned ref instead of the symlink.

The 3D preview draws with textures and models from the Minecraft client, which are Mojang's and never committed: `web/assets/mc/` is gitignored, and the extraction tool writes `web/assets/mc/26.3/` (block and item atlases, `models.json`, the entity textures in `mcEntityTextureIds`, about 2 MB) from a client jar before `jaspr build`. It reads only `assets/minecraft/` from the jar and makes no network request. Any 26.3 client jar works, for example the one Loom or NeoForm keeps in `~/.gradle/caches`. Without the pack the editor works and the stage says the pack is missing.

The browser check opens `../schema/fixtures/export` in the built site at desktop and phone widths:

```sh
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/home_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/npc_preview_browser.cjs
```

`npc_preview_browser.cjs` opens `test/fixtures/npc_preview` (every body type, pose and equipment slot, a skin document) and checks that each NPC's Preview draws a non-blank canvas at both widths, that text and orbiting work, and that a loaded skin PNG is drawn, with no console errors. `ANIMUS_SCREENSHOTS` is optional and should point outside the repository.

## NPC preview

An NPC's Preview (and Split, beside the code) is a WebGL2 stage adapted from Gloss Web's renderer: the body rig in its definition pose (standing, sneaking, sleeping, swimming, sitting), armour layers and held or worn items from all eight equipment slots, the native nameplate, the text display with the hologram lines and long or formatted names, and an orbit camera (drag, right or shift drag to pan, wheel or pinch to zoom). Bodies: player (classic and slim), armor stand (parts, angles, arms, base plate, small, invisible), horse (saddle and armour), turtle, frog, llama (carpet), armadillo and ender dragon. Rig geometry is the 26.3 client's `LayerDefinition`s. Slots a body does not show are listed under the stage.

Player skins: a definition's `skin` names a `skins/<hash>.json` document, from which the preview reads the arm model. Its signed texture comes from the server once the editor connects; until then the preview wears the default skin, or a PNG loaded from a file or a CORS-enabled URL with the controls above the stage (kept for the page session, per skin hash).

## Layout

| Path | Contents |
| --- | --- |
| `lib/model/` | Document kinds and documents; documents keep their exact source so unknown keys survive |
| `lib/logic/` | JSON Schema validator, shared schema catalog, data folder import, rig sampler and rig rules |
| `lib/state/` | Hash routes, editor store with undo, workspace repositories (IndexedDB, memory) |
| `lib/mc/` | Renderer core adapted from Gloss Web: assets and model resolver, meshers, rigs, scene, camera, and `npc/` (definition to scene) |
| `lib/components/` | Shell and document views; `mc/` holds the WebGL2 stage, renderer and asset loader |
| `lib/services/` | Theme preference, hash changes and file picking |
| `lib/config/shared_schemas.g.dart` | `../schema` embedded by `tool/embed_schemas.dart` |
| `web/` | `index.html`, `bootstrap.js` (pre-paint theme), numbered CSS, fonts; `assets/mc/` is generated |
| `tool/` | Schema embedding, Minecraft asset extraction (`mc_extract/`) |

## Deploying

Not yet. The deploy workflow arrives with the hosting phase. `firebase.json` already sets the cache rules, the single rewrite to `/index.html` and the security headers; scripts load only from the site, so the app runs without arcane_jaspr's inline fallback scripts.
