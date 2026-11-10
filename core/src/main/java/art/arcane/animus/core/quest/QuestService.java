package art.arcane.animus.core.quest;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.ShopCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Animus quests on one server: the catalog under {@code quests/}, each player's record, the play on NPC clicks, kills
 * and ticks ({@link QuestPlay}), the journal, the giver markers and the behavior hooks. Player work runs on each
 * player's owner; the catalog is read on the file lane and records are written by {@link QuestStore}.
 *
 * @param <P> the platform's online player
 * @param <S> the platform's item stack
 */
public final class QuestService<P, S> implements QuestControl, AutoCloseable {
    /** Ticks between the per-player checks of reach and collect objectives and the giver markers. */
    public static final int CHECK_TICKS = 10;
    private final AnimusRuntime runtime;
    private final ScreenManager<P, S> screens;
    private final NpcRuntime<P, ?> living;
    private final Executor files;
    private final Function<String, Optional<UUID>> playerNamed;
    private final Consumer<String> log;
    private final Path data;
    private final QuestStore store;
    private final QuestPlay<P, S> play;
    private final QuestMarkers<P> markers;
    private volatile QuestCatalog catalog = QuestCatalog.EMPTY;
    private volatile Map<String, NpcDefinition> npcSource;
    private volatile Map<String, UUID> npcIds = Map.of();
    private volatile Map<UUID, String> npcNames = Map.of();
    private volatile boolean closed;

    /**
     * @param displays draws the giver markers; {@code watch} makes it send a player's displays (the hologram service owns that)
     * @param servers the server trigger dispatcher quest events go to; it may answer null until behaviors start
     * @param facts the world facts requirement conditions read
     * @param playerNamed finds an online player's UUID by exact name, on any thread
     */
    public QuestService(AnimusRuntime runtime, ScreenManager<P, S> screens, NpcRuntime<P, ?> living, DisplayTransport<P> displays, Consumer<P> watch, Executor files,
                        Supplier<ServerTriggerDispatcher> servers, Supplier<BehaviorFacts> facts, Function<String, Optional<UUID>> playerNamed,
                        Consumer<String> log) throws IOException {
        this.runtime = Objects.requireNonNull(runtime);
        this.screens = Objects.requireNonNull(screens);
        this.living = Objects.requireNonNull(living);
        this.files = Objects.requireNonNull(files);
        this.playerNamed = Objects.requireNonNull(playerNamed);
        this.log = Objects.requireNonNull(log);
        data = runtime.documents().resolve(ShopCatalog.SHOP_FOLDER).getParent();
        store = new QuestStore(data.resolve("state/quests"), 2000, log);
        play = new QuestPlay<>(this, screens.host(), new QuestState(runtime.behaviorState(), facts), store, servers, log);
        markers = new QuestMarkers<>(this, displays, screens.host(), watch);
    }

    ScreenHost<P, S> host() { return screens.host(); }
    ScreenManager<P, S> screens() { return screens; }
    NpcRuntime<P, ?> living() { return living; }
    QuestPlay<P, S> play() { return play; }
    QuestStore store() { return store; }
    public QuestCatalog catalog() { return catalog; }
    public boolean enabled() { return !closed; }
    /** Why some saved records were set aside at start, for the startup banner; empty when all loaded. */
    public List<String> stateProblems() { return store.problems(); }

    /** The NPC's UUID by id, from the current definitions. */
    Optional<UUID> npc(String id) { refreshNpcs(); return Optional.ofNullable(npcIds.get(id)); }
    /** The NPC's id by UUID, from the current definitions. */
    Optional<String> npcId(UUID npc) { refreshNpcs(); return Optional.ofNullable(npcNames.get(npc)); }
    /** The NPC's display name by id, or the id when it has none or is unknown. */
    String npcName(String id) {
        NpcDefinition definition = runtime.npcs().snapshot().get(id);
        return definition == null ? id : definition.name();
    }

    private void refreshNpcs() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot == npcSource) return;
        Map<String, UUID> ids = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (NpcDefinition definition : snapshot.values()) {
            try { ids.put(definition.id(), definition.uuid()); names.put(definition.uuid(), definition.id()); }
            catch (RuntimeException invalid) { /* Definitions are validated on load; one without a UUID binds no quest. */ }
        }
        npcIds = Map.copyOf(ids);
        npcNames = Map.copyOf(names);
        npcSource = snapshot;
    }

    public String status() {
        QuestCatalog current = catalog;
        return "Quests " + (closed ? "stopped" : "enabled") + " | quests=" + current.quests().size() + " | problems=" + current.problems().size()
                + " | players=" + store.loaded() + " | offers=" + play.offers();
    }

    /** Reads the catalog on the file lane; the result completes once the new catalog is live, or at once when the queue refuses the work. */
    public CompletableFuture<Void> reload(Consumer<QuestCatalog> done) {
        CompletableFuture<Void> published = new CompletableFuture<>();
        try {
            files.execute(() -> {
                try {
                    QuestCatalog next;
                    try { next = QuestCatalog.load(data, runtime.behaviorSettings().actions()::commandAllowed); }
                    catch (IOException | RuntimeException failure) {
                        log.accept("Quest catalog could not be read: " + failure.getMessage());
                        next = new QuestCatalog(catalog.quests(), List.of("catalog: " + failure.getMessage()));
                    }
                    next = next.retaining(catalog, data, runtime.behaviorSettings().actions()::commandAllowed);
                    catalog = next;
                    next.problems().stream().limit(40).forEach((String problem) -> log.accept("Quest refused: " + problem));
                    done.accept(next);
                } finally { published.complete(null); }
            });
        } catch (RejectedExecutionException full) { log.accept("Quest catalog reload refused: the file queue is full"); published.complete(null); }
        return published;
    }

    /** A validated NPC click on the player's owner. True when a quest used it: an objective, a turn-in or an offer. */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        if (closed || attack) return false;
        if (sneaking && host().permitted(player, "animus.admin")) return false;
        Optional<String> id = npcId(npc);
        return id.isPresent() && play.clicked(player, npc, id.get());
    }

    /** On the player's owner every {@link #CHECK_TICKS} ticks: reach and collect objectives, stage changes behaviors made, offers that ran out and markers. */
    public void tick(P player) {
        if (closed) return;
        play.check(player);
        markers.tick(player);
    }

    /** A player killed an entity of {@code entity}, or the NPC {@code npc}; any thread. */
    public void killed(UUID player, String entity, Optional<UUID> npc) {
        if (closed) return;
        Optional<String> npcId = npc.flatMap(this::npcId);
        living.players().deliver(player, (P online) -> play.killed(online, entity, npcId));
    }

    /** On the player's owner as they join: reads their record. */
    public void joined(P player) { if (!closed) store.record(host().id(player)); }
    public void quit(P player) { UUID id = host().id(player); play.forget(id); markers.quit(player); store.release(id); }
    public void worldChanged(P player) { markers.reset(player); }

    /** Opens the journal on the player's owner. */
    public boolean journal(P player) { return !closed && screens.open(player, Optional.empty(), (ScreenManager<P, S>.Viewer viewer) -> Optional.of(new QuestJournal<>(this, viewer))); }

    /** A click on an offer's accept or decline button, on the player's owner. */
    public void choice(P player, String token, String option) { if (!closed) play.choice(player, token, option); }

    @Override public Optional<String> unavailable() { return closed ? Optional.of("quests are stopping") : Optional.empty(); }

    /** The quest by id, or by name in the namespace of {@code namespace} when it has no colon. */
    QuestDocument quest(String reference, Optional<String> namespace) {
        String id = reference.indexOf(':') < 0 && namespace.isPresent() ? namespace.get() + ":" + reference : reference;
        return catalog.quest(id).orElseThrow(() -> new Refused("quest-unknown", "No quest " + id + " is loaded; /animus quest list shows them"));
    }

    private P online(UUID player) {
        return living.players().owned(player).orElseThrow(() -> new Refused("player-offline", "The player is offline or on another region"));
    }

    @Override public long start(UUID player, String quest, boolean force) { return play.begin(online(player), quest(quest, Optional.empty()), force); }
    @Override public long advance(UUID player, String quest, OptionalInt stage) { return play.advance(online(player), quest(quest, Optional.empty()), stage); }
    @Override public long complete(UUID player, String quest, boolean rewards) { return play.finish(online(player), quest(quest, Optional.empty()), rewards); }
    @Override public int objective(UUID player, String quest, String objective, int amount) { return play.custom(online(player), quest(quest, Optional.empty()), objective, amount); }
    @Override public String status(UUID player, String quest) { return play.status(player, quest(quest, Optional.empty())); }

    /** Runs {@code work} on the player's owner; false when that player is not online. */
    public boolean onPlayer(UUID player, Runnable work) { return living.players().deliver(player, (P ignored) -> work.run()); }
    /** An online player's UUID by exact name, on any thread. */
    public Optional<UUID> playerNamed(String name) { return playerNamed.apply(name); }
    /** Opens the journal for an online player; false when they are offline. */
    public boolean journal(UUID player) { return living.players().deliver(player, this::journal); }
    /** An offer button click from the command; false when the player is offline. */
    public boolean choice(UUID player, String token, String option) { return living.players().deliver(player, (P online) -> choice(online, token, option)); }

    /**
     * Forgets one quest, or all the player has, and sets their stages back to 0; any thread. An online player's reset
     * runs on their owner, an offline player's record is read, reset and written.
     */
    public void reset(UUID player, Optional<String> quest) {
        Optional<QuestDocument> only = quest.map((String id) -> quest(id, Optional.empty()));
        if (onPlayer(player, () -> play.reset(player, only))) return;
        play.reset(player, only);
        store.release(player);
    }

    /** Loaded quest ids, for completion. */
    public List<String> questIds() { return catalog.quests().keySet().stream().sorted().toList(); }

    /** The custom objective ids of a quest, for completion. */
    public List<String> customObjectives(String quest) {
        List<String> ids = new java.util.ArrayList<>();
        catalog.quest(quest).ifPresent((QuestDocument found) -> found.stages().forEach((QuestDocument.Stage stage) -> stage.objectives().stream()
                .filter((QuestDocument.Objective objective) -> objective.type() == QuestDocument.Type.CUSTOM).forEach((QuestDocument.Objective objective) -> ids.add(objective.id()))));
        return ids.isEmpty() ? List.of("objective") : List.copyOf(ids);
    }

    /** {@code id | title | stages | givers}, one row per loaded quest. */
    public List<String> rows() {
        return catalog.quests().values().stream().map((QuestDocument quest) -> quest.id() + " | " + art.arcane.animus.core.text.RichText.plain(art.arcane.animus.core.text.RichText.colorize(quest.title()))
                + " | " + quest.stages().size() + " stages | " + (quest.givers().isEmpty() ? "no givers" : String.join(", ", quest.givers()))).toList();
    }

    /** Quest files that failed to load, then saved records set aside at start. */
    public List<String> problemRows() {
        List<String> rows = new java.util.ArrayList<>(catalog.problems());
        store.problems().forEach((String problem) -> rows.add("state: " + problem));
        return rows;
    }

    /** One player's active and finished quests; any thread. */
    public List<String> playerRows(UUID player) {
        QuestRecord record = store.peek(player).orElseGet(QuestRecord::new);
        List<String> rows = new java.util.ArrayList<>();
        record.active().forEach((String id, QuestRecord.Active active) -> {
            Optional<QuestDocument> quest = catalog.quest(id);
            int stages = quest.map((QuestDocument found) -> found.stages().size()).orElse(0);
            StringBuilder counts = new StringBuilder();
            quest.flatMap((QuestDocument found) -> QuestRules.stage(found, active)).ifPresent((QuestDocument.Stage stage) -> {
                for (int index = 0; index < stage.objectives().size(); index++)
                    counts.append(index == 0 ? "" : ", ").append(stage.objectives().get(index).id()).append(' ').append(Math.min(active.count(index), stage.objectives().get(index).amount()))
                            .append('/').append(stage.objectives().get(index).amount());
            });
            rows.add(id + " | " + (active.ready() ? "ready to turn in" : "stage " + (active.stage() + 1) + "/" + stages) + (counts.isEmpty() ? "" : " | " + counts));
        });
        record.finished().forEach((String id, QuestRecord.Finished done) -> rows.add(id + " | finished " + done.count() + "x, last " + java.time.Instant.ofEpochMilli(done.last())));
        return rows;
    }

    /** Saves the records; the caller closes the windows. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        markers.close();
        store.close();
    }
}
