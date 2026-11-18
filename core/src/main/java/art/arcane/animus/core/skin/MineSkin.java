package art.arcane.animus.core.skin;

import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MineSkin's v2 queue API ({@code POST /v2/queue}, then {@code GET /v2/queue/<job>} once a second until the job ends).
 * One request runs at a time and the next waits for the {@code rateLimit.next} MineSkin names; a refusal with 429 waits
 * {@code Retry-After} and tries again. Without an API key MineSkin still signs, at its anonymous limit. A skin MineSkin
 * already signed is read with {@code GET /v2/skins/<id>} through the same queue.
 */
public final class MineSkin implements SkinSigner {
    public static final URI API = URI.create("https://api.mineskin.org");
    static final int MAXIMUM_WAITING = 8, MAXIMUM_POLLS = 90, MAXIMUM_RETRIES = 3;
    static final long FIRST_POLL_MILLIS = 2000, POLL_MILLIS = 1000;
    private static final Pattern LINK = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?(?:mineskin\\.org|minesk\\.in)/(?:skins/)?([A-Za-z0-9-]{1,40})/?(?:[?#]\\S*)?");
    private static final Pattern UUID_ID = Pattern.compile("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern SHORT_ID = Pattern.compile("[A-Za-z0-9]{6,16}");

    /** One HTTP exchange; tests answer it without a network. */
    @FunctionalInterface public interface Transport { CompletableFuture<Reply> send(HttpRequest request); }

    /** @param retryAfter the {@code Retry-After} header in seconds, or -1 */
    public record Reply(int status, String body, long retryAfter) {}

    private final Transport transport;
    private final Supplier<String> key;
    private final String agent;
    private final ScheduledExecutorService timers;
    private final LongSupplier clock;
    private final Deque<Runnable> waiting = new ArrayDeque<>();
    private boolean running;
    private long nextAllowed;

    public MineSkin(Transport transport, Supplier<String> key, String agent, ScheduledExecutorService timers, LongSupplier clock) {
        this.transport = transport; this.key = key; this.agent = agent; this.timers = timers; this.clock = clock;
    }

    /** The real service over {@code http}. */
    public static Transport http(HttpClient http) {
        return (HttpRequest request) -> http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply((HttpResponse<String> response) ->
                new Reply(response.statusCode(), response.body(), response.headers().firstValueAsLong("retry-after").orElse(-1)));
    }

    @Override public Optional<String> unavailable() { return Optional.empty(); }

    /** Whether {@code text} is a mineskin.org or minesk.in link. */
    public static boolean isLink(String text) { return text != null && LINK.matcher(text.strip()).matches(); }

    /**
     * The skin a MineSkin link or id names: its UUID in lowercase without dashes, or its short id as typed. Empty for
     * anything else, such as another site's address.
     */
    public static Optional<String> skinId(String text) {
        String typed = text == null ? "" : text.strip();
        Matcher link = LINK.matcher(typed);
        String id = link.matches() ? link.group(1) : typed;
        if (UUID_ID.matcher(id).matches()) return Optional.of(id.replace("-", "").toLowerCase(Locale.ROOT));
        return SHORT_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    @Override public CompletableFuture<Signed> find(String linkOrId) {
        Optional<String> id = skinId(linkOrId);
        if (id.isEmpty()) return CompletableFuture.failedFuture(new Refused("Not a MineSkin link or id"));
        return queued(() -> fetch(id.get(), 0));
    }

    private boolean keyed() { return !key.get().isBlank(); }

    @Override public CompletableFuture<Signed> sign(Request request) {
        if (request.source() instanceof Image image) {
            Optional<String> problem = SkinPng.problem(image.png());
            if (problem.isPresent()) return CompletableFuture.failedFuture(new Refused(problem.get()));
        } else if (request.source() instanceof Address address && isLink(address.url())) {
            return find(address.url());
        } else if (request.source() instanceof Address address && !address.url().matches("https?://[^\\s]{1,248}")) {
            return CompletableFuture.failedFuture(new Refused("A skin address is an http or https link of at most 256 characters"));
        }
        return queued(() -> submit(request, 0));
    }

    /** Runs {@code work} after the requests already waiting, one at a time. */
    private CompletableFuture<Signed> queued(Supplier<CompletableFuture<Signed>> work) {
        CompletableFuture<Signed> result = new CompletableFuture<>();
        synchronized (this) {
            if (waiting.size() >= MAXIMUM_WAITING) return CompletableFuture.failedFuture(new Refused("MineSkin is busy with " + MAXIMUM_WAITING + " skins from this server; try again in a minute"));
            waiting.add(() -> work.get().whenComplete((Signed signed, Throwable failure) -> {
                if (failure != null) result.completeExceptionally(refusal(failure)); else result.complete(signed);
                next();
            }));
            if (!running) next();
        }
        return result;
    }

    /** Starts the next waiting request once MineSkin's delay has passed. */
    private synchronized void next() {
        Runnable work = waiting.poll();
        running = work != null;
        if (work == null) return;
        long delay = Math.max(0, nextAllowed - clock.getAsLong());
        if (delay == 0) work.run(); else timers.schedule(work, delay, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Signed> submit(Request request, int attempt) {
        return transport.send(queue(request)).thenCompose((Reply reply) -> {
            JsonObject body = json(reply);
            limit(body);
            if (reply.status() == 429 && attempt < MAXIMUM_RETRIES) return later(wait(reply, body), () -> submit(request, attempt + 1));
            if (reply.status() >= 400 || !body.has("job")) throw new Refused(problem(reply, body));
            JsonObject job = body.getAsJsonObject("job");
            Optional<Signed> done = completed(body, job);
            if (done.isPresent()) return CompletableFuture.completedFuture(done.get());
            String id = string(job, "id").filter((String text) -> text.matches("[A-Za-z0-9_-]{1,64}")).orElseThrow(() -> new Refused("MineSkin answered without a job"));
            return later(FIRST_POLL_MILLIS, () -> poll(id, 0));
        });
    }

    private CompletableFuture<Signed> poll(String job, int attempt) {
        HttpRequest request = base(API.resolve("/v2/queue/" + job)).GET().build();
        return transport.send(request).thenCompose((Reply reply) -> {
            JsonObject body = json(reply);
            if (reply.status() == 429 && attempt < MAXIMUM_POLLS) return later(wait(reply, body), () -> poll(job, attempt + 1));
            if (reply.status() >= 400 || !body.has("job") || !body.get("job").isJsonObject()) throw new Refused(problem(reply, body));
            JsonObject state = body.getAsJsonObject("job");
            String status = string(state, "status").orElse("unknown");
            if (status.equals("failed")) throw new Refused(problem(reply, body));
            Optional<Signed> done = completed(body, state);
            if (done.isPresent()) return CompletableFuture.completedFuture(done.get());
            if (attempt + 1 >= MAXIMUM_POLLS) throw new Refused("MineSkin did not finish the skin within " + MAXIMUM_POLLS + " seconds; try again later");
            return later(POLL_MILLIS, () -> poll(job, attempt + 1));
        });
    }

    private CompletableFuture<Signed> fetch(String id, int attempt) {
        return transport.send(base(API.resolve("/v2/skins/" + id)).GET().build()).thenCompose((Reply reply) -> {
            JsonObject body = json(reply);
            limit(body);
            if (reply.status() == 429 && attempt < MAXIMUM_RETRIES) return later(wait(reply, body), () -> fetch(id, attempt + 1));
            if (reply.status() == 404) throw new Refused("MineSkin has no skin " + id);
            if (reply.status() >= 400) throw new Refused(problem(reply, body));
            JsonObject skin = object(body, "skin");
            if (skin == null) throw new Refused("MineSkin answered without the skin");
            return CompletableFuture.completedFuture(signed(skin));
        });
    }

    /** The signed skin of a completed job, read from {@code skin.texture}. */
    static Optional<Signed> completed(JsonObject body, JsonObject job) {
        if (!"completed".equals(string(job, "status").orElse("")) || !body.has("skin") || !body.get("skin").isJsonObject()) return Optional.empty();
        return Optional.of(signed(body.getAsJsonObject("skin")));
    }

    /** A MineSkin skin's signed texture, from {@code texture.data}. */
    private static Signed signed(JsonObject skin) {
        JsonObject texture = object(skin, "texture"), data = texture == null ? null : object(texture, "data"), url = texture == null ? null : object(texture, "url");
        Optional<String> value = data == null ? Optional.empty() : string(data, "value"), signature = data == null ? Optional.empty() : string(data, "signature");
        if (value.isEmpty() || signature.isEmpty()) throw new Refused("MineSkin finished the skin without a signed texture");
        try {
            return new Signed(new BodySkin(value.get(), signature.get()), url == null ? "" : string(url, "skin").orElse(""), "slim".equals(string(skin, "variant").orElse("")));
        } catch (IllegalArgumentException unreadable) { throw new Refused("MineSkin's signed texture is unreadable"); }
    }

    private HttpRequest queue(Request request) {
        String name = request.name().replaceAll("[^A-Za-z0-9_. -]", "").strip();
        if (name.length() > 48) name = name.substring(0, 48).strip();
        HttpRequest.Builder builder = base(API.resolve("/v2/queue"));
        if (request.source() instanceof Address address) {
            JsonObject body = new JsonObject();
            body.addProperty("url", address.url());
            body.addProperty("variant", request.slim() ? "slim" : "classic");
            body.addProperty("visibility", "unlisted");
            if (!name.isEmpty()) body.addProperty("name", name);
            return builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonDocuments.write(body))).build();
        }
        String boundary = "AnimusSkin" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream form = new ByteArrayOutputStream();
        part(form, boundary, "variant", request.slim() ? "slim" : "classic");
        part(form, boundary, "visibility", "unlisted");
        if (!name.isEmpty()) part(form, boundary, "name", name);
        form.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        form.writeBytes(((Image) request.source()).png());
        form.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return builder.header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(form.toByteArray())).build();
    }

    private static void part(ByteArrayOutputStream form, String boundary, String name, String value) {
        form.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder base(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").header("User-Agent", agent);
        String secret = key.get();
        if (!secret.isBlank()) builder.header("Authorization", "Bearer " + secret);
        return builder;
    }

    /** Keeps {@code rateLimit.next.relative}: the earliest the next request may go. */
    private synchronized void limit(JsonObject body) {
        JsonObject rate = object(body, "rateLimit"), next = rate == null ? null : object(rate, "next");
        JsonElement relative = next == null ? null : next.get("relative");
        if (relative != null && relative.isJsonPrimitive() && relative.getAsJsonPrimitive().isNumber())
            nextAllowed = Math.max(nextAllowed, clock.getAsLong() + Math.min(60_000, Math.max(0, relative.getAsLong())));
    }

    private static long wait(Reply reply, JsonObject body) {
        if (reply.retryAfter() >= 0) return Math.min(60, reply.retryAfter()) * 1000;
        JsonObject rate = object(body, "rateLimit"), next = rate == null ? null : object(rate, "next");
        JsonElement relative = next == null ? null : next.get("relative");
        return relative != null && relative.isJsonPrimitive() && relative.getAsJsonPrimitive().isNumber() ? Math.min(60_000, Math.max(1000, relative.getAsLong())) : 6000;
    }

    private <T> CompletableFuture<T> later(long millis, Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        timers.schedule(() -> work.get().whenComplete((T value, Throwable failure) -> {
            if (failure != null) result.completeExceptionally(failure); else result.complete(value);
        }), millis, TimeUnit.MILLISECONDS);
        return result;
    }

    /** One line for a player from MineSkin's {@code errors}. */
    private String problem(Reply reply, JsonObject body) {
        JsonArray errors = body.has("errors") && body.get("errors").isJsonArray() ? body.getAsJsonArray("errors") : new JsonArray();
        JsonObject first = !errors.isEmpty() && errors.get(0).isJsonObject() ? errors.get(0).getAsJsonObject() : new JsonObject();
        String code = string(first, "code").orElse(""), message = string(first, "message").orElse("");
        String detail = message.isBlank() ? (code.isBlank() ? "HTTP " + reply.status() : code) : clip(message);
        return switch (code) {
            case "invalid_api_key" -> "MineSkin refused the API key in [skins] mineskinKey; check it in config.toml";
            case "unauthorized", "insufficient_grants" -> keyed() ? "MineSkin refused the request: " + detail
                    : "MineSkin needs an API key for this: set [skins] mineskinKey in config.toml (keys at account.mineskin.org)";
            case "rate_limit", "job_limit", "concurrency_limit" -> "MineSkin is rate limiting this server; try again in a minute"
                    + (keyed() ? "" : " or set [skins] mineskinKey in config.toml for a higher limit");
            case "invalid_image", "invalid_file", "missing_file", "invalid_image_url" -> "MineSkin could not read the skin image: " + detail;
            default -> (reply.status() == 401 || reply.status() == 403) && !keyed() ? "MineSkin needs an API key: set [skins] mineskinKey in config.toml (keys at account.mineskin.org)"
                    : "MineSkin could not sign the skin: " + detail;
        };
    }

    private static String clip(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    private static JsonObject json(Reply reply) {
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(reply.body() == null ? "" : reply.body());
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (RuntimeException notJson) { /* A proxy's HTML page: the status speaks for it. */ }
        if (reply.status() < 400) throw new Refused("MineSkin answered something that is not JSON (HTTP " + reply.status() + ")");
        return new JsonObject();
    }

    private static Throwable refusal(Throwable failure) {
        Throwable cause = failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure;
        if (cause instanceof Refused) return cause;
        if (cause instanceof IOException || cause instanceof java.net.http.HttpTimeoutException)
            return new Refused("Could not reach MineSkin; the server needs internet access");
        return new Refused("MineSkin could not sign the skin; try again later");
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static Optional<String> string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Optional.of(value.getAsString()) : Optional.empty();
    }
}
