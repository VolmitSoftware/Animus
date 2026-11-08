package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One saved route: rename, copy, delete, edit its points and change the settings every NPC on it shares. Every
 * change is written to {@code routes/<id>.json} at once with its own transaction.
 */
final class RouteDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, RENAME = 19, DUPLICATE = 20, EDIT = 21, ASSIGN = 23, WALK = 24,
            LOOP = 28, ORDER = 29, MODE = 30, SPEED = 31, DELETE = 33, UNASSIGN = 34;
    static final List<String> ORDERS = List.of("sequence", "pingpong", "random", "wander"), MODES = List.of("walk", "swim", "fly");
    private final Runnable changed;
    private String id;
    /** Null while the file is being read; empty once it is gone. */
    private Optional<RouteFiles.Route> route;

    /** {@code changed} runs after every write, so the route list below reads the files again. */
    RouteDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id, Runnable changed) {
        super(toolkit, viewer, commands);
        this.id = id; this.changed = changed;
        reload(id);
    }

    @Override String title() { return "Route " + id; }
    @Override int rows() { return 6; }

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
        if (route == null) { icon(22, "minecraft:clock", "&7Reading routes/" + id + ".json", List.of(), Optional.empty()); return; }
        if (route.isEmpty()) { icon(22, "minecraft:barrier", "&7routes/" + id + ".json is gone", List.of("&7Back returns to the list"), Optional.empty()); return; }
        RouteFiles.Route shown = route.get();
        List<String> info = new ArrayList<>();
        info.add(shown.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + shown.points() + " points"));
        info.add("&7Used by &f" + ToolkitFiles.count(shown.users().size()));
        if (!shown.users().isEmpty()) info.add("&7" + names(shown.users(), 6));
        icon(HEADER, "minecraft:lead", title(shown.id()), info, Optional.empty());

        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Renames the file and repoints its NPCs"), any("type the new id")),
                (GuiModel.Click click) -> prompt("New route id", shown.id(), (String typed) -> {
                    String to = typed.strip();
                    write(to, (ToolkitFiles ignored) -> files().rename(shown.id(), to));
                }));
        button(DUPLICATE, "minecraft:paper", title("Duplicate"), lore(List.of("&7A copy no NPC uses yet"), any("type the copy's id")),
                (GuiModel.Click click) -> prompt("Id for the copy", shown.id() + "-copy", (String typed) -> {
                    String to = typed.strip();
                    write(shown.id(), (ToolkitFiles ignored) -> files().duplicate(shown.id(), to));
                }));
        Optional<WaypointCapture> running = toolkit.capture(viewer.id());
        int unsaved = running.filter((WaypointCapture capture) -> !capture.source().equals(Optional.of(shown.id()))).map((WaypointCapture capture) -> capture.points().size()).orElse(0);
        guarded(EDIT, "minecraft:ender_eye", "Edit points", unsaved > 0 ? "Replaces your capture of " + unsaved + " points" : "Opens the points where you stand",
                0, unsaved > 0, this::edit);
        button(ASSIGN, "minecraft:lead", title("Put on the selection"), any("assign"),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> write(shown.id(), (ToolkitFiles files) -> files.route(shown.id(), ids))));
        button(WALK, "minecraft:leather_boots", title("Walk it now"), lore(List.of("&7Not saved on the NPCs"), any("walk the selection")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.walk(viewer.player(), ids, Optional.of(shown.id()))));

        boolean sequence = shown.order().equals(WaypointSettings.Order.SEQUENCE.key());
        button(LOOP, shown.loop() ? "minecraft:lime_dye" : "minecraft:gray_dye", title("Loop " + (shown.loop() ? "on" : "off")),
                lore(List.of(sequence ? "&7Back to the first point after the last" : "&7Only with order sequence"), any("toggle")), (GuiModel.Click click) -> {
                    if (!sequence) { toolkit.say(viewer.player(), ChatLine.error("Loop needs order sequence; pingpong and random repeat on their own")); return; }
                    setting("loop", (JsonObject document) -> { if (shown.loop()) document.remove("loop"); else document.addProperty("loop", true); });
                });
        button(ORDER, "minecraft:repeater", title("Order " + shown.order()), lore(List.of("&7sequence, pingpong, random or wander"), hints("next", "previous", "next")),
                (GuiModel.Click click) -> {
                    String next = cycle(click, ORDERS, shown.order());
                    setting("order", (JsonObject document) -> {
                        document.addProperty("order", next);
                        if (!next.equals(WaypointSettings.Order.SEQUENCE.key())) document.remove("loop");
                    });
                });
        button(MODE, "minecraft:feather", title("Mode " + shown.mode()), lore(List.of("&7walk, swim or fly"), hints("next", "previous", "next")),
                (GuiModel.Click click) -> {
                    String next = cycle(click, MODES, shown.mode());
                    setting("mode", (JsonObject document) -> document.addProperty("mode", next));
                });
        button(SPEED, "minecraft:sugar", title("Speed " + shown.speed() + " blocks/s"), lore(List.of("&71 to 5"), hints("+0.5", "-0.5", "ten steps")),
                (GuiModel.Click click) -> {
                    double next = step(click, shown.speed(), 0.5, 1, 5);
                    setting("speed", (JsonObject document) -> document.addProperty("speed", next));
                });

        guarded(DELETE, "minecraft:lava_bucket", "Delete", shown.users().isEmpty() ? "Removes routes/" + shown.id() + ".json" : "Refused while NPCs use it",
                0, true, () -> write(shown.id(), (ToolkitFiles ignored) -> files().delete(shown.id(), false)));
        if (!shown.users().isEmpty())
            guarded(UNASSIGN, "minecraft:tnt", "Unassign and delete", "Also removes route movement from its NPCs", shown.users().size(), true,
                    () -> write(shown.id(), (ToolkitFiles ignored) -> files().delete(shown.id(), true)));
    }

    private void setting(String what, Consumer<JsonObject> edit) {
        String route = id;
        write(route, (ToolkitFiles ignored) -> files().settings(route, what, edit));
    }

    /** Loads the file into a capture in the player's world and opens its points. */
    private void edit() {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty()) return;
        String route = id;
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().load(route, pose.get().worlds()), (WaypointCapture capture) -> {
            toolkit.edit(viewer.id(), capture);
            viewer.push(new PointsScreen<>(toolkit, viewer, commands, () -> { reload(route); changed.run(); }));
        });
    }
}
