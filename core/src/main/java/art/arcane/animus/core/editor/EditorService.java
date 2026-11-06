package art.arcane.animus.core.editor;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.relay.EditorRelayClient;
import art.arcane.animus.core.editor.transport.EditorRequestHandler;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The editor API on one server: identity, tokens, audit, change records, previews, event pacing and the optional embedded
 * listener. Platforms create one after the runtime starts, register {@link #commands} as {@code /animus editor}, and close
 * it before the runtime. The relay client passes its frames to {@link #handler}. A hotload of {@code [editor]} applies live:
 * the editor switch opens or closes the whole editor, a relay key restarts only the relay client, a bind key restarts only the
 * listener, and the rest swaps in place. Tokens and pairings survive every restart.
 */
public final class EditorService implements AutoCloseable {
    private final AnimusRuntime runtime;
    private final EditorHost host;
    private final Consumer<String> log;
    private final Clock clock;
    private volatile EditorSettings settings;
    private volatile EditorRelaySettings relaySettings;
    private volatile EditorRelayClient relay;
    private volatile EditorApi api;
    private volatile EditorListener listener;
    private ScheduledExecutorService timers;
    private ScheduledFuture<?> frames;
    private String state = "not started", listenerProblem = "";
    private boolean running;
    private final AtomicReference<PluginSettings> pending = new AtomicReference<>();
    private final ExecutorService restarts = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Animus-editor-settings").factory());
    private final AutoCloseable settingsListener;

    public EditorService(AnimusRuntime runtime, EditorHost host, Consumer<String> log) { this(runtime, host, log, Clock.systemUTC()); }

    public EditorService(AnimusRuntime runtime, EditorHost host, Consumer<String> log, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime);
        this.host = Objects.requireNonNull(host);
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(clock);
        settingsListener = runtime.onSettings(this::hotloaded);
    }

    /** Opens the editor state under {@code state/editor/} and starts the listener when enabled. A listener failure leaves the API usable over the relay. */
    public synchronized void start() throws IOException {
        if (api != null) throw new IllegalStateException("The editor service is already running");
        running = true;
        open(runtime.editorSettings(), runtime.editorRelaySettings());
    }

    private void open(EditorSettings editor, EditorRelaySettings relayNow) throws IOException {
        settings = editor;
        relaySettings = relayNow;
        if (!settings.enabled()) { state = "disabled; set [editor] enabled = true in config.toml"; return; }
        EditorIdentity identity = EditorIdentity.loadOrCreate(runtime.documents());
        EditorTokens tokens = new EditorTokens(runtime.documents(), clock);
        EditorAudit audit = new EditorAudit(runtime.documents(), clock);
        int pruned = audit.prune(settings.auditDays());
        EditorDocuments documents = new EditorDocuments(runtime.documents(), () -> runtime.recordingSettings().maximumFileBytes());
        EditorChanges changes = new EditorChanges(documents, clock, Duration.ofHours(settings.idempotencyHours()));
        EditorEvents events = new EditorEvents();
        EditorPreviews previews = new EditorPreviews(clock, (String id, String reason) -> {
            host.previews().ifPresent((EditorHost.PreviewPresenter presenter) -> presenter.end(id));
            com.google.gson.JsonObject event = new com.google.gson.JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", id);
            event.addProperty("state", reason);
            events.publish(event);
        });
        timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Animus-editor-timers").factory());
        EditorNotices notices = new EditorNotices(() -> settings.notices(), host.notices(), log, System::nanoTime, art.arcane.animus.core.AnimusLog.shared()::debug);
        api = new EditorApi(new EditorApi.Parts(settings, relaySettings.enabled(), host, identity, tokens, audit, documents, changes, previews, events, notices, runtime::npcs, runtime::importHost, timers, clock,
                runtime.environment().minecraftVersion()));
        frames = scheduleFrames(events, settings.eventFramesPerSecond());
        EditorApi opened = api;
        timers.scheduleAtFixedRate(guard(() -> {
            try { opened.housekeeping(); }
            catch (IOException failure) { log.accept("Editor token housekeeping failed: " + failure.getMessage()); }
        }), 1, 1, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(guard(opened::recheckAdmins), 60, 60, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(guard(notices::tick), 100, 100, TimeUnit.MILLISECONDS);
        timers.scheduleAtFixedRate(guard(() -> {
            try {
                host.files().execute(() -> {
                    try { changes.prune(); }
                    catch (IOException | RuntimeException failure) { log.accept("Editor change record cleanup failed: " + failure.getMessage()); }
                });
            } catch (RejectedExecutionException full) { /* The next hour retries. */ }
        }), 1, 60, TimeUnit.MINUTES);
        if (pruned > 0) log.accept("Editor audit: removed " + pruned + " day files older than " + settings.auditDays() + " days");
        state = "ready";
        openRelay();
        openListener();
    }

    private ScheduledFuture<?> scheduleFrames(EditorEvents events, int framesPerSecond) {
        long frameMillis = Math.max(10, 1000 / framesPerSecond);
        return timers.scheduleAtFixedRate(guard(events::tick), frameMillis, frameMillis, TimeUnit.MILLISECONDS);
    }

    private void openRelay() {
        if (!relaySettings.enabled()) return;
        relay = new EditorRelayClient(relaySettings, api.parts().identity(), api);
        relay.start();
        log.accept("Editor relay connecting to " + relaySettings.url() + " as " + api.parts().identity().fingerprint());
    }

    private void closeRelay() {
        if (relay != null) relay.close();
        relay = null;
    }

    private void openListener() {
        listenerProblem = "";
        if (!settings.listenerEnabled()) return;
        EditorListener started = new EditorListener(settings, runtime.environment().dataDirectory(), api, log);
        try {
            started.start();
            listener = started;
            state = "ready";
            log.accept(started.describe());
        } catch (IOException | RuntimeException failure) {
            started.close();
            listenerProblem = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            state = "ready; listener failed: " + listenerProblem;
            log.accept("Editor listener could not start: " + listenerProblem);
        }
    }

    private void closeListener() {
        if (listener != null) listener.close();
        listener = null;
    }

    /** Hears a hotload on the file lane and hands it to the editor's settings thread, so a relay or listener restart never blocks the lane. */
    private void hotloaded(PluginSettings next) {
        pending.set(next);
        try { restarts.execute(this::reconcile); }
        catch (RejectedExecutionException closed) { /* The service is closing. */ }
    }

    /**
     * Applies the newest hotloaded {@code [editor]} table. The editor switch opens or closes the whole editor; a changed
     * relay key restarts only the relay client; a changed address, port, TLS file, connection bound or listener switch
     * restarts only the listener. Everything else swaps in place. Tokens and pairings are kept across every restart.
     */
    private void reconcile() {
        PluginSettings next = pending.getAndSet(null);
        if (next == null) return;
        EditorRelayClient retired = null;
        synchronized (this) {
            if (!running) return;
            retired = relay;
            try { apply(next.editor(), next.relay()); }
            catch (IOException | RuntimeException failure) {
                state = "failed: " + failure.getMessage();
                log.accept("Editor settings could not be applied: " + failure.getMessage());
            }
            if (retired == relay) retired = null;
        }
        // A relay client waits up to two seconds for its socket; close it without holding the service lock.
        if (retired != null) retired.close();
    }

    private void apply(EditorSettings editor, EditorRelaySettings relayNow) throws IOException {
        EditorSettings current = settings;
        EditorRelaySettings currentRelay = relaySettings;
        if (current.enabled() != editor.enabled() || api == null) {
            if (!current.enabled() && !editor.enabled()) { settings = editor; relaySettings = relayNow; return; }
            relay = null;
            shut();
            log.accept(editor.enabled() ? "Editor enabled by config.toml" : "Editor disabled by config.toml");
            open(editor, relayNow);
            return;
        }
        api.settings(editor, relayNow.enabled());
        settings = editor;
        relaySettings = relayNow;
        if (current.idempotencyHours() != editor.idempotencyHours()) api.parts().changes().retention(Duration.ofHours(editor.idempotencyHours()));
        if (current.auditDays() != editor.auditDays()) {
            int pruned = api.parts().audit().prune(editor.auditDays());
            if (pruned > 0) log.accept("Editor audit: removed " + pruned + " day files older than " + editor.auditDays() + " days");
        }
        if (current.eventFramesPerSecond() != editor.eventFramesPerSecond()) {
            if (frames != null) frames.cancel(false);
            frames = scheduleFrames(api.parts().events(), editor.eventFramesPerSecond());
        }
        if (!currentRelay.equals(relayNow)) {
            relay = null;
            openRelay();
            if (!relayNow.enabled()) log.accept("Editor relay stopped by config.toml");
        }
        if (EditorListener.rebind(current, editor) || listener == null && editor.listenerEnabled()) {
            closeListener();
            openListener();
            if (!editor.listenerEnabled()) { state = "ready"; log.accept("Editor listener stopped by config.toml"); }
        } else if (listener != null) listener.settings(editor);
    }

    private Runnable guard(Runnable task) {
        return () -> {
            try { task.run(); }
            catch (RuntimeException failure) { log.accept("Editor task failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        };
    }

    /** The transport-neutral API, for the relay client. Empty while the editor is disabled or stopped. */
    public synchronized Optional<EditorRequestHandler> handler() { return Optional.ofNullable(api); }

    public synchronized Optional<EditorApi> api() { return Optional.ofNullable(api); }

    public synchronized EditorSettings settings() { return settings; }

    public synchronized Optional<EditorListener> listener() { return Optional.ofNullable(listener); }

    /** A pairing link for /animus editor pair. Call on the file lane. */
    public PairingLink pair(EditorTokens.Grant grant) throws IOException {
        EditorApi active;
        synchronized (this) { active = api; }
        if (active == null) throw new IOException("The editor is " + state);
        Optional<String> direct = directUrl(), relay = relaySettings.enabled() ? Optional.of(relaySettings.url()) : Optional.empty();
        if (direct.isEmpty() && relay.isEmpty())
            throw new IOException("Nothing can reach this server: enable [editor] listenerEnabled (with TLS or a proxy and advertisedUrl) or relayEnabled");
        EditorTokens.Pairing pairing;
        try { pairing = active.parts().tokens().pair(grant); }
        catch (IllegalStateException full) { throw new IOException(full.getMessage()); }
        PairingCode code;
        try {
            code = new PairingCode(direct, relay, active.parts().identity().fingerprint(), active.parts().identity().serverKey(), pairing.tokenId(), pairing.secret(),
                    pairing.expires(), grant.label().isEmpty() ? Optional.empty() : Optional.of(grant.label()),
                    Optional.ofNullable(host.serverName()).filter((String name) -> !name.isBlank()).map((String name) -> name.length() > 128 ? name.substring(0, 128) : name));
        } catch (IllegalArgumentException refused) {
            throw new IOException("Cannot build a pairing code: " + refused.getMessage());
        }
        active.parts().audit().record(new EditorAudit.Entry("pair-issued", Optional.of(pairing.tokenId()), grant.admin(), "server", "command", List.of(), "issued"));
        String base = settings.editorUrl().endsWith("/") ? settings.editorUrl().substring(0, settings.editorUrl().length() - 1) : settings.editorUrl();
        return new PairingLink(base + "/#/pair/" + code.encode(), pairing);
    }

    public record PairingLink(String link, EditorTokens.Pairing pairing) {}

    /** The address a browser uses on the direct path: advertisedUrl, or the listener itself when TLS or loopback make it reachable. */
    private Optional<String> directUrl() {
        if (!settings.advertisedUrl().isEmpty()) return Optional.of(settings.advertisedUrl());
        EditorListener active = listener;
        if (active == null) return Optional.empty();
        String host = settings.listenAddress().contains(":") ? "[" + settings.listenAddress() + "]" : settings.listenAddress();
        if (settings.tls()) return host.equals("0.0.0.0") || host.equals("[::]") ? Optional.empty() : Optional.of("https://" + host + ":" + active.port());
        return EditorSettings.loopback(settings.listenAddress()) ? Optional.of("http://127.0.0.1:" + active.port()) : Optional.empty();
    }

    public List<String> revoke(String idOrAll) throws IOException {
        EditorApi active;
        synchronized (this) { active = api; }
        if (active == null) throw new IOException("The editor is " + state);
        List<String> ids = active.revoke(idOrAll, "token-revoked");
        for (String id : ids) active.parts().audit().record(new EditorAudit.Entry("revoke", Optional.of(id), Optional.empty(), "server", "command", List.of(), "revoked"));
        return ids;
    }

    /** Open editor event streams, for diagnostics. */
    public synchronized int eventStreams() { return api == null ? 0 : api.parts().events().open(); }
    /** The relay connection is registered, for diagnostics. */
    public synchronized boolean relayRegistered() { return relay != null && relay.isRegistered(); }

    public synchronized List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("Editor: " + state);
        if (api == null) return lines;
        EditorApi.Parts parts = api.parts();
        lines.add("Editor fingerprint " + parts.identity().fingerprint() + " | tokens=" + parts.tokens().list().size()
                + " | pairings waiting=" + parts.tokens().pendingPairings() + " | event streams=" + parts.events().open() + " | previews=" + parts.previews().count());
        lines.add(listener == null ? "Editor listener off" + (settings.listenerEnabled() ? " (failed to start" + (listenerProblem.isEmpty() ? "" : ": " + listenerProblem) + ")" : "; set [editor] listenerEnabled = true") : listener.describe());
        lines.add(relay == null ? "Editor relay off; set [editor] relayEnabled = true" : "Editor relay " + relaySettings.url() + " | " + (relay.isRegistered() ? "registered" : "connecting"));
        lines.add("Editor previews " + (host.previews().isPresent() ? "available" : "unavailable: " + host.previewsUnavailable()));
        return lines;
    }

    @Override
    public void close() {
        try { settingsListener.close(); }
        catch (Exception ignored) { /* Removing a listener from a list does not fail. */ }
        restarts.shutdownNow();
        synchronized (this) {
            running = false;
            shut();
            state = "stopped";
        }
    }

    private void shut() {
        closeRelay();
        closeListener();
        if (api != null) api.close();
        api = null;
        if (timers != null) timers.shutdownNow();
        timers = null;
        frames = null;
        listenerProblem = "";
    }
}
