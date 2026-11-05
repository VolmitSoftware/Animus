package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.config.ModuleSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.behavior.BehaviorInvoker;
import art.arcane.animus.core.behavior.HostBehaviorInvoker;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.extension.loader.ModuleDiscovery;
import art.arcane.animus.core.extension.loader.StartupModules;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * One startup batch. Lifecycle callbacks run on its management worker; revocation never waits for them. A hotload of
 * {@code [modules]} applies live through {@link #settings(ModuleSettings)}.
 */
public final class ExtensionRuntime implements AutoCloseable {
    public enum State { NEW, STARTING, READY, DISABLED, FAILED, STOPPING, STOPPED }
    public record Status(State state, String failure, StartupModules.Lifecycle lifecycle,
                         StartupModules.Cleanup cleanup, ModuleHost.InvocationStatus invocations) {}

    private final Path directory;
    private volatile ModuleSettings settings;
    private final Platform platform;
    private final ModuleHost host;
    private final Consumer<String> report;
    private final Consumer<State> initializationObserver;
    private final BehaviorInvoker behaviorInvoker;
    private final BuiltinCapabilities builtins;
    // Private submission only: one startup (or one later first load after a hotload enables modules), one shutdown, then at most one scheduled cleanup poll.
    private final ScheduledThreadPoolExecutor management;
    private volatile StartupModules loader;
    private volatile State state = State.NEW;
    private volatile String failure = "none";
    private boolean started, closing, initializationReported, builtinsInstalled;
    private AutoCloseable settingsListener = () -> {};
    private Runnable behaviorReload = () -> {};
    private int cleanupDelaySeconds = 1;
    private boolean reportedRetention;

    public ExtensionRuntime(Path directory, Platform platform, DefinitionGateway definitions,
                            ModuleSettings settings, Consumer<String> report) {
        this(directory, platform, definitions, settings, report, state -> {}, null);
    }

    /** Observer is an engine-owned quick enqueue hook, never a module callback or a place to do file work. */
    public ExtensionRuntime(Path directory, Platform platform, DefinitionGateway definitions,
                            ModuleSettings settings, Consumer<String> report, Consumer<State> initializationObserver) {
        this(directory, platform, definitions, settings, report, initializationObserver, null);
    }

    /** Built-in capabilities bind at startup on every platform and stay bound when Java modules are disabled. */
    public ExtensionRuntime(Path directory, Platform platform, DefinitionGateway definitions, ModuleSettings settings,
                            Consumer<String> report, Consumer<State> initializationObserver, BuiltinCapabilities builtins) {
        this.builtins = builtins;
        this.directory = Objects.requireNonNull(directory);
        this.platform = Objects.requireNonNull(platform);
        this.settings = Objects.requireNonNull(settings);
        this.report = Objects.requireNonNull(report);
        this.initializationObserver = Objects.requireNonNull(initializationObserver);
        host = new ModuleHost(platform, definitions, settings.maximumInvocations(),
                Duration.ofMillis(settings.invocationTimeoutMillis()), Duration.ofMillis(settings.deliveryBudgetMillis()));
        HostBehaviorInvoker hostInvoker = new HostBehaviorInvoker(host, this::privilegedGranted);
        behaviorInvoker = new BehaviorInvoker() {
            @Override public boolean available(Capabilities.Key key, Capabilities.Kind kind) {
                return capabilityAvailable(key, kind);
            }
            @Override public boolean available(Capabilities.Key key, Capabilities.Kind kind, String behavior) {
                return admitting() && hostInvoker.available(key, kind, behavior);
            }
            @Override public Call<String> action(Invocation invocation) {
                if (!admitting()) return new Call<>(ExtensionRuntime.<String>unavailable(), () -> {}, () -> true);
                return hostInvoker.action(invocation);
            }
            @Override public Call<Boolean> condition(Invocation invocation) {
                if (!admitting()) return new Call<>(ExtensionRuntime.<Boolean>unavailable(), () -> {}, () -> true);
                return hostInvoker.condition(invocation);
            }
        };
        management = new ScheduledThreadPoolExecutor(1, job -> {
            Thread thread = new Thread(job, "Animus-module-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        management.setRemoveOnCancelPolicy(true);
        management.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        management.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public void start() {
        synchronized (this) {
            if (started || closing) throw new IllegalStateException("Module binaries load once; replacement requires server restart");
            started = true;
            if (settings.enabled()) {
                state = State.STARTING;
                management.execute(this::load);
                return;
            }
            state = State.DISABLED;
        }
        installBuiltins();
        initialized(State.DISABLED);
    }

    private void installBuiltins() {
        synchronized (this) {
            if (builtins == null || builtinsInstalled) return;
            builtinsInstalled = true;
        }
        Outcome<ModuleHost.ModuleStatus> installed = host.installBuiltin(builtins, builtins);
        if (installed.status() != Outcome.Status.OK)
            report.accept("Built-in behavior capabilities unavailable: " + installed.diagnostics().stream().map(Diagnostic::code).toList());
    }

    private boolean admitting() { return state == State.READY || state == State.DISABLED && builtins != null; }
    private boolean privilegedGranted(String behavior) { return builtins != null && builtins.commandAllowed(behavior); }

    private void load() {
        try {
            if (isClosing()) return;
            installBuiltins();
            StartupModules next = new StartupModules(host, ModuleDiscovery.discover(directory, platform));
            loader = next;
            if (isClosing()) return; // The already queued shutdown owns even an unconsumed discovery plan.
            List<StartupModules.Status> results = next.start();
            State terminal;
            boolean late;
            synchronized (this) {
                // A hotload may have switched modules off while they loaded; they stay loaded and suspended.
                terminal = settings.enabled() ? State.READY : State.DISABLED;
                host.suspendModules(terminal == State.DISABLED);
                if (!closing) state = terminal;
                late = initializationReported;
            }
            initialized(terminal);
            if (late) toggled();
            results.forEach(result -> report.accept("Module " + result.id() + " [" + result.archive() + "]: "
                    + result.state() + " | " + result.reason()));
        } catch (Exception | LinkageError | AssertionError error) {
            failure = "module-startup: " + error.getClass().getSimpleName();
            synchronized (this) { if (!closing) state = State.FAILED; }
            host.revoke();
            initialized(State.FAILED);
            try { report.accept(failure); }
            finally { close(); } // Complete global startup failure cleanup on the management worker too.
        }
    }

    private void initialized(State terminal) {
        synchronized (this) {
            if (closing || initializationReported || state != terminal) return;
            initializationReported = true;
        }
        try { initializationObserver.accept(terminal); }
        catch (RuntimeException | LinkageError | AssertionError error) {
            failure = "module-initialization-observer: " + error.getClass().getSimpleName();
            try { report.accept(failure); }
            catch (RuntimeException | LinkageError | AssertionError ignored) { /* failure remains in immutable status */ }
        }
    }

    private synchronized boolean isClosing() { return closing; }

    /**
     * Follows hotloads of {@code [modules]} until close, and reloads behaviors after modules switch on or off so their
     * capability checks see the change. Call once, after construction.
     */
    public void follow(AnimusRuntime runtime) {
        AutoCloseable listener = runtime.onSettings((PluginSettings next) -> settings(next.modules()));
        synchronized (this) {
            settingsListener = listener;
            behaviorReload = () -> runtime.catalogs().reload(new CatalogChange(Set.of(DocumentKind.BEHAVIOR), Set.of(), false));
        }
    }

    /**
     * A hotload of {@code [modules]}. Limits apply to the next invocation. {@code enabled = false} suspends discovered
     * modules: no new calls, triggers or interaction notices reach them, work already admitted finishes, and built-in
     * capabilities stay available. {@code enabled = true} resumes them, or loads them the first time when the server
     * started with modules off. Module archives are read once per server start, so a changed jar still needs a restart.
     */
    public void settings(ModuleSettings next) {
        Objects.requireNonNull(next);
        host.limits(next.maximumInvocations(), Duration.ofMillis(next.invocationTimeoutMillis()), Duration.ofMillis(next.deliveryBudgetMillis()));
        boolean load = false, changed = false;
        synchronized (this) {
            boolean was = settings.enabled();
            settings = next;
            if (closing || !started || was == next.enabled()) return;
            if (state == State.READY && !next.enabled()) {
                host.suspendModules(true);
                state = State.DISABLED;
                changed = true;
            } else if (state == State.DISABLED && next.enabled()) {
                if (loader != null) {
                    host.suspendModules(false);
                    state = State.READY;
                    changed = true;
                } else {
                    state = State.STARTING;
                    load = true;
                }
            }
        }
        report.accept(next.enabled() ? "Modules enabled by config.toml" : "Modules disabled by config.toml; built-in capabilities stay available");
        if (load) {
            try { management.execute(this::load); }
            catch (RejectedExecutionException stopping) { synchronized (this) { if (!closing) state = State.DISABLED; } }
        }
        if (changed) toggled();
    }

    private void toggled() {
        Runnable reload;
        synchronized (this) { reload = behaviorReload; }
        try { reload.run(); }
        catch (RuntimeException failure) { report.accept("Behavior reload after a module switch failed: " + failure.getMessage()); }
    }

    public Status status() {
        StartupModules current = loader;
        return new Status(state, failure, current == null ? null : current.lifecycleStatus(),
                current == null ? null : current.cleanupStatus(), host.invocationStatus());
    }
    public List<StartupModules.Status> startupStatuses() {
        StartupModules current = loader;
        return current == null ? List.of() : current.statuses();
    }
    public List<ModuleHost.ModuleStatus> modules() { return host.modules(); }
    public List<Capabilities.Availability> capabilities() { return host.capabilities(); }
    /** Single-key engine observation; descriptor/host admission is checked again when invoking. */
    public boolean capabilityAvailable(Capabilities.Key key, Capabilities.Kind kind) {
        return admitting() && host.capabilityAvailable(key, kind, false, false);
    }
    /** Catalog view of this registry: invalid arguments, an unavailable platform and a missing grant stay distinct. */
    public BehaviorCatalog.CapabilityResolver catalogResolver() {
        return new BehaviorCatalog.CapabilityResolver() {
            @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) {
                if (!admitting()) return new BehaviorCatalog.Capability(false, "modules-not-ready");
                return host.unavailableReason(requirement.key(), requirement.kind())
                        .map(reason -> new BehaviorCatalog.Capability(false, bounded(reason)))
                        .orElseGet(BehaviorCatalog.Capability::present);
            }
            @Override public BehaviorCatalog.Capability authorize(BehaviorCatalog.Requirement requirement, String behavior) {
                if (!host.privileged(requirement.key()) || privilegedGranted(behavior)) return BehaviorCatalog.Capability.present();
                return new BehaviorCatalog.Capability(false, "privileged-capability-not-granted: add this behavior or its namespace to the command allow-list");
            }
            @Override public List<Diagnostic> validate(BehaviorCatalog.Requirement requirement, String argumentsJson) {
                return host.validateArguments(requirement.key(), requirement.kind(), argumentsJson);
            }
            @Override public BehaviorCatalog.Capability module(String id) {
                if (!admitting()) return new BehaviorCatalog.Capability(false, "modules-not-ready");
                if (host.modulesSuspended()) return new BehaviorCatalog.Capability(false, "modules-disabled: set [modules] enabled = true in config.toml");
                for (ModuleHost.ModuleStatus module : host.modules()) {
                    if (!module.manifest().id().equals(id)) continue;
                    return module.state() == ModuleHost.State.ACTIVE ? BehaviorCatalog.Capability.present()
                            : new BehaviorCatalog.Capability(false, bounded("module-" + module.state().name().toLowerCase(java.util.Locale.ROOT) + ": " + module.reason()));
                }
                return new BehaviorCatalog.Capability(false, "module-not-installed");
            }
        };
    }
    private static String bounded(String reason) { return reason.length() > 200 ? reason.substring(0, 200) : reason; }
    /** Connect before start so module startup and the behavior catalog observe dispatch. */
    public void connectTriggers(art.arcane.animus.core.behavior.trigger.TriggerSink sink) { host.connectTriggers(sink); }
    /** Connect the engine-wide registry platform adapters wrap, before start. */
    public void connectHooks(ModuleHooks hooks) { host.connectHooks(hooks); }
    public long droppedInteractionNotices() { return host.droppedNotices(); }
    /**
     * Platform owner bridge for an accepted NPC interaction; quick and nonblocking. Subscribing modules receive it
     * as bounded module work. Ignored until modules are ready.
     */
    public void interacted(UUID npc, UUID viewer, boolean attack, double distance, boolean preview) {
        if (state != State.READY) return;
        host.interacted(new NpcHandle(npc), viewer, attack ? InteractionNotice.Action.ATTACK : InteractionNotice.Action.INTERACT,
                Double.isFinite(distance) && distance >= 0 ? distance : 0, preview);
    }
    /** Internal behavior bridge. Carries the execution's preview flag; a privileged grant comes only from the allow-list. */
    public BehaviorInvoker behaviorInvoker() { return behaviorInvoker; }

    public CompletionStage<Outcome<String>> action(Capabilities.Key key, NpcHandle npc, Optional<UUID> player,
                                                    String argumentsJson) {
        if (!admitting()) return unavailable();
        // Console execution is never implicitly granted by the administrative dispatch command.
        return host.action(key, npc, player, argumentsJson, false, false);
    }
    public CompletionStage<Outcome<Boolean>> condition(Capabilities.Key key, NpcHandle npc, Optional<UUID> player,
                                                       String argumentsJson) {
        if (!admitting()) return unavailable();
        return host.condition(key, npc, player, argumentsJson, false);
    }
    private static <T> CompletionStage<Outcome<T>> unavailable() {
        return CompletableFuture.completedFuture(Outcome.<T>rejected(Outcome.Status.UNAVAILABLE,
                "modules-unavailable", "Modules are not ready")).minimalCompletionStage();
    }

    /** Pure admission revocation happens immediately; trusted disable/resource cleanup runs off the caller. */
    @Override public synchronized void close() {
        if (closing) return;
        closing = true;
        try { settingsListener.close(); }
        catch (Exception ignored) { /* Removing a listener from a list does not fail. */ }
        state = State.STOPPING;
        host.revoke();
        management.execute(this::stop);
    }
    private void stop() {
        try {
            StartupModules current = loader;
            if (current != null) current.close(); else host.close();
            pollCleanup();
        } catch (RuntimeException | LinkageError | AssertionError error) {
            failure = "module-cleanup: " + error.getClass().getSimpleName();
            // Retain the failed owner and do not claim that its archives were released.
            management.shutdown();
            report.accept(failure);
        }
    }
    /** One queued retry, with capped backoff, for as long as tracked trusted work retains archives. */
    private void pollCleanup() {
        StartupModules current = loader;
        boolean released = current == null ? host.outstandingInvocations() == 0 : current.drainCleanup().archivesReleased();
        if (released) {
            synchronized (this) { state = State.STOPPED; notifyAll(); }
            management.shutdown();
        } else {
            if (!reportedRetention) {
                reportedRetention = true;
                report.accept("Module cleanup is waiting for " + host.outstandingInvocations() + " tracked invocations/results; archives remain retained");
            }
            management.schedule(() -> {
                try { pollCleanup(); }
                catch (RuntimeException | LinkageError | AssertionError error) {
                    failure = "module-cleanup-poll: " + error.getClass().getSimpleName();
                    management.shutdown();
                    report.accept(failure);
                }
            }, cleanupDelaySeconds, TimeUnit.SECONDS);
            cleanupDelaySeconds = Math.min(30, cleanupDelaySeconds * 2);
        }
    }
    /** Standalone verification only; shutdown on a platform owner must remain nonblocking. */
    public synchronized boolean awaitStopped(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Shutdown wait must be 0..60 seconds");
        long budget = timeout.toNanos(), startedAt = System.nanoTime(), remaining = budget;
        while (state != State.STOPPED) {
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = budget - (System.nanoTime() - startedAt);
        }
        return true;
    }
}
