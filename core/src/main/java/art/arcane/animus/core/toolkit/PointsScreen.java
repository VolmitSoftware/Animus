package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Words;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The points of the route being made or edited, one item each. A click chooses a point; the buttons below add a point
 * after it, set its wait, go there, move it or delete it. Save writes the route, Discard drops the points.
 */
final class PointsScreen<P, S> extends ToolScreen<P, S> {
    static final int ADD = ACTIONS, WAIT = ACTIONS + 1, VISIT = ACTIONS + 2, MOVE = ACTIONS + 3, INFO = ACTIONS + 4, DELETE = ACTIONS + 8;
    private final Runnable changed;

    PointsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { this(toolkit, viewer, commands, () -> {}); }

    /** {@code changed} runs after a save, so the screen below can read the routes again. */
    PointsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, Runnable changed) {
        super(toolkit, viewer, commands);
        this.changed = changed;
    }

    @Override String title() { return "Route points"; }
    @Override String node() { return PermissionNodes.NPC_MOVE; }
    @Override int rows() { return 6; }

    @Override void draw() {
        back();
        help("Click a point to choose it; the buttons below act on the chosen point. Tap sneak with the wand to add one at the end.", "21-in-game-toolkit", "points");
        Optional<CaptureFlow.Capturing> running = toolkit.captures().capturing(viewer.id());
        if (running.isEmpty()) {
            icon(22, "minecraft:compass", "&7No route being made", List.of("&7New route in Routes starts one"), Optional.empty());
            return;
        }
        CaptureFlow.Capturing making = running.get();
        WaypointCapture capture = making.points();
        List<Vec3> points = capture.points();
        List<Optional<String>> waits = capture.waits();
        int current = capture.current(), here = nearest(capture, points);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) indices.add(i);
        list(indices, (Integer index) -> "Point " + (index + 1), "No points yet; Add point drops one where you stand", (Integer slot, Integer index) -> {
            Vec3 point = points.get(index);
            List<String> lines = new ArrayList<>(List.of("&7" + (int) Math.floor(point.x()) + " " + (int) Math.floor(point.y()) + " " + (int) Math.floor(point.z())
                    + waits.get(index).map((String ticks) -> ", waits " + wait(ticks)).orElse("")));
            if (index == current) lines.add("&fNow: chosen");
            button(slot, index == current ? "minecraft:ender_eye" : "minecraft:ender_pearl", title("Point " + (index + 1) + (index == here ? " (here)" : "")),
                    lore(lines, any("choose it")), (GuiModel.Click click) -> capture.select(index));
        });
        String after = current < 0 ? "" : ", after point " + (current + 1);
        button(ADD, "minecraft:lime_dye", title("Add point"), lore(List.of("&7Where you stand" + after), any("add one")),
                (GuiModel.Click click) -> pose((ScreenHost.Pose pose) -> capture.insert(current, pose)));
        if (current >= 0) {
            String chosen = "point " + (current + 1);
            button(WAIT, "minecraft:clock", title("Wait"), lore(List.of("&7How long it stays at " + chosen, "&fNow: " + waits.get(current).map(PointsScreen::wait).orElse("the route's wait")),
                    any("type seconds")), (GuiModel.Click click) -> waitAt(capture, current, waits.get(current)));
            button(VISIT, "minecraft:ender_pearl", title("Go there"), lore(List.of("&7To " + chosen), any("go there")), (GuiModel.Click click) -> teleport(capture, points.get(current), current));
            button(MOVE, "minecraft:piston", title("Move here"), lore(List.of("&7Moves " + chosen + " to where you stand"), any("move it")),
                    (GuiModel.Click click) -> pose((ScreenHost.Pose pose) -> capture.move(current, pose)));
            confirm(DELETE, "minecraft:lava_bucket", "Delete point", "Deletes " + chosen + "; Discard drops the whole route", 0, true, () -> capture.delete(current));
        }
        List<String> info = new ArrayList<>(List.of("&7Tap sneak with the wand to add one at the end"));
        if (advanced()) info.add("&8" + RouteFiles.path(making.route()));
        icon(INFO, "minecraft:paper", "&f" + MovementScreen.points(points.size()), info, Optional.empty());
        String on = making.npcs().isEmpty() ? "" : " and puts it on " + named(making.npcs());
        button(bottom(SAVE), "minecraft:emerald", title("Save"), lore(List.of("&7Saves route " + Words.readable(making.route()) + on), any("save")),
                (GuiModel.Click click) -> toolkit.captures().save(viewer.player(), () -> { changed.run(); viewer.back(); }));
        confirm(bottom(DISCARD), "minecraft:bucket", "Discard", "Drops the points; saved routes stay", 0, true, () -> toolkit.captures().stop(viewer.player()));
    }

    /** A point's wait as written, {@code 40} or {@code 20-60} ticks, in seconds. */
    private static String wait(String ticks) {
        String[] range = ticks.split("-");
        if (range.length == 1) return MovementScreen.seconds(Integer.parseInt(range[0]));
        return MovementScreen.seconds(Integer.parseInt(range[0])).replaceAll(" seconds?$", "") + " to " + MovementScreen.seconds(Integer.parseInt(range[1]));
    }

    /** Runs {@code work} with where the player stands, telling them why when it is refused. */
    private void pose(Consumer<ScreenHost.Pose> work) {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty()) return;
        try { work.accept(pose.get()); }
        catch (IllegalStateException | IndexOutOfBoundsException refused) { refuse(String.valueOf(refused.getMessage())); }
    }

    private void teleport(WaypointCapture capture, Vec3 point, int index) {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        Optional<String> world = capture.worlds().stream().filter((String name) -> pose.map((ScreenHost.Pose at) -> at.in(name)).orElse(false)).sorted().findFirst();
        if (world.isEmpty()) { refuse("The route is in another world"); return; }
        float yaw = pose.map(ScreenHost.Pose::yaw).orElse(0f);
        if (!viewer.host().teleport(viewer.player(), world.get(), point.x(), point.y(), point.z(), yaw, 0)) refuse("Teleport to point " + (index + 1) + " was refused");
    }

    /** Wait: seconds at the chosen point, saved as ticks; blank uses the route's wait. */
    private void waitAt(WaypointCapture capture, int index, Optional<String> now) {
        String initial = now.filter((String ticks) -> !ticks.contains("-")).map((String ticks) -> MovementScreen.seconds(Integer.parseInt(ticks)).replaceAll(" seconds?$", "")).orElse("");
        prompt("Wait in seconds", initial, (String typed) -> {
            String text = typed.strip();
            Optional<Integer> ticks;
            try { ticks = text.isEmpty() ? Optional.empty() : Optional.of((int) Math.round(Double.parseDouble(text) * 20)); }
            catch (NumberFormatException notNumber) { refuse(text + " is not a number of seconds"); return; }
            try { capture.waitTicks(index, ticks); }
            catch (IllegalArgumentException outside) { refuse("A wait is 0 to " + MovementScreen.seconds(WaypointSettings.MAXIMUM_WAIT)); return; }
            catch (IndexOutOfBoundsException gone) { refuse("Point " + (index + 1) + " is gone"); return; }
            toolkit.say(viewer.player(), ChatLine.reply("Point " + (index + 1) + ticks.map((Integer wait) -> " waits " + MovementScreen.seconds(wait)).orElse(" uses the route's wait")));
        });
    }

    /** The point nearest the player in the route's world, or {@code -1}. */
    private int nearest(WaypointCapture capture, List<Vec3> points) {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty() || !capture.in(pose.get())) return -1;
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        int best = -1;
        for (int i = 0; i < points.size(); i++) if (best < 0 || points.get(i).distance(at) < points.get(best).distance(at)) best = i;
        return best;
    }
}
