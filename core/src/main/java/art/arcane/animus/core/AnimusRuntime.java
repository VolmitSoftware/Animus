package art.arcane.animus.core;

import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.config.NpcRuntimeSettings;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.config.BehaviorSettings;
import art.arcane.animus.core.config.BuildSettings;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.config.FileWorkSettings;
import art.arcane.animus.core.migration.CitizensDropIn;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns durable state and the one file-mutation lane used by platform commands and extensions. */
public final class AnimusRuntime implements AutoCloseable {
    private static final Pattern TRANSACTION_ID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    /** How long a stop waits for queued writes, the final flushes and the storage lock release before it gives up and logs. */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);
    /** How often rollback receipts rotate and unnamed generated objects are removed while the server runs. */
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofHours(6);
    /** A generated object younger than this may belong to a write still in progress, so maintenance keeps it. */
    private static final Duration OBJECT_GRACE = Duration.ofDays(1);
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
    private java.util.concurrent.ScheduledExecutorService maintenance;
    private final art.arcane.animus.core.extension.ModuleHooks moduleHooks = new art.arcane.animus.core.extension.ModuleHooks();
    private volatile CatalogReloads catalogs = CatalogReloads.NONE;
    private volatile art.arcane.animus.core.text.Placeholders placeholders = art.arcane.animus.core.text.Placeholders.NONE;
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
            System.Logger log = System.getLogger("Animus");
            PluginSettings loaded = PluginSettings.load(opened, (String warning) -> log.log(System.Logger.Level.WARNING, warning));
            migrateSchemas(opened);
            sweepPreviews(opened);
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
            maintenance = scheduleMaintenance(lane);
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
            log.log(System.Logger.Level.INFO, "Migrated " + report.migrated().size() + " documents to the current schema; rollback " + String.join(", ", report.transactions()));
        for (String problem : report.problems()) log.log(System.Logger.Level.WARNING, "Schema migration: " + problem);
    }

    /** Drops old rollback receipts, after migration so the migration's own receipt counts as the newest. An import awaiting review keeps its receipts. */
    private static void rotateBackups(DocumentStore store, FileWorkSettings files) {
        System.Logger log = System.getLogger("Animus");
        try {
            Set<String> pinned = new HashSet<>();
            String state = store.read(CitizensDropIn.STATE);
            if (state != null) for (Matcher found = TRANSACTION_ID.matcher(state); found.find(); ) pinned.add(found.group());
            DocumentStore.Rotation rotation = store.rotateBackups(files.backupsKept(), Duration.ofDays(files.backupDays()), pinned, Instant.now());
            if (rotation.receipts() > 0)
                log.log(System.Logger.Level.INFO, "Removed " + rotation.receipts() + " rollback receipts older than " + files.backupDays() + " days beyond the newest "
                        + files.backupsKept() + ", and " + rotation.objects() + " stored copies only they used");
        } catch (IOException | RuntimeException failure) {
            log.log(System.Logger.Level.WARNING, "Backup rotation skipped: " + failure.getMessage());
        }
    }

    /** Initiate shutdown without blocking. Queued writes drain and a running durable operation is not interrupted. */
    public synchronized void closeAsync() {
        if (settings == null || closing) return;
        closing = true;
        AnimusLog.shared().flush();
        maintenance.shutdownNow();
        definitions.close();
        fileWork.close();
    }

    /**
     * Initiates shutdown, then waits up to {@code timeout} for queued writes, the final behavior state flush and the
     * storage lock release. False when they are still finishing; never call this from the file worker.
     */
    public boolean closeAndWait(Duration timeout) throws InterruptedException {
        MutationLane lane;
        synchronized (this) { closeAsync(); lane = fileWork; }
        return lane == null || lane.awaitCleanup(timeout);
    }

    /** For standalone owners and tests: {@link #closeAndWait} for {@link #SHUTDOWN_WAIT}, failing when cleanup did not finish or failed. */
    @Override public void close() {
        try {
            if (!closeAndWait(SHUTDOWN_WAIT))
                throw new IllegalStateException("File worker is still finishing; storage remains owned until cleanup completes");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for file cleanup; shutdown remains pending", failure);
        }
        MutationLane lane;
        synchronized (this) { lane = fileWork; }
        if (lane != null && lane.status().cleanupState() != MutationLane.CleanupState.SUCCEEDED)
            throw new IllegalStateException("File cleanup failed", cleanupFailure);
    }

    /**
     * One daemon timer that only queues {@link #maintain} on the file lane, so maintenance never races a write. The first
     * run is right after start: a server upgraded from a build without rotation may have thousands of old receipts, and
     * removing them on the starting thread would stall the server.
     */
    private java.util.concurrent.ScheduledExecutorService scheduleMaintenance(MutationLane lane) {
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor((Runnable job) -> {
            Thread thread = new Thread(job, "Animus-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        Executor files = lane.executor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
            if (event == MutationLane.EngineTaskEvent.FAILED) System.getLogger("Animus").log(System.Logger.Level.WARNING, "File maintenance failed: " + failure);
        });
        long every = MAINTENANCE_INTERVAL.toMinutes();
        timer.scheduleAtFixedRate(() -> {
            try { files.execute(this::maintain); }
            catch (java.util.concurrent.RejectedExecutionException busy) { /* Busy or stopping: the next interval tries again. */ }
        }, 0, every, java.util.concurrent.TimeUnit.MINUTES);
        return timer;
    }

    /** Rotates rollback receipts and removes generated objects nothing names. Runs on the file lane. */
    void maintain() {
        DocumentStore store;
        FileWorkSettings files;
        synchronized (this) {
            if (documents == null || settings == null) return;
            store = documents;
            files = settings.files();
        }
        rotateBackups(store, files);
        collectObjects(store);
    }

    /** Import staging folders left by a stop mid-import; nothing imports yet at startup. */
    private static void sweepPreviews(DocumentStore store) {
        System.Logger log = System.getLogger("Animus");
        try {
            int removed = store.removeStalePreviews();
            if (removed > 0) log.log(System.Logger.Level.INFO, "Removed " + removed + " unfinished import previews under imports/");
        } catch (IOException | RuntimeException failure) {
            log.log(System.Logger.Level.WARNING, "Import preview cleanup skipped: " + failure.getMessage());
        }
    }

    private static void collectObjects(DocumentStore store) {
        System.Logger log = System.getLogger("Animus");
        try {
            int removed = store.collectObjects(OBJECT_GRACE, Instant.now());
            if (removed > 0) log.log(System.Logger.Level.INFO, "Removed " + removed + " stored copies under state/objects/ that nothing names any more");
        } catch (IOException | RuntimeException failure) {
            log.log(System.Logger.Level.WARNING, "Stored copy cleanup skipped: " + failure.getMessage());
        }
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
        finally { documents = null; npcs = null; definitions = null; settings = null; behaviorState = null; rigs = null; maintenance = null; }
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
        return BuildIdentity.NAME + " " + BuildIdentity.VERSION + " | " + environment.server()
                + " | Minecraft " + environment.minecraftVersion();
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
    public synchronized boolean splashScreen() { return settings().splashScreen(); }
    /** {@code metrics} in config.toml: report anonymous usage to bStats on Bukkit platforms. */
    public synchronized boolean metrics() { return settings().metrics(); }
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
    public synchronized art.arcane.animus.core.config.PlaceholderSettings placeholderSettings() { return settings().placeholders(); }

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
    /** PlaceholderAPI on Bukkit when it is installed; none elsewhere. The platform sets it during startup. */
    public art.arcane.animus.core.text.Placeholders placeholders() { return placeholders; }
    public void placeholders(art.arcane.animus.core.text.Placeholders filler) { placeholders = Objects.requireNonNull(filler, "filler"); }
    private PluginSettings settings() {
        if (!isRunning()) throw new IllegalStateException("Animus is not running");
        return settings;
    }
    public boolean isRunning() { return settings != null && !closing; }
}
