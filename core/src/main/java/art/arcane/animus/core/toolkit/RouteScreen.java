package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Saved routes to put on the selection, the live route controls and route capture: hold the wand and tap sneak to drop
 * a point where you stand, or use the Capture button here. Sneak-click a route for its details.
 */
final class RouteScreen<P, S> extends ToolScreen<P, S> {
    static final int CAPTURE = 46, SAVE = 47, DISCARD = 51, RESUME = 52, STOP = 53;
    private List<RouteFiles.Route> routes;

    RouteScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        reload();
    }

    /** Reads {@code routes/} again; queued after any save so the list shows it. */
    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> new RouteFiles(toolkit.npcs(), toolkit.documents()).routes(),
                (List<RouteFiles.Route> found) -> { routes = found; viewer.refresh(); });
    }

    @Override String title() { return "Routes"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        if (routes == null) icon(22, "minecraft:clock", "&7Reading routes/", List.of(), Optional.empty());
        else list(routes, "No routes yet; capture one below", (Integer slot, RouteFiles.Route route) ->
                button(slot, route.error().isPresent() ? "minecraft:barrier" : "minecraft:lead", title(route.id()), lore(List.of(
                        route.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + route.points() + " points"),
                        "&7Used by &f" + ToolkitFiles.count(route.users().size())), hints("put on the selection", "walk it now, not saved", "details, rename, delete")),
                        (GuiModel.Click click) -> {
                            if (sneak(click)) { viewer.push(new RouteDetailScreen<>(toolkit, viewer, commands, route.id(), this::reload)); return; }
                            Optional<List<String>> ids = chosen();
                            if (ids.isEmpty()) return;
                            if (right(click)) toolkit.walk(viewer.player(), ids.get(), Optional.of(route.id()));
                            else { toolkit.write(viewer, (ToolkitFiles files) -> files.route(route.id(), ids.get())); reload(); }
                        }));
        back();
        Optional<WaypointCapture> capture = toolkit.capture(viewer.id());
        int points = capture.map((WaypointCapture running) -> running.points().size()).orElse(0);
        button(CAPTURE, "minecraft:compass", title(capture.isPresent() ? "Capturing, " + points + " points" : "Capture a route"),
                lore(List.of("&7Or hold the wand and tap sneak"), hints(capture.isPresent() ? "add a point here" : "start with a point here", "points: move, insert, wait, delete", "remove the last point")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) toolkit.undoPoint(viewer.player());
                    else if (right(click)) viewer.push(new PointsScreen<>(toolkit, viewer, commands, this::reload));
                    else toolkit.point(viewer.player());
                });
        if (capture.isPresent()) {
            String target = capture.get().source().orElse("wp-" + viewer.host().name(viewer.player()).toLowerCase(Locale.ROOT));
            button(SAVE, "minecraft:writable_book", title("Save route"), lore(List.of("&7As routes/" + target + ".json"),
                    hints("save", "save and put on the selection", "save")), (GuiModel.Click click) -> { toolkit.saveCapture(viewer, right(click)); reload(); });
            guarded(DISCARD, "minecraft:bucket", "Discard capture", "Drops every point; saved files stay", 0, true, () -> toolkit.stopCapture(viewer.player()));
        }
        button(RESUME, "minecraft:lime_dye", title("Start route"), lore(List.of("&7Each NPC's saved route"), any("start now")), (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.walk(viewer.player(), ids, Optional.empty())));
        button(STOP, "minecraft:red_dye", title("Stop moving"), any("stop the selection"), (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> toolkit.halt(viewer.player(), ids)));
    }
}
