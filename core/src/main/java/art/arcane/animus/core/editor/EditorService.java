package art.arcane.animus.core.editor;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.editor.relay.EditorRelayClient;
import art.arcane.animus.core.editor.transport.EditorRequestHandler;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The editor API on one server: identity, tokens, audit, change records, previews, event pacing and the optional embedded
 * listener. Platforms create one after the runtime starts, register {@link #commands} as {@code /animus editor}, and close
 * it before the runtime. The relay client passes its frames to {@link #handler}.
 */
public final class EditorService implements AutoCloseable {
    private final AnimusRuntime runtime;
    private final EditorHost host;
    private final Consumer<String> log;
    private final Clock clock;
    private volatile EditorSettings settings;
    private volatile EditorRelaySettings relaySettings;
    private EditorRelayClient relay;
    private EditorApi api;
    private volatile EditorListener listener;
    private ScheduledExecutorService timers;
    private String state = "not started";

    public EditorService(AnimusRuntime runtime, EditorHost host, Consumer<String> log) { this(runtime, host, log, Clock.systemUTC()); }

    public EditorService(AnimusRuntime runtime, EditorHost host, Consumer<String> log, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime);
        this.host = Objects.requireNonNull(host);
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Opens the editor state under {@code state/editor/} and starts the listener when enabled. A listener failure leaves the API usable over the relay. */
    public synchronized void start() throws IOException {
        if (api != null) throw new IllegalStateException("The editor service is already running");
        settings = runtime.editorSettings();
        relaySettings = runtime.editorRelaySettings();
        if (!settings.enabled()) { state = "disabled; set [editor] enabled = true in config.toml"; return; }
        EditorIdentity identity = EditorIdentity.loadOrCreate(runtime.documents());
        EditorTokens tokens = new EditorTokens(runtime.documents(), clock);
        EditorAudit audit = new EditorAudit(runtime.documents(), clock);
        int pruned = audit.prune(settings.auditDays());
        EditorDocuments documents = new EditorDocuments(runtime.documents());
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
        api = new EditorApi(new EditorApi.Parts(settings, relaySettings.enabled(), host, identity, tokens, audit, documents, changes, previews, events, runtime::npcs, runtime::importHost, timers, clock,
                runtime.environment().minecraftVersion()));
        long frameMillis = Math.max(10, 1000 / settings.eventFramesPerSecond());
        timers.scheduleAtFixedRate(guard(events::tick), frameMillis, frameMillis, TimeUnit.MILLISECONDS);
        timers.scheduleAtFixedRate(guard(() -> {
            try { api.housekeeping(); }
            catch (IOException failure) { log.accept("Editor token housekeeping failed: " + failure.getMessage()); }
        }), 1, 1, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(guard(api::recheckAdmins), 60, 60, TimeUnit.SECONDS);
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
        if (relaySettings.enabled()) {
            relay = new EditorRelayClient(relaySettings, identity, api);
            relay.start();
            log.accept("Editor relay connecting to " + relaySettings.url() + " as " + identity.fingerprint());
        }
        if (settings.listenerEnabled()) {
            EditorListener started = new EditorListener(settings, runtime.environment().dataDirectory(), api, log);
            try {
                started.start();
                listener = started;
                log.accept(started.describe());
            } catch (IOException | RuntimeException failure) {
                state = "ready; listener failed: " + failure.getMessage();
                log.accept("Editor listener could not start: " + failure.getMessage());
            }
        }
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

    public synchronized List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("Editor: " + state);
        if (api == null) return lines;
        EditorApi.Parts parts = api.parts();
        lines.add("Editor fingerprint " + parts.identity().fingerprint() + " | tokens=" + parts.tokens().list().size()
                + " | pairings waiting=" + parts.tokens().pendingPairings() + " | event streams=" + parts.events().open() + " | previews=" + parts.previews().count());
        lines.add(listener == null ? "Editor listener off" + (settings.listenerEnabled() ? " (failed to start)" : "; set [editor] listenerEnabled = true") : listener.describe());
        lines.add(relay == null ? "Editor relay off; set [editor] relayEnabled = true" : "Editor relay " + relaySettings.url() + " | " + (relay.isRegistered() ? "registered" : "connecting"));
        lines.add("Editor previews " + (host.previews().isPresent() ? "available" : "unavailable: " + host.previewsUnavailable()));
        return lines;
    }

    @Override
    public synchronized void close() {
        if (relay != null) relay.close();
        relay = null;
        if (listener != null) listener.close();
        listener = null;
        if (api != null) api.close();
        api = null;
        if (timers != null) timers.shutdownNow();
        timers = null;
        state = "stopped";
    }
}
