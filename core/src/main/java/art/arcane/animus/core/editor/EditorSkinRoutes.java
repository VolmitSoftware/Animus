package art.arcane.animus.core.editor;

import art.arcane.animus.core.editor.transport.EditorRequest;
import art.arcane.animus.core.editor.transport.EditorResponse;
import art.arcane.animus.core.skin.SkinPng;
import art.arcane.animus.core.skin.SkinSigner;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code POST /v1/skins/sign} and {@code GET /v1/skins/sign/<job>}: MineSkin can take longer than one request may wait,
 * so the POST answers 202 with a job and the editor polls it. A signed job carries the skin document, which is not
 * saved here: the editor applies it like any other change.
 */
final class EditorSkinRoutes {
    static final int JOBS_PER_TOKEN = 4;
    static final Duration KEPT = Duration.ofMinutes(10);
    private static final String JOB = "[a-f0-9]{32}";

    private record Job(String token, CompletableFuture<SkinSigner.Signed> signing, String label, long started) {}

    private final Supplier<EditorApi.Parts> parts;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    EditorSkinRoutes(Supplier<EditorApi.Parts> parts) { this.parts = parts; }

    CompletionStage<EditorResponse> sign(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("image", "url", "slim", "label"));
        if (body.has("image") == body.has("url")) throw EditorMessages.invalid("$: send exactly one of image or url");
        SkinSigner.Source source;
        if (body.has("image")) {
            String encoded = EditorMessages.string(body, "image", "[A-Za-z0-9+/=]{1,40000}", "$.image");
            byte[] png;
            try { png = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("$.image: expected a base64 PNG"); }
            Optional<String> problem = SkinPng.problem(png);
            if (problem.isPresent()) throw EditorMessages.invalid("$.image: " + problem.get());
            source = new SkinSigner.Image(png);
        } else source = new SkinSigner.Address(EditorMessages.string(body, "url", "https?://\\S{1,248}", "$.url"));
        JsonElement slim = body.get("slim");
        if (slim != null && (!slim.isJsonPrimitive() || !slim.getAsJsonPrimitive().isBoolean())) throw EditorMessages.invalid("$.slim: expected a boolean");
        String label = EditorMessages.optionalString(body, "label", 64).map(String::strip).orElse("");
        if (label.chars().anyMatch(Character::isISOControl)) throw EditorMessages.invalid("$.label: one line of text");
        housekeeping();
        long running = jobs.values().stream().filter((Job job) -> job.token().equals(token.id()) && !job.signing().isDone()).count();
        if (running >= JOBS_PER_TOKEN) throw new EditorError(EditorError.Code.RATE_LIMITED, "At most " + JOBS_PER_TOKEN + " skins sign at once; wait for one to finish.", 2000, null);
        SkinSigner signer = parts.get().host().skinSigner();
        String id = UUID.randomUUID().toString().replace("-", "");
        jobs.put(id, new Job(token.id(), signer.sign(new SkinSigner.Request(source, slim != null && slim.getAsBoolean(), label)), label, System.nanoTime()));
        JsonObject response = new JsonObject();
        response.addProperty("job", id);
        response.addProperty("state", "signing");
        return CompletableFuture.completedFuture(EditorResponse.json(202, response));
    }

    CompletionStage<EditorResponse> status(EditorRequest request, EditorTokens.Token token, String[] segments) throws EditorError {
        String id = segments[2];
        Job job = id.matches(JOB) ? jobs.get(id) : null;
        if (job == null || !job.token().equals(token.id())) throw new EditorError(EditorError.Code.NOT_FOUND, "No skin signing job " + (id.length() > 40 ? id.substring(0, 40) : id) + ".");
        JsonObject response = new JsonObject();
        response.addProperty("job", id);
        if (!job.signing().isDone()) response.addProperty("state", "signing");
        else if (job.signing().isCompletedExceptionally()) {
            response.addProperty("state", "failed");
            response.addProperty("message", SkinSigner.message(job.signing().exceptionNow()));
        } else {
            response.addProperty("state", "signed");
            response.add("skin", document(job.signing().resultNow(), job.label()));
        }
        return CompletableFuture.completedFuture(EditorResponse.json(200, response));
    }

    /** The skin document the editor applies: {@code {id, source}}, the id being the SHA-256 of the written source. */
    static JsonObject document(SkinSigner.Signed signed, String label) {
        JsonObject source = new JsonObject();
        source.addProperty("textureRaw", signed.skin().textureValue());
        source.addProperty("signature", signed.skin().signature());
        if (!label.isBlank()) source.addProperty("label", label);
        JsonObject skin = new JsonObject();
        skin.addProperty("id", JsonDocuments.hash(JsonDocuments.write(source)));
        skin.add("source", source);
        return skin;
    }

    /** Forgets jobs that ended {@link #KEPT} ago, and any job older than an hour. */
    private void housekeeping() {
        long now = System.nanoTime();
        jobs.values().removeIf((Job job) -> now - job.started() > Duration.ofHours(1).toNanos() || job.signing().isDone() && now - job.started() > KEPT.toNanos());
    }
}
