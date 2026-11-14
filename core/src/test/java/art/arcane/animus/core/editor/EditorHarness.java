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
    static final String ORIGIN = "https://animus.volmitsoftware.com";

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
        final List<EditorNotice> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean online = true, previews = true, rejectFiles, commandGrants;
        Admin admin = Admin.ADMIN, commands = Admin.ADMIN;
        List<String> rawTextBehaviors = List.of();
        List<String> activation = List.of(), reloadProblems = List.of();
        volatile EditorTerrain terrain;
        /** Where each active NPC stands now, by uuid. */
        final Map<UUID, art.arcane.animus.core.virtual.WorldPosition> live = new java.util.concurrent.ConcurrentHashMap<>();

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

        @Override public Lookups lookups() {
            return new Lookups(Map.of("items", List.of("minecraft:stone", "minecraft:apple"), "worlds", List.of("world")), List.of("tp", "animus", "give"), List.of("vault", "player"));
        }

        @Override public List<Capabilities.Availability> capabilities() { return List.of(); }

        @Override public boolean online(UUID player) { return online; }

        @Override public boolean commandGrants() { return commandGrants; }

        @Override public void permission(UUID player, String node, Consumer<Admin> result) { result.accept(node.equals(EditorCommands.COMMANDS_PERMISSION) ? commands : admin); }

        @Override public List<String> rawTextBehaviors() { return rawTextBehaviors; }

        @Override public CompletionStage<List<String>> activationProblems(NpcDefinition definition) { return CompletableFuture.completedFuture(activation); }

        @Override public CompletionStage<List<String>> applied(Set<DocumentKind> kinds, Set<String> removedBehaviors, boolean rollback) {
            applied.add(kinds.isEmpty() ? EnumSet.noneOf(DocumentKind.class) : EnumSet.copyOf(kinds));
            rollbacks.add(rollback);
            return CompletableFuture.completedFuture(reloadProblems);
        }

        @Override public EditorNoticeChannel notices() {
            return new EditorNoticeChannel() {
                @Override public Optional<String> name(UUID player) { return player.equals(ADMIN) ? Optional.of("Brian") : Optional.empty(); }

                @Override public void deliver(EditorNotice notice) { notices.add(notice); }
            };
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

        @Override public Optional<EditorTerrain> terrain() { return Optional.ofNullable(terrain); }

        @Override public Optional<art.arcane.animus.core.virtual.WorldPosition> located(UUID npc) { return Optional.ofNullable(live.get(npc)); }
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
    /** The notice clock; {@link #flushNotices} moves it past the quiet window. */
    volatile long nanos;
    final EditorNotices notices;
    final EditorCatalog catalog;
    /** Console lines the API logged. */
    final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AutoCloseable writes;
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
        EditorDocuments documents = new EditorDocuments(store, art.arcane.animus.core.config.RecordingSettings.DEFAULT::maximumFileBytes);
        EditorEvents events = new EditorEvents();
        EditorPreviews previews = new EditorPreviews(clock, (String id, String state) -> {
            host.previews().ifPresent((EditorHost.PreviewPresenter presenter) -> presenter.end(id));
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", id);
            event.addProperty("state", state);
            events.publish(event);
        });
        notices = new EditorNotices(settings::notices, host.notices(), (String line) -> {}, () -> nanos, () -> false);
        catalog = new EditorCatalog(documents, events);
        writes = art.arcane.animus.core.storage.WriteNotices.listen(catalog);
        catalog.prime();
        api = new EditorApi(new EditorApi.Parts(settings, art.arcane.animus.core.config.EditorRelaySettings.DEFAULT, host, EditorIdentity.loadOrCreate(store),
                new EditorTokens(store, clock), new EditorAudit(store, clock), documents, catalog, new EditorChanges(documents, clock, Duration.ofHours(settings.idempotencyHours())),
                previews, events, notices, new EditorWarnings(warnings::add, clock), () -> npcs, () -> importHost, timers, clock, "26.1.2"));
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
        return request(request(method, target, token, body, count));
    }

    /** A counted request, for a test that sends it itself. */
    EditorRequest request(String method, String target, String token, JsonObject body) { return request(method, target, token, body, true); }

    private EditorRequest request(String method, String target, String token, JsonObject body, boolean count) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        if (token != null) headers.put("Authorization", "Bearer " + token);
        if (count && (method.equals("POST") || method.equals("DELETE"))) headers.put("X-Animus-Counter", Long.toString(++counter));
        headers.put(EditorReplies.PEER_HEADER, "127.0.0.1");
        return new EditorRequest(EditorRequest.Transport.DIRECT, method, target, headers, Optional.ofNullable(body));
    }

    long nextCounter() { return ++counter; }

    /** Lets the quiet window pass and returns the plain chat lines of the notices it sent. */
    List<String> flushNotices() {
        int before = host.notices.size();
        nanos += EditorNotices.QUIET.toNanos();
        notices.tick();
        List<String> lines = new ArrayList<>();
        for (EditorNotice notice : host.notices.subList(before, host.notices.size())) {
            StringBuilder plain = new StringBuilder();
            notice.chat().ifPresent((EditorNotice.Line line) -> {
                String whole = line.text() + line.copy().map(EditorNotice.Copy::text).orElse("");
                art.arcane.animus.core.text.RichText.sectionRuns(whole).forEach((art.arcane.animus.core.text.RichText.Run run) -> plain.append(run.text()));
            });
            lines.add(plain.toString());
        }
        return lines;
    }

    EditorResponse request(EditorRequest request) throws Exception { return api.handle(request).toCompletableFuture().get(10, TimeUnit.SECONDS); }

    static JsonObject json(EditorResponse response) { return response.body().orElseThrow().getAsJsonObject(); }

    String read(String path) throws IOException { return store.read(path); }

    @Override public void close() throws IOException {
        try { writes.close(); }
        catch (Exception ignored) { /* Removing a listener does not fail. */ }
        lane.shutdownNow();
        timers.shutdownNow();
        store.close();
    }
}
