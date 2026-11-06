package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.runtime.DefinitionSpec;
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
import art.arcane.animus.core.building.BuilderModule;
import java.util.*;

/** Local, loss-preserving conversion. Conversion status describes data, never gameplay parity. */
public final class CitizensImporter {
    public static final int CONVERTER_VERSION = 11;
    /**
     * A staged or installed imported definition path: {@code <source prefix>-<source number>}. Citizens uses the
     * {@code citizens} prefix; module converters use theirs. Groups other than {@code citizens} come from converter version 7.
     */
    static final String NPC_PATH = "npcs/[a-z0-9][a-z0-9_-]{0,63}/[a-z0-9][a-z0-9_-]{0,62}-[0-9]+\\.json";
    static final String BUNDLE_PATH = "(?:npcs/[a-z0-9][a-z0-9_-]{0,63}/[a-z0-9][a-z0-9_-]{0,62}-[0-9]+|skins/[a-f0-9]{64}|behaviors/citizens/(?:text|commands)-[0-9]+|groups/citizens-[a-f0-9]{8})\\.json";
    /** A generated behavior of converter version 8 and later. */
    static final String BEHAVIOR_PATH = "behaviors/citizens/(?:text|commands)-[0-9]+\\.json";
    public enum Status { CONVERTED, REQUIRES_INTEGRATION, REQUIRES_REVIEW, UNSUPPORTED_PRESERVED }
    /** A mapper's decision for one report field and everything under it, with the one-line reason a report shows. */
    record Note(Status status, String reason) {}
    public record Preview(String id, Path directory, int records, int valid, int skins, JsonObject summary) {
        public Preview { summary = summary.deepCopy(); }
        @Override public JsonObject summary() { return summary.deepCopy(); }
        /** Generated behavior documents in the bundle; none before converter version 8. */
        public int behaviors() { return summary.has("behaviors") ? summary.get("behaviors").getAsInt() : 0; }
        /** Trait groups factored out with the {@code factor-groups} option; none without it. */
        public int groups() { return summary.has("groups") ? summary.get("groups").getAsInt() : 0; }
        /** Counts only: routes and their points, look-close and generated behaviors. */
        public String conversions() {
            if (!summary.has("conversions")) return "nothing (converter v" + converterVersion() + ")";
            JsonObject counts = summary.getAsJsonObject("conversions");
            return counts.get("routes").getAsInt() + " routes (" + counts.get("routePoints").getAsInt() + " points, " + counts.get("routeGuides").getAsInt() + " guides), look-close "
                    + counts.get("lookClose").getAsInt() + ", text behaviors " + counts.get("textBehaviors").getAsInt() + ", command behaviors " + counts.get("commandBehaviors").getAsInt()
                    + (counts.has("skinsKept") && counts.get("skinsKept").getAsInt() > 0 ? ", skins on non-player NPCs kept unused " + counts.get("skinsKept").getAsInt() : "");
        }
        public int converterVersion() { return summary.get("converterVersion").getAsInt(); }
        public int warningCount() { return summary.has("migrationWarnings") ? summary.getAsJsonArray("migrationWarnings").size() : 0; }
    }
    private static final Set<String> INTEGRATIONS = Set.of("sentinel", "denizen", "assignment", "denizen_flags", "denizenflags", "triggers", "constants");
    private static final Set<String> REVIEW = Set.of("skintrait", "location", "owner", "equipment", "inventory", "waypoints", "text", "hologramtrait", "poses", "lookclose", "type", "spawned", "mounttrait", "controllable",
            BuilderMapping.BUILDER_TRAIT, BuilderMapping.CONSTRUCTOR_TRAIT);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Why a skin on a body that is not a player is kept: Citizens never drew it either. */
    static String unusedSkin(String type) {
        String body = type.substring(type.indexOf(':') + 1).replace('_', ' ');
        return "Citizens draws skins only on player NPCs, so this " + body + " never showed it; kept for when the NPC becomes a player";
    }

    public Preview preview(Path source, Path destination, Map<String, String> worldMap) throws IOException { return preview(source, destination, worldMap, false); }

    public Preview preview(Path source, Path destination, Map<String, String> worldMap, boolean factorGroups) throws IOException {
        try (DocumentStore store = new DocumentStore(destination)) { return preview(source, store, worldMap, factorGroups); }
    }

    public Preview preview(Path source, DocumentStore store, Map<String, String> worldMap) throws IOException { return preview(source, store, worldMap, false); }

    /** With {@code factorGroups}, NPCs that share their groupable fields are factored into trait groups ({@link GroupFactoring}). */
    public Preview preview(Path source, DocumentStore store, Map<String, String> worldMap, boolean factorGroups) throws IOException {
        String original = JsonDocuments.read(source);
        JsonObject options = new JsonObject();
        for (var entry : new TreeMap<>(worldMap).entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())
                throw new IOException("World mappings require nonblank source and destination names");
            options.addProperty(entry.getKey(), entry.getValue());
        }
        String id = JsonDocuments.hash("animus-citizens-v" + CONVERTER_VERSION + "\n" + JsonDocuments.hash(original) + "\n" + options + (factorGroups ? "\nfactor-groups" : ""));
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
                summary.addProperty("converterVersion", CONVERTER_VERSION);
                summary.addProperty("fieldPathFormat", "json-pointer");
                summary.addProperty("importId", id);
                summary.addProperty("sourceSha256", JsonDocuments.hash(original));
                summary.add("worldMap", options);
                summary.addProperty("notice", "Data conversion only. Rendering, movement, behavior and combat require separate runtime validation. Imported NPCs are staged with spawned=false; converted combat stays disabled until its review is accepted.");
                JsonObject mapping = identityMap(root.getAsJsonObject("npc"));
                JsonObject citizensDefaults = CitizensBehaviors.defaults(root.getAsJsonObject("npc"));
                JsonObject conversions = new JsonObject();
                for (String counter : List.of("routes", "routePoints", "routeGuides", "lookClose", "textBehaviors", "commandBehaviors", "skinsKept")) conversions.addProperty(counter, 0);
                int behaviorDocuments = 0;
                Map<String, List<String>> reverseIds = new HashMap<>();
                mapping.entrySet().forEach(e -> reverseIds.computeIfAbsent(e.getValue().getAsString(), ignored -> new ArrayList<>()).add(e.getKey()));
                JsonObject counts = new JsonObject();
                JsonObject types = new JsonObject();
                JsonObject sentinelFields = new JsonObject();
                JsonObject sentinelMapping = new JsonObject();
                JsonArray issues = new JsonArray();
                JsonArray migrationWarnings = new JsonArray();
                for (JsonElement inferred : citizensDefaults.getAsJsonArray("inferred")) {
                    boolean value = citizensDefaults.getAsJsonObject("lookclose").get(inferred.getAsString()).getAsBoolean();
                    if (value != CitizensBehaviors.LOOK_DEFAULTS.get(inferred.getAsString())) migrationWarnings.add("lookclose/" + inferred.getAsString() + ": this save only ever writes "
                            + !value + ", so NPCs without the field are converted with " + value + " (the server default that wrote the save); if that default was "
                            + !value + ", set " + inferred.getAsString() + " in animus:look-close on those NPCs");
                }
                int remappedLocations = 0;
                int resolvedReferences = 0;
                int unresolvedReferences = 0;
                JsonObject manifest = new JsonObject();
                Set<UUID> uuids = new HashSet<>();
                Set<Integer> numericIds = new HashSet<>();
                int valid = 0;
                Set<String> skins = new HashSet<>();
                Map<String, JsonObject> deferred = new LinkedHashMap<>();
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
                    String uuid = mapping.get(key).getAsString();
                    definition.addProperty("uuid", uuid);
                    definition.addProperty("id", "citizens-" + key);
                    definition.addProperty("group", "citizens"); // Set to the NPC's world folder once its location is known.
                    definition.addProperty("name", scalar(record, "name", "Citizen " + key));
                    definition.addProperty("type", type.contains(":") ? type : "minecraft:" + type);
                    definition.addProperty("spawned", false);
                    JsonObject migration = new JsonObject();
                    migration.addProperty("importId", id);
                    migration.addProperty("citizensId", numeric);
                    migration.add("originalSpawned", traits.has("spawned") ? traits.get("spawned").deepCopy() : JsonNull.INSTANCE);
                    definition.add("migration", migration);
                    JsonArray worldRemaps = new JsonArray();
                    JsonObject reviewed = traits.deepCopy();
                    remapKnownLocations(reviewed, worldMap, worldRemaps);
                    JsonArray references = references(traits, mapping, reverseIds);
                    migration.add("references", references.deepCopy());
                    for (JsonElement reference : references) {
                        String state = reference.getAsJsonObject().get("status").getAsString();
                        if (state.equals("resolved")) resolvedReferences++;
                        else if (!state.equals("none")) {
                            unresolvedReferences++;
                            migrationWarnings.add("citizens-" + key + ": " + reference.getAsJsonObject().get("source").getAsString() + " NPC reference " + state);
                        }
                    }
                    remappedLocations += worldRemaps.size();
                    JsonObject position = new JsonObject();
                    JsonObject location = reviewed.has("location") && reviewed.get("location").isJsonObject() ? reviewed.getAsJsonObject("location") : new JsonObject();
                    String world = scalar(location, "world", "");
                    position.addProperty("world", world);
                    for (String coordinate : List.of("x", "y", "z", "yaw", "pitch"))
                        position.add(coordinate, location.has(coordinate) ? location.get(coordinate).deepCopy() : (Set.of("yaw", "pitch").contains(coordinate) ? new JsonPrimitive(0) : JsonNull.INSTANCE));
                    CitizensPresentation.Result presentation = CitizensPresentation.map(definition.get("type").getAsString(), reviewed, record);
                    presentation.facing().ifPresent((CitizensPresentation.Facing facing) -> {
                        position.addProperty("yaw", facing.yaw());
                        position.addProperty("pitch", facing.pitch());
                    });
                    definition.add("position", position);
                    definition.addProperty("group", group(world, position.get("x"), position.get("z")));
                    for (Map.Entry<String, JsonElement> field : presentation.fields().entrySet()) definition.add(field.getKey(), field.getValue());
                    // A skin is only drawn on a player body; any other body keeps it under citizens:review.
                    boolean skinApplied = traits.has("skintrait") && definition.get("type").getAsString().equals(BodyRules.PLAYER);
                    List<String> presentationWarnings = new ArrayList<>(presentation.warnings());
                    if (traits.has("skintrait") && !skinApplied) increment(conversions, "skinsKept");
                    for (String warning : presentationWarnings) migrationWarnings.add("citizens-" + key + ": " + warning);
                    JsonObject modules = new JsonObject();
                    if (traits.has("sentinel")) {
                        SentinelMapping.Result combat = SentinelMapping.convertRaw(reviewed.get("sentinel"), reviewed.get("equipment"), references);
                        modules.add("animus:combat", combat.module());
                        if (reviewed.get("sentinel").isJsonObject()) {
                            JsonObject reviewedSentinel = reviewed.getAsJsonObject("sentinel");
                            for (String field : new ArrayList<>(reviewedSentinel.keySet())) {
                                increment(sentinelMapping, switch (SentinelMapping.disposition(field)) {
                                    case CONVERTED -> "converted";
                                    case UNSUPPORTED -> "unsupported";
                                    case STATISTIC -> "statistics";
                                    case UNKNOWN -> "unknown";
                                });
                                if (field.startsWith("stats_")) reviewedSentinel.remove(field);
                            }
                        }
                    }
                    if (traits.has("trader")) {
                        TraderMapping.Result trader = TraderMapping.map(traits.get("trader"), definition.get("name").getAsString());
                        trader.module().ifPresent((JsonObject shop) -> modules.add("animus:shop", shop));
                        trader.warning().ifPresent((String warning) -> migrationWarnings.add("citizens-" + key + ": " + warning));
                    }
                    if (CitizensTraits.targetable(reviewed)) {
                        if (!modules.has("animus:combat")) modules.add("animus:combat", CitizensTraits.targetableCombat(record));
                        modules.getAsJsonObject("animus:combat").addProperty("targetableByMobs", true);
                    }
                    riding(traits.get("controllable")).ifPresent((JsonObject riding) -> modules.add("animus:riding", riding));
                    if (traits.has(BuilderMapping.BUILDER_TRAIT)) {
                        BuilderMapping.Result build = BuilderMapping.convert(reviewed.get(BuilderMapping.BUILDER_TRAIT));
                        modules.add(BuilderModule.MODULE, build.module());
                        for (String warning : build.warnings()) migrationWarnings.add("citizens-" + key + ": " + warning);
                    }
                    if (traits.has(BuilderMapping.CONSTRUCTOR_TRAIT))
                        for (String warning : BuilderMapping.constructor(reviewed.get(BuilderMapping.CONSTRUCTOR_TRAIT))) migrationWarnings.add("citizens-" + key + ": " + warning);
                    CitizensBehaviors.Result runtime = CitizensBehaviors.map(key, definition.get("name").getAsString(), world, reviewed, record, citizensDefaults);
                    runtime.navigation().ifPresent((JsonObject navigation) -> {
                        modules.add("animus:navigation", navigation);
                        increment(conversions, "routes");
                    });
                    conversions.addProperty("routePoints", conversions.get("routePoints").getAsInt() + runtime.points());
                    conversions.addProperty("routeGuides", conversions.get("routeGuides").getAsInt() + runtime.guides());
                    runtime.lookClose().ifPresent((JsonObject look) -> {
                        modules.add("animus:look-close", look);
                        if (look.get("enabled").getAsBoolean()) increment(conversions, "lookClose");
                    });
                    for (String behavior : runtime.behaviors()) increment(conversions, behavior.startsWith("citizens:text-") ? "textBehaviors" : "commandBehaviors");
                    for (CitizensBehaviors.Document document : runtime.documents()) { writeBundle(temporary, document.path(), document.content(), manifest); behaviorDocuments++; }
                    for (String warning : runtime.warnings()) migrationWarnings.add("citizens-" + key + ": " + warning);
                    if (!modules.has("animus:navigation") && !world.isEmpty() && CitizensTraits.wanders(definition.get("type").getAsString(), record)) {
                        modules.add("animus:navigation", CitizensTraits.vanillaWander(record));
                        increment(conversions, "routes");
                    }
                    CitizensTraits.Result state = CitizensTraits.map(definition.get("type").getAsString(), record, reviewed, modules.has("animus:combat"),
                            modules.has("animus:navigation"), references);
                    for (Map.Entry<String, JsonElement> field : state.fields().entrySet()) definition.add(field.getKey(), field.getValue());
                    if (!state.migration().isEmpty()) migration.add("skin", state.migration());
                    for (String warning : state.warnings()) migrationWarnings.add("citizens-" + key + ": " + warning);
                    // This review copy applies only known location transformations. Exact originals stay in records/ and source.yml.
                    if (skinApplied) reviewed.remove("skintrait");
                    modules.add("citizens:review", reviewed);
                    if (!runtime.behaviors().isEmpty()) definition.add("behaviors", GSON.toJsonTree(runtime.behaviors()));
                    definition.add("modules", modules);
                    if (skinApplied) {
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
                    Set<String> convertedFields = new HashSet<>(presentation.converted());
                    convertedFields.addAll(runtime.converted());
                    convertedFields.addAll(state.converted());
                    Map<String, Note> notes = new HashMap<>(presentation.notes());
                    notes.putAll(runtime.notes());
                    notes.putAll(state.notes());
                    if (traits.has("skintrait") && !skinApplied)
                        notes.put("/traits/skintrait", new Note(Status.UNSUPPORTED_PRESERVED, unusedSkin(definition.get("type").getAsString())));
                    JsonObject reasons = new JsonObject();
                    classify(record, "", fields, convertedFields, notes, reasons);
                    report.add("presentationWarnings", GSON.toJsonTree(presentationWarnings));
                    report.add("runtimeWarnings", GSON.toJsonTree(runtime.warnings()));
                    report.addProperty("fieldPathFormat", "json-pointer");
                    report.add("fields", fields);
                    report.add("reasons", reasons);
                    report.add("worldRemaps", worldRemaps);
                    report.add("references", references);
                    List<String> errors = new ArrayList<>(validate(definition));
                    try { if (!uuids.add(UUID.fromString(uuid))) errors.add("$.uuid: duplicate NPC UUID"); }
                    catch (IllegalArgumentException e) { /* already diagnosed by validator */ }
                    report.add("validationErrors", GSON.toJsonTree(errors));
                    if (!errors.isEmpty()) issues.add("citizens-" + key + ": " + String.join("; ", errors));
                    else valid++;

                    String npcPath = "npcs/" + definition.get("group").getAsString() + "/citizens-" + key + ".json";
                    if (factorGroups) deferred.put(npcPath, definition);
                    else writeBundle(temporary, npcPath, JsonDocuments.write(definition), manifest);
                    DocumentStore.atomic(temporary.resolve("records/" + key + ".json"), JsonDocuments.write(record));
                    DocumentStore.atomic(temporary.resolve("reports/" + key + ".json"), JsonDocuments.write(report));
                }
                if (factorGroups) {
                    GroupFactoring.Result factored = GroupFactoring.factor(deferred);
                    for (Map.Entry<String, JsonObject> definition : factored.definitions().entrySet())
                        writeBundle(temporary, definition.getKey(), JsonDocuments.write(definition.getValue()), manifest);
                    JsonArray groups = new JsonArray();
                    for (GroupFactoring.Group group : factored.groups()) {
                        writeBundle(temporary, group.path(), group.content(), manifest);
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", group.id());
                        entry.addProperty("members", group.members().size());
                        groups.add(entry);
                    }
                    summary.addProperty("groups", factored.groups().size());
                    summary.add("factoredGroups", groups);
                }
                summary.addProperty("records", numericIds.size());
                summary.addProperty("valid", valid);
                summary.addProperty("skins", skins.size());
                summary.addProperty("behaviors", behaviorDocuments);
                summary.add("conversions", conversions);
                summary.add("citizensDefaults", citizensDefaults);
                summary.add("traits", counts);
                summary.add("types", types);
                summary.add("sentinelFields", sentinelFields);
                summary.add("sentinelMapping", sentinelMapping);
                summary.add("issues", issues);
                summary.add("migrationWarnings", migrationWarnings);
                summary.addProperty("remappedLocations", remappedLocations);
                summary.addProperty("resolvedReferences", resolvedReferences);
                summary.addProperty("unresolvedReferences", unresolvedReferences);
                JsonObject sourceFields = new JsonObject();
                JsonObject sourceMetadata = new JsonObject();
                root.entrySet().stream().filter(e -> !e.getKey().equals("npc")).forEach(e -> sourceMetadata.add(e.getKey(), e.getValue().deepCopy()));
                if (!sourceMetadata.isEmpty()) classify(sourceMetadata, "", sourceFields, Set.of(), Map.of(), new JsonObject());
                summary.add("sourceFields", sourceFields);
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

    /** Whether the text is a Citizens saves file: YAML with an {@code npc} mapping. */
    public static boolean recognizes(String text) {
        try {
            JsonObject root = parseYaml(text);
            return root.has("npc") && root.get("npc").isJsonObject();
        } catch (IOException | RuntimeException unreadable) { return false; }
    }

    public Preview readPreview(Path directory, String id) throws IOException {
        try {
            JsonObject summary = JsonDocuments.object(JsonDocuments.read(directory.resolve("report.json")));
            int version = summary.get("converterVersion").getAsBigDecimal().intValueExact();
            if (summary.get("schemaVersion").getAsInt() != 1 || version < 1 || version > CONVERTER_VERSION)
                throw new IOException("Unsupported import report version");
            if (!summary.get("importId").getAsString().equals(id)) throw new IOException("Import identity mismatch");
            int records = summary.get("records").getAsBigDecimal().intValueExact();
            int valid = summary.get("valid").getAsBigDecimal().intValueExact();
            int skins = summary.get("skins").getAsBigDecimal().intValueExact();
            int behaviors = summary.has("behaviors") ? summary.get("behaviors").getAsBigDecimal().intValueExact() : 0;
            if (records < 0 || records > 10000 || valid < 0 || valid > records || skins < 0 || skins > records || behaviors < 0 || behaviors > 2 * records
                    || !summary.get("documents").isJsonObject() || !summary.get("idMap").isJsonObject()
                    || summary.getAsJsonObject("idMap").size() != records)
                throw new IOException("Invalid import report counts or manifest");
            return new Preview(id, directory, records, valid, skins, summary);
        } catch (RuntimeException e) { throw new IOException("Invalid import report: " + directory.resolve("report.json"), e); }
    }

    /** Explicit application installs dormant definitions; it never enables imported combat. */
    public String apply(DocumentStore store, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve("imports/" + id);
        Preview preview = readPreview(staged, id);
        if (preview.records() == 0 || preview.valid() != preview.records()) throw new IOException("Import has validation errors; inspect report.json");
        String receiptPath = "imports/" + id + "/receipt.json";
        String existing = store.read(receiptPath);
        if (existing != null) {
            verifyReceipt(store, preview, existing);
            return "already-applied";
        }
        List<DocumentStore.Change> changes = new ArrayList<>();
        Set<UUID> identities = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Path active = store.resolve("npcs");
        for (Path path : JsonDocumentTree.files(active)) {
            NpcDefinition definition = NpcDefinition.parse(JsonDocuments.read(path));
            if (!store.resolve(definition.path()).equals(path)) throw new IOException("Existing NPC path mismatch: " + path);
            if (!identities.add(definition.uuid()) || !ids.add(definition.id())) throw new IOException("Existing NPC identity collision: " + path);
        }
        if ((long) identities.size() + preview.records() > 100_000) throw new IOException("NPC definition limit exceeded: 100000");
        JsonObject documents = preview.summary().getAsJsonObject("documents");
        int npcCount = 0;
        Set<String> requiredSkins = new HashSet<>();
        Set<String> bundledSkins = new HashSet<>();
        int behaviorCount = 0, groupCount = 0;
        for (var document : documents.entrySet()) {
            String relative = document.getKey();
            if (!relative.matches(BUNDLE_PATH)) throw new IOException("Invalid bundle path: " + relative);
            String content = JsonDocuments.read(store.resolve("imports/" + id + "/" + relative));
            if (!JsonDocuments.hash(content).equals(document.getValue().getAsString())) throw new IOException("Preview changed: " + relative + "; generate a new preview");
            String before = store.read(relative);
            if (relative.startsWith("npcs/")) {
                NpcDefinition definition = NpcDefinition.parse(content);
                if (!definition.path().equals(relative)) throw new IOException("NPC path mismatch");
                if (!identities.add(definition.uuid()) || !ids.add(definition.id()) || before != null) throw new IOException("NPC collision: " + relative);
                verifyDormant(definition);
                if (!definition.json().getAsJsonObject("migration").get("importId").getAsString().equals(id)) throw new IOException("NPC import identity mismatch");
                JsonObject json = definition.json();
                if (json.has("skin")) requiredSkins.add("skins/" + json.get("skin").getAsString() + ".json");
                npcCount++;
            } else if (relative.startsWith("groups/")) {
                groupCount++;
                if (GroupDocument.parse(relative.substring(7, relative.length() - 5), content).group().isEmpty()) throw new IOException("Invalid factored group: " + relative);
                if (before != null && !before.equals(content)) throw new IOException("Group collision: " + relative + " exists with other content");
                if (before != null) continue;
            } else if (relative.startsWith("behaviors/")) {
                behaviorCount++;
                if (before != null && !before.equals(content)) throw new IOException("Behavior collision: " + relative + " exists with other content");
                if (before != null) continue;
            } else {
                bundledSkins.add(relative);
                if (!relative.equals("skins/" + JsonDocuments.hash(content) + ".json")) throw new IOException("Skin hash mismatch");
                if (before != null && !before.equals(content)) throw new IOException("Skin collision: " + relative);
                if (before != null) continue;
            }
            changes.add(new DocumentStore.Change(relative, "missing", content));
        }
        if (npcCount != preview.records() || bundledSkins.size() != preview.skins() || behaviorCount != preview.behaviors() || groupCount != preview.groups() || !bundledSkins.containsAll(requiredSkins))
            throw new IOException("Incomplete import manifest or missing referenced skins");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importId", id);
        receipt.addProperty("records", npcCount);
        receipt.addProperty("state", "installed-dormant");
        changes.add(new DocumentStore.Change(receiptPath, "missing", JsonDocuments.write(receipt)));
        return store.commit(changes);
    }

    /** Blocks per side of a Minecraft region file, the area one import folder covers. */
    public static final int GROUP_AREA = 512;

    /**
     * The folder an imported NPC goes in: its world after world mapping and the region file it stands in, such as
     * {@code world-r0_-1} for {@code region/r.0.-1.mca}. The world becomes a lowercase path segment of at most 48
     * characters. Without usable x and z coordinates the folder is the world alone, and without a world it is
     * {@code citizens}. The same save always gives the same folders.
     */
    public static String group(String world, JsonElement x, JsonElement z) {
        String lowered = world.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^[^a-z0-9]+", "").replaceAll("[-_]+$", "");
        if (lowered.length() > 48) lowered = lowered.substring(0, 48).replaceAll("[-_]+$", "");
        if (lowered.isEmpty()) return "citizens";
        if (!coordinate(x) || !coordinate(z)) return lowered;
        long regionX = (long) Math.floor(x.getAsDouble() / GROUP_AREA), regionZ = (long) Math.floor(z.getAsDouble() / GROUP_AREA);
        return lowered + "-r" + regionX + "_" + regionZ;
    }

    private static boolean coordinate(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble()) && Math.abs(value.getAsDouble()) <= 30_000_000;
    }

    /** A Citizens {@code controllable} trait that is on becomes a disabled {@code animus:riding} section waiting for review. */
    static Optional<JsonObject> riding(JsonElement trait) {
        if (trait == null || !trait.isJsonObject()) return Optional.empty();
        JsonElement enabled = trait.getAsJsonObject().get("enabled");
        if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && !enabled.getAsBoolean()) return Optional.empty();
        JsonObject riding = new JsonObject();
        riding.addProperty("enabled", false);
        riding.addProperty("reviewAccepted", false);
        return Optional.of(riding);
    }

    static JsonObject parseYaml(String content) throws IOException {
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

    /** The schema rules, then the activation rules every server applies, so the valid count matches what can spawn. */
    static List<String> validate(JsonObject definition) {
        List<String> errors = new ArrayList<>(NpcDefinition.validate(definition));
        if (!errors.isEmpty()) return List.copyOf(errors);
        try { errors.addAll(DefinitionSpec.activation(NpcDefinition.parse(JsonDocuments.write(definition)))); }
        catch (IOException | IllegalArgumentException invalid) { errors.add(invalid.getMessage()); }
        return List.copyOf(errors);
    }

    /** Location fields Animus reads directly: the facing is one yaw for head and body, and the world is matched by name. */
    private static final Set<String> LOCATION = Set.of("/uuid", "/name", "/traits/type", "/traits/location/world", "/traits/location/x", "/traits/location/y",
            "/traits/location/z", "/traits/location/yaw", "/traits/location/pitch", "/traits/location/bodyYaw", "/traits/location/worldid");

    /**
     * Gives every leaf of the record a status: converted paths first, then the nearest note on the path or one of its
     * parents, then the trait's own rule. Every field that is not converted gets a one-line reason in {@code reasons}.
     */
    private static void classify(JsonElement value, String path, JsonObject fields, Set<String> converted, Map<String, Note> notes, JsonObject reasons) {
        if (value.isJsonObject() && !value.getAsJsonObject().isEmpty()) {
            value.getAsJsonObject().entrySet().forEach(e -> classify(e.getValue(), path + "/" + pointer(e.getKey()), fields, converted, notes, reasons));
            return;
        }
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            for (int i = 0; i < value.getAsJsonArray().size(); i++) classify(value.getAsJsonArray().get(i), path + "/" + i, fields, converted, notes, reasons);
            return;
        }
        Note note = converted.contains(path) || LOCATION.contains(path) ? new Note(Status.CONVERTED, "") : nearest(path, notes);
        if (note == null) note = rule(path, value);
        fields.addProperty(path, note.status().name());
        if (!note.reason().isEmpty()) reasons.addProperty(path, note.reason());
    }

    private static Note nearest(String path, Map<String, Note> notes) {
        for (String at = path; !at.isEmpty(); at = at.substring(0, at.lastIndexOf('/'))) {
            Note note = notes.get(at);
            if (note != null) return note;
        }
        return null;
    }

    /** The status and reason of a field no mapper decided on. */
    private static Note rule(String path, JsonElement value) {
        if (!path.startsWith("/traits/")) return new Note(Status.UNSUPPORTED_PRESERVED, "Citizens field with no Animus equivalent; kept in records/");
        String[] parts = path.substring(8).split("/", 3);
        String trait = parts[0];
        // An empty trait keeps Citizens' defaults, which are also Animus' defaults.
        if (parts.length == 1 && (value.isJsonObject() || value.isJsonArray())) return new Note(Status.CONVERTED, "");
        if (trait.equals("sentinel") && parts.length > 1) return switch (SentinelMapping.disposition(parts[1])) {
            case CONVERTED -> new Note(Status.CONVERTED, "");
            case STATISTIC -> new Note(Status.UNSUPPORTED_PRESERVED, "Sentinel's record of past fights; history, not a setting");
            case UNSUPPORTED -> new Note(Status.UNSUPPORTED_PRESERVED, "Sentinel setting with no animus:combat equivalent; kept in the combat review data");
            case UNKNOWN -> new Note(Status.REQUIRES_INTEGRATION, "Sentinel field this converter does not know; kept in the combat review data");
        };
        if (trait.equals("assignment") || trait.equals("triggers") || trait.startsWith("denizen") || trait.equals("constants"))
            return new Note(Status.REQUIRES_INTEGRATION, "Denizen data; its scripts are not in the save, so rewrite them as Animus behaviors by hand");
        if (INTEGRATIONS.contains(trait)) return new Note(Status.REQUIRES_INTEGRATION, "needs a plugin integration Animus does not have");
        if (trait.equals("skintrait")) return new Note(Status.CONVERTED, "");
        if (trait.equals("trader")) return parts.length > 1 && Set.of("guiName", "converted-to-v5").contains(parts[1]) ? new Note(Status.CONVERTED, "")
                : new Note(Status.REQUIRES_INTEGRATION, "dtlTraders data; convert it with the dtlTraders shop import");
        if (REVIEW.contains(trait)) return new Note(Status.REQUIRES_REVIEW, "not converted; check it in citizens:review");
        return new Note(Status.UNSUPPORTED_PRESERVED, "trait " + trait + " has no Animus equivalent; kept in citizens:review");
    }

    private static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    private static JsonObject identityMap(JsonObject records) throws IOException {
        if (records.size() > 10000) throw new IOException("Import exceeds 10000 NPC records");
        JsonObject map = new JsonObject();
        for (var entry : records.entrySet()) {
            if (!entry.getKey().matches("0|[1-9][0-9]{0,9}") || !entry.getValue().isJsonObject()) throw new IOException("Invalid NPC ID or record: " + entry.getKey());
            try { Integer.parseInt(entry.getKey()); }
            catch (NumberFormatException e) { throw new IOException("NPC ID exceeds integer range", e); }
            JsonObject record = entry.getValue().getAsJsonObject();
            map.addProperty(entry.getKey(), scalar(record, "uuid", UUID.nameUUIDFromBytes(("animus:citizens:" + entry.getKey()).getBytes(StandardCharsets.UTF_8)).toString()));
        }
        return map;
    }

    private static void remapKnownLocations(JsonObject traits, Map<String, String> worlds, JsonArray changes) {
        remapLocation(traits.get("location"), "/traits/location", worlds, changes);
        walkWaypointLocations(traits.get("waypoints"), "/traits/waypoints", worlds, changes);
        if (traits.has("sentinel") && traits.get("sentinel").isJsonObject()) {
            for (String key : List.of("spawnPoint", "avoidReturnPoint"))
                remapLocation(traits.getAsJsonObject("sentinel").get(key), "/traits/sentinel/" + key, worlds, changes);
        }
        if (traits.has("sittrait") && traits.get("sittrait").isJsonObject())
            remapLocation(traits.getAsJsonObject("sittrait").get("sittingAt"), "/traits/sittrait/sittingAt", worlds, changes);
    }

    private static void walkWaypointLocations(JsonElement value, String path, Map<String, String> worlds, JsonArray changes) {
        if (value == null) return;
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                String child = path + "/" + pointer(entry.getKey());
                if (entry.getKey().equals("location")) remapLocation(entry.getValue(), child, worlds, changes);
                else walkWaypointLocations(entry.getValue(), child, worlds, changes);
            }
        } else if (value.isJsonArray()) {
            for (int index = 0; index < value.getAsJsonArray().size(); index++)
                walkWaypointLocations(value.getAsJsonArray().get(index), path + "/" + index, worlds, changes);
        }
    }

    private static void remapLocation(JsonElement value, String path, Map<String, String> worlds, JsonArray changes) {
        if (value == null || !value.isJsonObject()) return;
        JsonObject location = value.getAsJsonObject();
        JsonElement worldValue = location.get("world");
        if (worldValue == null || !worldValue.isJsonPrimitive() || !worldValue.getAsJsonPrimitive().isString()) return;
        String before = worldValue.getAsString();
        String after = worlds.get(before);
        if (after == null || after.equals(before)) return;
        location.addProperty("world", after);
        JsonObject change = new JsonObject();
        change.addProperty("source", path);
        change.addProperty("from", before);
        change.addProperty("to", after);
        // A copied source UUID must not override the remapped name on another server.
        if (location.has("worldid")) change.add("removedWorldId", location.remove("worldid"));
        changes.add(change);
    }

    private static JsonArray references(JsonObject traits, JsonObject ids, Map<String, List<String>> reverseIds) {
        JsonArray references = new JsonArray();
        if (traits.has("mounttrait") && traits.get("mounttrait").isJsonObject() && traits.getAsJsonObject("mounttrait").has("mountedon"))
            references.add(reference("/traits/mounttrait/mountedon", "mount", traits.getAsJsonObject("mounttrait").get("mountedon"), false, ids, reverseIds));
        // SentinelTrait @Persist("guardedNPC") uses a Citizens integer ID; -1 means no guard.
        if (traits.has("sentinel") && traits.get("sentinel").isJsonObject() && traits.getAsJsonObject("sentinel").has("guardedNPC"))
            references.add(reference("/traits/sentinel/guardedNPC", "guard", traits.getAsJsonObject("sentinel").get("guardedNPC"), true, ids, reverseIds));
        return references;
    }

    private static JsonObject reference(String source, String kind, JsonElement original, boolean numeric, JsonObject ids, Map<String, List<String>> reverseIds) {
        JsonObject result = new JsonObject();
        result.addProperty("source", source); result.addProperty("kind", kind); result.add("original", original.deepCopy());
        String key = null;
        String status = "invalid";
        try {
            if (!original.isJsonPrimitive()) throw new IllegalArgumentException();
            if (numeric) {
                int id = new java.math.BigDecimal(original.getAsString()).intValueExact();
                if (id == -1) status = "none";
                else if (id >= 0) { key = Integer.toString(id); status = ids.has(key) ? "resolved" : "missing"; }
            } else {
                String uuid = original.getAsString();
                if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException();
                List<String> matches = reverseIds.getOrDefault(uuid, List.of());
                status = matches.isEmpty() ? "missing" : matches.size() == 1 ? "resolved" : "ambiguous";
                if (matches.size() == 1) key = matches.getFirst();
            }
            if (status.equals("resolved")) {
                String uuid = ids.get(key).getAsString();
                if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException();
                if (reverseIds.get(uuid).size() != 1) status = "ambiguous";
                else {
                    result.addProperty("npcUuid", uuid); result.addProperty("npcId", "citizens-" + key); result.addProperty("citizensId", Integer.parseInt(key));
                }
            }
        } catch (IllegalArgumentException | ArithmeticException e) { status = "invalid"; }
        result.addProperty("status", status);
        return result;
    }

    private static void verifyDormant(NpcDefinition definition) throws IOException {
        JsonObject json = definition.json();
        if (json.get("spawned").getAsBoolean()) throw new IOException("Imported NPC must remain dormant");
        JsonObject modules = json.getAsJsonObject("modules");
        if (modules != null && modules.has("animus:combat")) {
            JsonElement combatValue = modules.get("animus:combat");
            if (!combatValue.isJsonObject()) throw new IOException("Invalid imported combat configuration");
            JsonObject combat = combatValue.getAsJsonObject();
            if (!new JsonPrimitive(false).equals(combat.get("enabled")) || !new JsonPrimitive(false).equals(combat.get("reviewAccepted")))
                throw new IOException("Imported combat must remain disabled and unreviewed");
        }
        if (modules != null && modules.has("animus:navigation")) {
            JsonElement navigation = modules.get("animus:navigation");
            if (!navigation.isJsonObject() || !new JsonPrimitive(false).equals(navigation.getAsJsonObject().get("enabled"))
                    || !new JsonPrimitive(false).equals(navigation.getAsJsonObject().get("reviewAccepted")))
                throw new IOException("Imported patrols must remain disabled and unreviewed");
        }
    }

    private static void verifyReceipt(DocumentStore store, Preview preview, String text) throws IOException {
        try {
            JsonObject receipt = JsonDocuments.object(text);
            if (!receipt.get("importId").getAsString().equals(preview.id()) || receipt.get("records").getAsInt() != preview.records()
                    || !receipt.get("state").getAsString().equals("installed-dormant")) throw new IOException("Invalid import receipt");
            Map<String, String> paths = new HashMap<>();
            for (String relative : preview.summary().getAsJsonObject("documents").keySet())
                if (relative.startsWith("npcs/")) paths.put(relative.substring(relative.lastIndexOf('-') + 1, relative.length() - 5), relative);
            for (var entry : preview.summary().getAsJsonObject("idMap").entrySet()) {
                String path = paths.get(entry.getKey());
                if (path == null) throw new IOException("Import manifest has no definition for source NPC " + entry.getKey());
                String current = store.read(path);
                if (current == null) throw new IOException("Previously applied import is incomplete: " + path);
                NpcDefinition definition = NpcDefinition.parse(current);
                if (!definition.path().equals(path) || !definition.uuid().toString().equals(entry.getValue().getAsString())
                        || !definition.json().getAsJsonObject("migration").get("importId").getAsString().equals(preview.id()))
                    throw new IOException("Previously applied import identity changed: " + path);
                JsonObject data = definition.json();
                if (data.has("skin")) {
                    String skin = data.get("skin").getAsString();
                    if (!JsonDocuments.hash(store.read("skins/" + skin + ".json")).equals(skin))
                        throw new IOException("Previously applied import has a missing or changed skin: " + path);
                }
            }
        } catch (RuntimeException e) { throw new IOException("Invalid import receipt or installed definition", e); }
    }

    private static String scalar(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
    private static void increment(JsonObject counts, String key) { counts.addProperty(key, counts.has(key) ? counts.get(key).getAsInt() + 1 : 1); }
    static void writeBundle(Path root, String relative, String content, JsonObject manifest) throws IOException {
        DocumentStore.atomic(root.resolve(relative), content);
        manifest.addProperty(relative, JsonDocuments.hash(content));
    }
    static void deleteTemporary(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
