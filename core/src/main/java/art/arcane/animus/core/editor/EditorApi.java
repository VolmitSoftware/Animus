package art.arcane.animus.core.editor;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.config.EditorNotifySettings;
import art.arcane.animus.core.config.EditorRelaySettings;
import art.arcane.animus.core.config.EditorSettings;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.editor.transport.EditorEventSink;
import art.arcane.animus.core.editor.transport.EditorEventStream;
import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorRequestHandler;
import art.arcane.animus.core.editor.transport.EditorRequestHandler.Admission;
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The transport-neutral {@code /animus/v1} API. Every check lives here: origins, bearer tokens, scopes, NPC groups,
 * {@code X-Animus-Counter}, request rates, concurrency and body bounds. File work runs on the shared file lane, and a full
 * lane answers {@code busy}; a calling thread never waits for files.
 */
public final class EditorApi implements EditorRequestHandler {
    public static final int PROTOCOL = 1;
    /** How long a token keeps working while the server cannot tell whether its admin still holds {@code animus.editor.pair}. */
    public static final Duration UNCONFIRMED_ADMIN = Duration.ofDays(7);
    private static final String PAIR = art.arcane.animus.core.command.PermissionNodes.EDITOR_PAIR;
    private static final String COMMANDS_REMOVED = EditorCommands.COMMANDS_PERMISSION + " removed, apply:commands dropped",
            COMMANDS_UNCONFIRMED = EditorCommands.COMMANDS_PERMISSION + " unconfirmed, apply:commands dropped";
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers", "Authorization, Content-Type, X-Animus-Counter, If-None-Match", "Access-Control-Expose-Headers", "ETag", "Access-Control-Max-Age", "600",
            "Access-Control-Allow-Private-Network", "true", "Vary", "Origin");

    /**
     * What the API holds. {@code npcs} reaches the live NPC registry for spawn, despawn and imports; {@code importHost} says
     * whether Citizens or Shopkeepers still run; {@code warnings} takes the console lines for refusals.
     */
    public record Parts(EditorSettings settings, EditorRelaySettings relay, EditorHost host, EditorIdentity identity, EditorTokens tokens, EditorAudit audit,
                        EditorDocuments documents, EditorCatalog catalog, EditorChanges changes, EditorPreviews previews, EditorEvents events, EditorNotices notices,
                        EditorWarnings warnings, Supplier<NpcRepository> npcs, Supplier<CitizensDropIn.Host> importHost, ScheduledExecutorService timers, Clock clock,
                        String minecraftVersion) {
        /** The same parts under hotloaded {@code [editor]} settings. */
        public Parts with(EditorSettings next, EditorRelaySettings nextRelay) {
            return new Parts(Objects.requireNonNull(next), Objects.requireNonNull(nextRelay), host, identity, tokens, audit, documents, catalog, changes, previews, events, notices,
                    warnings, npcs, importHost, timers, clock, minecraftVersion);
        }
    }

    private volatile Parts parts;
    private final EditorSchemas schemas = new EditorSchemas();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>();
    private final EditorTerrainReads terrain = new EditorTerrainReads(this::parts);
    private final EditorImportRoutes imports = new EditorImportRoutes(this);
    private final EditorDocumentRoutes documents = new EditorDocumentRoutes(this);
    private final EditorPairingRoutes pairing = new EditorPairingRoutes(this);

    public EditorApi(Parts parts) { this.parts = Objects.requireNonNull(parts); }

    public Parts parts() { return parts; }

    /** A hotload of {@code [editor]}: origins, bounds and rates apply to the next request; tokens and pairings stay. */
    public void settings(EditorSettings next, EditorRelaySettings relay) { parts = parts.with(next, relay); }

    @Override
    public CompletionStage<EditorResponse> handle(EditorRequest request) {
        Map<String, String> cors = cors(request);
        CompletionStage<EditorResponse> answer;
        AnimusMetrics metrics = AnimusMetrics.current();
        long started = metrics.start();
        try { answer = route(request); }
        catch (EditorError refused) { answer = CompletableFuture.completedFuture(EditorReplies.error(refused)); }
        catch (RuntimeException failure) { answer = CompletableFuture.completedFuture(internal(failure)); }
        finally { metrics.stop(Subsystem.EDITOR, started); }
        return answer.handle((EditorResponse response, Throwable failure) -> {
            EditorResponse result = response;
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                result = cause instanceof EditorError refused ? EditorReplies.error(refused) : internal(cause);
            }
            return cors.isEmpty() ? result : EditorReplies.withHeaders(result, cors);
        });
    }

    private static EditorResponse internal(Throwable failure) {
        System.getLogger("Animus").log(System.Logger.Level.WARNING, "Editor request failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        return EditorReplies.error(new EditorError(EditorError.Code.INTERNAL, "The server could not complete the request; see the server log."));
    }

    /**
     * A head is checked before its body is read: the origin, and outside the open endpoints the bearer token and the
     * endpoint's scope. Only an authenticated request may send more than a small body.
     */
    @Override
    public Admission admit(EditorRequest head) {
        try {
            origin(head);
            String method = head.method(), path = head.route();
            if (method.equals("OPTIONS") || path.equals("/v1/hello") || path.equals("/v1/pair") || forgets(method, path)) return Admission.OPEN;
            EditorTokens.Token token = parts.tokens().authenticate(bearer(head));
            Optional<Route> route = path.startsWith("/v1/") ? match(method, path.substring(4).split("/", -1)) : Optional.empty();
            if (route.isPresent() && route.get().scope() != null && !token.has(route.get().scope())) throw lacks(route.get().scope());
            return Admission.AUTHENTICATED;
        } catch (EditorError refused) {
            Map<String, String> cors = cors(head);
            EditorResponse response = EditorReplies.error(refused);
            return Admission.refused(cors.isEmpty() ? response : EditorReplies.withHeaders(response, cors));
        }
    }

    /** CORS headers for an allowed browser origin on the direct path. */
    private Map<String, String> cors(EditorRequest request) {
        Optional<String> origin = request.header("origin");
        if (request.transport() != EditorRequest.Transport.DIRECT || origin.isEmpty() || !parts.settings().allowedOrigins().contains(origin.get())) return Map.of();
        Map<String, String> headers = new LinkedHashMap<>(CORS);
        headers.put("Access-Control-Allow-Origin", origin.get());
        return headers;
    }

    /**
     * The relay checks origins on its own browser handshake; the direct path checks every request. No Origin means no browser.
     * A refused origin is logged once an hour, and told to whoever has a pairing link open, since it is usually a pairing.
     */
    private void origin(EditorRequest request) throws EditorError {
        if (request.transport() != EditorRequest.Transport.DIRECT) return;
        Optional<String> origin = request.header("origin");
        if (origin.isEmpty() || parts.settings().allowedOrigins().contains(origin.get())) return;
        String shown = origin.get().length() > 128 ? origin.get().substring(0, 128) : origin.get();
        parts.warnings().once("origin " + shown, Duration.ofHours(1), "Editor refused origin " + shown + ": it is not in [editor] allowedOrigins");
        for (EditorTokens.Open open : parts.tokens().openPairings())
            if (parts.warnings().due("origin notice " + shown + " " + open.tokenId(), Duration.ofHours(1))) pairing.refused(open, "origin " + shown + " is not in allowedOrigins");
        throw new EditorError(EditorError.Code.ORIGIN_NOT_ALLOWED, "Origin " + shown + " is not in [editor] allowedOrigins.");
    }

    private CompletionStage<EditorResponse> route(EditorRequest request) throws EditorError {
        origin(request);
        String method = request.method(), path = request.route();
        if (method.equals("OPTIONS")) return done(EditorReplies.empty(204));
        if (!path.startsWith("/v1/")) {
            if (path.matches("/v[0-9]+/.*")) throw new EditorError(EditorError.Code.UNSUPPORTED_PROTOCOL, "This server speaks protocol " + PROTOCOL + " at /v1/.");
            throw new EditorError(EditorError.Code.NOT_FOUND, "No endpoint " + path + ".");
        }
        String[] segments = path.substring(4).split("/", -1);
        if (method.equals("GET") && path.equals("/v1/hello")) return done(EditorResponse.json(200, pairing.hello()));
        if (method.equals("POST") && path.equals("/v1/pair")) return done(pairing.pair(request));
        if (forgets(method, path)) return done(forget(request));
        EditorTokens.Token token = parts.tokens().authenticate(bearer(request));
        Route route = match(method, segments).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No endpoint " + method + " " + path + "."));
        if (route.scope() != null && !token.has(route.scope())) throw lacks(route.scope());
        rate(token);
        if ((method.equals("POST") || method.equals("DELETE")) && route.counted()) counter(request, token);
        AtomicInteger running = inFlight.computeIfAbsent(token.id(), (String id) -> new AtomicInteger());
        if (running.incrementAndGet() > parts.settings().concurrentRequestsPerToken()) {
            running.decrementAndGet();
            parts.warnings().limited(token.id());
            throw new EditorError(EditorError.Code.RATE_LIMITED, "At most " + parts.settings().concurrentRequestsPerToken() + " requests run at once per token.", 250, null);
        }
        CompletionStage<EditorResponse> result;
        Optional<String> action = action(method, segments);
        try { result = route.handler().handle(request, token, segments); }
        catch (EditorError refused) {
            running.decrementAndGet();
            action.ifPresent((String name) -> parts.notices().record(EditorNotices.refusal(EditorNotices.Who.of(token), name, refused.code().wireName(), refused.getMessage())));
            throw refused;
        }
        catch (RuntimeException failure) { running.decrementAndGet(); throw failure; }
        return result.whenComplete((EditorResponse response, Throwable failure) -> {
            running.decrementAndGet();
            if (action.isPresent() && response != null && response.status() >= 400) parts.notices().record(EditorNotices.refusal(EditorNotices.Who.of(token), action.get(), response));
        });
    }

    /** The server-changing endpoints whose refusals are announced in game: apply, undo, import steps, spawn and despawn. */
    private static Optional<String> action(String method, String[] segments) {
        if (!method.equals("POST")) return Optional.empty();
        if (segments[0].equals("changes")) return Optional.of(segments.length == 1 ? "apply" : "undo");
        if (segments[0].equals("imports") && segments.length == 3) return Optional.of("import " + segments[2]);
        if (segments[0].equals("npcs") && segments.length == 3) return Optional.of(segments[2]);
        return Optional.empty();
    }

    @FunctionalInterface private interface Endpoint { CompletionStage<EditorResponse> handle(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError; }

    /**
     * {@code counted}: a POST or DELETE that changes something and so needs {@code X-Animus-Counter}. A null scope is
     * checked by the handler: apply and undo need the apply scope of every document kind they write.
     */
    private record Route(EditorScope scope, boolean counted, Endpoint handler) {
        Route(EditorScope scope, Endpoint handler) { this(scope, true, handler); }
    }

    static EditorError lacks(EditorScope scope) { return EditorError.scopeMissing(scope, "This token lacks " + scope.wireName() + "."); }

    private Optional<Route> match(String method, String[] s) {
        String first = s[0];
        int n = s.length;
        Route route = switch (method) {
            case "GET" -> switch (first) {
                case "capabilities" -> n == 1 ? new Route(EditorScope.READ, this::capabilities) : null;
                case "schemas" -> n == 1 ? new Route(EditorScope.READ, this::schemas) : null;
                case "lookups" -> n == 1 ? new Route(EditorScope.READ, this::lookups) : null;
                case "catalog" -> n == 1 ? new Route(EditorScope.READ, documents::catalog) : null;
                case "documents" -> n >= 3 ? new Route(EditorScope.READ, documents::document) : null;
                case "skins" -> n == 3 && s[2].equals("texture.png") ? new Route(EditorScope.READ, this::skin) : null;
                case "changes" -> n == 2 ? new Route(EditorScope.READ, this::changeStatus) : null;
                case "imports" -> n == 1 ? new Route(EditorScope.IMPORT, imports::status) : null;
                case "terrain" -> n == 2 && s[1].equals("surface") ? new Route(EditorScope.TERRAIN, (EditorRequest request, EditorTokens.Token token, String[] path) -> terrain.surface(request, token))
                        : n == 2 && s[1].equals("blocks") ? new Route(EditorScope.TERRAIN, (EditorRequest request, EditorTokens.Token token, String[] path) -> terrain.blocks(request, token))
                        : n == 2 && s[1].equals("position") ? new Route(EditorScope.TERRAIN, (EditorRequest request, EditorTokens.Token token, String[] path) -> terrain.position(request, token)) : null;
                default -> null;
            };
            case "POST" -> switch (first) {
                case "documents" -> n == 2 && s[1].equals("read") ? new Route(EditorScope.READ, false, documents::read) : null;
                case "validate" -> n == 1 ? new Route(EditorScope.VALIDATE, this::validate) : null;
                case "previews" -> n == 1 ? new Route(EditorScope.PREVIEW, this::preview) : null;
                case "changes" -> n == 1 ? new Route(null, this::apply) : n == 3 && s[2].equals("undo") ? new Route(null, this::undo) : null;
                case "imports" -> n == 1 ? new Route(EditorScope.IMPORT, imports::upload)
                        : n == 2 && s[1].equals("stage") ? new Route(EditorScope.IMPORT, imports::stage)
                        : n == 3 && Set.of("apply", "rollback").contains(s[2]) ? new Route(EditorScope.IMPORT, imports::step) : null;
                case "npcs" -> n == 3 && (s[2].equals("spawn") || s[2].equals("despawn")) ? new Route(EditorScope.SPAWN, this::spawn) : null;
                default -> null;
            };
            case "DELETE" -> first.equals("previews") && n == 2 ? new Route(EditorScope.PREVIEW, this::endPreview) : null;
            default -> null;
        };
        return Optional.ofNullable(route);
    }

    private static String bearer(EditorRequest request) throws EditorError {
        String header = request.header("authorization").orElseThrow(() -> new EditorError(EditorError.Code.UNAUTHORIZED, "Authorization: Bearer <token> is required."));
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) throw new EditorError(EditorError.Code.UNAUTHORIZED, "Authorization: Bearer <token> is required.");
        return header.substring(7).strip();
    }

    private void counter(EditorRequest request, EditorTokens.Token token) throws EditorError {
        String header = request.header("x-animus-counter").orElseThrow(() -> new EditorError(EditorError.Code.INVALID_REQUEST, "X-Animus-Counter is required on POST and DELETE."));
        long value;
        try { value = Long.parseLong(header.strip()); }
        catch (NumberFormatException invalid) { throw new EditorError(EditorError.Code.INVALID_REQUEST, "X-Animus-Counter must be an integer."); }
        try { parts.tokens().advance(token.id(), value); }
        catch (IOException failure) { throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written."); }
    }

    /** Token bucket: requestsPerSecond, bursting to twice that. */
    private void rate(EditorTokens.Token token) throws EditorError {
        double perSecond = parts.settings().requestsPerSecond(), capacity = perSecond * 2;
        double[] bucket = buckets.computeIfAbsent(token.id(), (String id) -> new double[]{capacity, System.nanoTime()});
        synchronized (bucket) {
            long now = System.nanoTime();
            bucket[0] = Math.min(capacity, bucket[0] + (now - bucket[1]) / 1e9 * perSecond);
            bucket[1] = now;
            if (bucket[0] < 1) {
                parts.warnings().limited(token.id());
                throw new EditorError(EditorError.Code.RATE_LIMITED, "Too many requests for this token.", (long) Math.ceil((1 - bucket[0]) / perSecond * 1000), null);
            }
            bucket[0] -= 1;
        }
    }

    /** {@code [editor] serverName}, else the platform's name for the server: its MOTD, else its folder. */
    public String serverName() { return pairing.serverName(); }

    /** Tells the player who made a pairing link, in game, that a browser could not use it. */
    void pairingRefused(EditorTokens.Open open, String why) { pairing.refused(open, why); }

    private static CompletionStage<EditorResponse> done(EditorResponse response) { return CompletableFuture.completedFuture(response); }

    @FunctionalInterface interface FileWork { EditorResponse run() throws EditorError, IOException; }

    /**
     * Runs on the shared file lane; a full lane answers busy at once. The token is checked again as the work starts, so a
     * session revoked or expired while its request waited neither reads nor writes.
     */
    CompletionStage<EditorResponse> files(EditorTokens.Token token, FileWork work) throws EditorError {
        CompletableFuture<EditorResponse> result = new CompletableFuture<>();
        try {
            parts.host().files().execute(() -> {
                // Documents this work writes are announced with source editor.
                EditorCatalog.Source outer = EditorCatalog.enter(EditorCatalog.Source.EDITOR);
                try {
                    parts.tokens().stillValid(token.id());
                    result.complete(work.run());
                }
                catch (EditorError refused) { result.complete(EditorReplies.error(refused)); }
                catch (IOException | RuntimeException failure) { result.complete(internal(failure)); }
                finally { EditorCatalog.leave(outer); }
            });
        } catch (RejectedExecutionException full) {
            throw new EditorError(EditorError.Code.BUSY, "The file lane is full.", 500, null);
        }
        return result;
    }

    private CompletionStage<EditorResponse> capabilities(EditorRequest request, EditorTokens.Token token, String[] segments) {
        Map<String, EditorHost.Feature> features = new LinkedHashMap<>();
        features.put("editor.listener", parts.settings().listenerEnabled() ? EditorHost.Feature.yes() : EditorHost.Feature.no("[editor] listenerEnabled is false"));
        features.put("editor.relay", parts.relay().enabled() ? EditorHost.Feature.yes() : EditorHost.Feature.no("[editor] relayEnabled is false"));
        features.put("previews", parts.host().previews().isPresent() ? EditorHost.Feature.yes() : EditorHost.Feature.no(parts.host().previewsUnavailable()));
        features.put("imports", EditorHost.Feature.yes());
        features.put("spawn", EditorHost.Feature.yes());
        features.put("terrain", terrain.feature());
        features.put("skins.texture", EditorHost.Feature.no("the server does not cache decoded skin textures; the editor loads them from the skin's texture URL"));
        features.putAll(parts.host().features());
        JsonObject body = new JsonObject(), list = new JsonObject();
        features.forEach((String name, EditorHost.Feature feature) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("available", feature.available());
            if (!feature.available()) entry.addProperty("reason", feature.reason() == null || feature.reason().isBlank() ? "unavailable" : feature.reason().length() > 512 ? feature.reason().substring(0, 512) : feature.reason());
            list.add(name, entry);
        });
        body.add("features", list);
        JsonArray modules = new JsonArray();
        for (Map.Entry<String, String> module : parts.host().modules()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", module.getKey());
            entry.addProperty("status", module.getValue());
            modules.add(entry);
        }
        body.add("modules", modules);
        return done(EditorResponse.json(200, body));
    }

    private CompletionStage<EditorResponse> lookups(EditorRequest request, EditorTokens.Token token, String[] segments) {
        EditorHost.Lookups lookups = parts.host().lookups();
        JsonObject body = new JsonObject(), registries = new JsonObject();
        new TreeMap<>(lookups.registries()).forEach((String kind, List<String> ids) -> registries.add(kind, sortedArray(ids)));
        body.add("registries", registries);
        body.add("commands", sortedArray(lookups.commands()));
        body.add("papi", sortedArray(lookups.papi()));
        return done(EditorResponse.json(200, body));
    }

    private static JsonArray sortedArray(Collection<String> values) {
        JsonArray out = new JsonArray();
        new TreeSet<>(values).forEach(out::add);
        return out;
    }

    private CompletionStage<EditorResponse> schemas(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        return files(token, () -> EditorResponse.json(200, schemas.response(parts.host().capabilities())));
    }

    static boolean visible(EditorTokens.Token token, DocumentKind kind, String id) { return kind != DocumentKind.NPC || token.group(folder(id)); }

    /** The NPC folder of an NPC id: everything before its last slash. */
    static String folder(String id) { return id.substring(0, Math.max(0, id.lastIndexOf('/'))); }

    /** A change reaches its id and, for a move, the id it leaves. */
    private static void reach(EditorTokens.Token token, EditorMessages.Change change) throws EditorError {
        group(token, change.kind(), change.id());
        if (change.move()) group(token, change.kind(), change.from().orElseThrow());
    }

    static void group(EditorTokens.Token token, DocumentKind kind, String id) throws EditorError {
        if (!visible(token, kind, id)) throw new EditorError(EditorError.Code.GROUP_DENIED, "This token cannot reach NPC folder npcs/" + folder(id) + ".");
    }

    static String decode(String segment) throws EditorError {
        try { return java.net.URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("The path is not URL encoded."); }
    }

    private CompletionStage<EditorResponse> skin(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        throw new EditorError(EditorError.Code.NOT_FOUND, "This server keeps no decoded skin textures; read the skin document and load its texture URL.");
    }

    private CompletionStage<EditorResponse> validate(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("documents"));
        List<EditorMessages.Change> changes = EditorMessages.changes(body, parts.settings().changeSetDocuments());
        for (EditorMessages.Change change : changes) reach(token, change);
        return files(token, () -> {
            JsonArray documents = new JsonArray();
            boolean valid = true;
            Set<String> behaviors = new java.util.HashSet<>();
            for (EditorDocuments.Location location : parts.documents().list(List.of(DocumentKind.BEHAVIOR))) behaviors.add(location.id());
            for (EditorMessages.Change change : changes)
                if (change.kind() == DocumentKind.BEHAVIOR) {
                    if (change.delete()) behaviors.remove(change.id()); else behaviors.add(change.id());
                    change.from().ifPresent(behaviors::remove);
                }
            for (EditorMessages.Change change : changes) {
                JsonArray diagnostics = new JsonArray();
                if (!change.delete()) {
                    JsonObject source = change.source().orElseThrow().deepCopy();
                    EditorDocuments.Stored current = parts.documents().read(parts.documents().locate(change.kind(), change.from().orElse(change.id())));
                    if (EditorDocuments.REVISIONED.contains(change.kind())) source.addProperty("revision", current.revision() + 1);
                    diagnostics = parts.documents().validate(change.kind(), change.id(), source);
                    if (change.kind() == DocumentKind.NPC && source.has("behaviors") && source.get("behaviors").isJsonArray()) {
                        int index = 0;
                        for (JsonElement reference : source.getAsJsonArray("behaviors")) {
                            if (reference.isJsonPrimitive() && !behaviors.contains(reference.getAsString()))
                                diagnostics.add(EditorDocuments.diagnostic("warning", "$.behaviors[" + index + "]", "behavior " + reference.getAsString() + " is not in the catalog or this change set", "behavior-missing"));
                            index++;
                        }
                    }
                }
                valid &= !EditorDocuments.hasErrors(diagnostics);
                documents.add(EditorChanges.documentDiagnostics(change.kind(), change.id(), diagnostics));
            }
            JsonObject response = new JsonObject();
            response.addProperty("valid", valid);
            response.add("documents", documents);
            audit("validate", Optional.of(token.id()), token.admin(), request, names(changes), valid ? "valid" : "invalid");
            return EditorResponse.json(200, response);
        });
    }

    private CompletionStage<EditorResponse> preview(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("previewId", "documents"));
        Optional<String> replace = body.has("previewId") ? Optional.of(EditorMessages.string(body, "previewId", EditorMessages.OBJECT_ID, "$.previewId")) : Optional.empty();
        List<EditorMessages.Change> changes = EditorMessages.changes(body, parts.settings().changeSetDocuments());
        for (EditorMessages.Change change : changes) reach(token, change);
        EditorHost.PreviewPresenter presenter = parts.host().previews()
                .orElseThrow(() -> new EditorError(EditorError.Code.PREVIEW_REFUSED, "Previews are unavailable: " + parts.host().previewsUnavailable()));
        UUID viewer = token.admin().orElseThrow(() -> new EditorError(EditorError.Code.PLAYER_OFFLINE, "This token was paired from the console; previews show to the pairing player."));
        if (!parts.host().online(viewer)) throw new EditorError(EditorError.Code.PLAYER_OFFLINE, "The pairing player is not online.");
        List<NpcDefinition> npcs;
        try { npcs = EditorPreviews.candidates(changes, parts.settings().npcsPerPreview(), parts.documents().recordingBytes()); }
        catch (EditorError refused) {
            audit("preview", Optional.of(token.id()), token.admin(), request, names(changes), refused.code().wireName());
            throw refused;
        }
        List<String> ids = npcs.stream().map((NpcDefinition npc) -> npc.qualifiedId()).toList();
        EditorPreviews.Preview started = parts.previews().start(replace, token.id(), viewer, ids, parts.settings().previewsPerServer(),
                Duration.ofMinutes(parts.settings().previewMinutes()));
        audit("preview", Optional.of(token.id()), token.admin(), request, names(changes), "running");
        return presenter.present(started.id(), viewer, npcs, (String state) -> parts.previews().end(started.id(), state)).handle((Void ignored, Throwable failure) -> {
            if (failure != null) {
                parts.previews().end(started.id(), "ended");
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                return EditorReplies.error(new EditorError(EditorError.Code.PREVIEW_REFUSED, "The preview could not be shown: " + cause.getMessage()));
            }
            JsonObject response = new JsonObject();
            response.addProperty("previewId", started.id());
            response.addProperty("viewer", viewer.toString());
            response.addProperty("expiresAt", EditorMessages.instant(started.expires()));
            JsonArray list = new JsonArray();
            ids.forEach(list::add);
            response.add("npcs", list);
            JsonArray hidden = new JsonArray();
            hiddenLive(npcs).forEach(hidden::add);
            response.add("hidden", hidden);
            JsonObject event = new JsonObject();
            event.addProperty("type", "preview");
            event.addProperty("previewId", started.id());
            event.addProperty("state", "running");
            parts.events().publish(event);
            return EditorResponse.json(200, response);
        });
    }

    /** The live NPCs (group/id) the candidates stand in for, by id or UUID, as the viewer's runtime hides them. */
    private List<String> hiddenLive(List<NpcDefinition> candidates) {
        Map<String, NpcDefinition> live = parts.npcs().get().snapshot();
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        for (NpcDefinition candidate : candidates) {
            NpcDefinition same = live.get(candidate.id());
            if (same != null) { hidden.add(same.qualifiedId()); continue; }
            UUID uuid = candidate.uuid();
            for (NpcDefinition existing : live.values())
                if (existing.uuid().equals(uuid)) { hidden.add(existing.qualifiedId()); break; }
        }
        return List.copyOf(hidden);
    }

    private CompletionStage<EditorResponse> endPreview(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String id = segments[1];
        EditorPreviews.Preview preview = parts.previews().find(id).filter((EditorPreviews.Preview running) -> running.tokenId().equals(token.id()))
                .orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No running preview " + id + " for this token."));
        parts.previews().end(preview.id(), "ended");
        return done(EditorReplies.empty(204));
    }

    /** {@code DELETE /v1/token} takes no scope, counter or rate limit, so a browser forgetting this server can always end its session. */
    private static boolean forgets(String method, String path) { return method.equals("DELETE") && path.equals("/v1/token"); }

    /** The bearer revokes itself. A token already revoked answers the same 204, so a retried forget succeeds. */
    private EditorResponse forget(EditorRequest request) throws EditorError {
        EditorTokens.Token token;
        try { token = parts.tokens().authenticate(bearer(request)); }
        catch (EditorError refused) {
            if (refused.code() == EditorError.Code.TOKEN_REVOKED) return EditorReplies.empty(204);
            throw refused;
        }
        try {
            if (!revoke(token.id(), "token-revoked", "by its browser").isEmpty())
                audit("revoke", Optional.of(token.id()), token.admin(), request, List.of(), "revoked by its browser");
        } catch (IOException failure) { throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written."); }
        return EditorReplies.empty(204);
    }

    /** What {@link EditorCommandGuard} reads: the live registry, the raw text grants and the store. Call on the file lane. */
    EditorCommandGuard.Rules rules() { return EditorCommandGuard.Rules.of(parts.host(), parts.documents()); }

    void scopes(EditorTokens.Token token, Set<DocumentKind> kinds, List<String> npcIds) throws EditorError {
        for (DocumentKind kind : kinds)
            if (!token.has(kind.applyScope())) throw lacks(kind.applyScope());
        for (String id : npcIds) if (id.contains("/")) group(token, DocumentKind.NPC, id);
    }

    private CompletionStage<EditorResponse> apply(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.ChangeSet set = EditorMessages.changeSet(body, parts.settings().changeSetDocuments());
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        set.documents().forEach((EditorMessages.Change change) -> kinds.add(change.kind()));
        scopes(token, kinds, set.documents().stream().filter((EditorMessages.Change change) -> change.kind() == DocumentKind.NPC)
                .flatMap((EditorMessages.Change change) -> java.util.stream.Stream.concat(java.util.stream.Stream.of(change.id()), change.from().stream())).toList());
        String requestHash = EditorTokens.sha256(body.toString());
        parts.changes().queued(set.idempotencyKey());
        try {
            return files(token, () -> {
                try {
                    EditorChanges.Outcome outcome = parts.changes().apply(set, requestHash, token.id(), EditorCommandGuard.writes(token, rules()));
                    published(outcome, token, EditorNotifySettings.Kind.APPLY, "applied");
                    audit("apply", Optional.of(token.id()), token.admin(), request, names(set.documents()), outcome.body().get("status").getAsString());
                    return EditorResponse.json(outcome.status(), outcome.body());
                } finally { parts.changes().finished(set.idempotencyKey()); }
            });
        } catch (EditorError busy) { parts.changes().finished(set.idempotencyKey()); throw busy; }
    }

    /** Reloads, announces in game once the reload is live, and sends the change and catalog events of a committed change. */
    private void published(EditorChanges.Outcome outcome, EditorTokens.Token token, EditorNotifySettings.Kind kind, String verb) {
        if (!outcome.committed()) return;
        List<String> touched = new ArrayList<>();
        for (JsonElement version : outcome.body().getAsJsonArray("documents")) touched.add(EditorNotices.reference(version.getAsJsonObject()));
        for (JsonElement deleted : outcome.deleted()) touched.add(EditorNotices.reference(deleted.getAsJsonObject()));
        Optional<String> transaction = Optional.of(outcome.body().get("changeId").getAsString());
        announce(parts.host().applied(outcome.kinds(), removedBehaviors(outcome.deleted()), outcome.rollback()), token, kind, verb, touched, transaction);
        JsonObject change = new JsonObject();
        change.addProperty("type", "change");
        change.add("result", outcome.body());
        parts.events().publish(change);
        catalogChanged();
    }

    /** Sends the catalog event for what this file-lane work just wrote, after its change event. */
    void catalogChanged() {
        try { parts.catalog().flush(); }
        catch (IOException failure) { parts.warnings().once("catalog flush", Duration.ofMinutes(1), "Editor catalog event failed: " + failure.getMessage()); }
    }

    /** Records the notice once the reload completes, with the problems it reported. */
    void announce(CompletionStage<List<String>> reload, EditorTokens.Token token, EditorNotifySettings.Kind kind, String verb, List<String> documents,
                          Optional<String> transaction) {
        EditorNotices.Who who = EditorNotices.Who.of(token);
        reload.whenComplete((List<String> problems, Throwable failure) -> parts.notices().record(new EditorNotices.Event(kind, who, verb, "", documents, transaction,
                failure != null ? List.of("reload failed: " + failure.getMessage()) : problems)));
    }

    private CompletionStage<EditorResponse> changeStatus(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String key = segments[1];
        if (!key.matches(EditorMessages.KEY)) throw EditorMessages.invalid("Not an idempotency key: " + key);
        if (parts.changes().pending(key)) {
            JsonObject body = new JsonObject();
            body.addProperty("idempotencyKey", key);
            body.addProperty("status", "pending");
            return done(EditorResponse.json(200, body));
        }
        return files(token, () -> EditorResponse.json(200, parts.changes().status(key)));
    }

    private CompletionStage<EditorResponse> undo(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String changeId = segments[1];
        if (!changeId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new EditorError(EditorError.Code.NOT_FOUND, "No applied change " + changeId + ".");
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("idempotencyKey"));
        String key = EditorMessages.string(body, "idempotencyKey", EditorMessages.KEY, "$.idempotencyKey");
        parts.changes().queued(key);
        try {
            return files(token, () -> {
                try {
                    EditorChanges.Outcome outcome = parts.changes().undo(changeId, key, token.id(), (Set<DocumentKind> kinds, List<String> ids) -> scopes(token, kinds, ids),
                            EditorCommandGuard.writes(token, rules()));
                    published(outcome, token, EditorNotifySettings.Kind.ROLLBACK, "undid");
                    audit("undo", Optional.of(token.id()), token.admin(), request, List.of(changeId), outcome.body().get("status").getAsString());
                    return EditorResponse.json(outcome.status(), outcome.body());
                } finally { parts.changes().finished(key); }
            });
        } catch (EditorError busy) { parts.changes().finished(key); throw busy; }
    }

    /** Behavior ids among deleted {@code {kind, id}} references; their sources are gone on purpose. */
    static Set<String> removedBehaviors(JsonArray deleted) {
        Set<String> removed = new java.util.TreeSet<>();
        for (JsonElement value : deleted) {
            JsonObject reference = value.getAsJsonObject();
            if (reference.get("kind").getAsString().equals(DocumentKind.BEHAVIOR.wireName())) removed.add(reference.get("id").getAsString());
        }
        return removed;
    }

    private CompletionStage<EditorResponse> spawn(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String id = decode(segments[1]);
        boolean spawn = segments[2].equals("spawn");
        if (!NpcPaths.file("npcs/" + id + ".json")) throw new EditorError(EditorError.Code.NOT_FOUND, "No NPC " + id + "; NPC ids are their path under npcs/, such as town/guard.");
        group(token, DocumentKind.NPC, id);
        String shortId = id.substring(id.lastIndexOf('/') + 1);
        CompletableFuture<EditorResponse> answer = new CompletableFuture<>();
        CompletionStage<EditorResponse> prepared = files(token, () -> {
            NpcRepository repository = parts.npcs().get();
            NpcRepository.Draft before;
            try { before = repository.draft(shortId); }
            catch (IOException unknown) { throw new EditorError(EditorError.Code.NOT_FOUND, "No NPC " + id + "."); }
            NpcDefinition current = repository.find(shortId).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No NPC " + id + "."));
            if (!current.qualifiedId().equals(id)) throw new EditorError(EditorError.Code.NOT_FOUND, "No NPC " + id + ".");
            JsonObject document = before.document();
            document.addProperty("spawned", spawn);
            // Switching an NPC on starts whatever it runs, so it is checked like any other write of the document.
            if (spawn) EditorCommandGuard.require(token, DocumentKind.NPC, id, document, rules());
            NpcDefinition candidate = NpcDefinition.parse(JsonDocuments.write(document));
            NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
            CompletionStage<List<String>> checks = spawn ? parts.host().activationProblems(candidate) : CompletableFuture.completedFuture(List.of());
            checks.whenComplete((List<String> problems, Throwable failure) -> {
                if (failure != null) { answer.complete(internal(failure)); return; }
                if (!problems.isEmpty()) {
                    JsonArray diagnostics = new JsonArray();
                    problems.stream().limit(32).forEach((String problem) -> diagnostics.add(EditorDocuments.diagnostic("error", "$", problem, "activation")));
                    answer.complete(EditorReplies.error(new EditorError(EditorError.Code.INVALID_REQUEST, "The NPC cannot be spawned here.", -1, diagnostics)));
                    return;
                }
                try {
                    files(token, () -> {
                        try { repository.apply(draft); }
                        catch (IOException conflict) { throw new EditorError(EditorError.Code.CONFLICT, conflict.getMessage()); }
                        announce(parts.host().applied(EnumSet.of(DocumentKind.NPC), Set.of(), false), token,
                                spawn ? EditorNotifySettings.Kind.SPAWN : EditorNotifySettings.Kind.DESPAWN, spawn ? "spawned" : "despawned", List.of("npc:" + id), Optional.empty());
                        audit(spawn ? "spawn" : "despawn", Optional.of(token.id()), token.admin(), request, List.of("npc:" + id), "applied");
                        JsonObject body = new JsonObject();
                        body.addProperty("id", id);
                        body.addProperty("spawned", spawn);
                        return EditorResponse.json(200, body);
                    }).whenComplete((EditorResponse response, Throwable error) -> answer.complete(error == null ? response : internal(error)));
                } catch (EditorError busy) { answer.complete(EditorReplies.error(busy)); }
            });
            return null;
        });
        return prepared.thenCompose((EditorResponse early) -> early == null ? answer : CompletableFuture.completedFuture(early));
    }

    private static List<String> names(List<EditorMessages.Change> changes) { return changes.stream().map(EditorMessages.Change::name).toList(); }

    void audit(String event, Optional<String> tokenId, Optional<UUID> admin, EditorRequest request, List<String> documents, String result) {
        try { parts.audit().record(new EditorAudit.Entry(event, tokenId, admin, EditorReplies.address(request), request.method() + " " + request.route(), documents, result)); }
        catch (IOException failure) {
            System.getLogger("Animus").log(System.Logger.Level.WARNING, "Editor audit write failed: " + failure.getMessage());
        }
    }

    /**
     * {@code GET /v1/events}. The token comes in the Authorization header: the relay forwards the browser's, and the direct
     * listener copies it from the browser's first {@code event-auth} frame.
     */
    @Override
    public CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink) {
        String address = EditorReplies.address(request);
        try {
            origin(request);
            pairing.guard(address);
            EditorTokens.Token token;
            try { token = parts.tokens().authenticate(bearer(request)); }
            catch (EditorError refused) { pairing.failed(address); throw refused; }
            if (!token.has(EditorScope.READ)) throw lacks(EditorScope.READ);
            if (parts.events().open() >= parts.settings().openConnections())
                throw new EditorError(EditorError.Code.BUSY, "The server has its maximum of event streams open.", 5000, null);
            Runnable remove = parts.events().add(token.id(), sink, (String npc) -> visible(token, DocumentKind.NPC, npc));
            AtomicBoolean closed = new AtomicBoolean();
            Map<String, String> cors = cors(request);
            EditorResponse opened = EditorReplies.empty(200);
            return CompletableFuture.completedFuture(new EditorEventStream(cors.isEmpty() ? opened : EditorReplies.withHeaders(opened, cors), () -> {
                if (!closed.compareAndSet(false, true)) return;
                remove.run();
                if (parts.events().open(token.id()) == 0) parts.previews().endToken(token.id());
            }));
        } catch (EditorError refused) {
            parts.warnings().once("stream " + address + " " + refused.code().wireName(), EditorWarnings.MINUTE,
                    "Editor refused an event stream from " + address + ": " + refused.code().wireName());
            return CompletableFuture.completedFuture(EditorEventStream.refused(EditorReplies.error(refused)));
        }
    }

    /** Revokes tokens by command, closes their streams and ends their previews. */
    public List<String> revoke(String idOrAll, String reason) throws IOException { return revoke(idOrAll, reason, "by command"); }

    /** {@code cause} ends the in-game notice, such as {@code by command}. */
    private List<String> revoke(String idOrAll, String reason, String cause) throws IOException {
        Map<String, EditorTokens.Token> known = new HashMap<>();
        for (EditorTokens.Token token : parts.tokens().list()) known.put(token.id(), token);
        List<String> ids = parts.tokens().revoke(idOrAll);
        for (String id : ids) {
            parts.events().close(id, reason);
            parts.previews().endToken(id);
            EditorTokens.Token token = known.get(id);
            EditorNotices.Who who = token == null ? new EditorNotices.Who(id, "", Optional.empty()) : EditorNotices.Who.of(token);
            parts.notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.REVOKE, who, "was revoked", cause, List.of(), Optional.empty(), List.of()));
        }
        return ids;
    }

    /** Called once a second by the service: event frames are paced by {@link EditorEvents#tick} separately. */
    public void housekeeping() throws IOException {
        parts.warnings().tick(parts.settings());
        for (String expired : parts.tokens().expire()) {
            parts.events().close(expired, "token-expired");
            parts.previews().endToken(expired);
        }
        parts.previews().expire();
    }

    /**
     * Revokes tokens whose issuing player lost {@code animus.editor.pair} (which {@code animus.admin} grants), or whose admin went unconfirmed for {@link #UNCONFIRMED_ADMIN}.
     * A token with {@code apply:commands} also needs {@link EditorCommands#COMMANDS_PERMISSION}: losing it, or leaving it
     * unconfirmed as long, drops only that scope. A token counts as confirmed only when every node it needs was confirmed. The
     * answers arrive on the player's owner thread; recording them writes files, so it moves to the file lane. A full lane
     * retries next minute.
     */
    public void recheckAdmins() {
        for (EditorTokens.Token token : parts.tokens().list()) {
            token.admin().ifPresent((UUID admin) -> parts.host().permission(admin, PAIR, (EditorHost.Admin status) -> {
                boolean stale = !token.adminChecked().plus(UNCONFIRMED_ADMIN).isAfter(parts.clock().instant());
                if (status == EditorHost.Admin.UNKNOWN && !stale) return;
                if (status != EditorHost.Admin.ADMIN) {
                    String cause = status == EditorHost.Admin.UNKNOWN ? "after " + UNCONFIRMED_ADMIN.toDays() + " days without confirming " + PAIR : "after losing " + PAIR;
                    String reason = PAIR + (status == EditorHost.Admin.UNKNOWN ? " unconfirmed" : " removed");
                    onFiles(() -> {
                        if (!revoke(token.id(), "token-revoked", cause).isEmpty())
                            parts.audit().record(new EditorAudit.Entry("revoke", Optional.of(token.id()), Optional.of(admin), "server", "admin-check", List.of(), reason));
                    });
                    return;
                }
                if (!token.has(EditorScope.APPLY_COMMANDS)) { onFiles(() -> parts.tokens().confirmAdmin(token.id())); return; }
                parts.host().permission(admin, EditorCommands.COMMANDS_PERMISSION, (EditorHost.Admin commands) -> {
                    if (commands == EditorHost.Admin.UNKNOWN && !stale) return;
                    onFiles(() -> {
                        if (commands != EditorHost.Admin.ADMIN && parts.tokens().dropScope(token.id(), EditorScope.APPLY_COMMANDS))
                            parts.audit().record(new EditorAudit.Entry("scope-dropped", Optional.of(token.id()), Optional.of(admin), "server", "admin-check",
                                    List.of(), commands == EditorHost.Admin.UNKNOWN ? COMMANDS_UNCONFIRMED : COMMANDS_REMOVED));
                        parts.tokens().confirmAdmin(token.id());
                    });
                });
            }));
        }
    }

    @FunctionalInterface private interface TokenWork { void run() throws IOException; }

    /** Token bookkeeping on the file lane; a full lane leaves it for the next minute's check. */
    private void onFiles(TokenWork work) {
        try {
            parts.host().files().execute(() -> {
                try { work.run(); }
                catch (IOException failure) { System.getLogger("Animus").log(System.Logger.Level.WARNING, "Editor admin check failed: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException full) { /* The next minute retries. */ }
    }

    /** Ends every stream and preview at shutdown. */
    public void close() {
        parts.events().closeAll("server-stopping");
        parts.previews().endAll();
    }
}
