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
dart analyze
dart test
dart run jaspr_cli:jaspr serve       # development server on :8080
dart run jaspr_cli:jaspr build       # static site in build/jaspr
python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
```

CI clones arcane_jaspr into `.deps/arcane_jaspr` at a pinned ref instead of the symlink.

The browser check opens `../schema/fixtures/export` in the built site at desktop and phone widths:

```sh
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/home_browser.cjs
```

## Layout

| Path | Contents |
| --- | --- |
| `lib/model/` | Document kinds and documents; documents keep their exact source so unknown keys survive |
| `lib/logic/` | JSON Schema validator, shared schema catalog, data folder import, rig sampler and rig rules |
| `lib/state/` | Hash routes, editor store with undo, workspace repositories (IndexedDB, memory) |
| `lib/components/` | Shell and document views |
| `lib/services/` | Theme preference, hash changes and file picking |
| `lib/config/shared_schemas.g.dart` | `../schema` embedded by `tool/embed_schemas.dart` |
| `web/` | `index.html`, `bootstrap.js` (pre-paint theme), numbered CSS, fonts |

## Deploying

Not yet. The deploy workflow arrives with the hosting phase. `firebase.json` already sets the cache rules, the single rewrite to `/index.html` and the security headers; scripts load only from the site, so the app runs without arcane_jaspr's inline fallback scripts.
