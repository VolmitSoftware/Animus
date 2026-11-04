package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local, loss-preserving conversion. Conversion status describes data, never gameplay parity. */
public final class CitizensImporter {
    public enum Status { CONVERTED, REQUIRES_INTEGRATION, REQUIRES_REVIEW, UNSUPPORTED_PRESERVED }
    public record Preview(String id, Path directory, int records, int valid, int skins, JsonObject summary) {
        public Preview { summary = summary.deepCopy(); }
        @Override public JsonObject summary() { return summary.deepCopy(); }
    }
    private static final Set<String> INTEGRATIONS = Set.of("sentinel", "denizen", "assignment", "denizen_flags", "denizenflags", "triggers", "constants");
    private static final Set<String> REVIEW = Set.of("skintrait", "location", "owner", "equipment", "inventory", "waypoints", "text", "hologramtrait", "poses", "lookclose", "type", "spawned", "mounttrait", "controllable");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public Preview preview(Path source, Path destination, Map<String, String> worldMap) throws IOException {
        try (DocumentStore store = new DocumentStore(destination)) { return preview(source, store, worldMap); }
    }

    public Preview preview(Path source, DocumentStore store, Map<String, String> worldMap) throws IOException {
        String original = JsonDocuments.read(source);
        JsonObject options = new JsonObject();
        new TreeMap<>(worldMap).forEach(options::addProperty);
        String id = JsonDocuments.hash("animus-citizens-v1\n" + JsonDocuments.hash(original) + "\n" + options);
        {
            Path imports = store.resolve("imports");
            Files.createDirectories(imports);
            Path staged = store.resolve("imports/" + id);
            if (Files.exists(staged)) return readPreview(staged, id);
            JsonObject root = parseYaml(original);
            if (!root.has("npc") || !root.get("npc").isJsonObject()) throw new IOException("$.npc: expected Citizens NPC mapping");
            Path temporary = Files.createTempDirectory(imports, ".preview-");
            boolean published = false;
            try {
                DocumentStore.atomic(temporary.resolve("source.yml"), original);
                JsonObject summary = new JsonObject();
                summary.addProperty("schemaVersion", 1);
                summary.addProperty("converterVersion", 1);
                summary.addProperty("importId", id);
                summary.addProperty("sourceSha256", JsonDocuments.hash(original));
                summary.add("worldMap", options);
                summary.addProperty("notice", "Data conversion only. Rendering, movement, behavior and combat require separate runtime validation. Imported NPCs are staged with spawned=false; Sentinel is disabled.");
                JsonObject mapping = new JsonObject();
                JsonObject counts = new JsonObject();
                JsonObject types = new JsonObject();
                JsonObject sentinelFields = new JsonObject();
                JsonArray issues = new JsonArray();
                JsonObject manifest = new JsonObject();
                Set<UUID> uuids = new HashSet<>();
                Set<Integer> numericIds = new HashSet<>();
                int valid = 0;
                Set<String> skins = new HashSet<>();
                for (var npc : root.getAsJsonObject("npc").entrySet()) {
                    String key = npc.getKey();
                    if (!key.matches("0|[1-9][0-9]{0,9}")) throw new IOException("$.npc." + key + ": invalid numeric ID");
                    int numeric;
                    try { numeric = Integer.parseInt(key); }
                    catch (NumberFormatException e) { throw new IOException("$.npc." + key + ": ID exceeds integer range", e); }
                    if (!numericIds.add(numeric) || !npc.getValue().isJsonObject()) throw new IOException("Invalid or duplicate NPC " + key);
                    JsonObject record = npc.getValue().getAsJsonObject();
                    JsonObject traits = record.has("traits") && record.get("traits").isJsonObject() ? record.getAsJsonObject("traits") : new JsonObject();
                    for (String trait : traits.keySet()) increment(counts, trait);
                    if (traits.has("sentinel") && traits.get("sentinel").isJsonObject())
                        traits.getAsJsonObject("sentinel").keySet().forEach(field -> increment(sentinelFields, field));
                    String type = scalar(traits, "type", "PLAYER").toLowerCase(Locale.ROOT);
                    increment(types, type);
                    JsonObject definition = new JsonObject();
                    definition.addProperty("schemaVersion", 1);
                    definition.addProperty("revision", 1);
                    String uuid = scalar(record, "uuid", UUID.nameUUIDFromBytes(("animus:citizens:" + key).getBytes(StandardCharsets.UTF_8)).toString());
                    definition.addProperty("uuid", uuid);
                    definition.addProperty("id", "citizens-" + key);
                    definition.addProperty("group", "citizens");
                    definition.addProperty("name", scalar(record, "name", "Citizen " + key));
                    definition.addProperty("type", type.contains(":") ? type : "minecraft:" + type);
                    definition.addProperty("spawned", false);
                    JsonObject migration = new JsonObject();
                    migration.addProperty("importId", id);
                    migration.addProperty("citizensId", numeric);
                    migration.add("originalSpawned", traits.has("spawned") ? traits.get("spawned").deepCopy() : JsonNull.INSTANCE);
                    definition.add("migration", migration);
                    JsonObject position = new JsonObject();
                    JsonObject location = traits.has("location") && traits.get("location").isJsonObject() ? traits.getAsJsonObject("location") : new JsonObject();
                    String world = scalar(location, "world", "");
                    position.addProperty("world", worldMap.getOrDefault(world, world));
                    for (String coordinate : List.of("x", "y", "z", "yaw", "pitch"))
                        position.add(coordinate, location.has(coordinate) ? location.get(coordinate).deepCopy() : (Set.of("yaw", "pitch").contains(coordinate) ? new JsonPrimitive(0) : JsonNull.INSTANCE));
                    definition.add("position", position);
                    JsonObject modules = new JsonObject();
                    if (traits.has("sentinel")) {
                        JsonObject combat = new JsonObject();
                        combat.addProperty("enabled", false);
                        combat.addProperty("reviewAccepted", false);
                        combat.add("preservedSettings", traits.get("sentinel").deepCopy());
                        modules.add("animus:combat", combat);
                    }
                    // Preserve the original representation for adapters that have not been implemented yet.
                    JsonObject preserved = traits.deepCopy();
                    preserved.remove("skintrait");
                    modules.add("citizens:preserved", preserved);
                    definition.add("modules", modules);
                    if (traits.has("skintrait")) {
                        JsonElement skin = traits.get("skintrait");
                        String serialized = JsonDocuments.write(skin);
                        String hash = JsonDocuments.hash(serialized);
                        definition.addProperty("skin", hash);
                        skins.add(hash);
                        writeBundle(temporary, "skins/" + hash + ".json", serialized, manifest);
                    }
                    JsonObject report = new JsonObject();
                    report.addProperty("citizensId", numeric);
                    JsonObject fields = new JsonObject();
                    classify(record, "$", fields);
                    report.add("fields", fields);
                    List<String> errors = new ArrayList<>(NpcDefinition.validate(definition));
                    try { if (!uuids.add(UUID.fromString(uuid))) errors.add("$.uuid: duplicate NPC UUID"); }
                    catch (IllegalArgumentException e) { /* already diagnosed by validator */ }
                    report.add("validationErrors", GSON.toJsonTree(errors));
                    if (!errors.isEmpty()) issues.add("citizens-" + key + ": " + String.join("; ", errors));
                    else valid++;
                    mapping.addProperty(key, uuid);
                    writeBundle(temporary, "npcs/citizens/citizens-" + key + ".json", JsonDocuments.write(definition), manifest);
                    DocumentStore.atomic(temporary.resolve("records/" + key + ".json"), JsonDocuments.write(record));
                    DocumentStore.atomic(temporary.resolve("reports/" + key + ".json"), JsonDocuments.write(report));
                }
                summary.addProperty("records", numericIds.size());
                summary.addProperty("valid", valid);
                summary.addProperty("skins", skins.size());
                summary.add("traits", counts);
                summary.add("types", types);
                summary.add("sentinelFields", sentinelFields);
                summary.add("issues", issues);
                summary.add("idMap", mapping);
                summary.add("documents", manifest);
                DocumentStore.atomic(temporary.resolve("report.json"), JsonDocuments.write(summary));
                Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                return new Preview(id, staged, numericIds.size(), valid, skins.size(), summary);
            } finally {
                if (!published) deleteTemporary(temporary);
            }
        }
    }

    public Preview readPreview(Path directory, String id) throws IOException {
        JsonObject summary = JsonDocuments.object(JsonDocuments.read(directory.resolve("report.json")));
        if (!summary.get("importId").getAsString().equals(id)) throw new IOException("Import identity mismatch");
        return new Preview(id, directory, summary.get("records").getAsInt(), summary.get("valid").getAsInt(), summary.get("skins").getAsInt(), summary);
    }

    /** Explicit application installs dormant definitions; it never enables imported combat. */
    public String apply(DocumentStore store, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve("imports/" + id);
        Preview preview = readPreview(staged, id);
        if (preview.records() == 0 || preview.valid() != preview.records()) throw new IOException("Import has validation errors; inspect report.json");
        String receiptPath = "imports/" + id + "/receipt.json";
        String existing = store.read(receiptPath);
        if (existing != null) return "already-applied";
        List<DocumentStore.Change> changes = new ArrayList<>();
        Set<UUID> identities = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Path active = store.resolve("npcs");
        if (Files.exists(active)) try (var paths = Files.walk(active)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink in NPC directory");
                NpcDefinition definition = NpcDefinition.parse(JsonDocuments.read(path));
                identities.add(definition.uuid()); ids.add(definition.id());
            }
        }
        JsonObject documents = preview.summary().getAsJsonObject("documents");
        int npcCount = 0;
        for (var document : documents.entrySet()) {
            String relative = document.getKey();
            if (!relative.matches("(?:npcs/citizens/citizens-[0-9]+|skins/[a-f0-9]{64})\\.json")) throw new IOException("Invalid bundle path: " + relative);
            String content = JsonDocuments.read(store.resolve("imports/" + id + "/" + relative));
            if (!JsonDocuments.hash(content).equals(document.getValue().getAsString())) throw new IOException("Preview changed: " + relative + "; generate a new preview");
            String before = store.read(relative);
            if (relative.startsWith("npcs/")) {
                NpcDefinition definition = NpcDefinition.parse(content);
                if (!definition.path().equals(relative)) throw new IOException("NPC path mismatch");
                if (!identities.add(definition.uuid()) || !ids.add(definition.id()) || before != null) throw new IOException("NPC collision: " + relative);
                if (definition.json().get("spawned").getAsBoolean()) throw new IOException("Imported NPC must remain dormant");
                npcCount++;
            } else {
                if (!relative.equals("skins/" + JsonDocuments.hash(content) + ".json")) throw new IOException("Skin hash mismatch");
                if (before != null && !before.equals(content)) throw new IOException("Skin collision: " + relative);
                if (before != null) continue;
            }
            changes.add(new DocumentStore.Change(relative, "missing", content));
        }
        if (npcCount != preview.records()) throw new IOException("Incomplete import manifest");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importId", id);
        receipt.addProperty("records", npcCount);
        receipt.addProperty("state", "installed-dormant");
        changes.add(new DocumentStore.Change(receiptPath, "missing", JsonDocuments.write(receipt)));
        return store.commit(changes);
    }

    private static JsonObject parseYaml(String content) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(64);
        options.setCodePointLimit(JsonDocuments.MAX_BYTES);
        options.setAllowRecursiveKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            int depth = 0;
            int events = 0;
            for (var event : yaml.parse(new StringReader(content))) {
                if (++events > 1_000_000) throw new IOException("YAML exceeds event limit");
                if (event instanceof AliasEvent) throw new IOException("YAML aliases are not accepted");
                if (event instanceof CollectionStartEvent && ++depth > 64) throw new IOException("YAML nesting exceeds 64");
                if (event instanceof CollectionEndEvent) depth--;
            }
            Object loaded = yaml.load(content);
            JsonElement json = toJson(loaded);
            if (!json.isJsonObject()) throw new IOException("$: expected YAML mapping");
            return json.getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("Invalid Citizens YAML: " + e.getMessage(), e); }
    }

    private static JsonElement toJson(Object value) throws IOException {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String || entry.getKey() instanceof Number)) throw new IOException("YAML mapping keys must be strings or numbers");
                String key = entry.getKey().toString();
                if (object.has(key)) throw new IOException("Duplicate normalized YAML key: " + key);
                object.add(key, toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) array.add(toJson(item));
            return array;
        }
        if (value instanceof String string) return new JsonPrimitive(string);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IOException("Non-finite YAML number");
            return new JsonPrimitive(number);
        }
        throw new IOException("Unsupported YAML scalar type: " + value.getClass().getSimpleName() + "; original input was not changed");
    }

    private static void classify(JsonElement value, String path, JsonObject fields) {
        if (value.isJsonObject() && !value.getAsJsonObject().isEmpty()) {
            value.getAsJsonObject().entrySet().forEach(e -> classify(e.getValue(), path + "." + e.getKey(), fields));
        } else if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) classify(value.getAsJsonArray().get(i), path + "[" + i + "]", fields);
        } else {
            Status status = Status.UNSUPPORTED_PRESERVED;
            if (Set.of("$.uuid", "$.name", "$.traits.type", "$.traits.location.world", "$.traits.location.x", "$.traits.location.y", "$.traits.location.z", "$.traits.location.yaw", "$.traits.location.pitch").contains(path)) status = Status.CONVERTED;
            else if (path.startsWith("$.traits.")) {
                String trait = path.substring(9).split("[.\\[]", 2)[0];
                if (INTEGRATIONS.contains(trait) || trait.startsWith("denizen")) status = Status.REQUIRES_INTEGRATION;
                else if (trait.equals("skintrait")) status = Status.CONVERTED;
                else if (REVIEW.contains(trait)) status = Status.REQUIRES_REVIEW;
            }
            fields.addProperty(path, status.name());
        }
    }

    private static String scalar(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
    private static void increment(JsonObject counts, String key) { counts.addProperty(key, counts.has(key) ? counts.get(key).getAsInt() + 1 : 1); }
    private static void writeBundle(Path root, String relative, String content, JsonObject manifest) throws IOException {
        DocumentStore.atomic(root.resolve(relative), content);
        manifest.addProperty(relative, JsonDocuments.hash(content));
    }
    private static void deleteTemporary(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
