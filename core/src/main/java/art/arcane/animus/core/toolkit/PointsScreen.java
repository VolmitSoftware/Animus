package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The running capture's points, one item each. Click a point to go there, make it current, move it to where you stand
 * or delete it; the bottom row inserts after the current point, sets its wait and saves. A route opened from its
 * detail screen saves back to its own file.
 */
final class PointsScreen<P, S> extends ToolScreen<P, S> {
    static final int INSERT = 46, WAIT = 47, SAVE = 51, DISCARD = 52, SUMMARY = 53;
    private final Runnable changed;
    private int deleting = -1;

    PointsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { this(toolkit, viewer, commands, () -> {}); }

    /** {@code changed} runs after a save, so the screen below can read the routes again. */
    PointsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, Runnable changed) {
        super(toolkit, viewer, commands);
        this.changed = changed;
    }

    @Override String title() { return "Route points"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        back();
        Optional<WaypointCapture> running = toolkit.capture(viewer.id());
        if (running.isEmpty()) {
            icon(22, "minecraft:compass", "&7No capture running", List.of("&7Capture a route, or open a saved one with Edit points"), Optional.empty());
            return;
        }
        WaypointCapture capture = running.get();
        List<Vec3> points = capture.points();
        List<Optional<String>> waits = capture.waits();
        int current = capture.current(), here = nearest(points);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) indices.add(i);
        list(indices, "No points yet; Insert adds one where you stand", (Integer slot, Integer index) -> {
            Vec3 point = points.get(index);
            boolean big = points.size() > BULK, armed = deleting == index;
            List<String> lines = new ArrayList<>();
            lines.add("&7" + (int) Math.floor(point.x()) + " " + (int) Math.floor(point.y()) + " " + (int) Math.floor(point.z()));
            lines.add("&7Wait &f" + waits.get(index).map((String ticks) -> ticks + " ticks").orElse("route default"));
            if (index == current) lines.add("&aCurrent: inserts go after it");
            if (index == here) lines.add("&aHere: nearest to you");
            if (armed) lines.add("&cSneak-right again to delete");
            String name = (armed ? "&c" : "") + "Point " + (index + 1) + (index == here ? " (here)" : "");
            button(slot, index == current ? "minecraft:ender_eye" : "minecraft:ender_pearl", title(name),
                    lore(lines, List.of("&fLeft &7teleport there", "&fRight &7make it current", "&fSneak-left &7move it to you",
                            "&fSneak-right &7delete" + (big ? ", asks once more" : ""))),
                    (GuiModel.Click click) -> point(capture, index, click, big));
        });
        button(INSERT, "minecraft:lime_dye", title("Insert point"), lore(List.of("&7Where you stand, after point " + (current + 1)), any("insert")),
                (GuiModel.Click click) -> pose((ScreenHost.Pose pose) -> {
                    int added = capture.insert(current, pose);
                    toolkit.say(viewer.player(), ChatLine.reply("Inserted point " + (added + 1) + " of " + capture.points().size()));
                }));
        if (current >= 0) button(WAIT, "minecraft:clock", title("Wait at point " + (current + 1)),
                lore(List.of("&7Now " + waits.get(current).map((String ticks) -> ticks + " ticks").orElse("the route default"), "&7Ticks, 20 a second; blank uses the route's"), any("type the ticks")),
                (GuiModel.Click click) -> { deleting = -1; waitAt(capture, current, waits.get(current).orElse("")); });
        String target = capture.source().map((String id) -> "routes/" + id + ".json").orElse("routes/wp-" + viewer.host().name(viewer.player()).toLowerCase(Locale.ROOT) + ".json");
        button(SAVE, "minecraft:writable_book", title("Save route"), lore(List.of("&7As " + target), any("save")), (GuiModel.Click click) -> {
            deleting = -1;
            toolkit.saveCapture(viewer, false);
            changed.run();
        });
        guarded(DISCARD, "minecraft:bucket", "Discard", "Drops every point; saved files stay", 0, true, () -> toolkit.stopCapture(viewer.player()));
        icon(SUMMARY, "minecraft:paper", "&7" + points.size() + " points", List.of("&7" + (capture.source().isPresent() ? "Editing " + target : "New capture"),
                "&7Hold the wand and tap sneak to add at the end"), Optional.empty());
    }

    private void point(WaypointCapture capture, int index, GuiModel.Click click, boolean big) {
        if (click == GuiModel.Click.SHIFT_RIGHT) {
            if (big && deleting != index) { deleting = index; return; }
            deleting = -1;
            capture.delete(index);
            toolkit.say(viewer.player(), ChatLine.reply("Deleted point " + (index + 1) + ", " + capture.points().size() + " left"));
            return;
        }
        deleting = -1;
        if (click == GuiModel.Click.SHIFT_LEFT) pose((ScreenHost.Pose pose) -> {
            capture.move(index, pose);
            toolkit.say(viewer.player(), ChatLine.reply("Moved point " + (index + 1) + " to you"));
        });
        else if (right(click)) capture.select(index);
        else teleport(capture, capture.points().get(index), index);
    }

    /** Runs {@code work} with where the player stands, telling them why when it is refused. */
    private void pose(Consumer<ScreenHost.Pose> work) {
        deleting = -1;
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty()) return;
        try { work.accept(pose.get()); }
        catch (IllegalStateException | IndexOutOfBoundsException refused) { toolkit.say(viewer.player(), ChatLine.error(String.valueOf(refused.getMessage()))); }
    }

    private void teleport(WaypointCapture capture, Vec3 point, int index) {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        Optional<String> world = capture.worlds().stream().filter((String name) -> pose.map((ScreenHost.Pose at) -> at.in(name)).orElse(false)).sorted().findFirst();
        if (world.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("The capture is in another world")); return; }
        float yaw = pose.map(ScreenHost.Pose::yaw).orElse(0f);
        if (!viewer.host().teleport(viewer.player(), world.get(), point.x(), point.y(), point.z(), yaw, 0)) toolkit.say(viewer.player(), ChatLine.error("Teleport to point " + (index + 1) + " was refused"));
    }

    private void waitAt(WaypointCapture capture, int index, String initial) {
        prompt("Wait ticks, point " + (index + 1), initial, (String typed) -> {
            String text = typed.strip();
            try {
                Optional<Integer> ticks = text.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(text));
                capture.waitTicks(index, ticks);
                viewer.refresh();
                toolkit.say(viewer.player(), ChatLine.reply("Point " + (index + 1) + ticks.map((Integer wait) -> " waits " + wait + " ticks").orElse(" uses the route's wait")));
            } catch (NumberFormatException notNumber) { toolkit.say(viewer.player(), ChatLine.error(text + " is not a whole number of ticks")); }
            catch (IllegalArgumentException | IndexOutOfBoundsException refused) { toolkit.say(viewer.player(), ChatLine.error(String.valueOf(refused.getMessage()))); }
        });
    }

    /** The point nearest the player in the capture's world, or {@code -1}. */
    private int nearest(List<Vec3> points) {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        if (pose.isEmpty() || toolkit.capture(viewer.id()).map((WaypointCapture capture) -> !capture.in(pose.get())).orElse(true)) return -1;
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        int best = -1;
        for (int i = 0; i < points.size(); i++) if (best < 0 || points.get(i).distance(at) < points.get(best).distance(at)) best = i;
        return best;
    }
}
