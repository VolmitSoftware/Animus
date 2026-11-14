package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.AnimusCommandTree;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.CommandSound;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.command.RecentChanges;
import art.arcane.animus.core.command.ReplyActor;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.SelectionPick;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.shop.Prices;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Animus menu behind {@code /animus tools}: the selection wand, its outline and the chest screens; routes and
 * recordings being made live in {@link CaptureFlow}. One per platform, found by the command layer through the screen
 * manager it was installed with. Player work runs on the player's owner; file work on the file lane.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public final class Toolkit<P, S> implements AutoCloseable {
    /** The wand's item marker: a Bukkit persistent data key, and the same key in a mod item's custom data. */
    public static final String MARKER = "animus:tool", WAND = "wand";
    /** What the wand looks like: a glinting stick, which unlike a blaze rod grants no advancement. The marker, not the look, makes it a wand. */
    public static final NpcItem WAND_ITEM = new NpcItem("minecraft:stick", 1, Optional.of("[enchantment_glint_override=true]"), Optional.empty());
    public static final String WAND_NAME = RichText.colorize(Palette.PINK + "Animus wand");
    public static final List<String> WAND_LORE = List.of(RichText.colorize("&fClick: &7select an NPC"), RichText.colorize("&fRight-click: &7edit an NPC, or open the menu"),
            RichText.colorize("&fSneak-click: &7add or remove an NPC"));
    /** One click often arrives once per hand; a repeat on the same NPC inside this window is the same click. */
    static final long REPEAT_NANOS = 250_000_000L;
    /** "No NPC there" shows at most this often. */
    static final long MISS_NANOS = 2_000_000_000L;
    /** A wand swing at nothing says "No NPC there" only this near an NPC, or while sneaking; holding the wand elsewhere stays quiet. */
    static final double MISS_REACH = 6;
    /** Ticks a wand click on air or a block waits for an NPC click from the same press, which some servers deliver later. */
    static final int WAND_WAIT = 2;
    static final String CLICK_SOUND = "minecraft:ui.button.click";
    private static final Map<ScreenManager<?, ?>, Toolkit<?, ?>> INSTALLED = new ConcurrentHashMap<>();

    /** Moves an active NPC at once, on its owner. False when the place is blocked or the NPC is riding. */
    @FunctionalInterface public interface Relocator { boolean relocate(String id, WorldPosition destination); }

    /**
     * What the command layer hooks into the screens: the inspect reply, the editor pairing link, {@code revive} as
     * {@code /animus npc revive} does it, whether a player is recording now, the running behavior runs of an NPC, and how
     * shop prices read with the server's economy now.
     */
    public record Commands(BiConsumer<CommandActor, String> inspect, Consumer<CommandActor> pair, BiConsumer<CommandActor, String> revive, Predicate<UUID> recording,
                           Runs runs, Supplier<Prices> prices) {
        public Commands {
            Objects.requireNonNull(inspect); Objects.requireNonNull(pair); Objects.requireNonNull(revive); Objects.requireNonNull(recording); Objects.requireNonNull(runs);
            Objects.requireNonNull(prices);
        }
    }

    /** One running behavior run: its id, the NPC it runs on and the behavior. */
    public record Run(UUID id, String npc, String behavior) {}

    /** The running behavior runs of one NPC, so Cancel a run can list them. */
    @FunctionalInterface public interface Runs { CompletionStage<List<Run>> running(String npc); }

    /** {@code skins} reads a player's signed textures; {@code clock} is nanoseconds. */
    public record Parts<P, S>(Supplier<NpcRepository> npcs, Supplier<DocumentStore> documents, ScreenManager<P, S> screens, NpcRuntime<P, ?> living,
                              Executor files, Function<P, Optional<BodySkin>> skins, Optional<Relocator> relocator, LongSupplier clock) {
        public Parts {
            Objects.requireNonNull(npcs); Objects.requireNonNull(documents); Objects.requireNonNull(screens); Objects.requireNonNull(living);
            Objects.requireNonNull(files); Objects.requireNonNull(skins); Objects.requireNonNull(relocator); Objects.requireNonNull(clock);
        }
    }

    /** A wand click that reached no NPC, resolved {@link #WAND_WAIT} ticks later unless an NPC click of the same press arrives. */
    private enum Miss { HOME, CLEAR, SWING }

    /** Per player: outline tick count, the sneak state capture watches, the last wand click on an NPC and a waiting miss. */
    private static final class PlayerState {
        long ticks, clicked, missed;
        UUID last;
        boolean sneaking, warned;
        Miss miss;
        int waited;
    }

    @FunctionalInterface interface FileWork { String run(ToolkitFiles files) throws IOException; }
    @FunctionalInterface interface Load<T> { T run(ToolkitFiles files) throws IOException; }
    /** Builds a tool screen for a viewer; what {@link #open(UUID, ScreenFactory)} takes. */
    @FunctionalInterface interface ScreenFactory<P, S> { ToolScreen<P, S> create(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Commands commands); }

    /** The next wand click on an NPC answers this instead of selecting; {@code label} is shown above the hotbar. */
    private record Picker(String label, Consumer<String> chosen) {}

    private final Parts<P, S> parts;
    private final ToolkitSelection selection = new ToolkitSelection();
    private final CaptureFlow<P, S> captures = new CaptureFlow<>(this);
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<UUID, Picker> pickers = new ConcurrentHashMap<>();
    private final Map<UUID, String> notices = new ConcurrentHashMap<>();
    private final ToolkitPrefs prefs;
    /** Each player's changes, from menu replies and commands alike, for {@code /animus undo}. */
    private final RecentChanges changes = new RecentChanges();
    /** The command layer's hooks, set as the command tree is built; no menu opens before then. */
    private volatile Commands hooks;

    private Toolkit(Parts<P, S> parts) {
        this.parts = parts;
        prefs = new ToolkitPrefs(parts.documents(), parts.files());
    }

    /**
     * Installs the menu for this screen manager. The platform installs it before it builds the command tree, which hooks
     * its commands in through {@link #hook} as it is made, so the wand and the commands open the same menu from the start.
     */
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
        notices.clear();
    }

    ScreenHost<P, S> host() { return parts.screens().host(); }
    NpcRuntime<P, ?> living() { return parts.living(); }
    NpcRepository npcs() { return parts.npcs().get(); }
    /** The document store, for file classes beside {@link ToolkitFiles}; read and write it on the file lane only. */
    DocumentStore documents() { return parts.documents().get(); }
    ToolkitSelection selection() { return selection; }
    ScreenManager<P, S> screens() { return parts.screens(); }
    /** The file lane. */
    Executor lane() { return parts.files(); }
    /** The routes and recordings players are making. */
    CaptureFlow<P, S> captures() { return captures; }
    /** The command layer's hooks, empty until the command tree is built. */
    Optional<Commands> hooks() { return Optional.ofNullable(hooks); }
    Optional<Relocator> relocator() { return parts.relocator(); }
    Optional<BodySkin> skin(P player) { return parts.skins().apply(player); }

    /** The selected NPCs that still exist, in the order picked. */
    public List<String> selected(UUID player) {
        NpcRepository npcs = npcs();
        selection.retain(player, (String id) -> npcs.find(id).isPresent());
        return selection.of(player);
    }

    /** The player's menu mode; Simple until their saved mode is read at join or first open. */
    public ToolkitMode mode(UUID player) { return prefs.mode(player); }

    /** Switches the player's menu mode at once and saves it on the file lane. */
    public void mode(UUID player, ToolkitMode mode) { prefs.mode(player, mode); }

    public boolean advanced(UUID player) { return prefs.mode(player) == ToolkitMode.ADVANCED; }

    /** Each player's recent changes, which menu replies fill and {@code /animus undo} reads. */
    public RecentChanges changes() { return changes; }

    /** The player joined: their saved menu mode is read on the file lane. */
    public void joined(UUID player) { prefs.load(player); }

    /** From {@code /animus select}: the NPC becomes the player's whole selection, as a plain wand click makes it. */
    public void selectOnly(UUID player, String id) { selection.only(player, id); }

    /**
     * From {@code /animus undo}, after it rolled {@code transaction} back: the NPCs that transaction deleted and the undo
     * brought back become the player's whole selection, as they were when they were deleted. Any other change leaves the
     * selection alone. Reads the transaction's receipt, so call it on the file lane.
     */
    public void undone(UUID player, String transaction) {
        List<String> back = new ArrayList<>();
        try {
            DocumentStore store = documents();
            for (DocumentStore.Change undo : store.inverse(store.transaction(transaction)))
                if (NpcPaths.file(undo.path()) && undo.expectedHash().equals(JsonDocuments.hash(null)) && store.read(undo.path()) != null) back.add(NpcPaths.idOf(undo.path()));
        } catch (IOException unread) { return; }
        List<String> found = back.stream().filter((String id) -> npcs().find(id).isPresent()).toList();
        if (found.isEmpty()) return;
        selection.set(player, found);
        primary(player, found.getFirst());
    }

    /** Whether the player holds {@code node}; says which node is missing when not. Toolkit actions ask on every click. */
    boolean allows(P player, String node) {
        if (host().permitted(player, node)) return true;
        refuse(player, "No permission: " + node + "; ask an operator to grant it");
        return false;
    }

    /**
     * Why something did not happen: red in chat with the failure tone, and while a menu window shows, on that window
     * too until the player's next click.
     */
    void refuse(P player, String reason) {
        say(player, ChatLine.error(reason));
        UUID id = host().id(player);
        parts.screens().viewer(id).filter((ScreenManager<P, S>.Viewer viewer) -> viewer.open() && !viewer.suspended()).ifPresent((ScreenManager<P, S>.Viewer viewer) -> {
            notices.put(id, reason);
            viewer.refresh();
        });
    }

    /** The refusal the player's window shows, until their next click. */
    Optional<String> notice(UUID player) { return Optional.ofNullable(notices.get(player)); }

    void dismiss(UUID player) { notices.remove(player); }

    /** The quiet tick a handled menu click makes. */
    void clicked(P player) { host().sound(player, CLICK_SOUND, "master", 0.3f, 1f); }

    private PlayerState state(UUID player) { return players.computeIfAbsent(player, (UUID ignored) -> new PlayerState()); }

    /** Players the platform ticks: those with a selection to outline, a route being made, a recording starting or a wand click waiting. */
    public Set<UUID> watching() {
        Set<UUID> watched = new HashSet<>(selection.players());
        watched.addAll(captures.players());
        for (Map.Entry<UUID, PlayerState> player : players.entrySet()) if (player.getValue().miss != null) watched.add(player.getKey());
        return watched;
    }

    /**
     * A validated click on an NPC, first in the listener chain. With the wand a right-click selects only that NPC and
     * opens its screen, a left click only selects it and a sneak-click adds or removes it; the click is used either
     * way. Without the wand nothing happens here.
     */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        ScreenHost<P, S> host = host();
        if (!host.holdsWand(player) || !host.permitted(player, PermissionNodes.TOOLS)) return false;
        Optional<NpcRuntime.ActiveNpc> active = living().active(npc);
        if (active.isEmpty()) return false;
        UUID id = host.id(player);
        PlayerState state = state(id);
        long now = parts.clock().getAsLong();
        state.miss = null;
        if (npc.equals(state.last) && now - state.clicked < REPEAT_NANOS) return true;
        state.last = npc;
        state.clicked = now;
        boolean sneak = attack ? host.pose(player).map(ScreenHost.Pose::sneaking).orElse(false) : sneaking;
        String target = active.get().id();
        Picker picker = pickers.remove(id);
        if (picker != null) { picker.chosen().accept(target); return true; }
        if (!sneak) {
            only(player, target);
            if (!attack) open(id);
            return true;
        }
        boolean added;
        try { added = selection.toggle(id, target); }
        catch (IllegalStateException full) { bar(player, "&c" + full.getMessage()); return true; }
        if (added) { primary(id, target); glow(id, target); }
        int count = selection.of(id).size();
        bar(player, (added ? "Added " : "Removed ") + shown(id, target) + ", " + count + " selected");
        return true;
    }

    /**
     * The wand's right-click on air or a block, main hand: opens the Animus menu, or while sneaking clears the selection.
     * True when the click is the wand's, so the platform cancels it.
     */
    public boolean used(P player, boolean sneaking) { return missed(player, sneaking ? Miss.CLEAR : Miss.HOME); }

    /**
     * The wand swung at air: says "No NPC there" when an NPC stands within {@link #MISS_REACH} blocks or the player sneaks,
     * at most every {@link #MISS_NANOS}, and never while the player records, since the swing is part of the take. A swing
     * at a block never comes here.
     */
    public void swung(P player) { missed(player, Miss.SWING); }

    /** Holds a click that found no NPC for {@link #WAND_WAIT} ticks; an NPC click of the same press, before or after, drops it. */
    private boolean missed(P player, Miss miss) {
        ScreenHost<P, S> host = host();
        if (!host.holdsWand(player) || !host.permitted(player, PermissionNodes.TOOLS)) return false;
        PlayerState state = state(host.id(player));
        if (parts.clock().getAsLong() - state.clicked < REPEAT_NANOS) return true;
        if (state.miss == null || miss != Miss.SWING) { state.miss = miss; state.waited = 0; }
        return true;
    }

    private void resolve(P player, PlayerState state) {
        Miss miss = state.miss;
        state.miss = null;
        UUID id = host().id(player);
        Optional<String> picking = picking(id);
        switch (miss) {
            case HOME -> { if (picking.isPresent()) bar(player, picking.get() + ": click an NPC with the wand"); else home(id); }
            case CLEAR -> { selection.clear(id); bar(player, "Selection cleared"); }
            case SWING -> {
                Commands commands = hooks;
                if (!aimed(player) || commands != null && commands.recording().test(id)) return;
                long now = parts.clock().getAsLong();
                if (state.warned && now - state.missed < MISS_NANOS) return;
                state.warned = true;
                state.missed = now;
                bar(player, "No NPC there");
            }
        }
    }

    /** Whether a missed swing was meant for an NPC: the player sneaks, or an active NPC stands within {@link #MISS_REACH} blocks. */
    private boolean aimed(P player) {
        Optional<ScreenHost.Pose> pose = host().pose(player);
        if (pose.isEmpty()) return false;
        if (pose.get().sneaking()) return true;
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        for (NpcDefinition definition : npcs().all()) {
            Optional<VirtualNpcState.Snapshot> state = living().state(definition.uuid());
            if (state.isPresent() && pose.get().in(state.get().position().world()) && state.get().position().point().distance(at) <= MISS_REACH) return true;
        }
        return false;
    }

    /** Selects only this NPC, which also becomes the primary selection. */
    void only(P player, String id) {
        UUID uuid = host().id(player);
        selection.only(uuid, id);
        primary(uuid, id);
        glow(uuid, id);
        bar(player, "Selected " + shown(uuid, id));
    }

    /** The NPC as people read it: the name players see, with its id beside it in Advanced when the two differ. */
    String shown(UUID player, String id) {
        String name = npcs().find(id).map(NpcsScreen::name).orElse(id);
        return advanced(player) && !name.equals(id) ? name + " (" + id + ")" : name;
    }

    /**
     * The NPC glows for the player a moment, as {@code /animus select} makes it. The runtime keeps one viewer glow at a
     * time and only briefly, so the particle outline is what stays while NPCs are selected.
     */
    private void glow(UUID player, String id) {
        try { living().highlight(player, id, SelectionPick.GLOW_TICKS); }
        catch (RuntimeException refused) { /* the outline still shows it */ }
    }

    /** The runtime's own pick follows the wand, for platforms that read it; commands read {@link #selected}. */
    private void primary(UUID player, String id) {
        try { living().select(player, id); }
        catch (RuntimeException refused) { /* the runtime is full of viewers; the toolkit selection still holds it */ }
    }

    /**
     * Every owner tick for a watched player: a waiting wand click, a recording counting down or running, a route point
     * on a sneak press, and the outline every {@link ToolkitOutline#PERIOD} ticks.
     */
    public void tick(P player) {
        ScreenHost<P, S> host = host();
        UUID id = host.id(player);
        PlayerState state = state(id);
        if (state.miss != null && ++state.waited >= WAND_WAIT) resolve(player, state);
        captures.tick(player, id);
        Optional<ScreenHost.Pose> pose = host.pose(player);
        if (pose.isEmpty()) return;
        boolean sneaking = pose.get().sneaking();
        if (sneaking && !state.sneaking) captures.sneaked(player, pose.get());
        state.sneaking = sneaking;
        if (state.ticks++ % ToolkitOutline.PERIOD != 0) return;
        List<VirtualNpcState.Snapshot> shown = new ArrayList<>();
        NpcRepository npcs = npcs();
        for (String selected : selection.of(id)) npcs.find(selected).flatMap((NpcDefinition definition) -> living().state(definition.uuid())).ifPresent(shown::add);
        for (Vec3 corner : ToolkitOutline.outline(pose.get(), shown)) host.particle(player, ToolkitOutline.OUTLINE_PARTICLE, 1, 0, corner.x(), corner.y(), corner.z());
        Optional<WaypointCapture> capture = captures.capture(id);
        if (capture.isPresent())
            for (ToolkitOutline.Marker marker : ToolkitOutline.waypoints(pose.get(), capture.get()))
                host.particle(player, marker.current() ? ToolkitOutline.CURRENT_PARTICLE : ToolkitOutline.WAYPOINT_PARTICLE, 4, 0.1, marker.point().x(), marker.point().y() + 0.2, marker.point().z());
    }

    /** The player left: selection, capture, pick, click state and the cached menu mode go. */
    public void quit(UUID player) {
        selection.clear(player);
        captures.quit(player);
        players.remove(player);
        pickers.remove(player);
        notices.remove(player);
        prefs.forget(player);
    }

    /** The command tree's hooks, from the platform's command tree as it is built; the wand and the commands both use them. */
    public void hook(Commands commands) { hooks = Objects.requireNonNull(commands); }

    /** {@code /animus tools}: the NPC screen of the selection, over Home so Back goes there; Home when nothing is selected. */
    public void open(UUID player) { open(player, HomeScreen::new, !selected(player).isEmpty()); }

    /** The Animus menu's first screen. */
    public void home(UUID player) { open(player, HomeScreen::new, false); }

    /** Opens any menu screen on the player's owner, replacing the window they have open; a first open reads the saved menu mode before it draws. */
    void open(UUID player, ScreenFactory<P, S> factory) { open(player, factory, false); }

    private void open(UUID player, ScreenFactory<P, S> factory, boolean npc) {
        prefs.load(player, () -> living().players().deliver(player, (P online) -> {
            if (!show(online, factory)) return;
            if (npc) parts.screens().viewer(player).ifPresent((ScreenManager<P, S>.Viewer viewer) -> viewer.push(new NpcScreen<>(this, viewer, hooks)));
        }));
    }

    /**
     * Opens the screen as the player's window, replacing the one they have open. Refused, saying why, before the command
     * tree has hooked in or when the player lacks the screen's node; nothing is read or written then.
     */
    boolean show(P player, ScreenFactory<P, S> factory) {
        Commands commands = hooks;
        if (commands == null) { refuse(player, "The Animus menu is still starting; try again in a moment"); return false; }
        notices.remove(host().id(player));
        return parts.screens().open(player, Optional.empty(), (ScreenManager<P, S>.Viewer viewer) -> {
            ToolScreen<P, S> screen = factory.create(this, viewer, commands);
            return allows(player, screen.node()) ? Optional.of(screen) : Optional.empty();
        });
    }

    /**
     * Hides the viewer's window and asks for a line of text. The window comes back on the player's owner before
     * {@code answer} runs with the text; a cancelled prompt brings the window back and says so. A platform without
     * text input keeps the window open and says what to type instead.
     */
    void prompt(ScreenManager<P, S>.Viewer viewer, String label, String initial, Consumer<String> answer) { prompt(viewer, label, initial, "", answer); }

    /** As {@link #prompt(ScreenManager.Viewer, String, String, Consumer)}, with a hint line such as the characters an id allows. */
    void prompt(ScreenManager<P, S>.Viewer viewer, String label, String initial, String hint, Consumer<String> answer) {
        UUID id = viewer.id();
        ScreenHost.Prompt prompt = new ScreenHost.Prompt(label, initial, hint, (Optional<String> typed) -> living().players().deliver(id, (P online) -> {
            if (!viewer.open()) return;
            viewer.resume();
            if (typed.isPresent()) answer.accept(typed.get());
            else say(online, ChatLine.reply("Cancelled; nothing changed"));
            viewer.refresh();
        }));
        viewer.suspend();
        if (host().prompt(viewer.player(), prompt)) return;
        viewer.resume();
        refuse(viewer.player(), "This server has no text input for " + label + "; use the matching /animus command");
    }

    /**
     * The next wand click on an NPC answers {@code chosen} instead of selecting. The window is hidden while the player
     * looks for the NPC and comes back once they have clicked one; a second call replaces the first.
     */
    void pick(ScreenManager<P, S>.Viewer viewer, String label, Consumer<String> chosen) { pick(viewer, label, true, chosen); }

    /**
     * As {@link #pick(ScreenManager.Viewer, String, Consumer)}; when {@code reopen} is false the window stays closed
     * after the pick so the player sees what it did, unless {@code chosen} starts another pick.
     */
    void pick(ScreenManager<P, S>.Viewer viewer, String label, boolean reopen, Consumer<String> chosen) {
        UUID id = viewer.id();
        P player = viewer.player();
        if (!host().holdsWand(player) && host().giveWand(player) == ScreenHost.WandGift.NONE) { refuse(player, "This platform has no selection wand"); return; }
        viewer.suspend();
        pickers.put(id, new Picker(label, (String npc) -> {
            if (!viewer.open()) { refuse(player, label + " cancelled; the menu closed. Open it and pick again"); return; }
            if (reopen) viewer.resume();
            chosen.accept(npc);
            if (!reopen && !pickers.containsKey(id)) viewer.close();
        }));
        bar(player, label + ": click an NPC with the wand");
    }

    /** The pick the player is in the middle of, for the screens' hints. */
    Optional<String> picking(UUID player) { return Optional.ofNullable(pickers.get(player)).map(Picker::label); }

    /** From a command: gives the wand on the player's owner. */
    public void wand(UUID player) { living().players().deliver(player, this::giveWand); }

    /** After a new NPC: the wand in hand, given or moved there when needed, and one hint above the hotbar. */
    private void wandHint(P player) {
        ScreenHost.WandGift gift = host().holdsWand(player) ? ScreenHost.WandGift.MOVED : host().giveWand(player);
        switch (gift) {
            case GIVEN -> bar(player, "Wand given; right-click it to edit");
            case MOVED -> bar(player, "Right-click it with the wand to edit");
            case DROPPED -> say(player, ChatLine.success("Inventory full; the wand is at your feet"));
            case NONE -> { }
        }
    }

    /** Gives a wand, or moves the one the player carries to their hand, and says which happened. */
    void giveWand(P player) {
        switch (host().giveWand(player)) {
            case GIVEN -> say(player, ChatLine.success("Wand given; right-click an NPC to edit it"));
            case MOVED -> say(player, ChatLine.success("The wand is in your hand; right-click an NPC to edit it"));
            case DROPPED -> say(player, ChatLine.success("Inventory full; the wand is at your feet"));
            case NONE -> refuse(player, "This platform has no selection wand");
        }
    }

    void clear(P player) {
        clear(host().id(player));
        bar(player, "Selection cleared");
    }

    /** Empties the player's selection, as {@code /animus select none} does. */
    public void clear(UUID player) { selection.clear(player); }

    /**
     * From the chat chips of a route being made: {@code add}, {@code undo}, {@code save} or {@code cancel} for {@code route}
     * (empty for any), on the player's owner.
     */
    public void route(UUID player, String step, String route) { living().players().deliver(player, (P online) -> captures.step(online, step, route)); }

    /** Spawns or despawns the NPCs; a spawn is checked on this owner first, as {@code /animus spawn} checks it. */
    void spawned(ScreenManager<P, S>.Viewer viewer, List<String> ids, boolean spawn) {
        if (!allows(viewer.player(), PermissionNodes.NPC_EDIT)) return;
        later(viewer, (ToolkitFiles files) -> files.spawned(ids, spawn), (ToolkitFiles.Batch batch) -> {
            if (spawn) for (NpcDefinition candidate : batch.candidates()) {
                List<String> errors = living().validateActivation(candidate);
                if (!errors.isEmpty()) { refuse(viewer.player(), "Cannot spawn " + candidate.id() + ": " + errors.getFirst()); return; }
            }
            write(viewer, (ToolkitFiles files) -> files.apply(batch, spawn));
        });
    }

    /** Teleports the player beside the NPC, on the side they come from, facing it. */
    void visit(P player, String id) {
        Optional<VirtualNpcState.Snapshot> state = npcs().find(id).flatMap((NpcDefinition definition) -> living().state(definition.uuid()));
        UUID viewer = host().id(player);
        if (state.isEmpty()) { refuse(player, shown(viewer, id) + " is not active; it becomes active when its chunk loads or a player comes near"); return; }
        String world = state.get().position().world();
        Optional<Vec3> from = host().pose(player).filter((ScreenHost.Pose pose) -> pose.in(world)).map((ScreenHost.Pose pose) -> new Vec3(pose.x(), pose.y(), pose.z()));
        Landing.Spot spot = Landing.beside(state.get().position().point(), from);
        if (host().teleport(player, world, spot.at().x(), spot.at().y(), spot.at().z(), spot.yaw(), 0)) say(player, ChatLine.reply("Teleported to " + shown(viewer, id)));
        else refuse(player, "Teleport to " + shown(viewer, id) + " was refused");
    }

    /** From a command: teleports the player beside the NPC, on the player's owner. False when the player is not online. */
    public boolean visit(UUID player, String id) { return living().players().deliver(player, (P online) -> visit(online, id)); }

    /** Moves an active NPC to {@code destination} at once, on its owner; an inactive NPC moves when its saved place is read again. */
    public void relocate(String id, WorldPosition destination) {
        relocator().ifPresent((Relocator relocator) -> living().onOwner(id, () -> relocator.relocate(id, destination), (String refused) -> {}));
    }

    /**
     * Moves the NPCs active in the player's world in front of the player, facing them, where the guided create puts a
     * new NPC, and saves where they stand in one transaction so one Undo puts them all back. The others are left where
     * they are and counted in the reply.
     */
    void bring(ScreenManager<P, S>.Viewer viewer, List<String> ids) {
        P player = viewer.player();
        if (!allows(player, PermissionNodes.NPC_MOVE)) return;
        Optional<ScreenHost.Pose> pose = host().pose(player);
        if (pose.isEmpty()) { refuse(player, "Your position cannot be read right now; try again"); return; }
        Map<String, String> worlds = new LinkedHashMap<>();
        for (String id : ids)
            npcs().find(id).flatMap((NpcDefinition definition) -> living().state(definition.uuid())).map((VirtualNpcState.Snapshot state) -> state.position().world())
                    .filter(pose.get()::in).ifPresent((String world) -> worlds.put(id, world));
        if (worlds.isEmpty()) { refuse(player, (ids.size() == 1 ? shown(host().id(player), ids.getFirst()) + " is not" : "None of them is") + " active in your world"); return; }
        Landing.Spot spot = Landing.ahead(pose.get());
        List<String> moving = List.copyOf(worlds.keySet());
        int left = ids.size() - moving.size();
        write(viewer, (ToolkitFiles files) -> files.positions(moving, spot.at(), spot.yaw(), 0, left == 0 ? "" : ", " + left + " not active in your world"));
        for (Map.Entry<String, String> npc : worlds.entrySet()) relocate(npc.getKey(), new WorldPosition(npc.getValue(), spot.at()));
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
        String who = sent == 1 && ids.size() == 1 ? shown(host().id(player), ids.getFirst()) : ToolkitFiles.count(sent);
        say(player, ChatLine.reply((route.isPresent() ? "Walking " + route.get() + ": " : "Route started: ") + who + (sent == ids.size() ? "" : ", " + (ids.size() - sent) + " not active")));
    }

    void halt(P player, List<String> ids) {
        int sent = stop(ids);
        String who = sent == 1 && ids.size() == 1 ? shown(host().id(player), ids.getFirst()) : ToolkitFiles.count(sent);
        say(player, ChatLine.reply("Stopped " + who + (sent == ids.size() ? "" : ", " + (ids.size() - sent) + " not active")));
    }

    /** Stops the NPCs where they are, on their owners, without a chat line; returns how many were active to stop. */
    int stop(List<String> ids) {
        int sent = 0;
        for (String id : ids) if (living().onOwner(id, () -> living().stopNavigation(id), (String refused) -> {})) sent++;
        return sent;
    }

    /**
     * The guided create's last step: saves the planned NPC two blocks in front of the player, facing them, with any new
     * skin file, in one transaction; a spawn is checked on this owner first, as {@code /animus spawn} checks it. Then the
     * NPC is selected, the window closes so it shows, chat offers [Edit] and [Undo], and a player without a wand gets one.
     * All of that happens even when the window closed meanwhile. The position's world is the one NPC positions name, the
     * first of the pose's names that an existing NPC uses, else the shortest.
     */
    void create(ScreenManager<P, S>.Viewer viewer, CreateFlow.Plan plan) {
        P player = viewer.player();
        if (!allows(player, PermissionNodes.NPC_CREATE)) return;
        Optional<ScreenHost.Pose> pose = host().pose(player);
        if (pose.isEmpty()) { refuse(player, "Your position cannot be read right now; try again"); return; }
        String world = worldName(pose.get());
        Landing.Spot spot = Landing.ahead(pose.get());
        background(player, (ToolkitFiles ignored) -> CreateFlow.draft(npcs(), documents(), plan, world, spot, viewer.id()), (P drafted, CreateFlow.Draft draft) -> {
            if (plan.spawn()) {
                List<String> errors = living().validateActivation(draft.definition());
                if (!errors.isEmpty()) { refuse(drafted, "Cannot spawn " + plan.name() + ": " + errors.getFirst() + "; nothing was saved"); return; }
            }
            background(drafted, (ToolkitFiles ignored) -> CreateFlow.commit(npcs(), documents(), draft), (P online, String tx) -> {
                living().refresh();
                only(online, plan.id());
                viewer.close();
                say(online, AnimusCommandTree.made(true, plan.id(), shown(viewer.id(), plan.id()), "", plan.spawn(), tx));
                wandHint(online);
            });
        });
    }

    private String worldName(ScreenHost.Pose pose) {
        for (NpcDefinition definition : npcs().stored()) {
            String world = definition.json().getAsJsonObject("position").get("world").getAsString();
            if (pose.in(world)) return world;
        }
        return pose.worlds().stream().min(Comparator.comparingInt(String::length).thenComparing(String::compareTo)).orElse("world");
    }

    /** Saves the player's own signed skin, labelled with their name, and puts it on the selection. */
    void mySkin(ScreenManager<P, S>.Viewer viewer, List<String> ids) {
        if (!allows(viewer.player(), PermissionNodes.NPC_SKIN)) return;
        Optional<BodySkin> skin = skin(viewer.player());
        if (skin.isEmpty()) { refuse(viewer.player(), "Your skin has no signature; offline-mode servers cannot copy it"); return; }
        String label = host().name(viewer.player());
        write(viewer, (ToolkitFiles files) -> files.skin(Optional.of(new SkinFiles(npcs(), documents()).save(skin.get(), label)), ids));
    }

    /** Runs file work, then tells the player its line and redraws the screen. A line with a transaction refreshes the NPCs. */
    void write(ScreenManager<P, S>.Viewer viewer, FileWork work) { write(viewer, work, () -> {}); }

    /** As {@link #write(ScreenManager.Viewer, FileWork)}; {@code after} runs on the player's owner once the line is told, unless the work was refused. */
    void write(ScreenManager<P, S>.Viewer viewer, FileWork work, Runnable after) { line(viewer, (ToolkitFiles files) -> reply(work.run(files)), after); }

    /** A file work result in green when it wrote something, that is when it names a transaction. */
    static ChatLine reply(String result) { return ChatLine.parse(result.contains("; tx ") ? Palette.success(result) : Palette.reply(result)); }

    @FunctionalInterface private interface LineWork { ChatLine run(ToolkitFiles files) throws IOException; }

    /** {@link #write} for work that builds its own chat line. */
    private void line(ScreenManager<P, S>.Viewer viewer, LineWork work, Runnable after) {
        UUID id = viewer.id();
        try {
            parts.files().execute(() -> {
                ChatLine line;
                String failed = null;
                try {
                    line = work.run(files());
                    if (!line.transactions().isEmpty()) living().refresh();
                } catch (IOException | RuntimeException failure) { line = null; failed = String.valueOf(failure.getMessage()); }
                ChatLine reply = line;
                String refused = failed;
                living().players().deliver(id, (P online) -> {
                    if (refused != null) refuse(online, refused);
                    else { say(online, reply); after.run(); }
                    viewer.refresh();
                });
            });
        } catch (RejectedExecutionException full) { refuse(viewer.player(), CommandActor.BUSY); }
    }

    /** File work started without a window, such as from a chat chip: {@code done} gets the result on the player's owner; a failure is refused. */
    <T> void background(P player, Load<T> work, BiConsumer<P, T> done) {
        UUID id = host().id(player);
        try {
            parts.files().execute(() -> {
                T value;
                try { value = work.run(files()); }
                catch (IOException | RuntimeException failure) {
                    living().players().deliver(id, (P online) -> refuse(online, String.valueOf(failure.getMessage())));
                    return;
                }
                living().players().deliver(id, (P online) -> done.accept(online, value));
            });
        } catch (RejectedExecutionException full) { refuse(player, CommandActor.BUSY); }
    }

    /** Reads on the file lane, then hands the result to the screen on the player's owner while it is open. */
    <T> void later(ScreenManager<P, S>.Viewer viewer, Load<T> load, Consumer<T> done) { later(viewer, load, done, (String reason) -> { }); }

    /** As {@link #later(ScreenManager.Viewer, Load, Consumer)}; after a refusal {@code failed} gets the reason, while the window is open. */
    <T> void later(ScreenManager<P, S>.Viewer viewer, Load<T> load, Consumer<T> done, Consumer<String> failed) {
        UUID id = viewer.id();
        try {
            parts.files().execute(() -> {
                T value;
                try { value = load.run(files()); }
                catch (IOException | RuntimeException failure) {
                    String reason = String.valueOf(failure.getMessage());
                    living().players().deliver(id, (P online) -> {
                        refuse(online, reason);
                        if (viewer.open()) failed.accept(reason);
                    });
                    return;
                }
                living().players().deliver(id, (P online) -> { if (viewer.open()) done.accept(value); });
            });
        } catch (RejectedExecutionException full) {
            refuse(viewer.player(), CommandActor.BUSY);
            failed.accept(CommandActor.BUSY);
        }
    }

    private ToolkitFiles files() { return new ToolkitFiles(npcs(), parts.documents().get()); }

    /** Every menu chat line, refusals too, answers as a command reply does: see the player's actor below. */
    void say(P player, ChatLine line) { actor(host().id(player), () -> Optional.of(player)).rich(line); }

    /** Wand and capture feedback: grey text above the hotbar, {@code &c} for a refusal. */
    void bar(P player, String text) { host().actionBar(player, RichText.colorize(Palette.GREY + text)); }

    /**
     * The player as a command actor, for menu replies and the commands a click runs, such as inspect and the pairing link.
     * It answers as a command reply does: Simple mode hides change ids and keeps [Undo] and each change is kept for
     * {@code /animus undo}. A refusal plays the failure tone; success adds nothing to the click's own tick.
     */
    CommandActor actor(P player) {
        UUID id = host().id(player);
        return actor(id, () -> living().players().owned(id));
    }

    /** The player's actor, reaching them through {@code online}; a later answer reads it on the player's owner. */
    private CommandActor actor(UUID id, Supplier<Optional<P>> online) {
        return ReplyActor.menu(new CommandActor() {
            @Override public boolean permitted(String node) { return online.get().map((P found) -> host().permitted(found, node)).orElse(false); }
            @Override public void tell(String message) { rich(ChatLine.parse(Palette.reply(message))); }
            @Override public void rich(ChatLine line) { online.get().ifPresent((P found) -> host().chat(found, line)); }
            @Override public Optional<UUID> player() { return Optional.of(id); }
            @Override public boolean reply(Runnable action) { return living().players().deliver(id, (P found) -> action.run()); }
            @Override public void play(CommandSound sound) {
                online.get().ifPresent((P found) -> {
                    for (CommandSound.Tone tone : sound.tones(ThreadLocalRandom.current())) host().sound(found, tone.sound(), "master", tone.volume(), tone.pitch());
                });
            }
        }, !advanced(id), changes);
    }
}
