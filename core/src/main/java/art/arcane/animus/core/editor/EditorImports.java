package art.arcane.animus.core.editor;

import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The editor side of {@code /animus import citizens}: uploads land where the drop-in flow looks first, and stage, apply,
 * accept and rollback run the same {@link CitizensDropIn} steps as the command, so the API and the console always agree.
 * Every answer is an {@code import-review}. Call on the file lane.
 */
public final class EditorImports {
    /** What an upload is, and where it is written under the data folder. */
    public enum Upload {
        CITIZENS("citizens", "imports/saves.yml"),
        SHOPKEEPERS("shopkeepers", "imports/shopkeepers/save.yml"),
        SHOPKEEPERS_CONFIG("shopkeepers-config", "imports/shopkeepers/config.yml"),
        DTLTRADERS("dtltraders", "imports/dtltraders");

        private final String wireName, path;

        Upload(String wireName, String path) { this.wireName = wireName; this.path = path; }

        public String wireName() { return wireName; }

        public static Optional<Upload> parse(String wireName) {
            for (Upload upload : values()) if (upload.wireName.equals(wireName)) return Optional.of(upload);
            return Optional.empty();
        }
    }

    /** dtlTraders files the importer reads, by their path inside the dtlTraders data folder. */
    static final String TRADER_FILE = "(?:guis|config|messages)\\.yml|data\\.dat|shops/([A-Za-z0-9_.-]{1,64})/\\1\\.yml";
    static final int MAXIMUM_LINES = 200, MAXIMUM_ISSUES = 100;
    private static final Set<String> CITIZENS_REPORT = Set.of("importId", "converterVersion", "notice", "records", "valid", "skins", "behaviors", "conversions",
            "traits", "types", "issues", "migrationWarnings", "remappedLocations", "resolvedReferences", "unresolvedReferences", "worldMap");
    private static final String CHAT_PREFIX = RichText.plain(Palette.PREFIX);
    private static final Set<String> SHOP_REPORT = Set.of("importId", "importerVersion", "notice", "shops", "npcs", "types", "offers", "entries", "links", "currency", "issues");

    private final DocumentStore store;
    private final Supplier<NpcRepository> npcs;
    private final Supplier<CitizensDropIn.Host> host;

    public EditorImports(DocumentStore store, Supplier<NpcRepository> npcs, Supplier<CitizensDropIn.Host> host) {
        this.store = Objects.requireNonNull(store);
        this.npcs = Objects.requireNonNull(npcs);
        this.host = Objects.requireNonNull(host);
    }

    private CitizensDropIn dropIn() { return new CitizensDropIn(store, npcs.get(), host.get()); }

    /** The relative path an upload is written to; {@code name} is the file inside the dtlTraders folder. */
    public static String target(Upload upload, Optional<String> name) throws EditorError {
        if (upload != Upload.DTLTRADERS) {
            if (name.isPresent()) throw EditorMessages.invalid("Only dtltraders uploads take a name.");
            return upload.path;
        }
        String file = name.orElseThrow(() -> EditorMessages.invalid("A dtltraders upload needs name=, such as guis.yml or shops/baker/baker.yml."));
        if (!file.matches(TRADER_FILE)) throw EditorMessages.invalid("name= is guis.yml, config.yml, messages.yml, data.dat or shops/<shop>/<shop>.yml.");
        return upload.path + "/" + file;
    }

    /** Writes the upload and answers the review, which lists the sources the next stage will read. */
    public JsonObject upload(Upload upload, Optional<String> name, byte[] bytes) throws EditorError, IOException {
        String relative = target(upload, name);
        JsonObject before = dropIn().status();
        if (before.get("state").getAsString().equals("applied"))
            throw new EditorError(EditorError.Code.CONFLICT, "An import is applied. Roll it back before uploading another save.");
        Path file = store.resolve(relative);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".animus-upload-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        List<String> lines = List.of("Uploaded " + bytes.length + " bytes to " + relative + ". Stage the import to convert it; nothing is installed.");
        return review(lines);
    }

    /** {@code /animus import citizens preview [mode=] [copy=] [worlds=]}. */
    public JsonObject stage(String mode, Optional<String> label, Map<String, String> worlds) throws EditorError, IOException {
        for (Map.Entry<String, String> world : worlds.entrySet())
            if (world.getKey().isBlank() || world.getValue().isBlank()) throw EditorMessages.invalid("Invalid world mapping: " + world.getKey() + "=" + world.getValue());
        return run((CitizensDropIn dropIn) -> dropIn.preview(mode, label, worlds, false), EditorError.Code.INVALID_REQUEST);
    }

    public JsonObject apply(String importId) throws EditorError, IOException { expect(importId, false); return run(CitizensDropIn::applyStaged, EditorError.Code.CONFLICT); }

    /** The command namespaces applying this staged import adds to the allow-list; see {@link CitizensDropIn#commandGrants()}. */
    public List<String> commandGrants(String importId) throws EditorError, IOException { expect(importId, false); return dropIn().commandGrants(); }

    /** The documents applying this staged import writes, by data folder path; nothing is installed. */
    public Map<String, String> stagedWrites(String importId) throws EditorError, IOException {
        expect(importId, false);
        try { return dropIn().stagedWrites(); }
        catch (IOException refused) { throw new EditorError(EditorError.Code.CONFLICT, refused.getMessage() == null ? "The staged import cannot be read." : clip(refused.getMessage(), 2048)); }
    }

    public JsonObject rollback(String importId) throws EditorError, IOException { expect(importId, true); return run(CitizensDropIn::rollbackApplied, EditorError.Code.CONFLICT); }

    /** The current state, with the review lines once applied. */
    public JsonObject status() throws IOException {
        JsonObject status = dropIn().status();
        if (!status.get("state").getAsString().equals("applied")) return review(List.of());
        try { return review(dropIn().reviewApplied()); }
        catch (IOException refused) { return review(List.of(refused.getMessage())); }
    }

    /** The import id in the path must be the staged Citizens or Shopkeepers import, so a stale review is never applied. */
    private void expect(String importId, boolean applied) throws EditorError, IOException {
        JsonObject status = dropIn().status();
        String state = status.get("state").getAsString();
        if (applied && !state.equals("applied")) throw new EditorError(EditorError.Code.CONFLICT, "No import is applied.");
        if (!applied && state.equals("none")) throw new EditorError(EditorError.Code.CONFLICT, "Nothing is staged. Upload a save and stage it first.");
        for (String part : List.of("citizens", "shopkeepers"))
            if (status.has(part) && status.getAsJsonObject(part).get("importId").getAsString().equals(importId)) return;
        throw new EditorError(EditorError.Code.CONFLICT, "Import " + importId + " is not the staged import; reload the review.");
    }

    @FunctionalInterface private interface Step { List<String> run(CitizensDropIn dropIn) throws IOException; }

    private JsonObject run(Step step, EditorError.Code refusal) throws EditorError, IOException {
        List<String> lines;
        try { lines = step.run(dropIn()); }
        catch (art.arcane.animus.core.migration.CitizensImporter.OutdatedPreview outdated) {
            throw new EditorError(refusal, clip(outdated.next("upload and stage the save again, then apply").getMessage(), 2048));
        }
        catch (IOException refused) { throw new EditorError(refusal, refused.getMessage() == null ? "The import step was refused." : clip(refused.getMessage(), 2048)); }
        return review(lines);
    }

    private JsonObject review(List<String> lines) throws IOException {
        JsonObject review = dropIn().status();
        JsonArray shown = new JsonArray();
        lines.stream().limit(MAXIMUM_LINES).forEach((String line) -> shown.add(clip(plain(line), 2048)));
        review.add("lines", shown);
        JsonObject sources = new JsonObject();
        for (Upload upload : Upload.values()) {
            Path file = store.resolve(upload.path);
            if (upload == Upload.DTLTRADERS ? Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) : Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                sources.addProperty(upload.wireName, upload.path);
        }
        review.add("uploads", sources);
        JsonObject reports = new JsonObject();
        report(review, "citizens", CITIZENS_REPORT).ifPresent((JsonObject report) -> reports.add("citizens", report));
        report(review, "shopkeepers", SHOP_REPORT).ifPresent((JsonObject report) -> reports.add("shopkeepers", report));
        report(review, "dtltraders", SHOP_REPORT).ifPresent((JsonObject report) -> reports.add("dtltraders", report));
        review.add("reports", reports);
        return review;
    }

    /** The parts of a staged report.json the review shows; issue lists are cut to {@link #MAXIMUM_ISSUES}. */
    private Optional<JsonObject> report(JsonObject review, String part, Set<String> keys) throws IOException {
        if (!review.has(part)) return Optional.empty();
        String text = store.read(review.getAsJsonObject(part).get("report").getAsString());
        if (text == null) return Optional.empty();
        JsonObject full = JsonDocuments.object(text), summary = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : full.entrySet()) {
            if (!keys.contains(entry.getKey())) continue;
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && value.getAsJsonArray().size() > MAXIMUM_ISSUES) {
                JsonArray cut = new JsonArray();
                for (int index = 0; index < MAXIMUM_ISSUES; index++) cut.add(value.getAsJsonArray().get(index));
                summary.add(entry.getKey(), cut);
                summary.addProperty(entry.getKey() + "Total", value.getAsJsonArray().size());
            } else summary.add(entry.getKey(), value);
        }
        return Optional.of(summary);
    }

    /** The drop-in lines are written for chat; the editor shows the words without colour codes or the chat prefix. */
    static String plain(String line) {
        String text = RichText.plain(line);
        return text.startsWith(CHAT_PREFIX) ? text.substring(CHAT_PREFIX.length()) : text;
    }

    private static String clip(String text, int length) { return text.length() > length ? text.substring(0, length) : text; }
}
