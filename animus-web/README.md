# Animus Editor

The browser editor for Animus. It is a client-mode Jaspr app on arcane_jaspr, hosted on Firebase as `animus-editor.web.app` (project `animus-editor`). The design is in [docs/editor-design.md](../docs/editor-design.md) and the shared contracts in [docs/editor-contracts.md](../docs/editor-contracts.md).

The first screen opens a data folder (`plugins/Animus`, or `config/animus` on mod servers) from a directory pick or a zip. It lists NPCs, behaviors, packs, shops, GUIs, rigs and animations, checks each against the schemas in `../schema` in the browser, and keeps the result as a local workspace in IndexedDB. Each document has Visual, Preview, Code and Split views. Nothing is uploaded.

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
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/rig_editor_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
```

`rig_editor_browser.cjs` opens `test/fixtures/rig_editor` (the shared golem rig and two clips), moves a bone with the gizmo, undoes it, renames a bone, keys and scrubs an animation, plays it, downloads the server files and the workspace zip, and imports `test/fixtures/bbmodel/robot.bbmodel`, at both widths with no console errors.

`gui_editors_browser.cjs` opens `test/fixtures/gui_editors` (a chest GUI with a list, toggle and variant, one with a Gloss-only action, a shop, a hologram and a menu) and checks that each editor opens its fixture, that an edit round-trips to the document JSON, that a refused action is flagged at its field, that expression completion works, that undo works, that the data folder zip and the Gloss bundle export valid files and that `test/fixtures/gloss_bundle/gloss-workspace.json` imports, at both widths with no console errors.

`npc_preview_browser.cjs` opens `test/fixtures/npc_preview` (every body type, pose and equipment slot, a skin document) and checks that each NPC's Preview draws a non-blank canvas at both widths, that text and orbiting work, and that a loaded skin PNG is drawn, with no console errors. `ANIMUS_SCREENSHOTS` is optional and should point outside the repository.

## NPC preview

An NPC's Preview (and Split, beside the code) is a WebGL2 stage adapted from Gloss Web's renderer: the body rig in its definition pose (standing, sneaking, sleeping, swimming, sitting), armour layers and held or worn items from all eight equipment slots, the native nameplate, the text display with the hologram lines and long or formatted names, and an orbit camera (drag, right or shift drag to pan, wheel or pinch to zoom). Bodies: player (classic and slim), armor stand (parts, angles, arms, base plate, small, invisible), horse (saddle and armour), turtle, frog, llama (carpet), armadillo and ender dragon. Rig geometry is the 26.3 client's `LayerDefinition`s. Slots a body does not show are listed under the stage.

Player skins: a definition's `skin` names a `skins/<hash>.json` document, from which the preview reads the arm model. Its signed texture comes from the server once the editor connects; until then the preview wears the default skin, or a PNG loaded from a file or a CORS-enabled URL with the controls above the stage (kept for the page session, per skin hash).

## Rig editor

A rig's Visual and Preview views (and an animation's, which opens its rig) are the rig editor:

- Bone tree: add a child, rename (tracks in the rig's animations follow), reparent (with its subtree, rest pose unchanged because pivots are rig space), delete (children move to the parent). The 64-bone, depth-16 and parents-first rules of `schema/rig-v1.schema.json` are kept.
- Inspector: pivot, rest rotation, visual type (item, block or text display, or none), item id, `components`, item context, block state, text and billboard, visual offset, rotation and scale; `body` (hidden, or shown on a preview NPC body) and sockets.
- Stage: the bones drawn as the client draws the display entities (item displays turned 180 degrees and centred, block displays from their corner, text displays), the skeleton, and move, rotate and scale handles. In the rest pose a handle edits the pivot (with the subtree), rest rotation or visual scale; with an animation open it keys the bone at the playhead.
- Timeline: animation picker and new animation, duration, loop mode, playback speed, play, a scrubber, a lane per track with keyframe diamonds, and per keyframe its tick, value and easing (`linear`, `hold`, `ease_in`, `ease_out`, `ease_in_out`, `back_out`, `bezier(...)`). Playback uses `lib/logic/rig_sampler.dart`, the Dart port that matches the shared goldens.
- Import .bbmodel: every group becomes a bone, its cubes an item model at `pack/assets/animus/models/item/<rig path>/<bone>.json` with an item definition and the embedded textures beside it, and every Blockbench animation an animation document. The import is bounded (16 MiB file, 4,096 elements, 16 textures of at most 1024 px width, 64 animations, 256 keyframes per channel) and refuses a file over a bound. Cube rotations in 22.5-degree steps stay on the element; others become their own bone. Molang values, catmullrom and bezier keyframes and non-cube elements are approximated or skipped with a warning.
- Build resource pack: the server-side pack format is not documented yet, so this downloads `<rig>-server-files.zip` with the rig, its animations and the `pack/assets/` sources its bones use, laid out as data-folder files for the server's pack build. Export zip (top bar) downloads the whole workspace the same way.

Every edit is a workspace commit to IndexedDB and can be undone; gizmo drags commit once on release. Files under `pack/assets/` are kept as workspace resources when a folder or zip is opened.

## GUI editors

Chest GUIs, shops, holographic menus and holograms (Gloss formats, docs/gui.md and docs/shops.md) have visual editors, adapted from Gloss Web's editors. Visual shows the editor beside its live preview, Preview the preview alone, and Split the editor beside the code.

- Chest GUI: the window painted like the client's (title in the Minecraft font, item icons meshed by the stage renderer at their `gui` pose, counts, name and lore tooltips). Click a slot to edit what draws it: a mask key, an explicit slot or the list template. A brush paints keys into the mask. Tabs edit the window (title, size, mask rows, `show`, refresh), keys, the list (area, variable, source, page size, template), variants (which fields each replaces; the window shows and edits the chosen one) and named action lists.
- Shop: pages, the buy, sell and trade grids painted as the shop window with its bottom-row controls, and per entry its type, item, display, price, trade goods, command entries (with the allow-list note), limits with resets, stock, discount, permission and broadcast.
- Hologram: typed rows (text, item, head, block, entity), pages, variants, anchor, click box and actions, drawn in the 3D stage.
- Menu: components with offsets, data, icons and actions, menu placement and named lists, drawn in front of the viewer in the 3D stage.
- Actions: only the actions in `schema/gloss/source.json` that the surface accepts are offered, with fields per type. A refused action, component, icon or field is flagged with its exact path, as the server words it.
- Expressions: text and condition fields check `{{ }}` holes and expressions with the engine copied from Gloss Web (`lib/logic/gui/gui_expr.dart`), complete names and functions as you type, and previews fill holes with a sample viewer.
- New and Rename create and move GUI documents; Import and Export Gloss bundle read and write `gloss-editor-workspace` files (chest GUIs, menus, holograms, presets; documents with `animus:` actions are skipped on export).

Browser checks beyond the schemas are in `lib/logic/gui/gui_validation.dart`. The Java parsers are authoritative.

## Layout

| Path | Contents |
| --- | --- |
| `lib/model/` | Document kinds and documents; documents keep their exact source so unknown keys survive |
| `lib/logic/` | JSON Schema validator, shared schema catalog, data folder import and export, rig sampler, rig rules and edits, Blockbench import; `gui/` the GUI catalog, expressions, checks, layout and Gloss bundles |
| `lib/state/` | Hash routes, editor store with undo, workspace repositories (IndexedDB, memory) |
| `lib/mc/` | Renderer core adapted from Gloss Web: assets and model resolver, meshers, rigs, scene, camera, `npc/` (definition to scene), `rig/` (sampled rig to scene) and `gui/` (holograms and menus to scene) |
| `lib/components/` | Shell and document views; `mc/` holds the WebGL2 stage, renderer and asset loader; `rig/` the rig editor, gizmo and timeline; `gui/` the GUI editors |
| `lib/services/` | Theme preference, hash changes and file picking |
| `lib/config/shared_schemas.g.dart` | `../schema` embedded by `tool/embed_schemas.dart` |
| `web/` | `index.html`, `bootstrap.js` (pre-paint theme), numbered CSS, fonts; `assets/mc/` is generated |
| `tool/` | Schema embedding, Minecraft asset extraction (`mc_extract/`) |

## Deploying

Not yet. The deploy workflow arrives with the hosting phase. `firebase.json` already sets the cache rules, the single rewrite to `/index.html` and the security headers; scripts load only from the site, so the app runs without arcane_jaspr's inline fallback scripts.
