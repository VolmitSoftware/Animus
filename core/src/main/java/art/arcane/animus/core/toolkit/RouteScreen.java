package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.text.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Saved routes: a click puts one on the selected NPCs, a right-click opens its details. With nothing selected, as from
 * the Library, a click opens the details. New route names one, then the player walks it, tapping sneak at each point.
 */
final class RouteScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS;
    private List<RouteFiles.Route> routes;

    RouteScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    /** Reads the routes again; queued after any save so the list shows it. */
    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> new RouteFiles(toolkit.npcs(), toolkit.documents()).routes(),
                (List<RouteFiles.Route> found) -> { routes = found; viewer.refresh(); });
    }

    @Override String title() { return "Routes"; }
    @Override String node() { return PermissionNodes.NPC_MOVE; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> ids = toolkit.selected(viewer.id());
        if (routes == null) icon(22, "minecraft:clock", "&7Reading routes", List.of(), Optional.empty());
        else list(routes, RouteFiles.Route::id, "No routes yet; New route makes one", (Integer slot, RouteFiles.Route route) -> entry(slot, route, ids));
        Optional<WaypointCapture> making = toolkit.captures().capture(viewer.id());
        if (making.isPresent())
            button(CREATE, "minecraft:lead", title("Finish route (" + MovementScreen.points(making.get().points().size()) + ")"), lore(List.of("&7Check the points, then save"), any("open")),
                    (GuiModel.Click click) -> push(new PointsScreen<>(toolkit, viewer, commands, this::reload)));
        else if (routes == null) icon(CREATE, "minecraft:gray_dye", "&7New route", List.of("&7Reading routes"), Optional.empty());
        else button(CREATE, "minecraft:writable_book", title("New route"), lore(List.of("&7Walk it, tapping sneak at each point"), any("name it")), (GuiModel.Click click) -> create(ids));
        back();
        help(MovementScreen.ROUTE_HELP + " New route: walk it and tap sneak at each point.", MovementScreen.PAGE, "routes");
    }

    private void entry(int slot, RouteFiles.Route route, List<String> ids) {
        List<String> lines = new ArrayList<>();
        lines.add(route.error().map((String error) -> "&cInvalid: " + error).orElse("&7" + MovementScreen.points(route.points()) + ", used by " + ToolkitFiles.count(route.users().size())));
        List<String> walking = route.users().stream().filter(ids::contains).toList();
        if (!walking.isEmpty()) lines.add("&fNow: " + named(walking) + (walking.size() == 1 ? " walks it" : " walk it"));
        if (advanced()) lines.add("&8" + route.id());
        button(slot, route.error().isPresent() ? "minecraft:barrier" : "minecraft:lead", title(Words.readable(route.id())),
                lore(lines, ids.isEmpty() ? any("details") : hints("use on " + named(ids), "details", "")), (GuiModel.Click click) -> {
                    if (ids.isEmpty() || right(click)) { push(new RouteDetailScreen<>(toolkit, viewer, commands, route.id(), this::reload)); return; }
                    if (route.error().isPresent()) { refuse("Route " + Words.readable(route.id()) + " cannot be walked: " + route.error().get()); return; }
                    ask(slot, "Puts " + Words.readable(route.id()) + " on " + named(ids), ids.size(), false, () -> {
                        toolkit.write(viewer, (ToolkitFiles files) -> files.route(route.id(), ids));
                        reload();
                    });
                });
    }

    /**
     * New route: a free name is suggested; once named, the window closes so the player can walk it. A name taken by the
     * time it saves gets a number.
     */
    private void create(List<String> ids) {
        List<String> taken = routes.stream().map(RouteFiles.Route::id).toList();
        String base = ids.isEmpty() ? "" : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst()) + " route";
        prompt("Route name", RouteFiles.suggest(base, "route", taken), (String typed) -> {
            String id = RouteFiles.typed(typed);
            if (!id.matches(NpcPaths.SEGMENT)) { refuse(RouteFiles.NAMES); return; }
            if (taken.contains(id)) { refuse("Route " + Words.readable(id) + " already exists; pick another name"); return; }
            if (toolkit.captures().start(viewer.player(), id, ids)) viewer.close();
        });
    }
}
