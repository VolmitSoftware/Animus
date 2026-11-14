package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One saved route: rename, copy, edit its points, the settings every NPC on it shares, and delete. Every change is
 * written at once with its own transaction.
 */
final class RouteDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, RENAME = 19, DUPLICATE = 20, EDIT = 21, LOOP = 28, ORDER = 29, MODE = 30, SPEED = 31, DELETE = 35;
    static final List<String> ORDERS = List.of("sequence", "pingpong", "random", "wander"), MODES = List.of("walk", "swim", "fly");
    private static final Map<String, String> ORDER_NAMES = Map.of("sequence", "In order", "pingpong", "Back and forth", "random", "Random", "wander", "Wander");
    private final Runnable changed;
    private String id;
    /** Null while the file is being read; empty once it is gone. */
    private Optional<RouteFiles.Route> route;

    /** {@code changed} runs after every write, so the route list below reads the files again. */
    RouteDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
    }

    @Override void begin() { reload(id); }

    @Override String title() { return "Route " + Words.readable(id); }
    @Override String node() { return PermissionNodes.NPC_MOVE; }
    @Override int rows() { return 6; }

    /** An order key as words: {@code pingpong} reads {@code Back and forth}. */
    static String order(String key) { return ORDER_NAMES.getOrDefault(key, Words.readable(key)); }

    /** {@code 2} or {@code 2.5}, without a trailing {@code .0}. */
    static String number(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }

    /** Reads the route again after a change; a rename shows under {@code next} once it succeeded, a delete leaves only Back. */
    private void reload(String next) {
        String previous = id;
        toolkit.later(viewer, (ToolkitFiles ignored) -> {
            RouteFiles files = files();
            Optional<RouteFiles.Route> found = files.find(next);
            return found.isPresent() ? found : files.find(previous);
        }, (Optional<RouteFiles.Route> found) -> {
            route = found;
            found.ifPresent((RouteFiles.Route loaded) -> id = loaded.id());
            viewer.refresh();
        });
    }

    private RouteFiles files() { return new RouteFiles(toolkit.npcs(), toolkit.documents()); }

    /** Writes, then reads the route and the list below again. */
    private void write(String next, Toolkit.FileWork work) {
        toolkit.write(viewer, work);
        reload(next);
        changed.run();
    }

    @Override void draw() {
        back();
        help("Changes here apply to every NPC on this route. Order picks the next point; Speed is in blocks per second.", MovementScreen.PAGE, "routes");
        if (route == null) { icon(22, "minecraft:clock", "&7Reading route " + Words.readable(id), List.of(), Optional.empty()); return; }
        if (route.isEmpty()) { icon(22, "minecraft:barrier", "&7Route " + Words.readable(id) + " is gone", List.of("&7Back returns to the list"), Optional.empty()); return; }
        RouteFiles.Route shown = route.get();
        List<String> info = new ArrayList<>();
        info.add(shown.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + MovementScreen.points(shown.points())));
        info.add(shown.users().isEmpty() ? "&7Used by no NPC" : "&fUsed by " + npcNames(shown.users(), 6));
        if (advanced()) info.add("&8" + RouteFiles.path(shown.id()));
        icon(HEADER, "minecraft:lead", title(Words.readable(shown.id())), info, Optional.empty());

        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Its NPCs follow the new name"), any("type a name")),
                (GuiModel.Click click) -> prompt("New route name", shown.id(), (String typed) -> {
                    String to = RouteFiles.typed(typed);
                    write(to, (ToolkitFiles ignored) -> files().rename(shown.id(), to));
                }));
        button(DUPLICATE, "minecraft:paper", title("Duplicate"), lore(List.of("&7A copy no NPC uses yet"), any("type the copy's name")),
                (GuiModel.Click click) -> prompt("Name for the copy", shown.id() + "-copy", (String typed) -> {
                    String to = RouteFiles.typed(typed);
                    write(shown.id(), (ToolkitFiles ignored) -> files().duplicate(shown.id(), to));
                }));
        Optional<WaypointCapture> running = toolkit.captures().capture(viewer.id());
        int unsaved = running.filter((WaypointCapture capture) -> !capture.source().equals(Optional.of(shown.id()))).map((WaypointCapture capture) -> capture.points().size()).orElse(0);
        confirm(EDIT, "minecraft:ender_eye", "Edit points", unsaved > 0 ? "Drops the route you are making (" + MovementScreen.points(unsaved) + ")" : "Change, add or move its points",
                0, unsaved > 0, this::edit);

        boolean sequence = shown.order().equals(WaypointSettings.Order.SEQUENCE.key());
        button(LOOP, shown.loop() ? "minecraft:lime_dye" : "minecraft:gray_dye", title("Loop"),
                lore(List.of(sequence ? "&7Back to the first point after the last" : "&7Only with Order: In order", "&fNow: " + (shown.loop() ? "on" : "off")), any(shown.loop() ? "turn off" : "turn on")),
                (GuiModel.Click click) -> {
                    if (!sequence) { refuse("Loop needs Order: In order; the other orders repeat on their own"); return; }
                    setting("loop", shown.loop() ? "off" : "on", (JsonObject document) -> { if (shown.loop()) document.remove("loop"); else document.addProperty("loop", true); });
                });
        button(ORDER, "minecraft:repeater", title("Order"), lore(List.of("&7Which point comes next", "&fNow: " + order(shown.order())), hints("next", "previous", "")),
                (GuiModel.Click click) -> {
                    String next = cycle(click, ORDERS, shown.order());
                    setting("order", order(next), (JsonObject document) -> {
                        document.addProperty("order", next);
                        if (!next.equals(WaypointSettings.Order.SEQUENCE.key())) document.remove("loop");
                    });
                });
        button(MODE, "minecraft:feather", title("Mode"), lore(List.of("&7Walk, swim or fly", "&fNow: " + Words.readable(shown.mode())), hints("next", "previous", "")),
                (GuiModel.Click click) -> {
                    String next = cycle(click, MODES, shown.mode());
                    setting("mode", Words.readable(next), (JsonObject document) -> document.addProperty("mode", next));
                });
        button(SPEED, "minecraft:sugar", title("Speed"), lore(numberRow("Blocks per second, 1 to 5", number(shown.speed()) + " blocks per second"), hints("faster", "slower", "ten steps")),
                (GuiModel.Click click) -> {
                    double next = step(click, shown.speed(), 0.5, 1, 5);
                    setting("speed", number(next) + " blocks per second", (JsonObject document) -> document.addProperty("speed", next));
                });

        List<String> users = shown.users();
        confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes route " + Words.readable(shown.id()) + (users.isEmpty() ? "" : " and takes it off " + names(users, 4)) + "; Undo in chat brings it back",
                users.size(), true, () -> write(shown.id(), (ToolkitFiles ignored) -> files().delete(shown.id(), !users.isEmpty())));
    }

    private void setting(String what, String shown, Consumer<JsonObject> edit) {
        String route = id;
        write(route, (ToolkitFiles ignored) -> files().settings(route, what, shown, edit));
    }

    /** Loads the file into a capture in the player's world and opens its points. */
    private void edit() {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty()) return;
        String route = id;
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().load(route, pose.get().worlds()), (WaypointCapture capture) -> {
            toolkit.captures().edit(viewer.id(), capture);
            push(new PointsScreen<>(toolkit, viewer, commands, () -> { reload(route); changed.run(); }));
        });
    }
}
