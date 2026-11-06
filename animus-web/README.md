# Animus Editor

The browser editor for Animus. It is a client-mode Jaspr app on arcane_jaspr, hosted on Firebase as `animus-editor.web.app` (project `animus-editor`). The design is in [dev/editor-design.md](../dev/editor-design.md) and the shared contracts in [dev/editor-contracts.md](../dev/editor-contracts.md).

The first screen starts a workspace: blank and named, seeded with the example packs from `../examples/packs` (embedded at build time by `tool/embed_starter_packs.dart`), or opened from a data folder (`plugins/Animus`, or `config/animus` on mod servers) as a directory pick or a zip. Workspaces are listed there with Rename, Duplicate, Export zip and Delete (confirmed; Restore brings the last one back while the tab is open). Every document is checked against the schemas in `../schema` in the browser, and the workspace and its undo steps (50) are kept in IndexedDB. Each document has Visual, Preview, Code and Split views. Nothing is uploaded, and no server is needed for any of it.

## Working without a server

New document (`#/workspace/<id>/new`) lists every kind with a guided first step that makes a valid document (`lib/logic/offline/new_documents.dart`): an NPC from its name, folder, body, skin (a PNG file or address, kept as `editor/skins/<hash>.png` so the preview wears it after a reload) and position, with a behavior from a template; behaviors from the greeter, dialogue, patrol, shop, guard and quest templates; packs, routes, shops, chest GUIs, menus, holograms, rigs (empty or a block figure), animations (empty, bob or wave), skins, text animations and presets. Schematics are not editor documents. Every document has Rename, Duplicate and Delete (confirmed, undoable); a move takes along the NPCs that list a renamed behavior and the animations and NPCs of a renamed rig. Diagnostics offer fixes where the editor knows the repair (`lib/logic/offline/document_fixes.dart`).

An NPC's Preview draws the rig its `rig` names, with its animations playable. A behavior's Preview is a dry run (`lib/logic/offline/dry_run.dart`): pick a trigger, step through what the NPC says, asks and walks to, answer prompts (the matching `dialogue_choice` trigger runs next), flip conditions only a server can answer, and see commands, combat, building and shop trades listed as server only, not run. An NPC that lists the behavior speaks on the stage.

Send to a server (`#/workspace/<id>/sync`) works once a server is paired: Compare sorts the workspace into documents new to the server, different from its copy and already the same; Validate runs the server's validators on the selection; Push sends it as one change set, new documents with no base and replaced ones against the revision and hash the compare saw, so a copy that moved in between is not overwritten. Pull lists the server catalog and copies the chosen documents in as one undo step, keeping local edits unless told otherwise. The workspace stays local.

## Building

Needs Dart 3.13 or newer. arcane_jaspr is a path dependency at `.deps/arcane_jaspr`:

```sh
cd animus-web
tool/link_arcane_jaspr.sh            # symlink ~/Developer/RemoteGit/ArcaneArts/arcane_jaspr; or pass a path
dart pub get
dart run tool/embed_schemas.dart     # after any change under ../schema
dart run tool/embed_starter_packs.dart  # after any change under ../examples/packs
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
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/brand_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/npc_preview_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/rig_editor_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/inspectors_browser.cjs
dart run tool/fake_server.dart &     # for the live editing check
dart run tool/fake_server.dart --data=test/fixtures/npc_browser --port=9897 &
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_FAKE_URL=http://127.0.0.1:9897 ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/npc_browser_browser.cjs
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/live_editing_browser.cjs
dart run tool/fake_server.dart --data=test/fixtures/npc_browser &
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/offline_editor_browser.cjs
```

`offline_editor_browser.cjs` makes a blank workspace and one document of every kind from its guided step, duplicates and deletes with undo, fixes a broken id, plays a rig animation on an NPC, dry-runs a dialogue (answering its prompt) and a shop (a server-only step), exports the zip, checks persistence across a reload and the starter workspace, then pairs with the fake server, pulls an NPC, changes it and pushes the workspace with that conflict resolved, at both widths with no console errors and no sideways scrolling.

`brand_browser.cjs` checks the Animus colours (`lib/config/brand.dart`: pink `#FF5FA8` on near-black `#0D0D0F` in dark, deep pink `#C2185B` on `#F5F3F4` in light, greys for text and borders) in both themes at both widths: the surfaces and `theme-color`, the primary button's fill and contrast, the pink focus ring, the selected view tab, grey panels and the theme toggle, with no console errors and no sideways scrolling.

`rig_editor_browser.cjs` opens `test/fixtures/rig_editor` (the shared golem rig and two clips), moves a bone with the gizmo, undoes it, renames a bone, keys and scrubs an animation, plays it, downloads the server files and the workspace zip, and imports `test/fixtures/bbmodel/robot.bbmodel`, at both widths with no console errors.

`gui_editors_browser.cjs` opens `test/fixtures/gui_editors` (a chest GUI with a list, toggle and variant, one with a Gloss-only action, a shop, a hologram and a menu) and checks that each editor opens its fixture, that an edit round-trips to the document JSON, that a refused action is flagged at its field, that expression completion works, that undo works, that the data folder zip and the Gloss bundle export valid files and that `test/fixtures/gloss_bundle/gloss-workspace.json` imports, at both widths with no console errors.

`npc_preview_browser.cjs` opens `test/fixtures/npc_preview` (every body type, pose and equipment slot, a skin document) and checks that each NPC's Preview draws a non-blank canvas at both widths, that text and orbiting work, and that a loaded skin PNG is drawn, with no console errors. `ANIMUS_SCREENSHOTS` is optional and should point outside the repository.

`inspectors_browser.cjs` opens `../schema/fixtures/export`, edits an NPC field, a trigger and a behavior node, and checks code completion and the diagnostics line jump at both widths.

`npc_browser_browser.cjs` opens `test/fixtures/npc_browser` (six NPCs in three folders with tags, routes, combat, a shop, a rig, a skin and a dialogue behavior). It searches and filters the NPC browser, tags three NPCs and moves two to a new folder (undoing each in one step), switches combat off in bulk, adds and drags route points on the grid, flags and fixes an out-of-range combat value, sets equipment and a skin, and edits an answer in the conversation view. Against the fake server loaded with the same folder (paired with the `spawn` scope) it spawns and despawns a selection. Both widths, no console errors, no sideways scrolling.

`live_editing_browser.cjs` runs against the fake server: it pairs from a link, opens an NPC from the server catalog, edits it, validates on the server, starts a preview, applies, forces a conflict and merges it, cuts the direct path (the editor fails over to the relay) and then every path (it reconnects), applies again and undoes through the server, at both widths with no console errors and no sideways scrolling. `ANIMUS_WEB_URL` and `ANIMUS_FAKE_URL` move it off ports 8098 and 9797.

## Connecting to a server

`/animus editor pair` on a server prints a link to `#/pair/ANM1....`. Opening it (or pasting the link or code under Servers on the home page) shows the server's fingerprint and paths. Pair exchanges the one-time secret for a token over the first path that answers with that fingerprint, then replaces the address so the secret does not stay in history. The token is kept in sessionStorage, or in localStorage too when "Remember this browser" is ticked; saved servers are listed on the home page.

Each paired server has one connection (`lib/services/server/server_connection.dart`). It tries the direct path (`<directUrl>/animus/v1`, fetch and a WebSocket whose first frame is `event-auth`) and the relay (`<relayUrl>/app`, subscribed by fingerprint) in turn, the last good one first. Every `hello` must carry the pinned fingerprint, or the path is refused and nothing more is sent. When a path drops, the other is tried at once; after that it retries every 1, 2, 4 up to 30 seconds, and "Retry now" skips the wait. While online over the relay with a direct path paired, it probes the direct path (15, 30, then every 60 seconds, and at once when the browser comes back online or the tab becomes visible); once `hello` answers with the pinned fingerprint and a direct event stream has stayed open beside the relay stream for 2 seconds, new requests go direct, requests already sent finish over the relay, and events seen on both streams are delivered once. A failed probe leaves the relay session alone. A refused, expired or revoked token asks for a new pairing. Mutating requests carry `X-Animus-Counter`.

## Live editing

A paired server opens as a server workspace (`#/server/<id>`), next to the local ones. Its overview lists the server's catalog; opened documents join the workspace with the revision and hash they came from. The live bar above each document shows the connection and the changed drafts and offers:

- Validate: the server's validators on the changed drafts (or the open document), with their diagnostics shown in the document and the code view, marked as from the server.
- Preview: the changed drafts and the open NPC shown to the token's player only; End preview stops it. The server refuses commands, combat, economy and trades in previews.
- Apply: every changed draft (edited, new or deleted) as one change set with its base revision and hash. When the server's version moved, nothing is written and each document opens in a three-way merge (base, mine, server): fields only one side changed merge by themselves, and each field both changed keeps the side picked. Use merge moves the draft onto the server's revision for the next apply.
- Undo last apply: the server's rollback receipt for the last applied change set.

Unanswered change sets are kept with the workspace. After a reconnect (or a reload) the editor asks the server what became of each by its idempotency key, and reports one that never arrived; one the server is still writing settles from its `change` event. An event stream the server closed because the editor fell 256 events behind (reason `limit`) is explained, reconnected and followed by a fresh catalog load. Catalog events refresh documents that were not edited here; edited ones are marked changed on server, with a Merge button. The server's own schemas (with its modules) drive the inspectors while connected.

## Importing on a server

The Convert Citizens save screen can also import on a paired server: choose the Citizens `saves.yml`, any dtlTraders files and a Shopkeepers `save.yml` and `config.yml`, pick the server under Import on a server and press Upload and stage. The server converts and stages them and shows its review; Apply installs the import dormant, the review categories (spawn, patrols, combat, riding, shops, builder, denizen) are accepted one at a time or all at once, and Roll back undoes the import and every acceptance. The token needs the `import` scope, so pair with `/animus editor pair scopes=read,import,apply:npcs,apply:behaviors,apply:gui`. Over the relay, files larger than one relay frame (about 756 KiB) are refused before they are sent; connect over the direct path for those.

`import_browser.cjs` runs this against the fake server (`POST /__fake/pair?scopes=import` mints a code with the import scope) at both widths, and checks the relay limit.

## Fake server

`dart run tool/fake_server.dart` starts an in-memory editor-v1 server for local development and tests. It loads `../schema/fixtures/export` (`--data=`) once and never writes to disk. One loopback port (`--port=`, default 9797) serves the direct API at `http://127.0.0.1:9797/animus/v1/`, the browser side of the relay at `ws://127.0.0.1:9797/app`, and test controls at `http://127.0.0.1:9797/__fake/`. Allowed origins come from `--origin=` (repeatable, default `http://127.0.0.1:8098`), and previews show to the `--viewer=` UUID. At start it prints a `Pair:` link with both paths in it. The controls have no auth and answer only loopback callers: `POST /__fake/pair?paths=both|direct|relay` mints a fresh code, `POST /__fake/edit` changes a document on the server side, `POST /__fake/drop {"path":"direct|relay|all","seconds":N}` and `POST /__fake/offline {"seconds":N}` cut a connection or take the server off the relay, `POST /__fake/revoke`, `/restore` and `/reset` do what their names say, and `GET /__fake/state` and `GET /__fake/document?kind=&id=` show tokens, previews, changes and documents.

## Inspectors and code view

NPC definitions and behaviors have structured Visual views built from the generated schemas and `completions.json`, or from the connected server's schemas, which include loaded modules. The NPC inspector edits identity, body type, presence, position and behaviors (completed from the workspace), with the typed editors described under NPC browser and editors; other schema fields render generically, with help from the schema descriptions. The behavior editor reads top to bottom: the document (with the server switch), typed variables, routines as a collapsible tree, then triggers. Every node type of the behavior language is editable: sequence, action, condition, wait, repeat, foreach, while, break, continue, parallel, call, return and set. A collapsed row shows the node's type and a plain line ("Say: Welcome", "While count < 3"); Up and Down move between rows, Right and Left open and close one, Delete asks before removing it. Wherever the language takes an operand (`set.value`, `repeat.count`, `foreach.list`, `return.value`, call arguments) one control picks a value typed by the target, a declared variable of a compatible type, an expression, or a template with `{{ }}` holes. Expressions and templates are monospace fields that check the Gloss grammar with the behavior roots, facts and functions as you type and complete names below the field. An action or condition argument can be a null placeholder filled at run time from a variable, an expression or a template; the editor writes the null and the pointer in `bindings`, `expressions` or `templates`. Conditions test a capability or an expression. List, map and any variables have typed starting values. With the server switch on, NPC-only events, scopes and capabilities are hidden and timers start at 20 ticks. Keys the editors do not know are kept. `server-behaviors.json` has its own Visual view: add server behaviors from the workspace, remove and reorder them. In the Code view, Ctrl+Space (or typing in a string) completes keys and values from the same schemas, and names inside expression and template strings. The gutter marks lines with diagnostics, and a list under the text jumps to each one; server diagnostics are tagged. Text that is not JSON gets one message with its line and column, and the line is marked.

## NPC preview

An NPC's Preview (and Split, beside the code) is a WebGL2 stage adapted from Gloss Web's renderer: the body rig in its definition pose (standing, sneaking, sleeping, swimming, sitting), armour layers and held or worn items from all eight equipment slots, the native nameplate, the text display with the hologram lines and long or formatted names, and an orbit camera (drag, right or shift drag to pan, wheel or pinch to zoom). Bodies: player (classic and slim), armor stand (parts, angles, arms, base plate, small, invisible), horse (saddle and armour), turtle, frog, llama (carpet), armadillo and ender dragon. Rig geometry is the 26.3 client's `LayerDefinition`s. Slots a body does not show are listed under the stage.

Player skins: a definition's `skin` names a `skins/<hash>.json` document, from which the preview reads the arm model. Its signed texture comes from the server once the editor connects; until then the preview wears the default skin, or a PNG loaded from a file or a CORS-enabled URL with the controls above the stage (kept for the page session, per skin hash).

## NPC browser and editors

Browse (beside NPCs in the sidebar, `#/workspace/<id>/npcs`) lists every NPC with its folder (`npcs/<group>/`), tags, type, world and features. The search takes words (name, id, type, world, tag) and `tag:`, `folder:`, `type:`, `world:`, `has:` (route, combat, shop, rig, skin, behaviors) and `is:` (spawned, despawned, invalid) terms; a leading `-` negates one. The folder tree, tag cloud and feature chips write the same terms. Selected NPCs can be tagged and untagged, moved to another folder (the `group` and the file follow; an id already in that folder is skipped), have a module switched on, off or removed, be marked spawned or despawned, or be deleted. Each is one commit, so Undo reverts it whole, and on a server workspace it goes out in the next apply. With a token holding the `spawn` scope, Spawn and Despawn on server act on the server at once.

`tags` is part of the NPC schema: at most 32 unique labels of 1-32 lowercase letters, digits, `_` and `-`.

An NPC's Visual view puts the inspector beside the 3D stage. The inspector has typed editors for tags, the skin (workspace skins with their face once a PNG is loaded, or a hash, plus PNG from a file or URL), equipment (a row per slot with suggestions, icon, count and components), the route (`animus:navigation`: waypoints of its own drawn on a top-down grid where a click adds a point or guide and a drag moves one, with order, mode, speed, waits, loop, link distance and wander; or a reusable route shown read-only), combat (`animus:combat`, every section of wiki animus/08-combat, checked in the browser with the server's paths and messages by `lib/logic/npc/combat_settings.dart`), and the shop binding. Other modules get fields from their schema (the connected server's when online, so loaded modules are typed too) or JSON. The stage draws the route's points, guides and links around the body. Route documents open in the same route editor. A behavior that says or asks anything shows its conversation as a tree: what starts it, each line, each prompt with its answers and what each answer's `dialogue_choice` trigger runs, with the lines editable in place.

## Rig editor

A rig's Visual and Preview views (and an animation's, which opens its rig) are the rig editor:

- Bone tree: add a child, rename (tracks in the rig's animations follow), reparent (with its subtree, rest pose unchanged because pivots are rig space), delete (children move to the parent). The 64-bone, depth-16 and parents-first rules of `schema/rig-v1.schema.json` are kept.
- Inspector: pivot, rest rotation, visual type (item, block or text display, or none), item id, `components`, item context, block state, text and billboard, visual offset, rotation and scale; `body` (hidden, or shown on a preview NPC body) and sockets.
- Stage: the bones drawn as the client draws the display entities (item displays turned 180 degrees and centred, block displays from their corner, text displays), the skeleton, and move, rotate and scale handles. In the rest pose a handle edits the pivot (with the subtree), rest rotation or visual scale; with an animation open it keys the bone at the playhead.
- Timeline: animation picker and new animation, duration, loop mode, playback speed, play, a scrubber, a lane per track with keyframe diamonds, and per keyframe its tick, value and easing (`linear`, `hold`, `ease_in`, `ease_out`, `ease_in_out`, `back_out`, `bezier(...)`). Playback uses `lib/logic/rig_sampler.dart`, the Dart port that matches the shared goldens.
- Import .bbmodel: every group becomes a bone, its cubes an item model at `pack/assets/animus/models/item/<rig path>/<bone>.json` with an item definition and the embedded textures beside it, and every Blockbench animation an animation document. The import is bounded (16 MiB file, 4,096 elements, 16 textures of at most 1024 px width, 64 animations, 256 keyframes per channel) and refuses a file over a bound. Cube rotations in 22.5-degree steps stay on the element; others become their own bone. Molang values, catmullrom and bezier keyframes and non-cube elements are approximated or skipped with a warning.
- Build resource pack: downloads `<rig>-server-files.zip` with the rig, its animations and the `pack/assets/` sources its bones use, laid out as data-folder files; `/animus pack build` on the server turns them into the resource pack. The pack layout and zip format are in [wiki animus/04-npc-definitions](https://github.com/VolmitSoftware/docs/blob/master/animus/04-npc-definitions.md#resource-pack-layout), and `schema/fixtures/pack/` (written by `dart run tool/write_pack_fixture.dart`, checked by `test/pack_fixture_test.dart`) is what the server's importer must match. Export zip (top bar) downloads the whole workspace the same way.

Every edit is a workspace commit to IndexedDB and can be undone; gizmo drags commit once on release. Files under `pack/assets/` are kept as workspace resources when a folder or zip is opened.

## GUI editors

Chest GUIs, shops, holographic menus and holograms (Gloss formats, wiki animus/11-guis-holograms-menus and wiki animus/10-shops) have visual editors, adapted from Gloss Web's editors. Visual shows the editor beside its live preview, Preview the preview alone, and Split the editor beside the code.

- Chest GUI: the window painted like the client's (title in the Minecraft font, item icons meshed by the stage renderer at their `gui` pose, counts, name and lore tooltips). Click a slot to edit what draws it: a mask key, an explicit slot or the list template. A brush paints keys into the mask. Tabs edit the window (title, size, mask rows, `show`, refresh), keys, the list (area, variable, source, page size, template), variants (which fields each replaces; the window shows and edits the chosen one) and named action lists.
- Shop: pages, the buy, sell and trade grids painted as the shop window with its bottom-row controls, and per entry its type, item, display, price, trade goods, command entries (with the allow-list note), limits with resets, stock, discount, permission and broadcast.
- Hologram: typed rows (text, item, head, block, entity), pages, variants, anchor, click box and actions, drawn in the 3D stage.
- Menu: components with offsets, data, icons and actions, menu placement and named lists, drawn in front of the viewer in the 3D stage.
- Actions: only the actions in `schema/gloss/source.json` that the surface accepts are offered, with fields per type. A refused action, component, icon or field is flagged with its exact path, as the server words it.
- Expressions: text and condition fields check `{{ }}` holes and expressions with the engine copied from Gloss Web (`lib/logic/gui/gui_expr.dart`), complete names and functions as you type, and previews fill holes with a sample viewer.
- New and Rename create and move GUI documents; Import and Export Gloss bundle read and write `gloss-editor-workspace` files (chest GUIs, menus, holograms, text animations, presets; documents with `animus:` actions are skipped on export). Ids map as the server's `gui import gloss` and `gui export gloss` map them: a Gloss id comes in as `gloss:<id>`, and an Animus id goes out as its path in namespace `gloss`, otherwise as `<namespace>.<path>`.

Browser checks beyond the schemas are in `lib/logic/gui/gui_validation.dart`. The Java parsers are authoritative.

## Layout

| Path | Contents |
| --- | --- |
| `lib/model/` | Document kinds and documents; documents keep their exact source so unknown keys survive |
| `lib/logic/` | JSON Schema validator, shared schema catalog, data folder import and export, rig sampler, rig rules and edits, Blockbench import, change sets and the three-way merge; `inspect/` schema fields, completion and JSON outlines; `gui/` the GUI catalog, expressions, checks, layout and Gloss bundles; `offline/` new-document defaults and templates, fixes, the dry run, the starter workspace and push and pull plans |
| `lib/state/` | Hash routes, editor store with undo, workspace repositories (IndexedDB, memory), the server hub and live sessions |
| `lib/mc/` | Renderer core adapted from Gloss Web: assets and model resolver, meshers, rigs, scene, camera, `npc/` (definition to scene), `rig/` (sampled rig to scene) and `gui/` (holograms and menus to scene) |
| `lib/components/` | Shell and document views; `mc/` holds the WebGL2 stage, renderer and asset loader; `rig/` the rig editor, gizmo and timeline; `gui/` the GUI editors; `inspect/` the NPC inspector, behavior editor and code view; `server/` pairing, connection status, the live bar, merges and the server overview; `offline/` the home page, the New page, the dry run panel and the sync page |
| `lib/services/` | Theme preference, hash changes and file picking; `server/` pairing codes, saved servers and tokens, the direct and relay transports and the connection |
| `lib/config/shared_schemas.g.dart` | `../schema` embedded by `tool/embed_schemas.dart` |
| `web/` | `index.html`, `bootstrap.js` (pre-paint theme), numbered CSS, fonts; `assets/mc/` is generated |
| `tool/` | Schema embedding, Minecraft asset extraction (`mc_extract/`), the fake server (`fake_server/`) |

## Deploying

The editor is live at https://animus-editor.web.app. To deploy a new build from this folder (full steps, the relay and the server settings are in [dev/deployment.md](../dev/deployment.md)):

```sh
dart run tool/extract_minecraft_world_assets.dart --jar=<26.3 client jar> --version=26.3
dart run jaspr_cli:jaspr build
GOOGLE_APPLICATION_CREDENTIALS=<service account path> npx -y firebase-tools deploy --only hosting --project animus-editor --non-interactive
```

Never paste, print or commit the service account key. `firebase.json` sets the cache rules, the single rewrite to `/index.html` and the security headers; scripts load only from the site, so the app runs without arcane_jaspr's inline fallback scripts. The relay deploys separately to Cloud Run; see [relay/README.md](relay/README.md#cloud-run).
