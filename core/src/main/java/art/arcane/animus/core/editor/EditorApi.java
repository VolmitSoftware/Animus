package art.arcane.animus.core.editor;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.config.EditorNotifySettings;
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
import art.arcane.animus.core.editor.transport.EditorResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers", "Authorization, Content-Type, X-Animus-Counter", "Access-Control-Max-Age", "600",
            "Access-Control-Allow-Private-Network", "true", "Vary", "Origin");

    /** What the API holds. {@code npcs} reaches the live NPC registry for spawn, despawn and imports; {@code importHost} says whether Citizens or Shopkeepers still run. */
    public record Parts(EditorSettings settings, boolean relayEnabled, EditorHost host, EditorIdentity identity, EditorTokens tokens, EditorAudit audit,
                        EditorDocuments documents, EditorChanges changes, EditorPreviews previews, EditorEvents events, EditorNotices notices,
                        Supplier<NpcRepository> npcs, Supplier<CitizensDropIn.Host> importHost, ScheduledExecutorService timers, Clock clock, String minecraftVersion) {
        /** The same parts under hotloaded {@code [editor]} settings. */
        public Parts with(EditorSettings next, boolean relay) {
            return new Parts(Objects.requireNonNull(next), relay, host, identity, tokens, audit, documents, changes, previews, events, notices, npcs, importHost, timers, clock, minecraftVersion);
        }
    }

    private volatile Parts parts;
    private final EditorSchemas schemas = new EditorSchemas();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, double[]> buckets = new ConcurrentHashMap<>();
    private final Map<String, long[]> pairWindows = new ConcurrentHashMap<>();

    public EditorApi(Parts parts) { this.parts = Objects.requireNonNull(parts); }

    public Parts parts() { return parts; }

    /** A hotload of {@code [editor]}: origins, bounds and rates apply to the next request; tokens and pairings stay. */
    public void settings(EditorSettings next, boolean relayEnabled) { parts = parts.with(next, relayEnabled); }

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

    /** CORS headers for an allowed browser origin on the direct path. */
    private Map<String, String> cors(EditorRequest request) {
        Optional<String> origin = request.header("origin");
        if (request.transport() != EditorRequest.Transport.DIRECT || origin.isEmpty() || !parts.settings().allowedOrigins().contains(origin.get())) return Map.of();
        Map<String, String> headers = new LinkedHashMap<>(CORS);
        headers.put("Access-Control-Allow-Origin", origin.get());
        return headers;
    }

    /** The relay checks origins on its own browser handshake; the direct path checks every request. No Origin means no browser. */
    private void origin(EditorRequest request) throws EditorError {
        if (request.transport() != EditorRequest.Transport.DIRECT) return;
        Optional<String> origin = request.header("origin");
        if (origin.isPresent() && !parts.settings().allowedOrigins().contains(origin.get()))
            throw new EditorError(EditorError.Code.ORIGIN_NOT_ALLOWED, "Origin " + origin.get() + " is not in [editor] allowedOrigins.");
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
        if (method.equals("GET") && path.equals("/v1/hello")) return done(EditorResponse.json(200, hello()));
        if (method.equals("POST") && path.equals("/v1/pair")) return pair(request);
        EditorTokens.Token token = parts.tokens().authenticate(bearer(request));
        Route route = match(method, segments).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "No endpoint " + method + " " + path + "."));
        if (route.scope() != null && !token.has(route.scope()))
            throw new EditorError(EditorError.Code.SCOPE_MISSING, "This token lacks " + route.scope().wireName() + ".");
        if (route.anyApply() && token.scopes().stream().noneMatch((EditorScope scope) -> scope.wireName().startsWith("apply:")))
            throw new EditorError(EditorError.Code.SCOPE_MISSING, "This token has no apply scope.");
        rate(token);
        if (method.equals("POST") || method.equals("DELETE")) counter(request, token);
        AtomicInteger running = inFlight.computeIfAbsent(token.id(), (String id) -> new AtomicInteger());
        if (running.incrementAndGet() > parts.settings().concurrentRequestsPerToken()) {
            running.decrementAndGet();
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

    private record Route(EditorScope scope, boolean anyApply, Endpoint handler) {}

    private Optional<Route> match(String method, String[] s) {
        String first = s[0];
        int n = s.length;
        Route route = switch (method) {
            case "GET" -> switch (first) {
                case "capabilities" -> n == 1 ? new Route(EditorScope.READ, false, this::capabilities) : null;
                case "schemas" -> n == 1 ? new Route(EditorScope.READ, false, this::schemas) : null;
                case "lookups" -> n == 1 ? new Route(EditorScope.READ, false, this::lookups) : null;
                case "catalog" -> n == 1 ? new Route(EditorScope.READ, false, this::catalog) : null;
                case "documents" -> n >= 3 ? new Route(EditorScope.READ, false, this::document) : null;
                case "skins" -> n == 3 && s[2].equals("texture.png") ? new Route(EditorScope.READ, false, this::skin) : null;
                case "changes" -> n == 2 ? new Route(EditorScope.READ, false, this::changeStatus) : null;
                case "imports" -> n == 1 ? new Route(EditorScope.IMPORT, false, this::importStatus) : null;
                default -> null;
            };
            case "POST" -> switch (first) {
                case "validate" -> n == 1 ? new Route(EditorScope.VALIDATE, false, this::validate) : null;
                case "previews" -> n == 1 ? new Route(EditorScope.PREVIEW, false, this::preview) : null;
                case "changes" -> n == 1 ? new Route(null, true, this::apply) : n == 3 && s[2].equals("undo") ? new Route(null, true, this::undo) : null;
                case "imports" -> n == 1 ? new Route(EditorScope.IMPORT, false, this::upload)
                        : n == 2 && s[1].equals("stage") ? new Route(EditorScope.IMPORT, false, this::importStage)
                        : n == 3 && Set.of("apply", "accept", "rollback").contains(s[2]) ? new Route(EditorScope.IMPORT, false, this::importStep) : null;
                case "npcs" -> n == 3 && (s[2].equals("spawn") || s[2].equals("despawn")) ? new Route(EditorScope.SPAWN, false, this::spawn) : null;
                default -> null;
            };
            case "DELETE" -> first.equals("previews") && n == 2 ? new Route(EditorScope.PREVIEW, false, this::endPreview) : null;
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
            if (bucket[0] < 1) throw new EditorError(EditorError.Code.RATE_LIMITED, "Too many requests for this token.", (long) Math.ceil((1 - bucket[0]) / perSecond * 1000), null);
            bucket[0] -= 1;
        }
    }

    /** Fixed one-minute window per address for unauthenticated pairing and event authentication. */
    private void pairRate(String address) throws EditorError {
        long minute = parts.clock().millis() / 60_000;
        long[] window = pairWindows.computeIfAbsent(address, (String key) -> new long[]{minute, 0});
        synchronized (window) {
            if (window[0] != minute) { window[0] = minute; window[1] = 0; }
            if (++window[1] > parts.settings().pairAttemptsPerMinute())
                throw new EditorError(EditorError.Code.RATE_LIMITED, "Too many pairing attempts from this address.", 60_000 - parts.clock().millis() % 60_000, null);
        }
        if (pairWindows.size() > 4096) pairWindows.entrySet().removeIf((Map.Entry<String, long[]> entry) -> entry.getValue()[0] != minute);
    }

    private static CompletionStage<EditorResponse> done(EditorResponse response) { return CompletableFuture.completedFuture(response); }

    @FunctionalInterface private interface FileWork { EditorResponse run() throws EditorError, IOException; }

    /**
     * Runs on the shared file lane; a full lane answers busy at once. The token is checked again as the work starts, so a
     * session revoked or expired while its request waited neither reads nor writes.
     */
    private CompletionStage<EditorResponse> files(EditorTokens.Token token, FileWork work) throws EditorError {
        CompletableFuture<EditorResponse> result = new CompletableFuture<>();
        try {
            parts.host().files().execute(() -> {
                try {
                    parts.tokens().stillValid(token.id());
                    result.complete(work.run());
                }
                catch (EditorError refused) { result.complete(EditorReplies.error(refused)); }
                catch (IOException | RuntimeException failure) { result.complete(internal(failure)); }
            });
        } catch (RejectedExecutionException full) {
            throw new EditorError(EditorError.Code.BUSY, "The file lane is full.", 500, null);
        }
        return result;
    }

    private JsonObject hello() {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", PROTOCOL);
        JsonArray protocols = new JsonArray();
        protocols.add(PROTOCOL);
        body.add("protocols", protocols);
        body.addProperty("fingerprint", parts.identity().fingerprint());
        body.addProperty("animus", BuildIdentity.VERSION.length() > 64 ? BuildIdentity.VERSION.substring(0, 64) : BuildIdentity.VERSION);
        body.addProperty("platform", parts.host().platform());
        String minecraft = parts.minecraftVersion();
        body.addProperty("minecraft", minecraft.matches("\\d+\\.\\d+(\\.\\d+)?") ? minecraft : "0.0");
        String name = parts.host().serverName();
        if (name != null && !name.isBlank()) body.addProperty("serverName", name.length() > 128 ? name.substring(0, 128) : name);
        return body;
    }

    private CompletionStage<EditorResponse> pair(EditorRequest request) throws EditorError {
        pairRate(EditorReplies.address(request));
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("tokenId", "secret", "client"));
        String tokenId = EditorMessages.string(body, "tokenId", EditorMessages.TOKEN_ID, "$.tokenId");
        String secret = EditorMessages.string(body, "secret", EditorMessages.SECRET, "$.secret");
        Optional<String> client = EditorMessages.optionalString(body, "client", 128);
        Map.Entry<String, EditorTokens.Token> issued;
        try { issued = parts.tokens().exchange(tokenId, secret, client); }
        catch (IOException failure) { throw new EditorError(EditorError.Code.INTERNAL, "The token store could not be written."); }
        catch (EditorError refused) {
            audit("pair", Optional.of(tokenId), Optional.empty(), request, List.of(), refused.code().wireName());
            throw refused;
        }
        EditorTokens.Token token = issued.getValue();
        audit("pair", Optional.of(token.id()), token.admin(), request, List.of(), "paired");
        parts.notices().record(new EditorNotices.Event(EditorNotifySettings.Kind.PAIR, EditorNotices.Who.of(token), "paired", "", List.of(), Optional.empty(), List.of()));
        JsonObject response = new JsonObject();
        response.addProperty("token", issued.getKey());
        response.addProperty("tokenId", token.id());
        JsonArray scopes = new JsonArray();
        token.scopes().forEach((EditorScope scope) -> scopes.add(scope.wireName()));
        response.add("scopes", scopes);
        if (!token.groups().isEmpty()) {
            JsonArray groups = new JsonArray();
            token.groups().forEach(groups::add);
            response.add("groups", groups);
        }
        response.addProperty("expiresAt", EditorMessages.instant(token.expires()));
        response.addProperty("fingerprint", parts.identity().fingerprint());
        response.addProperty("counter", token.counter());
        token.admin().ifPresent((UUID admin) -> response.addProperty("admin", admin.toString()));
        return done(EditorResponse.json(200, response));
    }

    private CompletionStage<EditorResponse> capabilities(EditorRequest request, EditorTokens.Token token, String[] segments) {
        Map<String, EditorHost.Feature> features = new LinkedHashMap<>();
        features.put("editor.listener", parts.settings().listenerEnabled() ? EditorHost.Feature.yes() : EditorHost.Feature.no("[editor] listenerEnabled is false"));
        features.put("editor.relay", parts.relayEnabled() ? EditorHost.Feature.yes() : EditorHost.Feature.no("[editor] relayEnabled is false"));
        features.put("previews", parts.host().previews().isPresent() ? EditorHost.Feature.yes() : EditorHost.Feature.no(parts.host().previewsUnavailable()));
        features.put("imports", EditorHost.Feature.yes());
        features.put("spawn", EditorHost.Feature.yes());
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

    private CompletionStage<EditorResponse> catalog(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        Map<String, String> query = EditorReplies.query(request);
        EditorMessages.fields(queryObject(query), "$query", Set.of("kind", "after"));
        Set<DocumentKind> kinds = EnumSet.allOf(DocumentKind.class);
        String kindName = query.get("kind");
        if (kindName != null && !kindName.isEmpty())
            kinds = EnumSet.of(DocumentKind.parse(kindName).orElseThrow(() -> EditorMessages.invalid("kind: unknown kind " + kindName)));
        String rawAfter = query.get("after");
        Optional<String> after = rawAfter == null || rawAfter.isEmpty() ? Optional.empty() : Optional.of(cursor(rawAfter));
        Set<DocumentKind> chosen = kinds;
        return files(token, () -> {
            JsonArray documents = new JsonArray();
            String next = null;
            int page = 1000;
            for (EditorDocuments.Location location : parts.documents().list(chosen)) {
                String key = location.kind().ordinal() + "\n" + location.path();
                if (after.isPresent() && compare(key, after.get()) <= 0) continue;
                if (!visible(token, location.kind(), location.id())) continue;
                if (documents.size() == page) { next = encodeCursor(cursorKey(documents.get(page - 1).getAsJsonObject())); break; }
                EditorDocuments.Stored stored = parts.documents().read(location);
                if (!stored.exists()) continue;
                documents.add(EditorChanges.catalogEntry(location.kind(), location.id(), location.path(), stored.revision(), stored.hash()));
            }
            JsonObject body = new JsonObject();
            body.add("documents", documents);
            if (next == null) body.add("next", com.google.gson.JsonNull.INSTANCE); else body.addProperty("next", next);
            return EditorResponse.json(200, body);
        });
    }

    private static int compare(String key, String after) {
        int kindA = Integer.parseInt(key.substring(0, key.indexOf('\n'))), kindB = Integer.parseInt(after.substring(0, after.indexOf('\n')));
        return kindA != kindB ? Integer.compare(kindA, kindB) : key.substring(key.indexOf('\n') + 1).compareTo(after.substring(after.indexOf('\n') + 1));
    }

    private static String cursorKey(JsonObject entry) {
        return DocumentKind.parse(entry.get("kind").getAsString()).orElseThrow().ordinal() + "\n" + entry.get("path").getAsString();
    }

    private static String encodeCursor(String key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)); }

    private static String cursor(String value) throws EditorError {
        try {
            String key = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (key.matches("[0-9]{1,2}\n[a-z0-9_./-]{1,1024}")) return key;
        } catch (IllegalArgumentException invalid) { /* Refused below. */ }
        throw EditorMessages.invalid("after: not a catalog cursor");
    }

    private static JsonObject queryObject(Map<String, String> query) {
        JsonObject object = new JsonObject();
        query.forEach(object::addProperty);
        return object;
    }

    private static boolean visible(EditorTokens.Token token, DocumentKind kind, String id) {
        return kind != DocumentKind.NPC || token.group(id.substring(0, Math.max(0, id.lastIndexOf('/'))));
    }

    /** A change reaches its id and, for a move, the id it leaves. */
    private static void reach(EditorTokens.Token token, EditorMessages.Change change) throws EditorError {
        group(token, change.kind(), change.id());
        if (change.move()) group(token, change.kind(), change.from().orElseThrow());
    }

    private static void group(EditorTokens.Token token, DocumentKind kind, String id) throws EditorError {
        if (!visible(token, kind, id)) throw new EditorError(EditorError.Code.GROUP_DENIED, "This token cannot reach NPC folder npcs/" + id.substring(0, Math.max(0, id.lastIndexOf('/'))) + ".");
    }

    private static String decode(String segment) throws EditorError {
        try { return java.net.URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("The path is not URL encoded."); }
    }

    private CompletionStage<EditorResponse> document(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        DocumentKind kind = DocumentKind.parse(segments[1]).orElseThrow(() -> new EditorError(EditorError.Code.NOT_FOUND, "Unknown kind " + segments[1] + "."));
        List<String> pieces = new ArrayList<>();
        for (int index = 2; index < segments.length; index++) pieces.add(decode(segments[index]));
        String id = String.join("/", pieces);
        if (!id.matches(EditorMessages.DOCUMENT_ID)) throw EditorMessages.invalid("'" + id + "' is not a document id.");
        group(token, kind, id);
        return files(token, () -> {
            EditorDocuments.Stored stored = parts.documents().read(parts.documents().locate(kind, id));
            if (!stored.exists()) throw new EditorError(EditorError.Code.NOT_FOUND, "No " + kind.wireName() + " " + id + ".");
            JsonObject body = EditorChanges.catalogEntry(kind, id, stored.location().path(), stored.revision(), stored.hash());
            JsonObject source;
            JsonArray diagnostics;
            try {
                source = JsonDocuments.object(stored.content());
                diagnostics = parts.documents().validate(kind, id, source);
            } catch (IOException unreadable) {
                source = new JsonObject();
                diagnostics = new JsonArray();
                diagnostics.add(EditorDocuments.diagnostic("error", "$", "The stored file is not one JSON object: " + unreadable.getMessage(), "json"));
            }
            body.add("source", source);
            if (!diagnostics.isEmpty()) body.add("diagnostics", diagnostics);
            return EditorResponse.json(200, body);
        });
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

    private void scopes(EditorTokens.Token token, Set<DocumentKind> kinds, List<String> npcIds) throws EditorError {
        for (DocumentKind kind : kinds)
            if (!token.has(kind.applyScope())) throw new EditorError(EditorError.Code.SCOPE_MISSING, "This token lacks " + kind.applyScope().wireName() + ".");
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
                    EditorChanges.Outcome outcome = parts.changes().apply(set, requestHash, token.id());
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
        JsonObject catalog = new JsonObject();
        catalog.addProperty("type", "catalog");
        catalog.add("documents", outcome.catalog());
        if (!outcome.deleted().isEmpty()) catalog.add("deleted", outcome.deleted());
        parts.events().publish(catalog);
    }

    /** Records the notice once the reload completes, with the problems it reported. */
    private void announce(CompletionStage<List<String>> reload, EditorTokens.Token token, EditorNotifySettings.Kind kind, String verb, List<String> documents,
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
                    EditorChanges.Outcome outcome = parts.changes().undo(changeId, key, token.id(), (Set<DocumentKind> kinds, List<String> ids) -> scopes(token, kinds, ids));
                    published(outcome, token, EditorNotifySettings.Kind.ROLLBACK, "undid");
                    audit("undo", Optional.of(token.id()), token.admin(), request, List.of(changeId), outcome.body().get("status").getAsString());
                    return EditorResponse.json(outcome.status(), outcome.body());
                } finally { parts.changes().finished(key); }
            });
        } catch (EditorError busy) { parts.changes().finished(key); throw busy; }
    }

    /** Kinds an import writes: NPCs and skins, generated behaviors, and the shops and layouts of dtlTraders and Shopkeepers. */
    private static final Set<DocumentKind> IMPORTED = EnumSet.of(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.BEHAVIOR, DocumentKind.SHOP, DocumentKind.INVENTORY, DocumentKind.SERVER_BEHAVIORS);

    private EditorImports imports() { return new EditorImports(parts.documents().store(), parts.npcs(), parts.importHost()); }

    /** Imports reach every NPC group, so a group-limited token cannot stage or change one. */
    private static void ungrouped(EditorTokens.Token token) throws EditorError {
        if (!token.groups().isEmpty()) throw new EditorError(EditorError.Code.GROUP_DENIED, "Imports can reach every NPC group; use a token without groups.");
    }

    /** {@code GET /v1/imports}: where the import stands. */
    private CompletionStage<EditorResponse> importStatus(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        return files(token, () -> EditorResponse.json(200, imports().status()));
    }

    /** {@code POST /v1/imports?file=...&name=...}: the file's bytes, or over the relay a base64 JSON string. */
    private CompletionStage<EditorResponse> upload(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        ungrouped(token);
        Map<String, String> query = EditorReplies.query(request);
        for (String key : query.keySet()) if (!key.equals("file") && !key.equals("name")) throw EditorMessages.invalid("Unknown query parameter " + key + ".");
        EditorImports.Upload kind = EditorImports.Upload.parse(query.getOrDefault("file", "citizens"))
                .orElseThrow(() -> EditorMessages.invalid("file= is citizens, shopkeepers, shopkeepers-config or dtltraders."));
        Optional<String> name = Optional.ofNullable(query.get("name"));
        String target = EditorImports.target(kind, name);
        byte[] upload;
        try {
            JsonElement raw = request.body().orElseThrow(() -> EditorMessages.invalid("Upload the file as the request body."));
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) throw EditorMessages.invalid("The upload is the file's bytes, or over the relay a base64 JSON string.");
            upload = Base64.getDecoder().decode(raw.getAsString());
        } catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("The upload is not base64."); }
        if (upload.length == 0) throw EditorMessages.invalid("The uploaded file is empty.");
        if (upload.length > parts.settings().importBodyBytes()) throw new EditorError(EditorError.Code.TOO_LARGE, "Imports are limited to " + parts.settings().importBodyBytes() + " bytes.");
        byte[] bytes = upload;
        return files(token, () -> {
            try {
                JsonObject review = imports().upload(kind, name, bytes);
                audit("import-upload", Optional.of(token.id()), token.admin(), request, List.of(target), "uploaded");
                return EditorResponse.json(200, review);
            } catch (EditorError refused) {
                audit("import-upload", Optional.of(token.id()), token.admin(), request, List.of(target), refused.code().wireName());
                throw refused;
            }
        });
    }

    /** {@code POST /v1/imports/stage}: converts the uploaded saves and stages one reviewed plan. Nothing is installed. */
    private CompletionStage<EditorResponse> importStage(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        ungrouped(token);
        JsonObject body = request.body().isPresent() ? EditorMessages.body(request) : new JsonObject();
        EditorMessages.fields(body, "$", Set.of("mode", "label", "worlds"));
        String mode = body.has("mode") ? EditorMessages.string(body, "mode", "reject|replace|duplicate", "$.mode") : "reject";
        Optional<String> label = body.has("label") ? Optional.of(EditorMessages.string(body, "label", "[a-z0-9_-]{1,32}", "$.label")) : Optional.empty();
        if (mode.equals("duplicate") != label.isPresent()) throw EditorMessages.invalid("$.label: a duplicate import needs a copy label, and only a duplicate takes one.");
        Map<String, String> worlds = new LinkedHashMap<>();
        if (body.has("worlds")) {
            if (!body.get("worlds").isJsonObject()) throw EditorMessages.invalid("$.worlds: expected an object of old world names to new ones.");
            JsonObject map = body.getAsJsonObject("worlds");
            if (map.size() > 64) throw EditorMessages.invalid("$.worlds: at most 64 world mappings.");
            for (String from : map.keySet()) {
                if (!from.matches("[A-Za-z0-9_.-]{1,64}")) throw EditorMessages.invalid("$.worlds: world names use letters, digits, _, . and -.");
                worlds.put(from, EditorMessages.string(map, from, "[A-Za-z0-9_.-]{1,64}", "$.worlds." + from));
            }
        }
        return files(token, () -> {
            try {
                JsonObject review = imports().stage(mode, label, worlds);
                audit("import-stage", Optional.of(token.id()), token.admin(), request, staged(review), "staged");
                return EditorResponse.json(200, review);
            } catch (EditorError refused) {
                audit("import-stage", Optional.of(token.id()), token.admin(), request, List.of(), refused.code().wireName());
                throw refused;
            }
        });
    }

    /** {@code POST /v1/imports/{importId}/apply|accept|rollback}: the drop-in steps, with a catalog event for what changed. */
    private CompletionStage<EditorResponse> importStep(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String id = segments[1], step = segments[2];
        if (!id.matches("[a-f0-9]{64}")) throw new EditorError(EditorError.Code.NOT_FOUND, "No staged import " + id + ".");
        ungrouped(token);
        scopes(token, IMPORTED, List.of());
        List<String> categories = new ArrayList<>();
        if (step.equals("accept")) {
            JsonObject body = EditorMessages.body(request);
            EditorMessages.fields(body, "$", Set.of("categories"));
            if (!body.has("categories") || !body.get("categories").isJsonArray() || body.getAsJsonArray("categories").isEmpty())
                throw EditorMessages.invalid("$.categories: expected a non-empty array of review categories.");
            JsonArray list = body.getAsJsonArray("categories");
            for (int index = 0; index < list.size(); index++) {
                JsonElement category = list.get(index);
                if (!category.isJsonPrimitive() || !category.getAsJsonPrimitive().isString()) throw EditorMessages.invalid("$.categories[" + index + "]: expected a string.");
                categories.add(category.getAsString());
            }
        }
        return files(token, () -> {
            Map<String, String> before = hashes();
            JsonObject review;
            try {
                review = switch (step) {
                    case "apply" -> imports().apply(id);
                    case "accept" -> imports().accept(id, categories);
                    default -> imports().rollback(id);
                };
            } catch (EditorError refused) {
                audit("import-" + step, Optional.of(token.id()), token.admin(), request, List.of("import:" + id), refused.code().wireName());
                throw refused;
            }
            List<String> documents = new ArrayList<>(List.of("import:" + id));
            EditorNotifySettings.Kind kind = step.equals("rollback") ? EditorNotifySettings.Kind.ROLLBACK : EditorNotifySettings.Kind.IMPORT;
            String verb = step.equals("rollback") ? "rolled back an import of" : step.equals("apply") ? "applied an import of" : "accepted an import of";
            importPublished(before, hashes(), step.equals("rollback"), token, kind, verb).stream().limit(64).forEach(documents::add);
            audit("import-" + step, Optional.of(token.id()), token.admin(), request, documents, step.equals("rollback") ? "rolled-back" : step.equals("apply") ? "applied" : "accepted");
            return EditorResponse.json(200, review);
        });
    }

    private static List<String> staged(JsonObject review) {
        List<String> ids = new ArrayList<>();
        for (String part : List.of("citizens", "shopkeepers", "dtltraders"))
            if (review.has(part)) ids.add("import:" + review.getAsJsonObject(part).get("importId").getAsString());
        return ids;
    }

    /** Reloads what an import step changed and sends one catalog event; returns the changed documents as {@code kind:id}. */
    private List<String> importPublished(Map<String, String> before, Map<String, String> after, boolean rollback, EditorTokens.Token token,
                                         EditorNotifySettings.Kind notice, String verb) throws IOException {
        JsonArray catalog = new JsonArray(), deleted = new JsonArray();
        List<String> touched = new ArrayList<>();
        Set<DocumentKind> kinds = EnumSet.noneOf(DocumentKind.class);
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (entry.getValue().equals(before.get(entry.getKey()))) continue;
            String[] name = entry.getKey().split("\n", 3);
            DocumentKind kind = DocumentKind.parse(name[0]).orElseThrow();
            kinds.add(kind);
            touched.add(kind.wireName() + ":" + name[1]);
            catalog.add(EditorChanges.catalogEntry(kind, name[1], name[2], EditorDocuments.revision(parts.documents().store().read(name[2])), entry.getValue()));
        }
        for (String key : before.keySet()) {
            if (after.containsKey(key)) continue;
            String[] name = key.split("\n", 3);
            DocumentKind kind = DocumentKind.parse(name[0]).orElseThrow();
            kinds.add(kind);
            touched.add(kind.wireName() + ":" + name[1]);
            JsonObject reference = new JsonObject();
            reference.addProperty("kind", kind.wireName());
            reference.addProperty("id", name[1]);
            deleted.add(reference);
        }
        if (kinds.isEmpty()) return touched;
        announce(parts.host().applied(kinds, removedBehaviors(deleted), rollback), token, notice, verb, touched, Optional.empty());
        JsonObject event = new JsonObject();
        event.addProperty("type", "catalog");
        event.add("documents", catalog);
        if (!deleted.isEmpty()) event.add("deleted", deleted);
        parts.events().publish(event);
        return touched;
    }

    /** Behavior ids among deleted {@code {kind, id}} references; their sources are gone on purpose. */
    private static Set<String> removedBehaviors(JsonArray deleted) {
        Set<String> removed = new java.util.TreeSet<>();
        for (JsonElement value : deleted) {
            JsonObject reference = value.getAsJsonObject();
            if (reference.get("kind").getAsString().equals(DocumentKind.BEHAVIOR.wireName())) removed.add(reference.get("id").getAsString());
        }
        return removed;
    }

    private Map<String, String> hashes() throws IOException {
        Map<String, String> hashes = new HashMap<>();
        for (EditorDocuments.Location location : parts.documents().list(IMPORTED)) {
            String content = parts.documents().store().read(location.path());
            if (content != null) hashes.put(location.kind().wireName() + "\n" + location.id() + "\n" + location.path(), "sha256:" + JsonDocuments.hash(content));
        }
        return hashes;
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

    private void audit(String event, Optional<String> tokenId, Optional<UUID> admin, EditorRequest request, List<String> documents, String result) {
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
        try {
            origin(request);
            pairRate(EditorReplies.address(request));
            EditorTokens.Token token = parts.tokens().authenticate(bearer(request));
            if (!token.has(EditorScope.READ)) throw new EditorError(EditorError.Code.SCOPE_MISSING, "This token lacks read.");
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
        for (String expired : parts.tokens().expire()) {
            parts.events().close(expired, "token-expired");
            parts.previews().endToken(expired);
        }
        parts.previews().expire();
    }

    /**
     * Revokes tokens whose issuing player lost {@code animus.admin}. The permission answer arrives on the player's owner
     * thread; the revoke writes token and audit files, so it moves to the file lane. A full lane retries next minute.
     */
    public void recheckAdmins() {
        for (EditorTokens.Token token : parts.tokens().list()) {
            token.admin().ifPresent((UUID admin) -> parts.host().admin(admin, (EditorHost.Admin status) -> {
                if (status != EditorHost.Admin.NOT_ADMIN) return;
                try {
                    parts.host().files().execute(() -> {
                        try {
                            if (!revoke(token.id(), "token-revoked", "after losing animus.admin").isEmpty())
                                parts.audit().record(new EditorAudit.Entry("revoke", Optional.of(token.id()), Optional.of(admin), "server", "admin-check", List.of(), "animus.admin removed"));
                        } catch (IOException failure) {
                            System.getLogger("Animus").log(System.Logger.Level.WARNING, "Editor token revoke failed: " + failure.getMessage());
                        }
                    });
                } catch (RejectedExecutionException full) { /* The next minute retries. */ }
            }));
        }
    }

    /** Ends every stream and preview at shutdown. */
    public void close() {
        parts.events().closeAll("server-stopping");
        parts.previews().endAll();
    }
}
