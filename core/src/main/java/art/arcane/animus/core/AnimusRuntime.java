package art.arcane.animus.core;

import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.config.BehaviorSettings;
import art.arcane.animus.core.config.BuildSettings;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.extension.DefinitionGateway;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.extension.RepositoryDefinitions;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.SchemaMigrations;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.rig.RigCheckpoints;
import art.arcane.animus.core.rig.RigLibrary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns durable state and the one file-mutation lane used by platform commands and extensions. */
public final class AnimusRuntime implements AutoCloseable {
    private final RuntimeEnvironment environment;
    private final Consumer<Throwable> cleanupFailureObserver;
    private volatile PluginSettings settings;
    private volatile boolean closing;
    private volatile Throwable cleanupFailure;
    private DocumentStore documents;
    private NpcRepository npcs;
    private MutationLane fileWork;
    private RepositoryDefinitions definitions;
    private BehaviorStateStore behaviorState;
    private volatile art.arcane.animus.core.migration.CitizensDropIn.Host importHost = art.arcane.animus.core.migration.CitizensDropIn.Host.NONE;
    private RigLibrary rigs;
    private final art.arcane.animus.core.extension.ModuleHooks moduleHooks = new art.arcane.animus.core.extension.ModuleHooks();
    private volatile CatalogReloads catalogs = CatalogReloads.NONE;
    private final java.util.List<Consumer<PluginSettings>> settingsListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AnimusRuntime(RuntimeEnvironment environment) {
        this(environment, failure -> System.getLogger("Animus").log(System.Logger.Level.ERROR,
                "File cleanup failed: " + failure.getClass().getSimpleName()));
    }

    public AnimusRuntime(RuntimeEnvironment environment, Consumer<Throwable> cleanupFailureObserver) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.cleanupFailureObserver = Objects.requireNonNull(cleanupFailureObserver, "cleanupFailureObserver");
    }

    public synchronized void start() throws IOException {
        if (settings != null) throw new IllegalStateException(BuildIdentity.NAME + " is already running or closing");
        // Debug mode lasts until the server restarts; a new start begins quiet.
        AnimusLog.shared().debug(false);
        if (fileWork != null) {
            MutationLane.Status previous = fileWork.status();
            if (previous.cleanupState() != MutationLane.CleanupState.SUCCEEDED || !previous.quiescent())
                throw new IllegalStateException("Previous file worker cleanup or result callbacks are still pending: " + previous.cleanupState());
        }
        if (!BuildIdentity.MINECRAFT_VERSIONS.contains(environment.minecraftVersion()))
            throw new IOException("Unsupported Minecraft version: " + environment.minecraftVersion());

        DocumentStore opened = new DocumentStore(environment.dataDirectory());
        try {
            PluginSettings loaded = PluginSettings.load(opened);
            migrateSchemas(opened);
            NpcRepository repository = new NpcRepository(opened);
            repository.loadForStartup();
            BehaviorStateStore state = BehaviorStateStore.open(opened, loaded.behaviors().state());
            MutationLane lane = new MutationLane(loaded.files().maximumOperations(),
                    Duration.ofMillis(loaded.files().deliveryBudgetMillis()), this::closeStorage,
                    failure -> { cleanupFailure = failure; cleanupFailureObserver.accept(failure); });
            RepositoryDefinitions facade = new RepositoryDefinitions(repository, lane);
            state.attach(lane.executor((task, event, failure) -> state.flushAbandoned()));
            RigLibrary rigLibrary = new RigLibrary(environment.dataDirectory(), new RigCheckpoints(opened));
            rigLibrary.reload();
            documents = opened;
            npcs = repository;
            fileWork = lane;
            definitions = facade;
            behaviorState = state;
            rigs = rigLibrary;
            cleanupFailure = null;
            closing = false;
            settings = loaded;
        } catch (IOException | RuntimeException e) { opened.close(); throw e; }
    }

    /** Upgrades stale definitions, behaviors and shops before anything reads them; refusals are logged and the files kept. */
    private static void migrateSchemas(DocumentStore store) throws IOException {
        SchemaMigrations.Report report = SchemaMigrations.standard().apply(store);
        System.Logger log = System.getLogger("Animus");
        if (!report.migrated().isEmpty())
            log.log(System.Logger.Level.INFO, "Migrated " + report.migrated().size() + " documents to the current schema; rollback " + report.transaction());
        for (String problem : report.problems()) log.log(System.Logger.Level.WARNING, "Schema migration: " + problem);
    }

    /** Initiate shutdown without blocking a platform owner. A running durable operation is not interrupted. */
    public synchronized void closeAsync() {
        if (settings == null || closing) return;
        closing = true;
        definitions.close();
        fileWork.close();
    }

    /** For standalone owners/tests. Platform shutdown uses closeAsync; never call this from the file worker. */
    @Override public void close() {
        MutationLane lane;
        synchronized (this) { closeAsync(); lane = fileWork; }
        if (lane == null) return;
        try {
            if (!lane.awaitCleanup(Duration.ofSeconds(5)))
                throw new IllegalStateException("File worker is still finishing; storage remains owned until cleanup completes");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for file cleanup; shutdown remains pending", failure);
        }
        if (lane.status().cleanupState() != MutationLane.CleanupState.SUCCEEDED)
            throw new IllegalStateException("File cleanup failed", cleanupFailure);
    }

    /** Only the lane invokes this hook, after mutation and internal cancellation work have ended. */
    private synchronized void closeStorage() {
        DocumentStore owned = documents;
        BehaviorStateStore state = behaviorState;
        try {
            try { if (state != null) state.close(); }
            finally { if (owned != null) owned.close(); }
        }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        finally { documents = null; npcs = null; definitions = null; settings = null; behaviorState = null; rigs = null; }
    }

    public synchronized Executor fileExecutor(MutationLane.EngineTaskObserver observer) {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return fileWork.executor(observer);
    }

    public synchronized DefinitionGateway definitionGateway() {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return definitions;
    }

    /** Read-only diagnostics remain available while cleanup or result delivery is pending. */
    public synchronized MutationLane.Status fileStatus() {
        if (fileWork == null) throw new IllegalStateException("Animus has no file worker");
        return fileWork.status();
    }

    /** Standalone restart barrier; never wait on a platform owner or a file/result callback. */
    public boolean awaitFileQuiescence(Duration timeout) throws InterruptedException {
        MutationLane lane;
        synchronized (this) { lane = fileWork; }
        return lane == null || lane.awaitQuiescence(timeout);
    }

    public String status() {
        PluginSettings active = settings;
        if (active == null) throw new IllegalStateException(BuildIdentity.NAME + " is not running");
        return BuildIdentity.NAME + " " + BuildIdentity.VERSION + " | " + environment.platform()
                + " | Minecraft " + environment.minecraftVersion() + " | " + active.statusMessage();
    }

    /** Internal access stays available to the already-running operation during asynchronous close. */
    public synchronized NpcRepository npcs() {
        if (npcs == null) throw new IllegalStateException("Animus is not running");
        return npcs;
    }

    public synchronized DocumentStore documents() {
        if (documents == null) throw new IllegalStateException("Animus is not running");
        return documents;
    }

    public synchronized BehaviorStateStore behaviorState() {
        if (behaviorState == null) throw new IllegalStateException("Animus is not running");
        return behaviorState;
    }

    /** Rigs and animations; reload on the file lane. */
    public synchronized RigLibrary rigs() {
        if (rigs == null) throw new IllegalStateException("Animus is not running");
        return rigs;
    }

    public synchronized boolean packetProofEnabled() { return settings().packetProofEnabled(); }
    public synchronized NpcRuntimeSettings npcSettings() { return settings().npcs(); }
    public synchronized ModuleSettings moduleSettings() { return settings().modules(); }
    public synchronized art.arcane.animus.core.config.FileWorkSettings fileSettings() { return settings().files(); }
    public synchronized BehaviorSettings behaviorSettings() { return settings().behaviors(); }
    public synchronized CombatBudgets combatBudgets() { return settings().combat(); }
    public synchronized art.arcane.animus.core.config.ShopSettings shopSettings() { return settings().shops(); }
    public synchronized BuildSettings buildSettings() { return settings().builds(); }
    public synchronized art.arcane.animus.core.config.RigSettings rigSettings() { return settings().rigs(); }
    public synchronized art.arcane.animus.core.config.NavigationSettings navigationSettings() { return settings().navigation(); }
    public synchronized art.arcane.animus.core.config.EditorSettings editorSettings() { return settings().editor(); }
    public synchronized art.arcane.animus.core.config.EditorRelaySettings editorRelaySettings() { return settings().relay(); }
    public synchronized art.arcane.animus.core.config.HotloadSettings hotloadSettings() { return settings().hotload(); }
    public synchronized art.arcane.animus.core.config.RecordingSettings recordingSettings() { return settings().recordings(); }

    /**
     * Replaces the running settings after a hotload of {@code config.toml}: the file lane takes its new limits here, then
     * every listener hears the change and moves it onto its own owner thread. The few keys that still need a restart are
     * listed in {@link art.arcane.animus.core.hotload.SettingsLiveness}.
     */
    public void applySettings(PluginSettings next) {
        Objects.requireNonNull(next, "next");
        synchronized (this) {
            if (!isRunning()) throw new IllegalStateException("Animus is not running");
            fileWork.limits(next.files().maximumOperations(), Duration.ofMillis(next.files().deliveryBudgetMillis()));
            settings = next;
        }
        for (Consumer<PluginSettings> listener : settingsListeners) listener.accept(next);
    }

    /** Hears {@link #applySettings}, on the thread that applied them, until the returned handle is closed. */
    public AutoCloseable onSettings(Consumer<PluginSettings> listener) {
        Consumer<PluginSettings> added = Objects.requireNonNull(listener, "listener");
        settingsListeners.add(added);
        return () -> settingsListeners.remove(added);
    }
    public RuntimeEnvironment environment() { return environment; }
    /** Module protection, economy and import converter registrations; platforms wrap their adapters with it. */
    public art.arcane.animus.core.extension.ModuleHooks moduleHooks() { return moduleHooks; }
    /** The platform's plugins folder and Citizens check for the drop-in Citizens migration; mod servers keep the default. */
    public art.arcane.animus.core.migration.CitizensDropIn.Host importHost() { return importHost; }
    public void importHost(art.arcane.animus.core.migration.CitizensDropIn.Host host) { importHost = Objects.requireNonNull(host, "host"); }
    /** What reloads shops, GUIs, holograms, rigs and behaviors after a committed change; the platform sets it once its services exist. */
    public CatalogReloads catalogs() { return catalogs; }
    public void catalogs(CatalogReloads reloads) { catalogs = Objects.requireNonNull(reloads, "reloads"); }
    private PluginSettings settings() {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return settings;
    }
    public boolean isRunning() { return settings != null && !closing; }
}
