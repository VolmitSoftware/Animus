package art.arcane.animus.core.editor;

import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.skin.MineSkin;
import art.arcane.animus.core.skin.SkinPng;
import art.arcane.animus.core.skin.SkinSigner;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.toolkit.SkinLookup;
import art.arcane.volmlib.util.editor.EditorTokens;
import art.arcane.volmlib.util.editor.transport.EditorRequest;
import art.arcane.volmlib.util.editor.transport.EditorResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code POST /v1/skins/sign} and {@code GET /v1/skins/sign/<job>}: a skin PNG or address signed through MineSkin, a skin
 * MineSkin already signed (its link or id), or a Minecraft account's skin by name, looked up as {@code /animus npc skin}
 * does. Lookups can take longer than one request may wait, so the POST answers 202 with a job and the editor polls it. A
 * finished job carries the skin document, which is not saved here: the editor applies it like any other change.
 */
final class EditorSkinRoutes {
    static final int JOBS_PER_TOKEN = 4;
    static final Duration KEPT = Duration.ofMinutes(10);
    private static final String JOB = "[a-f0-9]{32}";
    private static final List<String> SOURCES = List.of("image", "url", "player", "mineskin");

    private record Job(String token, CompletableFuture<JsonObject> skin, long started) {}

    private final Supplier<EditorApi.Parts> parts;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    EditorSkinRoutes(Supplier<EditorApi.Parts> parts) { this.parts = parts; }

    CompletionStage<EditorResponse> sign(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments) throws EditorError {
        JsonObject body = EditorMessages.body(request);
        EditorMessages.fields(body, "$", Set.of("image", "url", "player", "mineskin", "slim", "label"));
        if (SOURCES.stream().filter(body::has).count() != 1) throw EditorMessages.invalid("$: send exactly one of image, url, player or mineskin");
        JsonElement slim = body.get("slim");
        if (slim != null && (!slim.isJsonPrimitive() || !slim.getAsJsonPrimitive().isBoolean())) throw EditorMessages.invalid("$.slim: expected true or false");
        String label = EditorMessages.optionalString(body, "label", 64).map(String::strip).orElse("");
        if (label.chars().anyMatch(Character::isISOControl)) throw EditorMessages.invalid("$.label: one line of text");
        boolean thin = slim != null && slim.getAsBoolean();
        Supplier<CompletableFuture<JsonObject>> work;
        if (body.has("player")) {
            String name = typed(body, "player");
            if (!name.matches(SkinLookup.NAME)) throw EditorMessages.invalid(name + " is not a Minecraft name (1-16 letters, digits or _)");
            SkinLookup lookup = parts.get().host().skinLookup();
            work = () -> lookup.find(name).thenApply((SkinLookup.Found found) -> {
                if (found.skin().isEmpty()) throw new SkinSigner.Refused(found.problem());
                String spelled = found.name().orElse(name);
                return document(found.skin().get(), label.isEmpty() ? spelled : label, Optional.of(spelled));
            });
        } else if (body.has("mineskin")) {
            String typed = typed(body, "mineskin");
            if (MineSkin.skinId(typed).isEmpty()) throw EditorMessages.invalid("Not a MineSkin link or id");
            SkinSigner signer = parts.get().host().skinSigner();
            String named = label.isEmpty() ? SkinSigner.label(MineSkin.isLink(typed) ? typed : "https://minesk.in/" + typed) : label;
            work = () -> signer.find(typed).thenApply((SkinSigner.Signed signed) -> document(signed.skin(), named, Optional.empty()));
        } else {
            SkinSigner.Source source;
            if (body.has("image")) {
                String encoded = EditorMessages.string(body, "image", "[A-Za-z0-9+/=]{1,40000}", "$.image", "a base64 PNG");
                byte[] png;
                try { png = Base64.getDecoder().decode(encoded); }
                catch (IllegalArgumentException invalid) { throw EditorMessages.invalid("$.image: expected a base64 PNG"); }
                Optional<String> problem = SkinPng.problem(png);
                if (problem.isPresent()) throw EditorMessages.invalid(problem.get());
                source = new SkinSigner.Image(png);
            } else source = new SkinSigner.Address(EditorMessages.string(body, "url", "https?://\\S{1,248}", "$.url", "an http or https address"));
            SkinSigner signer = parts.get().host().skinSigner();
            work = () -> signer.sign(new SkinSigner.Request(source, thin, label)).thenApply((SkinSigner.Signed signed) -> document(signed.skin(), label, Optional.empty()));
        }
        housekeeping();
        long running = jobs.values().stream().filter((Job job) -> job.token().equals(token.id()) && !job.skin().isDone()).count();
        if (running >= JOBS_PER_TOKEN) throw new EditorError(EditorError.Code.RATE_LIMITED, "At most " + JOBS_PER_TOKEN + " skins sign at once; wait for one to finish.", 2000, null);
        String id = UUID.randomUUID().toString().replace("-", "");
        jobs.put(id, new Job(token.id(), work.get(), System.nanoTime()));
        JsonObject response = new JsonObject();
        response.addProperty("job", id);
        response.addProperty("state", "signing");
        return CompletableFuture.completedFuture(EditorResponse.json(202, response));
    }

    /** The text at {@code key}: one line of at most 256 characters. */
    private static String typed(JsonObject body, String key) throws EditorError {
        JsonElement value = body.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw EditorMessages.invalid("$." + key + ": expected text");
        String text = value.getAsString().strip();
        if (text.isEmpty() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) throw EditorMessages.invalid("$." + key + ": expected one line of at most 256 characters");
        return text;
    }

    CompletionStage<EditorResponse> status(EditorRequest request, EditorTokens.Token<EditorScope> token, String[] segments) throws EditorError {
        String id = segments[2];
        Job job = id.matches(JOB) ? jobs.get(id) : null;
        if (job == null || !job.token().equals(token.id())) throw new EditorError(EditorError.Code.NOT_FOUND, "No skin signing job " + (id.length() > 40 ? id.substring(0, 40) : id) + ".");
        JsonObject response = new JsonObject();
        response.addProperty("job", id);
        if (!job.skin().isDone()) response.addProperty("state", "signing");
        else if (job.skin().isCompletedExceptionally()) {
            response.addProperty("state", "failed");
            response.addProperty("message", SkinSigner.message(job.skin().exceptionNow()));
        } else {
            response.addProperty("state", "signed");
            response.add("skin", job.skin().resultNow());
        }
        return CompletableFuture.completedFuture(EditorResponse.json(200, response));
    }

    /**
     * The skin document the editor applies: {@code {id, source}}, the id being the SHA-256 of the written source. A skin
     * looked up by name keeps the account in {@code player}, which Follow player looks up again.
     */
    static JsonObject document(BodySkin skin, String label, Optional<String> player) {
        JsonObject source = new JsonObject();
        source.addProperty("textureRaw", skin.textureValue());
        source.addProperty("signature", skin.signature());
        if (!label.isBlank()) source.addProperty("label", label);
        player.ifPresent((String name) -> source.addProperty("player", name));
        JsonObject result = new JsonObject();
        result.addProperty("id", JsonDocuments.hash(JsonDocuments.write(source)));
        result.add("source", source);
        return result;
    }

    /** Forgets jobs that ended {@link #KEPT} ago, and any job older than an hour. */
    private void housekeeping() {
        long now = System.nanoTime();
        jobs.values().removeIf((Job job) -> now - job.started() > Duration.ofHours(1).toNanos() || job.skin().isDone() && now - job.started() > KEPT.toNanos());
    }
}
