# Animus web editor design

This is the design for `animus-web`, the browser editor (Milestone 6). It is built and deployed at https://animus-editor.web.app; [animus-web/README.md](../animus-web/README.md) describes what it does now and [deployment](deployment.md) how to publish it. The shared contracts are under `schema/` (described in [editor-contracts.md](editor-contracts.md)). It records where the editor lives, what it copies from Gloss Web and React Web, how Animus adopts Gloss's GUI document formats without needing Gloss, how NPCs, rigs and animations are rendered on the server and in the browser, how the browser reaches a server, and the order of work.

Sources studied: `Gloss/gloss-web` and its `sync-relay`, `React/react-web` and its `relay`, the React plugin's web listener, the Gloss plugin (`src/`, `schema/`, `DROP_SCRIPT_FORMAT.md`), arcane_jaspr 6.0.0, and the current Animus docs. The arcane_jaspr codex directory named in the user's instructions does not exist; `arcane_jaspr/arcane_jaspr_docs/arcane_jaspr_docs_web/` is the documentation and example source instead.

## Summary

- `animus-web/` lives in the Animus repository next to the Gradle modules, with the relay in `animus-web/relay/`. It is a client-mode Jaspr app on arcane_jaspr, built and hosted the way Gloss Web is.
- GUI documents (chest GUIs and shops, holographic menus, holograms) use Gloss's JSON formats, parsed and rendered by Animus's own code. Gloss is never required. With Gloss installed, Animus can import from and export to Gloss's folders, and NPC behaviors can open Gloss menus by id.
- NPCs, skins, equipment, poses and holograms render in the browser with a WebGL2 Minecraft renderer adapted from Gloss Web. Character rigs are new: a JSON rig of bones plus JSON keyframe animations. The server plays them as packet-only item and block displays with client-side interpolation, and never creates world entities.
- The server exposes one versioned API. The browser reaches it directly over HTTPS, or through a WebSocket relay that the plugin connects to outbound. Pairing gives each browser a scoped, expiring, revocable token. Editing follows draft, validate, preview, apply, with per-document revisions, conflict reports, undo and retry-safe applies.

## 1. Repository layout

One Animus repository holds everything:

```text
Animus/
  animus-api/ core/ bukkit/ converter/ adapters/   existing Gradle modules
  schema/                       hand-checked wire and format schemas shared by Java and Dart
    editor-v1.schema.json       server API envelopes
    rig-v1.schema.json          rig documents
    animation-v1.schema.json    animation documents
    fixtures/                   golden JSON shared by Java and Dart tests
  animus-web/
    pubspec.yaml                package animus_editor
    lib/
      main.client.dart
      app.dart
      model/                    Animus document models; codecs keep unknown keys
      doctype/                  one adapter per document kind (npc, behavior, pack, route,
                                skin, rig, animation, inventory, menu, hologram, shop)
      logic/                    validation mirrors, expression preview, transform sampler
      mc/                       renderer adapted from gloss-web (assets, mesh, raster, rigs, scene)
      components/               shell, inspector, stage, timeline, tree, json, per-kind editors
      services/                 server client, relay client, pairing, file import/export
      state/                    EditorStore, workspace, undo stack, routes
      l10n/
    web/
      index.html  bootstrap.js  styles/  assets/fonts/  assets/mc/<version>/  manifests/
    tool/                       asset extraction, field-doc extraction, locale tools
    test/                       Dart tests and browser scripts
    relay/                      package animus_relay (Dart shelf) with Dockerfile and tests
    firebase.json  .firebaserc  README.md
  .github/workflows/animus-web-firebase-hosting.yml
```

`schema/` sits at the repository root, as in Gloss, because both the plugin and the editor read it. Java contract tests and Dart tests read the same `schema/fixtures/`, as Gloss Web's `cross_repo_fixture_pins_test.dart` does, but inside one repository there is nothing to pin.

`animus-web/` is not a Gradle module. `./gradlew build` does not run Dart, and the web workflow does not run Gradle except for the contract tests.

## 2. Conventions to copy

Both existing sites share almost everything at the build level. They differ in routing, state and connection. Animus takes the parts below.

| Area | Take from | Detail |
| --- | --- | --- |
| Toolchain | Both | Dart 3.13.x, `jaspr ^0.23.1`, `jaspr_cli`, `jaspr_builder`, `build_runner` and `build_daemon` pinned the same way. `jaspr: {mode: client, styles: standalone}` in `pubspec.yaml`, output in `build/jaspr/`. |
| arcane_jaspr | Both | Path dependency at `animus-web/.deps/arcane_jaspr` (gitignored). Locally it is a symlink to `/Users/creepshow/Developer/RemoteGit/ArcaneArts/arcane_jaspr`. CI clones it at `ARCANE_JASPR_REF`. Also `arcane_jaspr_ascii` by path, with `dependency_overrides` for `arcane_jaspr` and `arcane_jaspr_shadcn`. |
| Theme | Both | `ArcaneApp(stylesheet: const AsciiStylesheet(), brightness: ...)`, dark by default, choice saved in localStorage as `animus.theme`, pre-paint script in `web/bootstrap.js`. Geist, Lucide and Minecraft-Regular fonts as local assets. |
| Styling | Gloss | Numbered CSS files under `web/styles/` (foundations first, stage last). Editor surfaces need more CSS than a dashboard. Follow arcane_jaspr's rule: no one-sided coloured stripes on rounded containers. |
| Routing | Gloss | Hash routes parsed by hand (`state/workspace_route.dart`). Hash routes keep pairing secrets out of server logs and work with the single `/index.html` rewrite. Routes: `#/workspace/<id>`, `#/workspace/<id>/<kind>/<docId>`, `#/pair/<code>`, `#/server/<serverId>/...`. |
| State | Gloss | One `EditorStore extends ChangeNotifier` behind an `EditorScope` InheritedWidget, an undo stack and an IndexedDB workspace repository with cross-tab conflict protection. Split it by area from the start (`npc_store.dart`, `rig_store.dart`, `gui_store.dart`); Gloss's single 3,573-line store is the part not to copy. |
| Connection state | React | `FleetManager`/`ConnectionManager` style controllers with broadcast streams for server connections, kept separate from document state. Saved server profiles in localStorage; tokens in localStorage only when the user ticks "remember this browser", otherwise sessionStorage. |
| Document views | Gloss | Each kind has Visual, Preview, Code (two-way JSON) and Split views. Codecs keep unknown keys so module data and newer fields survive a round trip. |
| Inspector help | Gloss | `tool/extract_field_docs.dart` generates `lib/config/field_docs.g.dart` from schema descriptions. Animus reads its generated `npc`, `behavior` and `pack` schemas plus `schema/*.schema.json`. |
| Localization | Both | JSON locale files and per-locale web manifests. Start with English and the same locale list; untranslated keys fall back to English. |
| Hosting | Both, CSP from React | Single rewrite to `/index.html`, no-store for `/`, `/index.html` and `/main.client.dart.js`, long immutable cache for fonts and `/assets/mc/**`. Security headers from React's `firebase.json` (section 7). |
| Relay | React | WebSocket pass-through relay, Ed25519 server identity, fingerprint pinning, exact origin allowlist, frame and rate limits (section 6). |
| Tests | Both | `dart analyze`, `dart test` for the app and the relay, Playwright `.cjs` scripts against `python3 -m http.server --directory build/jaspr`, and a fake server like React's `tool/visual_qa/server.dart` so the editor can run without Minecraft. |
| Deploy | Both | One workflow: `dart pub get --enforce-lockfile`, analyze, test, `jaspr build`, `npx firebase-tools@<pinned> deploy --only hosting`, then poll the site until it serves a `main.client.dart.js` whose SHA-256 matches the build. |

arcane_jaspr gaps that the editor must fill itself, through `package:arcane_jaspr/web.dart` and raw DOM: a tree view (the runtime has `role="tree"` scripts but no widget), a keyframe timeline, generic drag and drop, the WebGL canvas, and keyboard shortcuts. Split panes use `ArcaneResizable`; inspectors use the field providers (`ArcaneFieldDirectProvider`, typed field nodes); lists use `DataTable`; menus use `ArcaneMenubar` and `ArcaneContextMenu`; the command palette uses `ArcaneCommand`. All Dart callbacks need the hydrated client, which client mode provides.

React Web's CSP sets `script-src 'self'`, while arcane_jaspr's fallback runtime is one inline script. Check how React Web reconciles the two before copying its headers. If it turns off `includeFallbackScripts` and ships the runtime as a file, Animus does the same, so that `'unsafe-inline'` is not needed for scripts.

## 3. Gloss formats in Animus

### What is adopted

Animus reads and runs these Gloss document shapes itself. The field names, nesting, `schemaVersion` numbers and action objects stay identical, so a document is valid in both plugins when it only uses shared features.

| Animus kind | Gloss kind | Gloss version | Animus folder |
| --- | --- | --- | --- |
| Chest GUI | `inventories/` (`gloss-inventories.schema.json`) | 1 | `plugins/Animus/gui/inventories/<namespace>/<path>.json` |
| Holographic menu | `menus/` (`gloss.schema.json`, unversioned) | none | `plugins/Animus/gui/menus/<namespace>/<path>.json` |
| Hologram | `holograms/` (`gloss-holograms.schema.json`) | 3 | `plugins/Animus/gui/holograms/<namespace>/<path>.json` |
| Text animation | `animations/` (frame lists) | 1 | `plugins/Animus/gui/text-animations/<namespace>/<path>.json` |
| Presets | `presets.json` | 1 | `plugins/Animus/gui/presets.json` |

Gloss menus have no envelope. Animus wraps nothing around them either; it identifies them by content hash, as Gloss does, and gives them a revision only inside the editor API (section 6).

Shared pieces Animus implements natively:

- The `{{ expr }}` expression grammar and standard library described in `DROP_SCRIPT_FORMAT.md` (arithmetic, comparisons, ternary, `clamp`, `lerp`, `select`, `bar`, colours and the rest), with the same 512-character and depth limits. Namespaces `player.*`, `viewer.*`, `server.*`, `time.*` and `args.*` mean the same thing. Animus adds `npc.*` (name, id, health, spawned) and `state.*` mapped to the behavior's flags and quests. `papi(...)` works when PlaceholderAPI is installed, which is a PlaceholderAPI feature, not a Gloss one.
- Colour text: `&` codes and MiniMessage, as Gloss.
- Conditions: `show` and `when` as booleans or expression strings.
- The action list (`schema/gloss/source.json` is the source of truth). Animus implements `command`, `sound`, `message`, `title`, `actionbar`, `close`, `navigate`, `inventory`, `setSession`, `give`, `take`, `economy`, `delay`, `sequence`, `if`, `switch`, `chance`, `cooldown`, `stop`, `call`, `teleport`, `bossbar`, `broadcast`, `effect`, `particle`, `parallel` and `repeat`. Gloss-only actions (`connect`, `surface`, `prompt`, `dialog`, `book`, `emit`, `setState`, `addState`, `clearState`, `sky`, `camera`, `glow`) are refused at load with a field diagnostic such as `$.keys.X.actions[1].type: action 'camera' is not available in Animus`. Animus never silently skips an action. Animus-only actions use a namespaced type, today `{"type": "animus:open-shop", "shop": "town:baker"}`, so they never collide with a future Gloss type.
- Chest GUI layout: `title`, `resolution`, `mask`, `keys`, `slots`, `list` paging, `show`, `select`, `variants`, `refresh`.
- Menu components: `button`, `decoration`, `toggle`, `list`, `tabs`, with icon types `text`, `item`, `block`, `playerHead` and `entity`. `customItem` (ItemsAdder, Oraxen, Nexo, CraftEngine, MMOItems) follows later through the same kind of soft integration Gloss uses.

`command` actions use the same allow-list as `animus:command`; a GUI cannot run a console command that a behavior could not.

### Shops

dtlTraders-style shops are an Animus document kind (`shops/<namespace>/<path>.json`, parsed by core's `ShopDocument`). A shop draws its own window: pages of buy, sell and trade grids keyed by slot, with previous page, close, next page and mode switch controls on the bottom row:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "town:baker",
  "title": "&6Baker",
  "modes": ["buy", "sell"],
  "pages": [
    {
      "name": "Bread",
      "rows": 3,
      "buy": {
        "10": {
          "id": "bread",
          "type": "buy",
          "item": {"id": "minecraft:bread", "count": 4},
          "price": 12,
          "stock": {"maximum": 256, "restockSeconds": 1200},
          "limit": {"perPlayer": 64, "resetSeconds": 86400}
        }
      },
      "sell": {
        "10": {"id": "bread-back", "type": "sell", "item": {"id": "minecraft:bread"}, "price": 3}
      }
    }
  ]
}
```

Entry types are `buy`, `sell`, `trade` (`needs` and `gives` item lists), `command` (`commands`, needing the command allow-list) and `static` (decoration). A shop with `owner` and `containers` stocks from and pays into those chests, so it has only trade and static entries, no price and no stock of its own. An NPC opens a shop with `animus:open-shop` or the behavior action `animus:open-gui` (below). Stock and limit counters are kept by the server under each entry's `id`. The dtlTraders and Shopkeepers importers write shops in this form.

### Opening GUIs from NPCs

New built-in action:

```json
{"type": "action", "capability": "animus:open-gui",
 "arguments": {"gui": "town:baker", "kind": "inventory"}}
```

`kind` is `inventory`, `menu`, `shop` or `gloss`. The first three are always Animus's own documents. `gloss` opens a Gloss menu by id through `GlossAPI.open`, and is the only place Animus calls Gloss at run time.

### Without Gloss

Every GUI kind above works. Animus renders chest GUIs with Bukkit inventories, holographic menus and holograms with its own packet-only text, item and block displays and interaction entities (extending `NativePacketAdapter`), and evaluates expressions with its own engine. No class is loaded from Gloss and no feature checks for it. The `gloss` value of `animus:open-gui` reports `gloss-unavailable` when Gloss is missing, which is a correct refusal, not a missing Animus feature.

### With Gloss

Gloss is a soft dependency (`softdepend: [Gloss]`). Animus touches the Gloss API only through one small bridge class, loaded by reflection after startup, so the Animus jar has no compile or runtime requirement on Gloss. The bridge provides:

- `animus:open-gui` with `kind: gloss`.
- `/animus gui import gloss <kind> <id|all>`: reads documents from `plugins/Gloss/<kind>/` on the same server, checks their `schemaVersion` against the versions in the table above, refuses newer versions with the exact field and version, and refuses documents that use actions Animus lacks. Accepted documents are copied into `plugins/Animus/gui/` with a new Animus revision. Nothing in Gloss's folders changes.
- `/animus gui export gloss <kind> <id|all>`: writes the documents into Gloss's folders when they contain no `animus:` actions, so Gloss's watcher loads them. Documents with Animus-only actions are listed and skipped.

Servers without Gloss on the same machine (the usual case) use files instead:

- `/animus gui import file <path>` and `/animus gui export file <path>` read and write the same layout as `/gloss export` (`<kind>/<id>.json` plus `workspace.json`).
- The editor imports and exports `gloss-editor-workspace` bundles, so a GUI made in Gloss Web opens in Animus Web and the reverse.

### Keeping the formats in step

Gloss checks `schemaVersion` by exact match and has no upgrader, so Animus pins the versions it understands. Animus copies Gloss's schema files for the adopted kinds into `schema/gloss/` with the Gloss commit they came from, and its contract tests run the Gloss fixtures through the Animus parser. When Gloss raises a version, Animus adds support for the new version in its own release; until then it refuses the newer documents by name. Animus does not edit Gloss's schemas.

## 4. Rendering plan

### NPC data the editor renders

The NPC definition already holds `type`, `equipment` (eight slots, `components` text), `hologram.lines`, `nameplate.visible`, `pose`, `armorStand` parts and a skin reference to a content-hashed document in `skins/`. The editor renders exactly these, and later the `rig` field below. It adds nothing that the server cannot show.

### Browser renderer

Gloss Web has a hand-written WebGL2 Minecraft renderer (`lib/components/mc/`, `lib/mc/`, about 5,500 lines): vanilla block and item model resolution, element and generated-item meshers, player and mob box rigs, camera, a CSS-3D text layer, glow outlines, and a software rasteriser fallback. Animus Web adapts it into `animus-web/lib/mc/`:

- Player bodies: `mc_player_rig.dart` with classic and slim arms, the outer skin layer, and the player poses Animus supports (standing, sneaking, sleeping, swimming, sitting).
- Skins: Gloss bakes one skin into its assets at build time. Animus needs any skin, so the editor asks the server for the decoded PNG (`GET /v1/skins/{hash}/texture.png`). The browser does not fetch `textures.minecraft.net` directly, because WebGL needs CORS headers on texture images. Offline workspaces use skin PNGs imported with the files.
- Non-player bodies: the observed types are armor stand, horse, turtle, frog, llama, armadillo and ender dragon. Gloss has box rigs for six mobs and draws the rest as billboards. Animus needs proper rigs for its observed types first: armor stand (with part angles, small, arms, base plate), horse (with armor and saddle slots), then the rest. Each rig is a Dart description of boxes and UVs from the vanilla entity textures extracted by `tool/extract_minecraft_world_assets.dart`.
- Equipment: held items through the item model resolver with vanilla display transforms; armour as the vanilla armour layer textures on the player and armor stand rigs. Item `components` text is parsed for `custom_model_data`, `item_model` and enchantment glint where the editor can show them; anything else shows the base item.
- Holograms and names: the CSS-3D DOM layer with the same `&` code and MiniMessage rendering as Gloss (`mc_text`), stacked above the body with even spacing, as the server does.
- Gloss-format GUIs: chest GUI and holographic menu previews come from Gloss Web's per-kind painters, adapted the same way.

Copying this code means two copies to maintain. The alternative is to move the renderer into a shared Dart package that both editors depend on by path. That is a decision for the user (section 9).

### Rig documents

A rig is a tree of bones. Each bone has a pivot, a rest rotation and an optional visual. Visuals are things a display entity can show.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "town:golem",
  "units": "blocks",
  "body": "hidden",
  "bones": [
    {"id": "root", "pivot": [0, 0, 0]},
    {"id": "torso", "parent": "root", "pivot": [0, 1.1, 0],
     "visual": {"type": "item", "item": {"id": "minecraft:paper", "components": "[item_model=\"animus:golem/torso\"]"},
                "translation": [0, 0.3, 0], "scale": [1, 1, 1]}},
    {"id": "head", "parent": "torso", "pivot": [0, 1.6, 0], "rotation": [0, 0, 0],
     "visual": {"type": "block", "block": "minecraft:carved_pumpkin", "scale": [0.6, 0.6, 0.6]}},
    {"id": "label", "parent": "head", "pivot": [0, 2.1, 0],
     "visual": {"type": "text", "text": "&6Golem", "billboard": "center"}}
  ],
  "sockets": {"main_hand": "torso"},
  "animations": {"idle": "town:golem/idle", "walk": "town:golem/walk"}
}
```

- `body`: `hidden` (only the rig shows; a player or mob body still provides the hitbox server-side) or `shown` (the vanilla body shows and the rig adds props such as wings or a backpack).
- Visual types: `item` (any item, including `item_model` and `custom_model_data` from a resource pack), `block` (any block state), `text` (a text display), and `none` for pure pivot bones.
- `sockets` attach equipment slots to bones.
- Limits: 64 bones per rig, depth 16, 256 KiB per document. Bone ids follow the same character rules as behavior ids.

Files live at `plugins/Animus/rigs/<namespace>/<path>.json`. An NPC definition uses one with `"rig": {"id": "town:golem"}`. Rigs follow the existing revision, last-valid checkpoint and reload rules.

### Animation documents

The keyframe vocabulary follows Gloss's clip system (`animation/clip/`): clips with `durationTicks`, a loop mode, tracks with a target, a blend and keyframes with an easing. Using the same words and easings keeps the two plugins familiar to the same users. The format itself is Animus's.

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "id": "town:golem/walk",
  "rig": "town:golem",
  "durationTicks": 20,
  "loop": "loop",
  "tracks": [
    {"bone": "torso", "target": "rotation", "blend": "replace",
     "keyframes": [
       {"tick": 0,  "value": [0, 0, -4], "easing": "ease_in_out"},
       {"tick": 10, "value": [0, 0, 4],  "easing": "ease_in_out"},
       {"tick": 20, "value": [0, 0, -4]}
     ]},
    {"bone": "head", "target": "translation", "blend": "add",
     "keyframes": [{"tick": 0, "value": [0, 0, 0]}, {"tick": 5, "value": [0, 0.05, 0], "easing": "back_out"}]}
  ],
  "events": [{"tick": 10, "capability": "animus:play-sound", "arguments": {"sound": "minecraft:entity.iron_golem.step"}}]
}
```

- Targets: `rotation` (Euler degrees, applied X then Y then Z, as Gloss), `translation`, `scale`, `visible`.
- `loop`: `once`, `loop`, `pingpong`, `hold` (stay on the last frame).
- Easings: `linear`, `hold`, `ease_in`, `ease_out`, `ease_in_out`, `back_out`, `bezier(x1,y1,x2,y2)`, as Gloss.
- `events` run built-in or module capabilities at a tick, through the same schema checks as behavior actions. Only sound and particle style capabilities are allowed here, so an animation cannot run commands or combat.
- Files: `plugins/Animus/animations/<namespace>/<path>.json`. Limits: 1,200 ticks, 64 tracks, 256 keyframes per track.

Behaviors drive them with two new built-in actions:

| Capability | Arguments | Result |
| --- | --- | --- |
| `animus:play-animation` | `animation`, optional `speed` (0.25-4), `loop` override, `layer` (0-3) | Starts the clip on that layer. Higher layers blend over lower ones by each track's `blend`. |
| `animus:stop-animation` | optional `layer`, optional `fadeTicks` (0-40) | Stops the layer and returns to the rig's `idle` animation. |

Navigation switches between the rig's `idle` and `walk` animations by itself when both exist.

### Server playback through packets

The server plays rigs with packet-only display entities, the same way Animus already shows hologram text displays. Nothing joins the world entity list.

1. On presentation, the viewer receives one display entity per visible bone: an item display, block display or text display. All of them ride the NPC body (or, with `body: hidden`, the existing hidden body that still carries the hitbox) through the passengers packet, so they follow movement with no per-tick teleports. This reuses the passenger roster in `NativePacketAdapter` and `PassengerGraph`.
2. The server flattens the bone tree once per keyframe segment. For each bone it multiplies the parent chain into a model-space transform, then folds in the NPC's body yaw, because passengers do not inherit their vehicle's rotation. The result is decomposed into translation, left rotation quaternion and scale, the fields a display entity accepts.
3. Each segment is one metadata packet per changed bone: interpolation start delay 0, interpolation duration equal to the segment length in ticks, plus the new transform. The client interpolates linearly between them.
4. Non-linear easings and multi-track blends are sampled into short linear segments. The default sample step is 2 ticks; a segment that is already linear within 0.01 blocks and 0.5 degrees is merged with the next. The sampler is pure Java in `core/`, with no platform types.
5. Body yaw changes outside a segment (turning to look at a player) resend only the root-level transforms.

Work and packet bounds reuse the `[npcs]` budget model. Proposed new settings under `[rigs]`: `enabled`, `maximumBones` per viewer (default 512), `updatesPerTick` per viewer (default 64 metadata packets), and `sampleTicks` (default 2). When a viewer's budget is short, animation detail drops first: sample steps grow and far NPCs hold their pose. Authoritative state (position, health, hitbox) never waits on animation.

Hit detection keeps using the body's hitbox, not the bones. Rig visuals are cosmetic.

### Resource pack models

Bones that use vanilla items and blocks need no resource pack. Custom shapes need item models. Animus generates a pack from rigs whose bones reference `animus:` item models:

- The editor imports Blockbench `.bbmodel` files. Every group becomes a bone and its cubes one item model, and each Blockbench animation becomes an animation document. Cube rotations in 22.5-degree steps on one axis stay on the cube; other rotations become bones, where display transforms allow any angle. The files and ids are in [runtime.md](https://github.com/VolmitSoftware/docs/blob/master/animus/04-npc-definitions.md#resource-pack-layout).
- `/animus pack build` writes a reproducible zip (sorted entries, stored, one fixed date) of `pack/assets/` and the server's own conversions of `models/*.bbmodel`, and a mergeable folder under `plugins/Animus/pack/out/`. The zip format is documented so the editor can build the same zip in the browser.
- Delivery is the operator's choice: an operator URL with `{sha1}`, or merging the folder into an existing server pack. Animus does not start a pack web server by default.

Player-skinned limbs (a rig that moves a player's own arms and legs) need pack models that map the player head texture onto limb shapes. This is a research task in phase 4 with a fallback: the vanilla player body with rig props attached and the vanilla swing, crouch and pose animations.

### Browser playback

The editor plays animations with a Dart port of the same sampler, so what the timeline shows is what the server sends. `schema/fixtures/animation/` holds rigs, clips and the expected sampled transforms at chosen ticks. Java and Dart tests both check them within 0.0001 blocks and 0.01 degrees. The stage shows the rig on the NPC body, with bone gizmos for pivot, rotation and translation, and a timeline component (tracks per bone, keyframe diamonds, easing picker, scrubber, loop range, playback speed).

## 5. Editor features

In the order users meet them:

- Workspace: local IndexedDB workspaces, open a folder or zip of Animus files, export a zip, import converter output and Gloss workspace bundles. Everything except preview and apply works with no server.
- NPC list: search, folders from the `group` path, tags, multi-select bulk edits (equipment, skins, behaviors, combat enable), diagnostics badges from validation.
- NPC editor: the 3D stage plus inspectors for identity, location, appearance, skin, equipment, pose, armor stand parts, hologram, nameplate, owner, inventory slots, combat settings, mounts and module configuration. Unknown module configuration shows as JSON and is kept.
- Paths: waypoint routes on a top-down block map around the NPC, from the server's chunk snapshot when connected.
- Behaviors: a structured tree editor for routines and triggers plus a JSON view with schema completion from the server's generated `behavior.schema.json`, references between behaviors, routes, dialogue choices and GUIs, and execution diagnostics from preview runs.
- GUIs and shops: Gloss-style visual editors for chest GUIs, menus, holograms and shops.
- Rigs and animations: bone tree, stage gizmos, timeline, Blockbench import, pack build trigger.
- Import review: upload a Citizens save to a connected server's staged import, then review the converter's per-NPC report, warnings and preserved data before applying.

The Java converter stays the only converter. The editor does not reimplement Citizens conversion in Dart. "Offline conversion" means running `converter` locally and opening its output in the editor, or uploading the save to a server for a staged import.

## 6. Server API and connections

### One API, two paths

The plugin serves one API, `/animus/v1`, as HTTP JSON requests plus one WebSocket for events. The same handlers answer both paths:

```text
Direct:  browser (https://animus.volmitsoftware.com)  --HTTPS/WSS-->  plugin listener (behind TLS)
Relay:   browser  --WSS-->  relay  <--WSS (outbound from plugin)--  plugin
```

This follows React rather than Gloss. Gloss's long-poll snapshot relay suits whole-workspace publishing, but Animus needs live previews, validation round trips and server events while the user edits, which a request and event channel handles better.

### Direct path

- The listener is on by default (`[editor] listenerEnabled = true`). It binds `listenAddress` and `port` (default `127.0.0.1:9797`).
- An HTTPS page cannot call plain HTTP or WS endpoints, except that Chrome and Firefox allow `http://127.0.0.1` and `http://localhost`. Safari does not. So the direct path is for:
  - an HTTPS reverse proxy (Caddy, nginx, a host panel) in front of the listener, given to Animus as `advertisedUrl = "https://..."`;
  - or Animus serving TLS itself from an operator-provided certificate and key (`tlsCertificate`, `tlsPrivateKey` paths, PEM);
  - or local development on the same machine through `http://127.0.0.1`.
- Animus refuses to put a plain `http://` non-loopback address into a pairing code, so a user never receives a link that the browser will block.

### Relay path

- The plugin opens an outbound WSS connection to `relayUrl` (on by default, using the Volmit relay at `wss://animus-relay-823521865655.us-central1.run.app`), so it works behind NAT and on hosts that allow no inbound ports. This is the path most servers will use.
- Identity: the plugin keeps an Ed25519 key in `state/editor/identity.json` (`EditorIdentity`). The relay sends a challenge, the plugin signs `animus-relay/1 ` followed by the nonce, and the relay registers the server under `sha256:` and the hex SHA-256 of the raw public key, the same fingerprint as in pairing codes.
- The browser subscribes by fingerprint and sends `relay-request` frames `{id, method, path, headers, body}`. The relay rewrites request ids so each response reaches only the browser that asked. `GET /v1/events` with the token in `Authorization` opens an event stream on the same connection; either side ends it with a `relay-close` frame. The relay writes `Origin` and `X-Forwarded-For` from the browser's connection, so the plugin's origin check and audit log work as on the direct path.
- The relay keeps no documents and no state on disk. All state is in memory and lost on restart; both ends reconnect.
- Limits: 1 MiB frames, 120 messages per second per browser, 64 pending requests and 4 streams per browser, 64 browsers per server, 10 s handshake and request timeouts, 30 s pings, an exact `ALLOWED_APP_ORIGINS` list on the browser handshake. Imports over 1 MiB need the direct path.
- The relay can read the bearer tokens and documents it forwards, as React's relay can. End-to-end encryption is deferred to a later release (Decisions); until then the relay operator can read the traffic it forwards. Tokens stay scoped, expiring and revocable.
- The protocol, settings and Cloud Run steps are in `animus-web/relay/README.md`; the plugin side is `core/.../editor/relay/EditorRelayClient.java`, which replays frames into `EditorRequestHandler`, the same transport-neutral API the direct listener serves.

### Pairing and credentials

- `/animus editor pair [label] [scopes=...] [groups=...] [expires=7d]` (needs `animus.admin`) prints a link: `https://animus-editor.web.app/#/pair/ANM1.<base64url JSON>`. The JSON holds `directUrl` (if any), `relayUrl`, the server public key and fingerprint, a token id and a one-time pairing secret. The secret lives only in the URL fragment, which browsers never send to the host.
- The browser exchanges the pairing secret once for a token (`POST /v1/pair`). The pairing secret expires after 10 minutes or first use.
- Tokens are a random id plus 32 random bytes. Animus stores only a SHA-256 hash, the label, the issuing admin's UUID, scopes, creation time, expiry and last use, in `state/editor/tokens.json`. Comparison is constant time.
- Scopes:

  | Scope | Allows |
  | --- | --- |
  | `read` | Catalog, documents, schemas, diagnostics, skins, events |
  | `validate` | Validation of drafts; writes nothing |
  | `preview` | Viewer-isolated previews for the token's admin player |
  | `apply:npcs`, `apply:behaviors`, `apply:gui`, `apply:rigs` | Applying change sets of those kinds |
  | `import` | Uploading saves for staged import and applying an import |
  | `spawn` | Spawn and despawn |
  | `admin` | Token management and module status |

  Pairing defaults to `read validate preview apply:npcs apply:behaviors apply:gui apply:rigs` and 7 days. Tokens can also be limited to NPC groups (`groups=town,harbor`).
- `/animus editor tokens` and `/animus editor revoke <id|all>` manage tokens, and `/animus editor status` shows the listener, the relay and open streams. The names are the same on every platform. Revocation closes the token's open connections at once. Removing `animus.admin` from the issuing player revokes their tokens at the next check (on connect and every 60 s).
- Mutating requests carry `X-Animus-Counter`, a strictly increasing number per token, as React does, so a captured request cannot be replayed.
- Origin checks: the direct listener and the relay accept only origins in `allowedOrigins` (default `https://animus.volmitsoftware.com` and `http://127.0.0.1:8098` for local development). Requests with another `Origin` are refused with `origin-not-allowed`. There are no wildcards and no cookies.
- Audit: every pairing, revocation, validate, preview, apply, undo and import is appended to `state/editor/audit/<yyyy-mm-dd>.jsonl` with token id, admin UUID, address, path, documents touched and result. Files older than `auditDays` (default 30) are removed at startup.

### Endpoints

| Method and path | Scope | Purpose |
| --- | --- | --- |
| `GET /v1/hello` | none | Protocol version, server fingerprint, Animus version, platform and target. No secrets. |
| `POST /v1/pair` | pairing secret | Exchange the pairing secret for a token. |
| `GET /v1/capabilities` | read | Which features this server supports, with reasons for unavailable ones (Folia combat, rigs disabled, PlaceholderAPI missing, Gloss bridge present or not). |
| `GET /v1/schemas` | read | The generated `npc`, `behavior` and `pack` schemas (including loaded modules) and the rig, animation and GUI schemas. |
| `GET /v1/catalog?kind=&after=` | read | Ids, paths, revisions and hashes of every document, paged. |
| `GET /v1/documents/{kind}/{id}` | read | One document with its revision and hash. |
| `GET /v1/skins/{hash}/texture.png` | read | Decoded skin texture for the renderer. |
| `POST /v1/validate` | validate | Validate a change set as a candidate. Returns field diagnostics per document. Writes nothing. |
| `POST /v1/previews` | preview | Start or replace a preview from a change set. |
| `DELETE /v1/previews/{id}` | preview | End a preview. |
| `POST /v1/changes` | apply:* | Apply a change set (below). |
| `GET /v1/changes/{key}` | read | Outcome of a change set by idempotency key. |
| `POST /v1/changes/{id}/undo` | apply:* | Undo an applied change set through its rollback receipt. |
| `GET /v1/imports` | import | Where the drop-in import stands, with its review. |
| `POST /v1/imports?file=&name=` | import | Upload a Citizens `saves.yml`, a Shopkeepers `save.yml` or `config.yml`, or a dtlTraders file (16 MiB limit) to where `/animus import citizens` looks first. Refused while an import is applied. |
| `POST /v1/imports/stage` | import | Stage the uploaded saves (`mode`, `label`, `worlds`) as `/animus import citizens` does; returns the review. Installs nothing. |
| `POST /v1/imports/{id}/apply`, `/accept`, `/rollback` | import and apply:npcs, apply:behaviors, apply:gui | Apply the staged import, accept review categories, or roll the import and every acceptance back. `{id}` must be the staged import. |
| `POST /v1/npcs/{id}/spawn`, `/despawn` | spawn | Same checks as the commands. |
| `WS /v1/events` | read | Catalog changes, diagnostics, preview status, apply results. First frame is `{"type": "auth", "token": ...}`; 10 s to send it. |

### Draft, validate, preview, apply

- Drafts live in the browser workspace (IndexedDB), as in Gloss Web. The server holds no drafts, so a disconnect loses nothing.
- A change set lists documents, each with `kind`, `id`, the new source (or `delete`), and the `baseRevision` and `baseHash` the edit started from:

  ```json
  {
    "idempotencyKey": "6f1c...",
    "documents": [
      {"kind": "npc", "id": "town/guard", "baseRevision": 7, "baseHash": "sha256:...", "source": {"...": "..."}},
      {"kind": "behavior", "id": "town:greeter", "baseRevision": 2, "baseHash": "sha256:...", "source": {"...": "..."}}
    ]
  }
  ```

- Validate runs the same validators as `/animus validate` and `/animus behaviors validate` against the candidate registry and catalog, including cross-document checks (behavior references, GUI references, rig and animation pairs).
- Preview shows the candidate only to the token's admin player, if online:
  - previewed NPCs are presented to that one viewer as packet-only bodies, never world entities and never saved; for an NPC that already exists, the viewer sees the candidate in place of the live one, and a new NPC appears only for them; other players keep seeing the live version;
  - preview NPCs stand still at their definition position with their appearance, equipment and skin; they run no behaviors or triggers, and the live NPC they replace keeps running for everyone else. For the viewer the live NPC is hidden and stops affecting them: they fire none of its proximity, interaction or choice triggers and receive none of its speech, sounds or particles, until the preview ends. The `preview-response` lists these live NPCs in `hidden`;
  - `animus:command`, `animus:combat-control`, `give`, `take`, `economy` and `animus:trade` are refused in previews with `preview-refused`; preview NPCs take and deal no damage, and clicking or hitting one does nothing: no trigger, selection, GUI, shop or ride;
  - a viewer has one preview at a time: replacing it swaps the shown set, and a preview from another token ends the earlier one;
  - a preview ends after 15 minutes, when the token's connections close, when the player leaves, or on request, and the viewer then sees the live NPCs again.
- Apply re-validates, then writes every document in one transaction through the existing journal on the shared file lane, together with its checkpoints and a rollback receipt. It is all or nothing: if any document's base revision or hash no longer matches, nothing is written and the response is `conflict` with the current server version of each conflicting document.
- The editor resolves conflicts field by field with a three-way view (base, mine, server), then sends a new change set based on the server's revision.
- The response returns only after the journal commit, so an `applied` answer is durable. Each applied change set gets a change id and the new revisions.
- Retries are safe: the server remembers `idempotencyKey` results for 24 hours. After a disconnect the editor asks `GET /v1/changes/{key}` and learns whether the change set applied, instead of guessing.
- Undo uses the rollback receipt, which already refuses to overwrite later changes; a refused undo comes back as `conflict`.
- Gloss menus have no revision field. The API gives them revision 0 and uses their content hash as the base, as Gloss does.

### Bounds

Request bodies 4 MiB (imports 16 MiB), change sets 512 documents, 8 concurrent requests per token, 64 open connections in total, events at most 20 frames per second per connection with coalescing, previews at most 4 at once per server and 64 NPCs each. All editor work runs on the existing file lane and engine queues with their admission limits; a full queue answers `busy`, never blocks a server thread.

### Settings

```toml
[editor]
enabled = true
editorUrl = "https://animus-editor.web.app"
listenerEnabled = true
listenAddress = "127.0.0.1"
port = 9797
advertisedUrl = ""
tlsCertificate = ""
tlsPrivateKey = ""
relayEnabled = true
relayUrl = "wss://animus-relay-823521865655.us-central1.run.app"
relayReconnectMinSeconds = 1
relayReconnectMaxSeconds = 60
relayFrameBytes = 1048576
relayConcurrentRequests = 64
allowedOrigins = ["https://animus-editor.web.app", "http://127.0.0.1:8098"]
tokenDays = 7
auditDays = 30
requestBodyBytes = 4194304
importBodyBytes = 16777216
changeSetDocuments = 512
concurrentRequestsPerToken = 8
requestsPerSecond = 20
pairAttemptsPerMinute = 10
openConnections = 64
eventFramesPerSecond = 20
previewsPerServer = 4
npcsPerPreview = 64
previewMinutes = 15
idempotencyHours = 24

[editor.notify]
enabled = true
chat = true
actionBar = false
console = true
# [editor.notify.sounds.<apply|rollback|import|spawn|despawn|pair|revoke|refused>] sound, volume, pitch
```

`EditorNotices` turns server-side editor events (apply, undo, import steps, spawn, despawn, pair, revoke, and refusals of the server-changing endpoints) into in-game notices. Success notices are recorded when `EditorHost.applied` completes, which chains on `CatalogReloads.reloaded`, so reload problems are reported. Events merge per token, kind and verb until 400 ms pass quietly or 3 s since the first; the service ticks it every 100 ms. Platforms deliver through `EditorNoticeChannel` on each player's own scheduler.

The listener is Animus's own small HTTP/1.1 and WebSocket server on virtual threads (no extra library to shade), in `core/` behind `EditorRequestHandler`, so Bukkit, the mod loaders and the relay client share every handler. A listener on a non-loopback address refuses to start without `tlsCertificate` and `tlsPrivateKey` (PEM chain and unencrypted PKCS#8 key; relative paths are under the data folder). Unknown `[editor]` keys are refused at startup.

## 7. Hosting and deployment

- Firebase project and site: `animus-editor` (name to confirm), custom domain `animus.volmitsoftware.com` (to confirm).
- `animus-web/firebase.json`: public `build/jaspr`, rewrite `**` to `/index.html`, the cache rules from section 2, and React's security headers with a tighter `connect-src`:

  ```text
  default-src 'self';
  connect-src 'self' https: wss: http://127.0.0.1:* http://localhost:* ws://127.0.0.1:* ws://localhost:*;
  script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:;
  worker-src 'self' blob:; frame-ancestors 'none'; object-src 'none'; base-uri 'none'
  ```

  plus `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and a `Permissions-Policy` that denies camera, microphone and geolocation. `connect-src` must stay open to `https:` and `wss:` because each server's direct URL is different.
- `.github/workflows/animus-web-firebase-hosting.yml` runs on changes to `animus-web/**`, `schema/**` and the core schema generators. It runs `dart pub get --enforce-lockfile`, `dart analyze` and `dart test` for the app and the relay, the Java schema contract tests, `jaspr build --dart-define=ANIMUS_VERSION=... --dart-define=ANIMUS_COMMIT=...`, and then `npx firebase-tools@<pinned> deploy --only hosting` with `${{ secrets.FIREBASE_SERVICE_ACCOUNT_ANIMUS }}`. After deploy it polls the site until `main.client.dart.js` matches the built file's SHA-256, then runs the Playwright scripts against the live site.
- Local commands, documented in `animus-web/README.md`:

  ```sh
  cd animus-web
  ln -s /Users/creepshow/Developer/RemoteGit/ArcaneArts/arcane_jaspr .deps/arcane_jaspr
  dart pub get
  dart run tool/extract_minecraft_world_assets.dart --jar=<client jar> --version=26.3
  jaspr serve                     # development
  jaspr build                     # build/jaspr
  python3 -m http.server 8098 --directory build/jaspr
  dart run tool/fake_server.dart  # editor API without Minecraft
  ```

- The relay is not deployed by CI. `animus-web/relay/README.md` documents building its image (distroless, non-root) and running it as one Cloud Run instance with `ALLOWED_APP_ORIGINS`, behind Cloud Run's TLS. The user publishes it.
- The service-account file is never read, printed, copied or committed; only the GitHub secret is used.

## 8. Implementation order

### Phase 0: contracts

Before parallel work, write the shared contracts with fixtures and contract tests on both sides:

- `schema/editor-v1.schema.json`: request, response, change-set, diagnostic and event envelopes.
- `schema/rig-v1.schema.json`, `schema/animation-v1.schema.json`, and sampled-transform fixtures.
- `schema/gloss/`: the copied Gloss schemas for adopted kinds, with the Gloss commit, and the list of supported and refused actions.
- The pairing code format and token scopes.

### Phases 1 to 4: parallel lanes

| Lane | Work | Depends on |
| --- | --- | --- |
| A. Server API | Transport interface in `core/`, listener with optional TLS, `hello`, `capabilities`, `schemas`, `catalog`, `documents`, `validate`, `changes` with idempotency and undo, events, tokens, pairing, origin checks, audit, bounds. | Phase 0 |
| B. Relay | `animus-web/relay`: agent and app paths, Ed25519 registration, routing, limits, tests; plugin-side relay client that replays frames into the same handlers. | Phase 0 |
| C. Editor shell | Project setup, theme, routes, workspace and IndexedDB, file import and export, NPC list, schema-driven inspectors, JSON view with completion, undo, fake server tool. | Phase 0 |
| D. Renderer | Adapt the Gloss Web renderer, asset extraction for 26.x, player rig with skins and poses, equipment, holograms, armor stand and horse rigs, then the other observed types. | C (shell only) |
| E. GUI runtime (server) | Expression engine, Gloss-format chest GUIs, holograms and menus on Animus's packet adapter, actions, `animus:open-gui`, shops with stock and limits, Gloss bridge and file import and export. | Phase 0 |
| F. Rig runtime (server) | Rig and animation documents with checkpoints, the sampler in `core/`, display bones on the passenger roster, budgets, `animus:play-animation` and `animus:stop-animation`, navigation idle and walk, pack build. | Phase 0 |
| G. GUI editors (web) | Chest GUI, menu, hologram and shop editors and previews, Gloss workspace bundle import and export. | C, D |
| H. Rig editor (web) | Bone tree, gizmos, timeline, Dart sampler on the shared fixtures, Blockbench import, pack build trigger. | C, D |

A, B, C, E and F can start together after phase 0. D starts once the shell runs. G and H start once D renders a body.

### Phase 5: connecting it

- Pairing flow in the browser, direct and relay connection with failover between them, fingerprint pinning.
- Validate, preview and apply in the editor, conflict view, change status after reconnect, undo.
- Import upload and review.
- Payload encryption between browser and plugin over the relay (if required for the first release).

### Phase 6: hosting

- Firebase project, site and domain, `firebase.json`, workflow, first deploy, browser checks against the live site.
- Relay image and Cloud Run instructions for the user.
- The Milestone 6 run-through: upload the supplied save to a test server, review the conversion, edit single NPCs, connect, preview, apply live, and recover from a conflict and from a dropped connection.

## 9. Decisions for the user

1. Renderer and GUI editor code from Gloss Web: copy into `animus-web/lib/mc/` (simple, two copies to maintain) or move into a shared Dart package used by both editors (one copy, but Gloss Web changes too and Animus gains a second path dependency next to arcane_jaspr). This design assumes copying.
2. Domains and names: `animus.volmitsoftware.com` for the editor, `relay.animus.volmitsoftware.com` for the relay, Firebase project `animus-editor`.
3. Relay payload encryption: required for the first release, or added after it. Without it the relay operator can read forwarded tokens and documents, as with React's relay today.
4. Player-skinned limb rigs: worth the research spike in phase 4, or keep player NPCs on the vanilla body with rig props only.

## Decisions

Recorded 2026-11-04:

- Copy gloss-web's renderer and GUI editors into `animus-web`. No shared package.
- Deploy to the Firebase default host `animus-editor.web.app` (project `animus-editor`). A custom domain comes later.
- Relay end-to-end encryption is deferred to a later release. Until then the relay operator can read the traffic it forwards.
- Player skins keep the vanilla body. Rigs animate non-player models and attached display-entity parts.
