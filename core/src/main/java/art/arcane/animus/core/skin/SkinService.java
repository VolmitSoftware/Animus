package art.arcane.animus.core.skin;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.config.SkinSettings;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.Placeholders;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.toolkit.SkinFiles;
import art.arcane.animus.core.toolkit.SkinLookup;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Skin work the server does by itself, off its main thread: unsigned skins NPCs wear are signed through MineSkin,
 * {@code animus:skin fromName} NPCs without a skin get the skin of the account named like them, and {@code refresh}
 * NPCs follow their player's skin. Each change is one transaction with Undo and one console line.
 */
public final class SkinService implements AutoCloseable {
    public static final long SCAN_SECONDS = 15;
    static final Duration LOOKUP_SPACING = Duration.ofSeconds(2), FOUND_FOR = Duration.ofMinutes(5), MISSING_RETRY = Duration.ofHours(6),
            UNREACHABLE_RETRY = Duration.ofMinutes(5), SIGN_RETRY = Duration.ofMinutes(30);

    /**
     * @param fill fills a placeholder skin source such as {@code %top_player%} for no viewer, on the server's main thread
     * @param spacing the wait between two Mojang lookups
     */
    public record Parts(Supplier<NpcRepository> npcs, Supplier<DocumentStore> documents, Executor files, Runnable refresh, Supplier<SkinSettings> settings,
                        SkinLookup lookup, SkinSigner signer, Function<String, CompletableFuture<Optional<String>>> fill, Consumer<String> log, LongSupplier clock,
                        Duration spacing) {}

    private record Unsigned(String hash, SkinFile file) {}
    private record Follower(String id, Optional<String> url) {}
    private record Plan(List<Unsigned> unsigned, Map<String, List<String>> named, Map<String, List<Follower>> following) {}
    private record Kept(SkinLookup.Found found, long at) {}

    private final Parts parts;
    /** Skin hashes decided for good: signed, or unsigned with nothing to sign from. A hash names one content forever. */
    private final Set<String> settled = ConcurrentHashMap.newKeySet();
    /** NPC ids seen following their player at the last scan, so one that starts following is checked at the next. */
    private final Set<String> following = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet(), noted = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<String, Kept> found = new ConcurrentHashMap<>();
    private final Map<String, SkinFile> files = new ConcurrentHashMap<>();
    private CompletableFuture<Void> lookups = CompletableFuture.completedFuture(null);
    private volatile long lastRefresh;
    private volatile boolean closed;
    private ScheduledExecutorService timers;

    public SkinService(Parts parts) {
        this.parts = parts;
        lastRefresh = parts.clock().getAsLong();
    }

    /**
     * The service of a running server: MineSkin over the network with {@code [skins] mineskinKey}, Mojang's profile
     * lookups, and the scans scheduled on its own daemon thread. The signer is also handed to {@code runtime} for the
     * web editor and the skin commands.
     */
    public static SkinService start(AnimusRuntime runtime, Executor files, Runnable refresh, Function<String, CompletableFuture<Optional<String>>> fill, Consumer<String> log) {
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Animus-skins").factory());
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        MineSkin signer = new MineSkin(MineSkin.http(http), () -> runtime.skinSettings().mineskinKey(), BuildIdentity.NAME + "/" + BuildIdentity.VERSION, timers, System::currentTimeMillis);
        runtime.skinSigner(signer);
        SkinService service = new SkinService(new Parts(runtime::npcs, runtime::documents, files, refresh, runtime::skinSettings, SkinLookup.shared(), signer, fill, log,
                System::currentTimeMillis, LOOKUP_SPACING));
        service.timers = timers;
        timers.scheduleWithFixedDelay(service::tick, 5, SCAN_SECONDS, TimeUnit.SECONDS);
        return service;
    }

    /** No placeholder plugin: placeholder skin sources stay unfilled. */
    public static CompletableFuture<Optional<String>> unfilled(String text) { return CompletableFuture.completedFuture(Optional.empty()); }

    @Override public void close() {
        closed = true;
        if (timers != null) timers.shutdownNow();
    }

    private void tick() {
        if (closed) return;
        try {
            scan();
            long every = Duration.ofMinutes(parts.settings().get().refreshMinutes()).toMillis();
            if (parts.clock().getAsLong() - lastRefresh >= every) refresh();
        } catch (RuntimeException failure) { parts.log().accept("Skin check failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
    }

    /** One scan: signs the unsigned skins worn, gives fromName NPCs their skin, and checks NPCs that just started following. */
    CompletableFuture<Void> scan() {
        return files(() -> plan(false)).thenCompose((Plan plan) -> {
            List<CompletableFuture<Void>> work = new ArrayList<>();
            for (Unsigned unsigned : plan.unsigned()) work.add(sign(unsigned));
            plan.named().forEach((String name, List<String> ids) -> work.add(fromName(name, ids)));
            plan.following().forEach((String source, List<Follower> followers) -> work.add(follow(source, followers)));
            return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
        });
    }

    /** The full refresh: every NPC that follows its player is checked. */
    CompletableFuture<Void> refresh() {
        lastRefresh = parts.clock().getAsLong();
        return files(() -> plan(true)).thenCompose((Plan plan) -> {
            List<CompletableFuture<Void>> work = new ArrayList<>();
            plan.following().forEach((String source, List<Follower> followers) -> work.add(follow(source, followers)));
            return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
        });
    }

    /** What to do, read on the file lane: {@code everyone} lists every follower, else only the ones new since the last scan. */
    private Plan plan(boolean everyone) throws IOException {
        NpcRepository npcs = parts.npcs().get();
        DocumentStore store = parts.documents().get();
        long now = parts.clock().getAsLong();
        Set<String> worn = new LinkedHashSet<>();
        Map<String, List<String>> named = new LinkedHashMap<>();
        Map<String, List<Follower>> follows = new LinkedHashMap<>();
        Set<String> followingNow = new java.util.HashSet<>();
        for (NpcDefinition npc : npcs.all()) {
            JsonObject json = npc.json();
            if (!BodyRules.PLAYER.equals(string(json, "type"))) continue;
            Optional<SkinOptions> options;
            try { options = SkinOptions.parse(json); }
            catch (IllegalArgumentException invalid) { options = Optional.empty(); }
            String skin = string(json, "skin");
            if (skin != null && skin.matches("[a-f0-9]{64}")) {
                worn.add(skin);
                if (options.isPresent() && options.get().refresh()) {
                    followingNow.add(npc.id());
                    if (!everyone && following.contains(npc.id())) continue;
                    SkinFile file = file(store, skin);
                    Optional<String> source = file == null ? Optional.empty() : file.player();
                    if (source.isEmpty() && options.get().fromName()) source = plainName(npc);
                    if (source.isEmpty()) { note("follow-nothing:" + npc.id(), "NPC " + npc.id() + " follows its player's skin, but its skin did not come from a player name; set it by name to follow one"); continue; }
                    follows.computeIfAbsent(source.get(), (String key) -> new ArrayList<>()).add(new Follower(npc.id(), file == null ? Optional.empty() : file.url()));
                }
            } else if (options.isPresent() && options.get().fromName()) {
                Optional<String> name = plainName(npc).filter((String plain) -> plain.matches(SkinLookup.NAME));
                if (name.isEmpty()) { note("from-name:" + npc.id(), "NPC " + npc.id() + " takes its skin from its name, but '" + RichText.plain(string(json, "name") == null ? "" : string(json, "name")) + "' is not a Minecraft name"); continue; }
                String key = "name:" + name.get().toLowerCase(Locale.ROOT);
                if (busy.contains(key) || waiting(key, now)) continue;
                named.computeIfAbsent(name.get(), (String ignored) -> new ArrayList<>()).add(npc.id());
            }
        }
        following.retainAll(followingNow);
        following.addAll(followingNow);
        for (GroupDocument group : npcs.groups().groups().values()) {
            JsonObject values = group.values();
            String skin = string(values, "skin");
            if (skin != null) worn.add(skin);
            JsonElement pool = values.get("skins");
            if (pool != null && pool.isJsonArray()) for (JsonElement entry : pool.getAsJsonArray()) if (entry.isJsonPrimitive()) worn.add(entry.getAsString());
        }
        List<Unsigned> unsigned = new ArrayList<>();
        for (String hash : worn) {
            if (!hash.matches("[a-f0-9]{64}") || settled.contains(hash) || busy.contains("sign:" + hash) || waiting("sign:" + hash, now)) continue;
            SkinFile file = file(store, hash);
            if (file == null) continue;
            if (file.signed()) { settled.add(hash); continue; }
            if (!file.signable()) {
                settled.add(hash);
                parts.log().accept("Skin " + name(file, hash) + " is unsigned, so players see the default skin; it keeps no PNG to sign from: upload it again through a paired web editor, or set it by player name");
                continue;
            }
            unsigned.add(new Unsigned(hash, file));
        }
        return new Plan(unsigned, named, follows);
    }

    /** Signs one unsigned skin and moves everything wearing it to the signed file. */
    private CompletableFuture<Void> sign(Unsigned unsigned) {
        String key = "sign:" + unsigned.hash();
        if (!busy.add(key)) return CompletableFuture.completedFuture(null);
        SkinFile file = unsigned.file();
        SkinSigner.Source source = file.image().<SkinSigner.Source>map(SkinSigner.Image::new).orElseGet(() -> new SkinSigner.Address(file.url().orElseThrow()));
        if (!parts.settings().get().keyed()) note("anonymous", "Signing skins through MineSkin without an API key, at most 10 a minute; set [skins] mineskinKey in config.toml for more");
        return parts.signer().sign(new SkinSigner.Request(source, file.slim(), file.name().orElse(""))).handle((SkinSigner.Signed signed, Throwable failure) -> {
            if (failure == null) return files(() -> signedWrite(unsigned, signed));
            busy.remove(key);
            retryAt.put(key, parts.clock().getAsLong() + SIGN_RETRY.toMillis());
            Throwable cause = failure.getCause() != null && !(failure instanceof SkinSigner.Refused) ? failure.getCause() : failure;
            parts.log().accept("Skin " + name(file, unsigned.hash()) + " is unsigned, so players see the default skin; " + cause.getMessage() + "; trying again in " + SIGN_RETRY.toMinutes() + " minutes");
            return CompletableFuture.completedFuture((Void) null);
        }).thenCompose(Function.identity()).whenComplete((Void ignored, Throwable failure) -> busy.remove(key));
    }

    private Void signedWrite(Unsigned unsigned, SkinSigner.Signed signed) throws IOException {
        DocumentStore store = parts.documents().get();
        String text = store.read("skins/" + unsigned.hash() + ".json");
        if (text == null) return null;
        JsonObject document = JsonDocuments.object(text);
        document.addProperty("textureRaw", signed.skin().textureValue());
        document.addProperty("signature", signed.skin().signature());
        document.remove("image");
        SkinFiles.Moved moved = new SkinFiles(parts.npcs().get(), store).replace(unsigned.hash(), JsonDocuments.write(document));
        settled.add(unsigned.hash());
        settled.add(moved.hash());
        parts.refresh().run();
        parts.log().accept("Signed skin " + name(unsigned.file(), unsigned.hash()) + " through MineSkin, so players see it; now " + moved.hash().substring(0, 12)
                + moved.carried(parts.npcs().get()) + "; tx " + moved.tx());
        return null;
    }

    /** Looks the name up and puts its skin on the NPCs named like it that still wear none. */
    private CompletableFuture<Void> fromName(String name, List<String> ids) {
        String key = "name:" + name.toLowerCase(Locale.ROOT);
        if (!busy.add(key)) return CompletableFuture.completedFuture(null);
        return look(name).thenCompose((SkinLookup.Found answer) -> {
            if (answer.skin().isEmpty()) {
                missed(key, answer, "NPC " + String.join(", ", ids) + " takes its skin from its name, but " + answer.problem());
                return CompletableFuture.completedFuture((Void) null);
            }
            String spelled = answer.name().orElse(name);
            return files(() -> {
                NpcRepository npcs = parts.npcs().get();
                List<String> bare = ids.stream().filter((String id) -> npcs.find(id).map((NpcDefinition npc) -> !npc.json().has("skin")).orElse(false)).toList();
                if (bare.isEmpty()) return null;
                String line = new SkinFiles(npcs, parts.documents().get()).wear(answer.skin().get(), spelled, Optional.of(spelled), bare);
                parts.refresh().run();
                parts.log().accept("Took the skin from the NPC's name: " + line);
                return null;
            });
        }).whenComplete((Void ignored, Throwable failure) -> busy.remove(key));
    }

    /** Looks the source up again and moves the followers whose skin differs from the account's now. */
    private CompletableFuture<Void> follow(String source, List<Follower> followers) {
        String key = "follow:" + source.toLowerCase(Locale.ROOT);
        if (!busy.add(key)) return CompletableFuture.completedFuture(null);
        return account(source).thenCompose((Optional<String> name) -> {
            if (name.isEmpty()) {
                note("unfilled:" + source, "Skin source " + source + " did not fill to a Minecraft name" + (Placeholders.marked(source) ? "; is PlaceholderAPI installed?" : ""));
                return CompletableFuture.completedFuture((Void) null);
            }
            return look(name.get()).thenCompose((SkinLookup.Found answer) -> {
                if (answer.skin().isEmpty()) {
                    missed("follow-name:" + name.get().toLowerCase(Locale.ROOT), answer, "Could not refresh the skin of " + name.get() + ": " + answer.problem());
                    return CompletableFuture.completedFuture((Void) null);
                }
                Optional<String> now = SkinFile.texture(answer.skin().get().textureValue()).url();
                List<String> stale = followers.stream().filter((Follower follower) -> now.isEmpty() || !now.equals(follower.url())).map(Follower::id).toList();
                if (stale.isEmpty()) return CompletableFuture.completedFuture((Void) null);
                String spelled = answer.name().orElse(name.get());
                return files(() -> {
                    String line = new SkinFiles(parts.npcs().get(), parts.documents().get()).wear(answer.skin().get(), spelled, Optional.of(source), stale);
                    parts.refresh().run();
                    parts.log().accept(spelled + " changed their skin: " + line);
                    return null;
                });
            });
        }).whenComplete((Void ignored, Throwable failure) -> busy.remove(key));
    }

    /** The account a skin source names: the name itself, or a placeholder filled for no viewer. */
    private CompletableFuture<Optional<String>> account(String source) {
        if (!Placeholders.marked(source)) return CompletableFuture.completedFuture(Optional.of(source).filter((String name) -> name.matches(SkinLookup.NAME)));
        return parts.fill().apply(source).thenApply((Optional<String> filled) -> filled.map((String text) -> RichText.plain(text).strip()).filter((String name) -> name.matches(SkinLookup.NAME)));
    }

    /** One Mojang lookup, queued behind the others and {@link Parts#spacing} after the last; answers are kept {@link #FOUND_FOR}. */
    private synchronized CompletableFuture<SkinLookup.Found> look(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Kept kept = found.get(key);
        if (kept != null && parts.clock().getAsLong() - kept.at() < FOUND_FOR.toMillis()) return CompletableFuture.completedFuture(kept.found());
        CompletableFuture<SkinLookup.Found> answer = lookups.thenCompose((Void ignored) -> parts.lookup().find(name)).thenApply((SkinLookup.Found result) -> {
            if (!result.unreachable()) found.put(key, new Kept(result, parts.clock().getAsLong()));
            return result;
        });
        long spacing = parts.spacing().toMillis();
        CompletableFuture<Void> done = answer.handle((SkinLookup.Found ignored, Throwable failure) -> (Void) null);
        lookups = spacing <= 0 ? done : done.thenCompose((Void ignored) -> CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(spacing, TimeUnit.MILLISECONDS)));
        return answer;
    }

    /** A lookup that found no skin: logged once, and asked again after a while. */
    private void missed(String key, SkinLookup.Found answer, String line) {
        retryAt.put(key, parts.clock().getAsLong() + (answer.unreachable() ? UNREACHABLE_RETRY : MISSING_RETRY).toMillis());
        note(key + ":" + answer.problem(), line);
    }

    private boolean waiting(String key, long now) {
        Long at = retryAt.get(key);
        return at != null && now < at;
    }

    private void note(String key, String line) { if (noted.add(key)) parts.log().accept(line); }

    /** A skin file, parsed once per hash; null when it is missing or unreadable. */
    private SkinFile file(DocumentStore store, String hash) throws IOException {
        SkinFile known = files.get(hash);
        if (known != null) return known;
        String text = store.read("skins/" + hash + ".json");
        if (text == null) return null;
        try {
            SkinFile parsed = SkinFile.parse(text);
            files.put(hash, parsed);
            return parsed;
        } catch (IOException unreadable) {
            note("unreadable:" + hash, "Skin " + hash.substring(0, 12) + " is unreadable: " + unreadable.getMessage());
            return null;
        }
    }

    private static Optional<String> plainName(NpcDefinition npc) {
        String name = string(npc.json(), "name");
        return name == null ? Optional.empty() : Optional.of(RichText.plain(name).strip()).filter((String plain) -> !plain.isEmpty());
    }

    private static String name(SkinFile file, String hash) { return file.name().orElse(hash.substring(0, 12)); }

    @FunctionalInterface private interface FileWork<T> { T run() throws IOException; }

    /** Work on the file lane; a full or closed lane fails the work, which the next scan tries again. */
    private <T> CompletableFuture<T> files(FileWork<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) { result.cancel(false); return result; }
        try {
            parts.files().execute(() -> {
                try { result.complete(work.run()); }
                catch (IOException | RuntimeException failure) {
                    parts.log().accept("Skin file work failed: " + failure.getMessage());
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        return result;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
