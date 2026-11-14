# Animus tutorial clips

Records the in-game tutorial clips (`<id>-ingame.webm`) on a real Minecraft client, and the web editor clips (`<id>-editor.webm`) with Playwright. Each in-game clip is one sheet in `sheets/`; changing what a clip shows means editing its sheet, not code.

## Layout

| Path | What it is |
|---|---|
| `demo.py` | Command line: check, list, record, cleanup. |
| `sheet.py` | Loads and validates sheets, compiles one into the recorder's step script. |
| `stage.py` | The burner 26.3 servers, isolated, in the shared Multiplexor workspace: Paper (`animus-clips-paper-26-3`) and, for sheets with `stage.platform: fabric`, Fabric (`animus-clips-fabric-26-3`, the `adapters/fabric` mod with Fabric API, ViaFabric, ViaVersion and ViaBackwards). Builds the town and flat worlds once per platform, keeps a copy of each, and resets to it before every clip. |
| `client.py` | Prepares the Prism instance, queues scripts, launches it hidden, and restores the instance afterwards. |
| `clip_check.py` | Checks a clip: VP9, 1920x1080, length within a third of the sheet's target. |
| `stage/town/` | The town: `town.mcfunction` (the build, sent through the console) and `Animus/` (NPCs, routes, behaviors, shop, GUI, skins). |
| `mod/` | Animus Camera, the Fabric 26.2 client mod that hides the window, drives the game from step scripts and writes the webm through ffmpeg. Its own Gradle build, not part of the plugin. |
| `editor/` | The web editor recorder: `lib.cjs` (shared helpers), one script per clip, `record.cjs`. |
| `tests/` | Python tests for sheets, clip checks and cues. The mod's motion tests are in `mod/src/test`. |

## Record one clip

```
python3 qa/demo/demo.py record 04-palette
```

From the repository root. This builds the mod and `:bukkit:shadowJar`, creates the stage, installs the mod in the Prism instance, records, checks the clip, then deletes the stage and puts the Prism instance back as it was. The clip lands in `build/demo/clips/04-palette-ingame.webm`, the client's step log in `build/demo/clips/logs/`.

All in-game clips: `python3 qa/demo/demo.py record --all`.

Useful options:

| Option | Meaning |
|---|---|
| `--output <dir>` | Where clips go. |
| `--animus-jar <jar>` / `--fabric-jar <jar>` / `--mod-jar <jar>` | Skip a build and use this jar (Paper plugin, Fabric mod, recorder). |
| `ANIMUS_DEMO_STAGE` (environment) | Name of the Paper stage instance; the Fabric stage takes the same name with `fabric` for `paper`. |
| `--keep-stage` / `--keep-client` | Leave the server or the Prism changes for the next run. `demo.py cleanup` removes both later. |
| `--player <name>` | The recording account. Default: the active Prism account. |
| `--plugins <dir>` | Plugin jars. ViaVersion and ViaBackwards are downloaded here on first use (the Fabric stage's jars in `fabric/`); third-party plugins a sheet names (ModelEngine, MythicMobs and so on) must be put here by hand. Default `build/demo/plugins`. |

`python3 qa/demo/demo.py check` validates every sheet; `list` shows each sheet's target and estimated length.

Needs: Prism Launcher with the `[AUTOMATER]` instance (Fabric 26.2, logged in with an account that owns Minecraft; override with `ANIMUS_DEMO_INSTANCE`), Java 25, `ffmpeg` with libvpx-vp9 (`ANIMUS_DEMO_FFMPEG` to point elsewhere), `ffprobe`, tmux, and the ServerMultiplexor checkout next to this repository. The 26.2 client joins the 26.3 stage through ViaVersion and ViaBackwards.

Clips record one after another: one account holds one session. The client window is never shown; a watchdog kills it if it ever turns into a foreground app.

## Sheets

```json
{
  "id": "23-conversation-editor",
  "title": "Conversation editor",
  "description": "Writing a conversation with one question in the block editor",
  "target": 52,
  "stage": {"world": "town", "console": ["animus create greeter name=greeter world=world x=-2.5 y=64 z=-9.5"]},
  "setup": [{"run": "/gamemode creative"}, {"tp": [-14, 64, 8, -90, 5]}],
  "beats": [{"palette": true}, {"click": "Behaviors"}, {"result": "the Behaviors screen", "ms": 1800}],
  "closing": "plaza:welcome is saved: one line, one question, one answer"
}
```

- `description` is the video's aria-label in the docs. `target` is the intended length in seconds.
- `stage.world` is `town` (stand at y=64, the plaza and its cast) or `flat` (stand at y=61, empty). `stage.console` runs after the reset, before the client joins. `stage.files` writes files under `plugins/` before the server starts (on Fabric, `Animus/` lands in `config/animus/`) (JSON, text, `{"from": "stage/assets/<file>"}`, or `null` to remove a file from the reset copy). `stage.plugins` names third-party plugins. `stage.after` runs other sheets first, unrecorded, when a clip needs the state they leave. `stage.titleScreen` starts the client on the title screen instead of joining. `stage.reset: false` keeps the previous clip's world.
- `cues` map a name to console commands. A `{"cue": "<name>"}` beat makes the player run a harmless command; the recorder sees it in the server log and sends those commands, for things only the console can do mid-clip.
- `client.options` overrides `options.txt` keys for one clip (clip 02 widens the chat for the long help pages).
- `client.packs` maps a resource pack file name to a file under the stage's `plugins/`; it is copied into the Prism instance's `resourcepacks/` and enabled for that clip, then removed (clips 31, 32 and 35b load the pack Model Engine generates: `{"animus-modelengine.zip": "ModelEngine/resource pack.zip"}`).
- `setup` runs before recording starts, `beats` are recorded, then `closing` shows as a caption for 3 s.
- `{player}` and `{address}` in commands are filled in at run time.
- `pace.clickRest` sets how long the cursor rests on a slot before each click (default 400 ms, 300 to 800). `seed` fixes the cursor paths (default: derived from the id, so re-records move the same way).

Beats are one action each, plus an optional `note`. The shorthand ones:

| Beat | Does |
|---|---|
| `{"tp": [x, y, z, yaw, pitch]}` | Silent teleport. |
| `{"run": "/cmd"}` | Silent command. |
| `{"type": "/cmd"}` | Opens chat and types the command at typing speed. |
| `{"palette": true}` | Types `/animus tools` and waits for the palette. |
| `{"click": "Name"}`, `{"clickSlot": 8}` | Glides to the slot, rests, clicks. `button: "right"`, `sneak: true`, `rest`, `glide`. |
| `{"hover": "Name", "ms": 1800}`, `{"hoverSlot": 10}` | Glides to the slot and holds so the tooltip and lore are read (default 1500 ms). |
| `{"anvil": "text"}` | Types into the anvil and takes the result. |
| `{"screen": "Anvil"}`, `{"expect": "Saved"}` | Waits for a screen title or a chat line. |
| `{"result": "what the viewer reads", "ms": 2000}` | Holds still on a visible result; 1500 to 2500 ms. Every clip has at least one. |
| `{"cue": "name"}` | Fires a cue. |

Any other recorder step can be used as written (`wait`, `perspective`, `hotbar`, `lookAtEntity`, `look`, `lookAt`, `attackTarget`, `useItem`, `sneakHold`, `move`, `key`, `closeScreen`, `clearChat`, `clickChat`, `hoverChat`, `clickButton`, `caption`, `join`, `waitForWorld`). `sheet.py` lists the options each takes.

## Cursor

The cursor never parks. Each glide starts where the cursor is, a new screen keeps the last position, and the cursor hides only while no screen is open. A glide is a cubic Bezier bent a few pixels to one side, eased in and out, timed by distance (180 to 900 ms), and moves of 150 GUI pixels or more pass the target by up to 6 pixels and settle back. Clicks on slots, chat text and buttons all rest on the target before clicking. Typing speed and teleports are unchanged.

## Web editor clips

Serve the editor and the fake server from `animus-web/`:

```
dart run tool/fake_server.dart --port=9991 --origin=http://127.0.0.1:8191 --data=test/fixtures/npc_browser
python3 tool/serve_site.py --port 8191
```

Then `node qa/demo/editor/record.cjs 05-conversation`, or `--all`. Flags: `--web=` (`ANIMUS_WEB_URL`), `--fake=` (`ANIMUS_FAKE_URL`), `--pair=` (`ANIMUS_PAIR_LINK`), `--out=` (`ANIMUS_DEMO_OUT`, default `build/demo/editor`). Clips are 1920x1080 VP9, like the in-game ones, so `clip_check.py` checks them too.

Each clip's header says what it needs:

| Clips | Needs |
|---|---|
| 01-10, 15 | the fake server with `test/fixtures/npc_browser` |
| 13, 14, 16-21, 32-37, 43, 48 | only the site (browser workspaces, made before filming; 20 opens `editor/fixtures/harbor-server` as a data folder) |
| 22-31 | the fake server with `../qa/demo/editor/fixtures/town-deep` |
| 38-42 | the fake server with `test/fixtures/gui_editors` |
| 11, 12, 44-47, 49 | a real server on the town stage, paired with `--pair=` |

For the real server clips, build the stage's world once with `stage.py` (the town data, the plugin jar from `:bukkit:shadowJar`), set its `[editor]` `port` to a free port, add the site's origin to `allowedOrigins`, point `editorUrl` at the site and turn `relayEnabled` off. Each pairing link works once, so make one per clip from the console with `animus editor pair`; a console pairing has every scope but `apply:commands`, which 12 needs (`scopes=read,validate,apply:npcs,apply:behaviors,apply:gui,apply:commands`). 47 needs the herald's own points and 44 the `ANIMUS_LINK_DOWN` / `ANIMUS_LINK_UP` commands, as their headers say. 12, 45 and 49 change the server; their headers say how to put it back before another take.

Clips glide the cursor on eased curves from where it is, rest on each target before clicking, type 25% slower than the raw delays with a 1 s hold after typing, hold each result 1.5 to 2.5 s and end on a 3 s closing caption (`finish(film, {closing})` refuses a clip without one). `finish()` trims the video from `mark()` to the end of the caption.

## Tests

```
python3 -m unittest discover -s qa/demo/tests -p 'test_*.py'
qa/demo/mod/gradlew -p qa/demo/mod test
```
