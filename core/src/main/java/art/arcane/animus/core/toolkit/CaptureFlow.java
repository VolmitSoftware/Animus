package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Words;
import art.arcane.animus.core.virtual.Vec3;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * The routes and recordings players make from the Animus menu: points dropped with the wand or the chat chips, and the
 * countdown, chips and assignment of a recording. Once saved, either goes on the NPCs that started it and Movement opens
 * on them. State is per player and lives on their owner.
 */
final class CaptureFlow<P, S> {
    /** Ticks of the countdown before a recording started from the menu begins, and ticks then allowed for it to start. */
    static final int COUNTDOWN = 60, START_WAIT = 40;
    /** The command a route chip runs, before {@code add}, {@code undo}, {@code save} or {@code cancel}. */
    static final String ROUTE_STEP = "/animus tools capture ";
    static final String NOT_MAKING = "No route being made; New route in Routes starts one";

    /** A route being made or edited: its points, the route it saves as, and the NPCs that get it when it is saved. */
    record Capturing(WaypointCapture points, String route, List<String> npcs) {
        Capturing { npcs = List.copyOf(npcs); }
    }

    /** A recording started from the menu; {@code before} is the file's hash at the start, so a save shows as a change. */
    private static final class Take {
        final String id;
        final List<String> npcs;
        final Predicate<UUID> recording;
        int countdown = COUNTDOWN, waited;
        boolean seen;
        volatile String before = "";
        Take(String id, List<String> npcs, Predicate<UUID> recording) { this.id = id; this.npcs = List.copyOf(npcs); this.recording = recording; }
    }

    private final Toolkit<P, S> toolkit;
    private final Map<UUID, Capturing> captures = new ConcurrentHashMap<>();
    private final Map<UUID, Take> takes = new ConcurrentHashMap<>();

    CaptureFlow(Toolkit<P, S> toolkit) { this.toolkit = toolkit; }

    Optional<WaypointCapture> capture(UUID player) { return capturing(player).map(Capturing::points); }
    Optional<Capturing> capturing(UUID player) { return Optional.ofNullable(captures.get(player)); }

    /** Players making a route or starting a recording, which the platform ticks. */
    Set<UUID> players() {
        Set<UUID> making = new HashSet<>(captures.keySet());
        making.addAll(takes.keySet());
        return making;
    }

    void quit(UUID player) {
        captures.remove(player);
        takes.remove(player);
    }

    void clear() {
        captures.clear();
        takes.clear();
    }

    /**
     * New route: starts making {@code route} where the player stands, for {@code npcs}. Sneaking with the wand or the
     * chat chips add points; the wand goes in hand for that. False, after saying why, when the position is unknown.
     */
    boolean start(P player, String route, List<String> npcs) {
        Optional<ScreenHost.Pose> pose = toolkit.host().pose(player);
        if (pose.isEmpty()) { toolkit.refuse(player, "Your position cannot be read right now; try again"); return false; }
        Capturing making = new Capturing(new WaypointCapture(pose.get().worlds()), route, npcs);
        captures.put(toolkit.host().id(player), making);
        if (!toolkit.host().holdsWand(player)) toolkit.host().giveWand(player);
        toolkit.bar(player, "Walk, tap sneak to drop a point");
        chips(player, making);
        return true;
    }

    /**
     * A chat chip of the route being made: {@code add}, {@code undo}, {@code save} or {@code cancel}. A chip names the
     * route it was sent for; one left in chat from another route is refused instead of acting on this one.
     */
    void step(P player, String step, String route) {
        if (!toolkit.allows(player, PermissionNodes.NPC_MOVE)) return;
        Capturing making = captures.get(toolkit.host().id(player));
        if (making != null && !route.isEmpty() && !making.route().equals(route)) {
            toolkit.refuse(player, "That chip is for route " + Words.readable(route) + "; the route you are making is " + Words.readable(making.route()));
            return;
        }
        switch (step.toLowerCase(Locale.ROOT)) {
            case "add" -> point(player);
            case "undo" -> undoPoint(player);
            case "save" -> save(player, () -> {});
            case "cancel" -> stop(player);
            default -> toolkit.refuse(player, "A route step is add, undo, save or cancel");
        }
    }

    /** Adds a point where the player stands to the route being made. */
    void point(P player) {
        Capturing making = captures.get(toolkit.host().id(player));
        if (making == null) { toolkit.refuse(player, NOT_MAKING); return; }
        toolkit.host().pose(player).ifPresent((ScreenHost.Pose pose) -> point(player, making, pose));
    }

    /** A sneak press with the wand in hand while making a route drops a point. */
    void sneaked(P player, ScreenHost.Pose pose) {
        Capturing making = captures.get(toolkit.host().id(player));
        if (making != null && toolkit.host().holdsWand(player)) point(player, making, pose);
    }

    private void point(P player, Capturing making, ScreenHost.Pose pose) {
        try {
            int count = making.points().add(pose);
            Vec3 last = making.points().points().getLast();
            toolkit.bar(player, "Point " + count + " at " + (int) Math.floor(last.x()) + " " + (int) Math.floor(last.y()) + " " + (int) Math.floor(last.z()));
            chips(player, making);
        } catch (IllegalStateException refused) { toolkit.bar(player, "&c" + refused.getMessage()); }
    }

    /**
     * The point count and the chips that add, undo, save or cancel, sent again after every change so the newest line is
     * at hand. Chat lines cannot be replaced, so each one is kept short.
     */
    private void chips(P player, Capturing making) {
        String on = making.npcs().isEmpty() ? "" : " and puts it on " + ToolkitFiles.named(toolkit.npcs(), making.npcs());
        toolkit.say(player, ChatLine.reply(MovementScreen.points(making.points().points().size()))
                .run("Add", chip("add", making), "Adds a point where you stand")
                .run("Undo", chip("undo", making), "Removes the last point")
                .run("Save", chip("save", making), "Saves route " + Words.readable(making.route()) + on)
                .run("Cancel", chip("cancel", making), "Drops the points; nothing is saved"));
    }

    /** The command a chip runs: the step and the route it belongs to. */
    static String chip(String step, Capturing making) { return ROUTE_STEP + step + " name=" + making.route(); }

    /** A saved route opened for editing; Save writes it back over its own file. */
    void edit(UUID player, WaypointCapture capture) { captures.put(player, new Capturing(capture, capture.source().orElseThrow(), List.of())); }

    void undoPoint(P player) {
        Capturing making = captures.get(toolkit.host().id(player));
        if (making == null) { toolkit.refuse(player, NOT_MAKING); return; }
        if (!making.points().removeLast()) { toolkit.bar(player, "&cNo points to remove"); return; }
        toolkit.bar(player, "Removed the last point, " + making.points().points().size() + " left");
        chips(player, making);
    }

    void stop(P player) {
        Capturing making = captures.remove(toolkit.host().id(player));
        if (making == null) { toolkit.refuse(player, NOT_MAKING); return; }
        toolkit.say(player, ChatLine.reply(making.points().source().isPresent() ? "Dropped your changes to route " + Words.readable(making.route()) : "Dropped route " + Words.readable(making.route()) + "; nothing saved"));
    }

    /**
     * Saves the route being made over the route it was loaded from, or as a new route put on the NPCs that started it, in
     * one transaction. Movement then opens on those NPCs; without any, {@code done} runs.
     */
    void save(P player, Runnable done) {
        UUID id = toolkit.host().id(player);
        Capturing making = captures.get(id);
        if (making == null) { toolkit.refuse(player, NOT_MAKING); return; }
        int count = making.points().points().size();
        if (count < RouteFiles.MINIMUM_POINTS) { toolkit.refuse(player, "A route needs at least " + RouteFiles.MINIMUM_POINTS + " points; it has " + count); return; }
        String document;
        try { document = making.points().document(); }
        catch (IOException | RuntimeException invalid) { toolkit.refuse(player, String.valueOf(invalid.getMessage())); return; }
        boolean advanced = toolkit.advanced(id);
        toolkit.background(player, (ToolkitFiles ignored) -> {
            RouteFiles.Saved saved = new RouteFiles(toolkit.npcs(), toolkit.documents()).save(making.route(), making.points(), document, making.npcs());
            captures.remove(id, making);
            toolkit.living().refresh();
            return saved;
        }, (P online, RouteFiles.Saved saved) -> {
            ChatLine line = Toolkit.reply(saved.line());
            toolkit.say(online, advanced ? line.path(RouteFiles.path(saved.id())) : line);
            if (saved.users().isEmpty()) done.run();
            else movement(online, saved.users());
        });
    }

    /**
     * New recording: the window closes and a countdown runs so the walk out of the menu is not recorded, then it records
     * with the start command's chat chips to stop or cancel. Chat says first that what the player holds and wears is
     * recorded too, since playback dresses the NPC in it. Once saved it goes on {@code npcs} and Movement opens on them.
     */
    void record(ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String recording, List<String> npcs) {
        P player = viewer.player();
        UUID id = viewer.id();
        Take take = new Take(recording, npcs, commands.recording());
        try {
            toolkit.lane().execute(() -> {
                try { take.before = toolkit.documents().hash(RecordingFiles.path(recording)); }
                catch (IOException unread) { take.before = ""; }
            });
        } catch (RejectedExecutionException full) { toolkit.refuse(player, CommandActor.BUSY); return; }
        takes.put(id, take);
        viewer.close();
        toolkit.say(player, ChatLine.reply("Recording " + Words.readable(recording) + " starts in " + COUNTDOWN / 20 + " seconds; it copies what you hold and wear"));
        toolkit.bar(player, "Recording starts in " + COUNTDOWN / 20);
    }

    /** One owner tick: the countdown of a recording started from the menu, then watching the session until it ends. */
    void tick(P player, UUID id) {
        Take take = takes.get(id);
        if (take == null) return;
        if (take.countdown > 0) {
            if (--take.countdown > 0) { if (take.countdown % 20 == 0) toolkit.bar(player, "Recording starts in " + take.countdown / 20); return; }
            toolkit.host().command(Optional.of(player), "animus record start " + take.id);
            return;
        }
        if (take.recording.test(id)) { take.seen = true; return; }
        if (!take.seen) { if (++take.waited >= START_WAIT) takes.remove(id, take); return; }
        takes.remove(id, take);
        if (take.npcs.isEmpty()) return;
        toolkit.background(player, (ToolkitFiles ignored) -> {
            if (toolkit.documents().hash(RecordingFiles.path(take.id)).equals(take.before)) return Optional.<ChatLine>empty();
            String line = new RecordingFiles(toolkit.npcs(), toolkit.documents()).assign(take.id, take.npcs);
            toolkit.living().refresh();
            return Optional.of(Toolkit.reply(line));
        }, (P online, Optional<ChatLine> line) -> line.ifPresent((ChatLine saved) -> {
            toolkit.say(online, saved);
            movement(online, take.npcs);
        }));
    }

    /** Opens Movement on the NPCs, over their NPC screen and Home, with them selected, so the new route or recording shows. */
    private void movement(P player, List<String> ids) {
        NpcRepository npcs = toolkit.npcs();
        List<String> kept = ids.stream().filter((String npc) -> npcs.find(npc).isPresent()).toList();
        if (kept.isEmpty()) return;
        UUID id = toolkit.host().id(player);
        toolkit.selection().set(id, kept);
        if (!toolkit.show(player, HomeScreen::new)) return;
        Toolkit.Commands commands = toolkit.hooks().orElseThrow();
        toolkit.screens().viewer(id).ifPresent((ScreenManager<P, S>.Viewer viewer) -> {
            viewer.push(new NpcScreen<>(toolkit, viewer, commands));
            viewer.push(new MovementScreen<>(toolkit, viewer, commands));
        });
    }
}
