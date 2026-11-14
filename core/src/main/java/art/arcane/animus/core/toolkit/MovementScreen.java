package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Words;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Movement of the selected NPC: a route, a recording, or standing still, side by side so the difference shows.
 * Advanced adds the live controls, the movement settings and mounts.
 */
final class MovementScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, ROUTE = 20, RECORDING = 22, STILL = 24, WALK = 37, START = 38, STOP = 39, SETTINGS = 40, MOUNTS = 41;
    static final String PAGE = "05-movement-mounts-riding", RECORDINGS = "19-performance-recordings";
    static final String ROUTE_HELP = "A route is points on the ground; the NPC walks from one to the next and finds its own way between them.",
            RECORDING_HELP = "A recording is your own movement, replayed exactly: steps, looks, sneaks, arm swings.";

    MovementScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Movement"; }
    @Override int rows() { return 6; }
    @Override boolean forSelection() { return true; }

    /** The keys that put a saved route or recording on an NPC; Stand still takes only these off. */
    static final List<String> ASSIGNMENT = List.of("route", "recording", "playback");

    /**
     * {@code Now: walks route market}, {@code Now: replays recording wave}, {@code Now: walks its own route} for points
     * kept in the NPC's own file, as imports write them, or {@code Now: stands still}.
     */
    static String now(Toolkit<?, ?> toolkit, String id) {
        JsonObject navigation = RouteFiles.navigation(NpcScreen.stored(toolkit, id));
        boolean on = navigation != null && navigation.has("enabled") && text(navigation.get("enabled")).equals("true");
        if (on && navigation.has("recording")) return "Now: replays recording " + Words.readable(text(navigation.get("recording")));
        if (on && navigation.has("route")) return "Now: walks route " + Words.readable(text(navigation.get("route")));
        if (on && navigation.has("points")) return "Now: walks its own route";
        return "Now: stands still";
    }

    private static String text(JsonElement value) { return value.isJsonPrimitive() ? value.getAsString() : ""; }

    /** {@code 1 point}, {@code 3 points}. */
    static String points(int count) { return count + (count == 1 ? " point" : " points"); }

    /** Ticks as people read them: {@code 1 second}, {@code 2.5 seconds}. */
    static String seconds(int ticks) {
        double value = ticks / 20.0;
        String shown = value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
        return shown + (shown.equals("1") ? " second" : " seconds");
    }

    @Override void draw() {
        back();
        button(bottom(HELP), "minecraft:paper", title("Help"), any("explain this screen in chat"), (GuiModel.Click click) -> toolkit.say(viewer.player(),
                ChatLine.reply(ROUTE_HELP + " " + RECORDING_HELP).open("Routes", ChatLine.docsUrl(PAGE, "routes"), "Open the Animus wiki")
                        .open("Recordings", ChatLine.docsUrl(RECORDINGS, "recording-a-performance"), "Open the Animus wiki")));
        List<String> ids = toolkit.selected(viewer.id());
        if (ids.isEmpty()) {
            button(HEADER, "minecraft:leather_boots", title("Nothing selected"), lore(List.of("&7Pick an NPC first"), any("pick one")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        List<String> header = new ArrayList<>(List.of("&f" + now(toolkit, ids.getFirst())));
        if (ids.size() > 1) header.add("&7" + names(ids, 6));
        icon(HEADER, "minecraft:leather_boots", title("Movement"), header, Optional.empty());
        opens(ROUTE, "minecraft:lead", "Route", lore(List.of("&7Walks point to point."), any("open")), () -> new RouteScreen<>(toolkit, viewer, commands));
        opens(RECORDING, "minecraft:music_disc_13", "Recording", lore(List.of("&7Replays what you did."), any("open")), () -> new RecordingScreen<>(toolkit, viewer, commands));
        if (!holds(PermissionNodes.NPC_MOVE)) denied(STILL, "Stand still", PermissionNodes.NPC_MOVE);
        else confirm(STILL, "minecraft:red_dye", "Stand still", "Takes " + (ids.size() == 1 ? "its" : "their") + " route or recording off and sends " + (ids.size() == 1 ? "it" : "them") + " home",
                ids.size(), true, () -> { if (allows(PermissionNodes.NPC_MOVE)) still(ids); });
        if (!advanced()) return;
        gated(WALK, PermissionNodes.NPC_MOVE, "minecraft:leather_boots", "Walk once", lore(List.of("&7Walks a route now, not saved"), any("pick a route")), (GuiModel.Click click) -> walkOnce(ids));
        gated(START, PermissionNodes.NPC_MOVE, "minecraft:lime_dye", "Start", lore(List.of("&7Starts its own route or recording now"), any("start")), (GuiModel.Click click) -> {
            if (allows(PermissionNodes.NPC_MOVE)) ask(START, "Starts " + named(ids), ids.size(), false, () -> toolkit.walk(viewer.player(), ids, Optional.empty()));
        });
        gated(STOP, PermissionNodes.NPC_MOVE, "minecraft:red_concrete", "Stop now", lore(List.of("&7Stops where it is; nothing is saved"), any("stop")), (GuiModel.Click click) -> {
            if (allows(PermissionNodes.NPC_MOVE)) ask(STOP, "Stops " + named(ids), ids.size(), false, () -> toolkit.halt(viewer.player(), ids));
        });
        PropertyScreen.Section movement = PropertyScreen.SECTIONS.stream().filter((PropertyScreen.Section section) -> section.name().equals("Movement")).findFirst().orElseThrow();
        opens(SETTINGS, "minecraft:feather", "Movement settings", lore(List.of("&7Speed, doors, water, falls"), any("open")), () ->
                new FieldScreen<>(toolkit, viewer, commands, "Movement settings", PropertyScreen.section(PropertyScreen.SCHEMA, movement, Optional.empty()), NpcScreen.scope(toolkit, ids), movement.path()));
        opens(MOUNTS, "minecraft:saddle", "Mounts and riding", lore(List.of("&7Ride, carry and player riding"), any("open")), () -> new MountScreen<>(toolkit, viewer, commands));
    }

    /** Walk once: a saved route to walk now without saving it on the NPCs. */
    private void walkOnce(List<String> ids) {
        if (!allows(PermissionNodes.NPC_MOVE)) return;
        toolkit.later(viewer, (ToolkitFiles ignored) -> new RouteFiles(toolkit.npcs(), toolkit.documents()).routes(), (List<RouteFiles.Route> found) -> {
            List<RouteFiles.Route> usable = found.stream().filter((RouteFiles.Route route) -> route.error().isEmpty()).toList();
            if (usable.isEmpty()) { refuse("No routes yet; New route in Routes makes one"); return; }
            push(new PickScreen<>(toolkit, viewer, commands, PermissionNodes.NPC_MOVE, "Walk once", usable, (RouteFiles.Route route) -> new PickScreen.Look("minecraft:lead", route.id(), points(route.points())),
                    (RouteFiles.Route route) -> toolkit.walk(viewer.player(), ids, Optional.of(route.id()))));
        });
    }

    /**
     * Stops the NPCs now and saves them standing still, in one transaction: the module is switched off and only the route
     * or recording on it goes. Its own points, speed, order and every other setting stay, so turning it on again or Undo
     * brings the walk back; a module left with nothing but the switch is removed. The saved change is the one chat line.
     * Each NPC then goes back to its saved spot, where a walk or a replay took it from and where it would stand after a
     * restart.
     */
    private void still(List<String> ids) {
        toolkit.stop(ids);
        toolkit.write(viewer, (ToolkitFiles ignored) -> {
            List<NpcRepository.Draft> drafts = new ArrayList<>();
            for (String id : ids) {
                NpcRepository.Draft draft = toolkit.npcs().draft(id);
                JsonObject document = draft.document();
                if (!still(document)) continue;
                drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
            }
            if (drafts.isEmpty()) return named(ids) + " already stands still";
            return ToolkitFiles.named(drafts) + " stands still; tx " + RouteFiles.commit(toolkit.npcs(), toolkit.documents(), drafts, List.of());
        }, () -> home(ids));
    }

    /** Moves each active NPC to the spot its file saves. */
    private void home(List<String> ids) {
        for (String id : ids) toolkit.npcs().find(id).ifPresent((NpcDefinition definition) -> {
            JsonObject position = definition.json().getAsJsonObject("position");
            toolkit.relocate(id, new WorldPosition(position.get("world").getAsString(),
                    new Vec3(position.get("x").getAsDouble(), position.get("y").getAsDouble(), position.get("z").getAsDouble())));
        });
    }

    /** Switches the document's movement module off and takes its route or recording off; false when it already stood still. */
    static boolean still(JsonObject document) {
        JsonObject navigation = RouteFiles.navigation(document);
        if (navigation == null) return false;
        JsonObject before = navigation.deepCopy();
        for (String key : ASSIGNMENT) navigation.remove(key);
        navigation.addProperty("enabled", false);
        if (navigation.equals(before)) return false;
        if (navigation.size() == 1) {
            JsonObject modules = document.getAsJsonObject("modules");
            modules.remove(ToolkitFiles.NAVIGATION);
            if (modules.isEmpty()) document.remove("modules");
        }
        return true;
    }
}
