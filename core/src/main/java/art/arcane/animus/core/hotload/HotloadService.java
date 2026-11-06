package art.arcane.animus.core.hotload;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.building.schematic.NbtReader;
import art.arcane.animus.core.building.schematic.SchematicReader;
import art.arcane.animus.core.config.BuildSettings;
import art.arcane.animus.core.config.HotloadSettings;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.storage.WriteNotices;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Applies hand edits to the data folder live, as Adapt does: every 500 ms it checks the watched files, waits until a
 * change reads the same twice, checks it with the editor's validators, applies it through the same catalog reloads an
 * editor apply uses, and tells online operators each changed key with a note block ding. An invalid edit keeps the last
 * valid version and operators see the exact field error. Animus's own writes are never reported.
 */
public final class HotloadService implements AutoCloseable {
    public static final long POLL_MILLIS = 500;
    private static final long APPLY_TIMEOUT_SECONDS = 60, ACTIVATION_TIMEOUT_SECONDS = 10;
    private static final Set<DocumentKind> NPC_GROUP = EnumSet.of(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE, DocumentKind.RECORDING);
    private static final Set<DocumentKind> SCREENS = EnumSet.of(DocumentKind.SHOP, DocumentKind.INVENTORY, DocumentKind.MENU,
            DocumentKind.HOLOGRAM, DocumentKind.TEXT_ANIMATION, DocumentKind.PRESETS);

    /**
     * @param files the runtime's file lane, where catalog reloads run
     * @param living the NPC runtime, for the activation check an NPC edit gets in the editor too
     * @param console runs work on the server's console owner (the main thread, or Folia's global region); rejects when it cannot
     * @param refreshScreens re-renders open shop and GUI windows, on their owners
     */
    public record Parts(AnimusRuntime runtime, Executor files, Executor console, NpcRuntime<?, ?> living, OperatorChannel operators, Runnable refreshScreens, Consumer<String> log) {
        public Parts {
            Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(console); Objects.requireNonNull(living);
            Objects.requireNonNull(operators); Objects.requireNonNull(refreshScreens); Objects.requireNonNull(log);
        }
    }

    private record Item(HotloadEngine.Snapshot snapshot, HotloadTargets.Target target, HotloadValidation.Verdict verdict) {
        Item refused(String error) { return new Item(snapshot, target, HotloadValidation.Verdict.refused(error)); }
    }

    private final Parts parts;
    private final HotloadFiles source;
    private final HotloadEngine engine;
    private ScheduledExecutorService worker;
    private AutoCloseable notices;
    private volatile boolean closed;
    private boolean primed, watchingAll, warnedTruncated;

    public HotloadService(Parts parts) {
        this.parts = Objects.requireNonNull(parts, "parts");
        source = new HotloadFiles(parts.runtime().environment().dataDirectory(), this::settings, this::schematicLimits);
        engine = new HotloadEngine(source);
    }

    /** Starts hearing Animus's own writes and polling on a daemon thread. */
    public synchronized void start() {
        if (worker != null || closed) return;
        listen();
        worker = Executors.newSingleThreadScheduledExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "Animus-Hotload");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> {
            try { cycle(); }
            catch (RuntimeException | LinkageError failure) { parts.log().accept("Hotload poll failed: " + failure); }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void close() {
        closed = true;
        if (notices != null) {
            try { notices.close(); } catch (Exception ignored) { /* Removing a listener cannot fail. */ }
            notices = null;
        }
        if (worker != null) { worker.shutdownNow(); worker = null; }
        engine.clear();
    }

    /** Registers for Animus's own writes, which are never reported as hand edits. */
    void listen() {
        notices = WriteNotices.listen(new WriteNotices.Listener() {
            @Override public void written(Path path, String text) { source.relative(path).ifPresent((String relative) -> engine.noteSelfWrite(relative, text)); }
            @Override public void touched(Path path) { source.relative(path).ifPresent(engine::noteTouched); }
        });
    }

    /** One poll: the first reads the starting point, later ones apply stable changes. */
    void cycle() {
        if (closed || !parts.runtime().isRunning()) return;
        HotloadSettings settings = settings();
        if (!primed || watchingAll != settings.enabled()) {
            engine.clear();
            watchingAll = settings.enabled();
            try { engine.prime(); }
            catch (IOException failure) { parts.log().accept("Hotload could not read the data folder: " + failure.getMessage()); return; }
            primed = true;
            return;
        }
        List<HotloadEngine.Snapshot> snapshots;
        try { snapshots = engine.poll(settings.readsPerPoll()); }
        catch (IOException failure) { parts.log().accept("Hotload scan failed: " + failure.getMessage()); return; }
        if (source.truncated() && !warnedTruncated) {
            warnedTruncated = true;
            parts.log().accept("Hotload watches at most " + settings.maximumFiles() + " files ([hotload] maximumFiles); edits to the rest need /animus reload");
        }
        if (!snapshots.isEmpty()) apply(snapshots);
    }

    private void apply(List<HotloadEngine.Snapshot> snapshots) {
        List<Item> items = new ArrayList<>();
        for (HotloadEngine.Snapshot snapshot : snapshots) {
            Optional<HotloadTargets.Target> target = HotloadTargets.of(snapshot.path());
            if (target.isEmpty()) { engine.settle(snapshot, HotloadEngine.Outcome.APPLIED); continue; }
            if (target.get().area() == HotloadTargets.Area.CONFIG && snapshot.deleted()) {
                engine.settle(snapshot, HotloadEngine.Outcome.APPLIED);
                parts.log().accept("config.toml was removed; the running settings stay until a restart writes the defaults again");
                continue;
            }
            items.add(new Item(snapshot, target.get(), HotloadValidation.check(target.get(), snapshot, validation())));
        }
        items.sort(Comparator.comparingInt((Item item) -> priority(item.target())).thenComparing((Item item) -> item.target().path()));
        List<Item> checked = new ArrayList<>(items.size());
        for (Item item : items) {
            if (!item.verdict().valid() || item.verdict().npc().isEmpty()) { checked.add(item); continue; }
            Optional<List<String>> problems = activationProblems(item.verdict().npc().get());
            if (problems.isEmpty()) { engine.settle(item.snapshot(), HotloadEngine.Outcome.RETRY); continue; }
            checked.add(problems.get().isEmpty() ? item : item.refused(problems.get().getFirst()));
        }
        List<Item> valid = checked.stream().filter((Item item) -> item.verdict().valid() && engine.current(item.snapshot())).toList();
        List<String> reported = new ArrayList<>();
        if (!valid.isEmpty()) {
            Optional<List<String>> problems = applyOnFileLane(valid);
            if (problems.isEmpty()) {
                for (Item item : valid) engine.settle(item.snapshot(), HotloadEngine.Outcome.RETRY);
                checked.removeAll(valid);
            } else reported = problems.get();
        }
        boolean screens = false;
        for (Item item : checked) {
            Item settled = reloadRefusal(item, reported);
            String file = item.target().path();
            if (!settled.verdict().valid()) {
                engine.settle(item.snapshot(), HotloadEngine.Outcome.REJECTED);
                String error = settled.verdict().error().orElse("invalid");
                parts.log().accept("Hotload refused " + file + ": " + error);
                String kept = item.target().area() == HotloadTargets.Area.SCHEMATIC ? "builds cannot use it until it is fixed" : HotloadMessages.KEPT;
                parts.operators().notifyOperators(List.of(HotloadMessages.refused(file, error, kept)), settings().sound());
                continue;
            }
            Optional<HotloadEngine.Delta> delta = engine.settle(item.snapshot(), HotloadEngine.Outcome.APPLIED);
            if (delta.isEmpty()) continue;
            screens |= item.target().area() == HotloadTargets.Area.CONFIG || item.verdict().kind().map(SCREENS::contains).orElse(false);
            boolean liveSwitch = parts.living().switchesLive();
            Predicate<String> restart = item.target().area() == HotloadTargets.Area.CONFIG ? (String key) -> SettingsLiveness.restartRequired(key, liveSwitch) : (String key) -> false;
            List<HotloadDiff.Change> changes = HotloadDiff.diff(file, delta.get().before(), delta.get().after());
            if (changes.isEmpty()) continue;
            parts.log().accept("Hotloaded " + file + " (" + changes.size() + (changes.size() == 1 ? " change)" : " changes)"));
            parts.operators().notifyOperators(HotloadMessages.applied(file, changes, restart), settings().sound());
        }
        if (screens) parts.refreshScreens().run();
    }

    /**
     * The settings and catalog reloads on the file lane, as an editor apply runs them, finished once every reloaded catalog is
     * live so a run right after the notice sees the new version. Empty when the lane is busy or slow, to retry.
     */
    private Optional<List<String>> applyOnFileLane(List<Item> valid) {
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        Set<String> removed = new TreeSet<>();
        Optional<art.arcane.animus.core.config.PluginSettings> settings = Optional.empty();
        for (Item item : valid) {
            item.verdict().kind().ifPresent(kinds::add);
            item.verdict().removedBehavior().ifPresent(removed::add);
            if (item.verdict().settings().isPresent()) settings = item.verdict().settings();
        }
        // Settings can change the command allow-list that shops, chest GUIs, menus and holograms are checked against.
        if (settings.isPresent()) { kinds.add(DocumentKind.SHOP); kinds.add(DocumentKind.MENU); }
        Optional<art.arcane.animus.core.config.PluginSettings> next = settings;
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        try {
            parts.files().execute(() -> {
                try {
                    List<String> problems = new ArrayList<>();
                    next.ifPresent(parts.runtime()::applySettings);
                    CatalogChange change = new CatalogChange(kinds, removed, false);
                    // Behavior, shop and hologram reloads queue behind this task; the notice waits for them to go live.
                    CompletableFuture<Void> live = change.empty() ? CompletableFuture.completedFuture(null) : parts.runtime().catalogs().reloaded(change, problems::add);
                    live.whenComplete((Void ignored, Throwable failure) -> {
                        if (failure != null) done.completeExceptionally(failure);
                        else done.complete(List.copyOf(problems));
                    });
                } catch (RuntimeException | LinkageError failure) { done.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException busy) { return Optional.empty(); }
        try { return Optional.of(done.get(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
        catch (TimeoutException slow) { return Optional.empty(); }
        catch (ExecutionException failure) {
            parts.log().accept("Hotload apply failed: " + failure.getCause());
            return Optional.of(List.of(String.valueOf(failure.getCause().getMessage())));
        }
    }

    /**
     * An NPC reload applies each definition file on its own, so only a problem that names this file refuses it; another
     * file's error never refuses a valid edit of the same batch. A rig refusal names its file too.
     */
    private static Item reloadRefusal(Item item, List<String> problems) {
        if (!item.verdict().valid() || problems.isEmpty() || item.verdict().kind().isEmpty()) return item;
        DocumentKind kind = item.verdict().kind().get();
        String path = item.target().path();
        Optional<String> own = problems.stream().filter((String problem) -> problem.startsWith(path) || problem.contains(" " + path)).findFirst()
                .map((String problem) -> problem.startsWith(path + ": ") ? problem.substring(path.length() + 2) : problem);
        if (own.isPresent() && (NPC_GROUP.contains(kind) || kind == DocumentKind.RIG || kind == DocumentKind.ANIMATION)) return item.refused(own.get());
        return item;
    }

    /** The editor's activation check on the console owner. Empty when the server refused or did not answer in time. */
    private Optional<List<String>> activationProblems(NpcDefinition definition) {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        try {
            parts.console().execute(() -> {
                try {
                    boolean accepted = parts.living().players().console(() -> {
                        try { result.complete(parts.living().validateActivation(definition)); }
                        catch (RuntimeException failure) { result.complete(List.of(String.valueOf(failure.getMessage()))); }
                    });
                    if (!accepted) result.cancel(false);
                } catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException refused) { return Optional.empty(); }
        try { return Optional.of(result.get(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
        catch (TimeoutException | ExecutionException | java.util.concurrent.CancellationException slow) { return Optional.empty(); }
    }

    /** Settings first, then what NPCs depend on (skins, routes and recordings, rigs, behaviors), then NPCs, GUIs and schematics. */
    private static int priority(HotloadTargets.Target target) {
        if (target.area() == HotloadTargets.Area.CONFIG) return 0;
        if (target.area() == HotloadTargets.Area.SCHEMATIC) return 7;
        return switch (target.kind().orElseThrow()) {
            case SKIN -> 1;
            case ROUTE, RECORDING -> 2;
            case RIG, ANIMATION -> 3;
            case BEHAVIOR, PACK -> 4;
            case NPC -> 5;
            default -> 6;
        };
    }

    /** The running command allow-list and data folder, read once per batch. */
    private HotloadValidation.Context validation() {
        java.util.function.Predicate<String> allowed;
        try {
            art.arcane.animus.core.config.BuiltinActionSettings actions = parts.runtime().behaviorSettings().actions();
            allowed = actions::commandAllowed;
        } catch (IllegalStateException stopped) { allowed = (String id) -> false; }
        int recordingBytes;
        try { recordingBytes = parts.runtime().recordingSettings().maximumFileBytes(); }
        catch (IllegalStateException stopped) { recordingBytes = art.arcane.animus.core.config.RecordingSettings.DEFAULT.maximumFileBytes(); }
        return new HotloadValidation.Context(source.root(), allowed, recordingBytes);
    }

    private HotloadSettings settings() {
        try { return parts.runtime().hotloadSettings(); }
        catch (IllegalStateException stopped) { return HotloadSettings.DEFAULT; }
    }

    private SchematicReader.Limits schematicLimits() {
        try {
            BuildSettings builds = parts.runtime().buildSettings();
            return new SchematicReader.Limits(NbtReader.Limits.DEFAULT, builds.maximumVolume(), builds.maximumPalette(), builds.maximumSide());
        } catch (IllegalStateException stopped) { return SchematicReader.Limits.DEFAULT; }
    }
}
