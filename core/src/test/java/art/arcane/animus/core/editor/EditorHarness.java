package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A data folder copied from schema/fixtures/export with the editor API over it and a recording platform. */
final class EditorHarness implements AutoCloseable {
    static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final String ORIGIN = "https://animus-editor.web.app";

    /** A clock tests move by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }

        void advance(Duration duration) { now = now.plus(duration); }
    }

    /** The platform side, recording what the API asked of it. */
    final class Host implements EditorHost {
        final List<Set<DocumentKind>> applied = new ArrayList<>();
        final List<Boolean> rollbacks = new ArrayList<>();
        final List<String> presented = new ArrayList<>(), ended = new ArrayList<>();
        final Map<String, Consumer<String>> leave = new LinkedHashMap<>();
        boolean online = true, previews = true, rejectFiles;
        Admin admin = Admin.ADMIN;
        List<String> activation = List.of();

        @Override public String platform() { return "paper"; }

        @Override public String serverName() { return "Town"; }

        @Override public java.util.concurrent.Executor files() {
            return (Runnable task) -> {
                if (rejectFiles) throw new RejectedExecutionException("full");
                lane.execute(task);
            };
        }

        @Override public Map<String, Feature> features() { return Map.of("combat", Feature.no("Folia combat is not available"), "rigs", Feature.yes()); }

        @Override public List<Map.Entry<String, String>> modules() { return List.of(Map.entry("example", "ACTIVE")); }

        @Override public List<Capabilities.Availability> capabilities() { return List.of(); }

        @Override public boolean online(UUID player) { return online; }

        @Override public void admin(UUID player, Consumer<Admin> result) { result.accept(admin); }

        @Override public CompletionStage<List<String>> activationProblems(NpcDefinition definition) { return CompletableFuture.completedFuture(activation); }

        @Override public void applied(Set<DocumentKind> kinds, Set<String> removedBehaviors, boolean rollback) {
            applied.add(kinds.isEmpty() ? EnumSet.noneOf(DocumentKind.class) : EnumSet.copyOf(kinds));
            rollbacks.add(rollback);
        }

        @Override public Optional<PreviewPresenter> previews() {
            if (!previews) return Optional.empty();
            return Optional.of(new PreviewPresenter() {
                @Override public CompletionStage<Void> present(String previewId, UUID viewer, List<NpcDefinition> npcs, Consumer<String> ended) {
                    presented.add(previewId + ":" + npcs.size());
                    leave.put(previewId, ended);
                    return CompletableFuture.completedFuture(null);
                }

                @Override public void end(String previewId) { ended.add(previewId); }
            });
        }

        @Override public String previewsUnavailable() { return "not built for this platform"; }
    }

    final Path root;
    final DocumentStore store;
    final TestClock clock = new TestClock();
    final Host host = new Host();
    final ExecutorService lane = Executors.newSingleThreadExecutor();
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    final EditorApi api;
    final EditorSettings settings;
    final NpcRepository npcs;
    /** Whether the drop-in import sees Citizens as still running. */
    volatile boolean citizensRunning;
    final art.arcane.animus.core.migration.CitizensDropIn.Host importHost =
            new art.arcane.animus.core.migration.CitizensDropIn.Host(Optional.empty(), () -> citizensRunning, () -> false, () -> false);
    private long counter;

    EditorHarness(Path directory) throws IOException { this(directory, EditorSettings.DEFAULT); }

    EditorHarness(Path directory, EditorSettings settings) throws IOException {
        this.settings = settings;
        root = directory.resolve("data");
        Path export = SharedFixtures.fixture("export");
        try (Stream<Path> files = Files.walk(export)) {
            for (Path file : files.toList()) {
                Path target = root.resolve(export.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
        store = new DocumentStore(root);
        npcs = new NpcRepository(store);
        npcs.reload();
        EditorDocuments documents = new EditorDocuments(store);
        EditorEvents events = new EditorEvents();
        EditorPreviews previews = new EditorPreviews(clock, (String id, String state) -> {
            host.previews().ifPresent((EditorHost.PreviewPresenter presenter) -> presenter.end(id));
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", id);
            event.addProperty("state", state);
            events.publish(event);
        });
        api = new EditorApi(new EditorApi.Parts(settings, false, host, EditorIdentity.loadOrCreate(store), new EditorTokens(store, clock), new EditorAudit(store, clock), documents,
                new EditorChanges(documents, clock, Duration.ofHours(settings.idempotencyHours())), previews, events, () -> npcs, () -> importHost, timers, clock, "26.1.2"));
    }

    /** Pairs and returns the bearer token. */
    String token(EditorTokens.Grant grant) throws Exception {
        EditorTokens.Pairing pairing = api.parts().tokens().pair(grant);
        JsonObject body = new JsonObject();
        body.addProperty("tokenId", pairing.tokenId());
        body.addProperty("secret", pairing.secret());
        EditorResponse response = send("POST", "/v1/pair", null, body, false);
        if (response.status() != 200) throw new IllegalStateException(response.body().toString());
        return json(response).get("token").getAsString();
    }

    String token() throws Exception { return token(grant(EditorScope.DEFAULT_PAIRING)); }

    static EditorTokens.Grant grant(List<EditorScope> scopes) { return new EditorTokens.Grant("laptop", Optional.of(ADMIN), scopes, List.of(), Duration.ofDays(7)); }

    EditorResponse send(String method, String target, String token, JsonObject body) throws Exception { return send(method, target, token, body, true); }

    EditorResponse send(String method, String target, String token, JsonObject body, boolean count) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        if (token != null) headers.put("Authorization", "Bearer " + token);
        if (count && (method.equals("POST") || method.equals("DELETE"))) headers.put("X-Animus-Counter", Long.toString(++counter));
        headers.put(EditorReplies.PEER_HEADER, "127.0.0.1");
        return request(new EditorRequest(EditorRequest.Transport.DIRECT, method, target, headers, Optional.ofNullable(body)));
    }

    long nextCounter() { return ++counter; }

    EditorResponse request(EditorRequest request) throws Exception { return api.handle(request).toCompletableFuture().get(10, TimeUnit.SECONDS); }

    static JsonObject json(EditorResponse response) { return response.body().orElseThrow().getAsJsonObject(); }

    String read(String path) throws IOException { return store.read(path); }

    @Override public void close() throws IOException {
        lane.shutdownNow();
        timers.shutdownNow();
        store.close();
    }
}
