package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The in-game toolkit: the selection wand, its outline, route point capture and the chest-GUI tool screens. One per
 * platform, found by the command layer through the screen manager it was installed with. Player work runs on the
 * player's owner; file work on the file lane.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public final class Toolkit<P, S> implements AutoCloseable {
    public static final String ADMIN = "animus.admin";
    /** The wand's item marker: a Bukkit persistent data key, and the same key in a mod item's custom data. */
    public static final String MARKER = "animus:tool", WAND = "wand";
    /** What the wand looks like; the marker, not the name, makes it a wand. */
    public static final NpcItem WAND_ITEM = new NpcItem("minecraft:blaze_rod", 1, Optional.empty(), Optional.empty());
    public static final String WAND_NAME = RichText.colorize(Palette.PINK + "Animus wand");
    public static final List<String> WAND_LORE = List.of(RichText.colorize("&fClick NPC &7select only it"), RichText.colorize("&fSneak-click NPC &7add or remove"),
            RichText.colorize("&fTap sneak &7drop a route point while capturing"), RichText.colorize("&f/animus tools &7opens the tools"));
    /** One click often arrives once per hand; a repeat on the same NPC inside this window is the same click. */
    static final long REPEAT_NANOS = 250_000_000L;
    private static final Map<ScreenManager<?, ?>, Toolkit<?, ?>> INSTALLED = new ConcurrentHashMap<>();

    /** Moves an active NPC at once, on its owner. False when the place is blocked or the NPC is riding. */
    @FunctionalInterface public interface Relocator { boolean relocate(String id, WorldPosition destination); }

    /** What the command layer lends the screens: the inspect reply and the editor pairing link. */
    public record Commands(BiConsumer<CommandActor, String> inspect, Consumer<CommandActor> pair) {
        public Commands { Objects.requireNonNull(inspect); Objects.requireNonNull(pair); }
    }

    /** {@code skins} reads a player's signed textures; {@code clock} is nanoseconds. */
    public record Parts<P, S>(Supplier<NpcRepository> npcs, Supplier<DocumentStore> documents, ScreenManager<P, S> screens, NpcRuntime<P, ?> living,
                              Executor files, Function<P, Optional<BodySkin>> skins, Optional<Relocator> relocator, LongSupplier clock) {
        public Parts {
            Objects.requireNonNull(npcs); Objects.requireNonNull(documents); Objects.requireNonNull(screens); Objects.requireNonNull(living);
            Objects.requireNonNull(files); Objects.requireNonNull(skins); Objects.requireNonNull(relocator); Objects.requireNonNull(clock);
        }
    }

    /** Per player: outline tick count, the sneak state capture watches and the last wand click. */
    private static final class PlayerState {
        long ticks, clicked;
        UUID last;
        boolean sneaking, touched;
    }

    public enum Tool { PALETTE, MANAGE }

    @FunctionalInterface interface FileWork { String run(ToolkitFiles files) throws IOException; }
    @FunctionalInterface interface Load<T> { T run(ToolkitFiles files) throws IOException; }
    /** Builds a tool screen for a viewer; what {@link #open(UUID, Commands, ScreenFactory)} takes. */
    @FunctionalInterface interface ScreenFactory<P, S> { ToolScreen<P, S> create(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Commands commands); }

    /** The next wand click on an NPC answers this instead of selecting; {@code label} is shown above the hotbar. */
    private record Picker(String label, Consumer<String> chosen) {}

    private final Parts<P, S> parts;
    private final ToolkitSelection selection = new ToolkitSelection();
    private final Map<UUID, WaypointCapture> captures = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<UUID, Picker> pickers = new ConcurrentHashMap<>();

    private Toolkit(Parts<P, S> parts) { this.parts = parts; }

    public static <P, S> Toolkit<P, S> install(Parts<P, S> parts) {
        Toolkit<P, S> toolkit = new Toolkit<>(parts);
        INSTALLED.put(parts.screens(), toolkit);
        return toolkit;
    }

    /** The toolkit installed with this screen manager. */
    public static Optional<Toolkit<?, ?>> of(ScreenManager<?, ?> screens) { return Optional.ofNullable(INSTALLED.get(screens)); }

    @Override public void close() {
        INSTALLED.remove(parts.screens(), this);
        captures.clear();
        players.clear();
        pickers.clear();
    }

    ScreenHost<P, S> host() { return parts.screens().host(); }
    NpcRuntime<P, ?> living() { return parts.living(); }
    NpcRepository npcs() { return parts.npcs().get(); }
    /** The document store, for file classes beside {@link ToolkitFiles}; read and write it on the file lane only. */
    DocumentStore documents() { return parts.documents().get(); }
    ToolkitSelection selection() { return selection; }
    Optional<WaypointCapture> capture(UUID player) { return Optional.ofNullable(captures.get(player)); }
    Optional<Relocator> relocator() { return parts.relocator(); }
    Optional<BodySkin> skin(P player) { return parts.skins().apply(player); }

    /** The selected NPCs that still exist, in the order picked. */
    List<String> selected(UUID player) {
        NpcRepository npcs = npcs();
        selection.retain(player, (String id) -> npcs.find(id).isPresent());
        return selection.of(player);
    }

    /** A player who has not used the toolkit yet starts from the NPC they selected by command or click. */
    private void seed(UUID player) {
        PlayerState state = state(player);
        if (state.touched) return;
        state.touched = true;
        if (selection.of(player).isEmpty()) living().selection(player).ifPresent((String id) -> selection.only(player, id));
    }

    private PlayerState state(UUID player) { return players.computeIfAbsent(player, (UUID ignored) -> new PlayerState()); }

    /** Players the platform ticks: those with a selection to outline or a route capture running. */
    public Set<UUID> watching() {
        Set<UUID> watched = new HashSet<>(selection.players());
        watched.addAll(captures.keySet());
        return watched;
    }

    /**
     * A validated click on an NPC, first in the listener chain. With the wand an admin's click selects only that NPC and
     * a sneak-click adds or removes it; the click is used either way. Without the wand nothing happens here.
     */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        ScreenHost<P, S> host = host();
        if (!host.holdsWand(player) || !host.permitted(player, ADMIN)) return false;
        Optional<NpcRuntime.ActiveNpc> active = living().active(npc);
        if (active.isEmpty()) return false;
        UUID id = host.id(player);
        PlayerState state = state(id);
        long now = parts.clock().getAsLong();
        if (npc.equals(state.last) && now - state.clicked < REPEAT_NANOS) return true;
        state.last = npc;
        state.clicked = now;
        boolean sneak = attack ? host.pose(player).map(ScreenHost.Pose::sneaking).orElse(false) : sneaking;
        String target = active.get().id();
        Picker picker = pickers.remove(id);
        if (picker != null) { picker.chosen().accept(target); return true; }
        if (!sneak) { only(player, target); return true; }
        seed(id);
        boolean added;
        try { added = selection.toggle(id, target); }
        catch (IllegalStateException full) { bar(player, "&c" + full.getMessage()); return true; }
        if (added) primary(id, target);
        int count = selection.of(id).size();
        bar(player, (added ? "Added " : "Removed ") + target + ", " + count + " selected");
        return true;
    }

    /** Selects only this NPC, which also becomes the primary selection. */
    void only(P player, String id) {
        UUID uuid = host().id(player);
        selection.only(uuid, id);
        state(uuid).touched = true;
        primary(uuid, id);
        bar(player, "Selected " + id);
    }

    private void primary(UUID player, String id) {
        try { living().select(player, id); }
        catch (RuntimeException refused) { /* the multi-selection still holds it; @selected keeps its previous NPC */ }
    }

    /** Every owner tick for a watched player: route capture on a sneak press, and the outline every {@link ToolkitOutline#PERIOD} ticks. */
    public void tick(P player) {
        ScreenHost<P, S> host = host();
        UUID id = host.id(player);
        Optional<ScreenHost.Pose> pose = host.pose(player);
        if (pose.isEmpty()) return;
        PlayerState state = state(id);
        WaypointCapture capture = captures.get(id);
        boolean sneaking = pose.get().sneaking();
        if (capture != null && sneaking && !state.sneaking && host.holdsWand(player)) point(player, capture, pose.get());
        state.sneaking = sneaking;
        if (state.ticks++ % ToolkitOutline.PERIOD != 0) return;
        List<VirtualNpcState.Snapshot> shown = new ArrayList<>();
        NpcRepository npcs = npcs();
        for (String selected : selection.of(id)) npcs.find(selected).flatMap((NpcDefinition definition) -> living().state(definition.uuid())).ifPresent(shown::add);
        for (Vec3 corner : ToolkitOutline.outline(pose.get(), shown)) host.particle(player, ToolkitOutline.OUTLINE_PARTICLE, 1, 0, corner.x(), corner.y(), corner.z());
        if (capture != null)
            for (ToolkitOutline.Marker marker : ToolkitOutline.waypoints(pose.get(), capture))
                host.particle(player, marker.current() ? ToolkitOutline.CURRENT_PARTICLE : ToolkitOutline.WAYPOINT_PARTICLE, marker.current() ? 4 : 2, 0.1, marker.point().x(), marker.point().y() + 0.2, marker.point().z());
    }

    /** The player left: selection, capture, pick and click state go. */
    public void quit(UUID player) {
        selection.clear(player);
        captures.remove(player);
        players.remove(player);
        pickers.remove(player);
    }

    /** From a command: opens a tool screen on the player's owner. */
    public void open(UUID player, Tool tool, Commands commands) {
        open(player, commands, switch (tool) {
            case PALETTE -> PaletteScreen::new;
            case MANAGE -> ManageScreen::new;
        });
    }

    /** Opens any tool screen on the player's owner, replacing the window they have open. */
    public void open(UUID player, Commands commands, ScreenFactory<P, S> factory) {
        living().players().deliver(player, (P online) -> show(online, commands, factory));
    }

    void show(P player, Commands commands, ScreenFactory<P, S> factory) {
        seed(host().id(player));
        parts.screens().open(player, Optional.empty(), (ScreenManager<P, S>.Viewer viewer) -> Optional.of(factory.create(this, viewer, commands)));
    }

    /**
     * Hides the viewer's window and asks for a line of text. The window comes back on the player's owner before
     * {@code answer} runs with the text; a cancelled prompt brings the window back and says so. A platform without
     * text input keeps the window open and says what to type instead.
     */
    void prompt(ScreenManager<P, S>.Viewer viewer, String label, String initial, Consumer<String> answer) {
        UUID id = viewer.id();
        ScreenHost.Prompt prompt = new ScreenHost.Prompt(label, initial, (Optional<String> typed) -> living().players().deliver(id, (P online) -> {
            if (!viewer.open()) return;
            viewer.resume();
            if (typed.isPresent()) answer.accept(typed.get());
            else say(online, ChatLine.reply("Cancelled; nothing changed"));
        }));
        viewer.suspend();
        if (host().prompt(viewer.player(), prompt)) return;
        viewer.resume();
        say(viewer.player(), ChatLine.error("This server has no text input for " + label + "; use the matching /animus command"));
    }

    /**
     * The next wand click on an NPC answers {@code chosen} instead of selecting. The window is hidden while the player
     * looks for the NPC and comes back once they have clicked one; a second call replaces the first.
     */
    void pick(ScreenManager<P, S>.Viewer viewer, String label, Consumer<String> chosen) {
        UUID id = viewer.id();
        P player = viewer.player();
        if (!host().holdsWand(player) && !host().giveWand(player)) { say(player, ChatLine.error("This platform has no selection wand")); return; }
        viewer.suspend();
        pickers.put(id, new Picker(label, (String npc) -> {
            if (!viewer.open()) return;
            viewer.resume();
            chosen.accept(npc);
        }));
        bar(player, label + ": click an NPC with the wand");
    }

    /** The pick the player is in the middle of, for the screens' hints. */
    Optional<String> picking(UUID player) { return Optional.ofNullable(pickers.get(player)).map(Picker::label); }

    /** Forgets a pending pick, for example when the window is closed another way. */
    void cancelPick(UUID player) { pickers.remove(player); }

    /** From a command: gives the wand on the player's owner. */
    public void wand(UUID player) { living().players().deliver(player, this::giveWand); }

    void giveWand(P player) {
        if (host().giveWand(player)) say(player, ChatLine.success("Wand given; click an NPC to select it, sneak-click to add or remove"));
        else say(player, ChatLine.error("This platform has no selection wand"));
    }

    void clear(P player) {
        UUID id = host().id(player);
        selection.clear(id);
        state(id).touched = true;
    }

    /** Starts a capture where the player stands, or adds the next point. */
    void point(P player) {
        Optional<ScreenHost.Pose> pose = host().pose(player);
        if (pose.isEmpty()) return;
        WaypointCapture capture = captures.computeIfAbsent(host().id(player), (UUID ignored) -> new WaypointCapture(pose.get().worlds()));
        point(player, capture, pose.get());
    }

    private void point(P player, WaypointCapture capture, ScreenHost.Pose pose) {
        try {
            int count = capture.add(pose);
            Vec3 last = capture.points().getLast();
            bar(player, "Point " + count + " at " + (int) Math.floor(last.x()) + " " + (int) Math.floor(last.y()) + " " + (int) Math.floor(last.z()));
        } catch (IllegalStateException refused) { bar(player, "&c" + refused.getMessage()); }
    }

    /** Replaces the player's capture with a saved route opened for editing. */
    void edit(UUID player, WaypointCapture capture) { captures.put(player, capture); }

    void undoPoint(P player) {
        WaypointCapture capture = captures.get(host().id(player));
        boolean removed = capture != null && capture.removeLast();
        bar(player, removed ? "Removed the last point, " + capture.points().size() + " left" : "&cNo points to remove");
    }

    void stopCapture(P player) {
        if (captures.remove(host().id(player)) != null) say(player, ChatLine.reply("Route capture discarded"));
    }

    /** Saves the capture as {@code routes/wp-<player>.json}, or over the route it was loaded from, then, when asked, puts it on the selection. */
    void saveCapture(ScreenManager<P, S>.Viewer viewer, boolean assign) {
        P player = viewer.player();
        UUID id = viewer.id();
        WaypointCapture capture = captures.get(id);
        if (capture == null) { say(player, ChatLine.error("No route capture; click Capture to start one")); return; }
        String document;
        try { document = capture.document(); }
        catch (IOException | RuntimeException invalid) { say(player, ChatLine.error(String.valueOf(invalid.getMessage()))); return; }
        String name = host().name(player);
        List<String> chosen = selected(id);
        write(viewer, (ToolkitFiles files) -> {
            RouteFiles.Saved saved = new RouteFiles(npcs(), documents()).save(name, capture, document);
            captures.remove(id, capture);
            if (!assign || chosen.isEmpty()) return saved.line();
            return "Saved routes/" + saved.id() + ".json; " + files.route(saved.id(), chosen);
        });
    }

    /** Spawns or despawns the NPCs; a spawn is checked on this owner first, as {@code /animus spawn} checks it. */
    void spawned(ScreenManager<P, S>.Viewer viewer, List<String> ids, boolean spawn) {
        later(viewer, (ToolkitFiles files) -> files.spawned(ids, spawn), (ToolkitFiles.Batch batch) -> {
            if (spawn) for (NpcDefinition candidate : batch.candidates()) {
                List<String> errors = living().validateActivation(candidate);
                if (!errors.isEmpty()) { say(viewer.player(), ChatLine.error("Cannot spawn " + candidate.id() + ": " + errors.getFirst())); return; }
            }
            write(viewer, (ToolkitFiles files) -> files.apply(batch, spawn));
        });
    }

    /** Teleports the player to the NPC. */
    void visit(P player, String id) {
        Optional<VirtualNpcState.Snapshot> state = npcs().find(id).flatMap((NpcDefinition definition) -> living().state(definition.uuid()));
        if (state.isEmpty()) { say(player, ChatLine.error(id + " is not active; it starts when its chunk loads or a player comes near")); return; }
        Vec3 at = state.get().position().point();
        if (host().teleport(player, state.get().position().world(), at.x(), at.y(), at.z(), host().pose(player).map(ScreenHost.Pose::yaw).orElse(0f), 0)) say(player, ChatLine.reply("Teleported to " + id));
        else say(player, ChatLine.error("Teleport to " + id + " was refused"));
    }

    /** Moves the NPC to the player and saves the place. */
    void bring(ScreenManager<P, S>.Viewer viewer, String id) {
        P player = viewer.player();
        Optional<ScreenHost.Pose> pose = host().pose(player);
        Optional<VirtualNpcState.Snapshot> state = npcs().find(id).flatMap((NpcDefinition definition) -> living().state(definition.uuid()));
        if (pose.isEmpty() || state.isEmpty()) { say(player, ChatLine.error(id + " is not active here")); return; }
        if (!pose.get().in(state.get().position().world())) { say(player, ChatLine.error(id + " is in another world")); return; }
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        WorldPosition destination = new WorldPosition(state.get().position().world(), at);
        relocator().ifPresent((Relocator relocator) -> living().onOwner(id, () -> relocator.relocate(id, destination), (String refused) -> {}));
        write(viewer, (ToolkitFiles files) -> files.position(id, at, pose.get().yaw(), 0));
    }

    /** Starts each NPC's saved route now, or walks the given route without saving it. */
    void walk(P player, List<String> ids, Optional<String> route) {
        int sent = 0;
        NpcRepository npcs = npcs();
        for (String id : ids) {
            Optional<NpcDefinition> definition = npcs.find(id);
            if (definition.isEmpty()) continue;
            String reference;
            try { reference = route.orElseGet(() -> WaypointSettings.reference(definition.get())); }
            catch (IllegalArgumentException invalid) { reference = null; }
            String chosen = reference;
            if (living().onOwner(id, () -> {
                if (chosen != null) living().followRoute(id, chosen);
                else living().resumePatrol(id);
            }, (String refused) -> {})) sent++;
        }
        say(player, ChatLine.reply((route.isPresent() ? "Walking " + route.get() + ": " : "Route started: ") + ToolkitFiles.count(sent) + (sent == ids.size() ? "" : ", " + (ids.size() - sent) + " not active")));
    }

    void halt(P player, List<String> ids) {
        int sent = 0;
        for (String id : ids) if (living().onOwner(id, () -> living().stopNavigation(id), (String refused) -> {})) sent++;
        say(player, ChatLine.reply("Stopped " + ToolkitFiles.count(sent) + (sent == ids.size() ? "" : ", " + (ids.size() - sent) + " not active")));
    }

    /**
     * Saves a new NPC where the player stands, selects it, and when asked spawns it as {@code /animus spawn} would.
     * The position's world is the one NPC positions name, the first of the pose's names that an existing NPC uses,
     * else the shortest.
     */
    void create(ScreenManager<P, S>.Viewer viewer, String id, String type, boolean spawn) {
        P player = viewer.player();
        Optional<ScreenHost.Pose> pose = host().pose(player);
        if (pose.isEmpty()) { say(player, ChatLine.error("Your position cannot be read right now; try again")); return; }
        String world = worldName(pose.get());
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        float yaw = pose.get().yaw();
        later(viewer, (ToolkitFiles files) -> files.create(id, type, world, at, yaw, viewer.id()), (String line) -> {
            living().refresh();
            only(player, id);
            say(player, ChatLine.parse(Palette.success(line)));
            if (spawn) spawned(viewer, List.of(id), true);
        });
    }

    private String worldName(ScreenHost.Pose pose) {
        for (NpcDefinition definition : npcs().stored()) {
            String world = definition.json().getAsJsonObject("position").get("world").getAsString();
            if (pose.in(world)) return world;
        }
        return pose.worlds().stream().min(java.util.Comparator.comparingInt(String::length).thenComparing(String::compareTo)).orElse("world");
    }

    /** Saves the player's own signed skin, labelled with their name, and puts it on the selection. */
    void mySkin(ScreenManager<P, S>.Viewer viewer, List<String> ids) {
        Optional<BodySkin> skin = skin(viewer.player());
        if (skin.isEmpty()) { say(viewer.player(), ChatLine.error("Your skin has no signature; offline-mode servers cannot copy it")); return; }
        String label = host().name(viewer.player());
        write(viewer, (ToolkitFiles files) -> files.skin(Optional.of(new SkinFiles(npcs(), documents()).save(skin.get(), label)), ids));
    }

    /** Runs file work, then tells the player its line and redraws the screen. A line with a transaction refreshes the NPCs. */
    void write(ScreenManager<P, S>.Viewer viewer, FileWork work) {
        UUID id = viewer.id();
        try {
            parts.files().execute(() -> {
                ChatLine line;
                try {
                    String result = work.run(files());
                    boolean changed = result.contains("; tx ");
                    if (changed) living().refresh();
                    line = ChatLine.parse(changed ? Palette.success(result) : Palette.reply(result));
                } catch (IOException | RuntimeException failure) { line = ChatLine.error(String.valueOf(failure.getMessage())); }
                ChatLine reply = line;
                living().players().deliver(id, (P online) -> {
                    host().chat(online, reply);
                    viewer.refresh();
                });
            });
        } catch (RejectedExecutionException full) { say(viewer.player(), ChatLine.error(CommandActor.BUSY)); }
    }

    /** Reads on the file lane, then hands the result to the screen on the player's owner while it is open. */
    <T> void later(ScreenManager<P, S>.Viewer viewer, Load<T> load, Consumer<T> done) {
        UUID id = viewer.id();
        try {
            parts.files().execute(() -> {
                T value;
                try { value = load.run(files()); }
                catch (IOException | RuntimeException failure) {
                    living().players().deliver(id, (P online) -> host().chat(online, ChatLine.error(String.valueOf(failure.getMessage()))));
                    return;
                }
                living().players().deliver(id, (P online) -> { if (viewer.open()) done.accept(value); });
            });
        } catch (RejectedExecutionException full) { say(viewer.player(), ChatLine.error(CommandActor.BUSY)); }
    }

    private ToolkitFiles files() { return new ToolkitFiles(npcs(), parts.documents().get()); }

    void say(P player, ChatLine line) { host().chat(player, line); }

    /** Wand and capture feedback: grey text above the hotbar, {@code &c} for a refusal. */
    private void bar(P player, String text) { host().actionBar(player, RichText.colorize(Palette.GREY + text)); }

    /** The player as a command actor, for the inspect reply and the pairing link. */
    CommandActor actor(P player) {
        UUID id = host().id(player);
        return new CommandActor() {
            @Override public boolean permitted(String node) { return living().players().owned(id).map((P online) -> host().permitted(online, node)).orElse(false); }
            @Override public void tell(String message) { rich(ChatLine.parse(Palette.reply(message))); }
            @Override public void rich(ChatLine line) { living().players().owned(id).ifPresent((P online) -> host().chat(online, line)); }
            @Override public Optional<UUID> player() { return Optional.of(id); }
            @Override public boolean reply(Runnable action) { return living().players().deliver(id, (P online) -> action.run()); }
        };
    }
}
