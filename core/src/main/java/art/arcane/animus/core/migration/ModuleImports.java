package art.arcane.animus.core.migration;

import art.arcane.animus.api.ImportConverters;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.ModuleHooks;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.storage.NpcPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Module converters write the same reviewed preview bundle as the built-in Citizens converter, so {@code import apply},
 * {@code import stage} and {@code import plan} handle them unchanged. Call from the file lane; it waits for the
 * bounded module invocation, whose own deadline always settles it.
 */
public final class ModuleImports {
    private ModuleImports() {}

    /** The source's numeric identity: Citizens records keep {@code citizensId}; module records use {@code sourceId}. */
    static int sourceNumber(JsonObject migration) throws IOException {
        JsonElement value = migration.has("citizensId") ? migration.get("citizensId") : migration.get("sourceId");
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IOException("Expected exact integer: citizensId or sourceId", invalid); }
    }
    /** The id prefix of an imported definition: {@code citizens}, or the converter key with its colon as a dash. */
    static String sourcePrefix(JsonObject migration) throws IOException {
        if (migration.has("citizensId")) return "citizens";
        JsonElement converter = migration.get("converter");
        if (converter == null || !converter.isJsonPrimitive() || !converter.getAsJsonPrimitive().isString()) throw new IOException("Expected string: converter");
        return prefix(converter.getAsString());
    }
    static String prefix(String converterKey) { return converterKey.replace(':', '-'); }

    /**
     * The converter to use: the named one, else the single available converter that recognizes the source.
     * Detection runs through each module's bounded invocation path.
     */
    public static ModuleHooks.Converter select(ModuleHooks hooks, ImportConverters.Source source, Optional<String> key) throws IOException {
        if (key.isPresent()) {
            ModuleHooks.Converter named = hooks.converter(key.get()).orElseThrow(() -> new IOException("No module import converter " + key.get()));
            if (!named.owner().available()) throw new IOException("Import converter " + key.get() + " is unavailable: " + named.owner().reason());
            return named;
        }
        List<ModuleHooks.Converter> recognized = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ModuleHooks.Converter converter : hooks.converters()) {
            if (!converter.owner().available()) continue;
            Outcome<ImportConverters.Detection> detected = await(converter.detect().apply(source));
            if (detected.status() != Outcome.Status.OK) { notes.add(converter.descriptor().key() + ": " + describe(detected)); continue; }
            ImportConverters.Detection detection = detected.value().orElseThrow();
            if (detection.recognized()) recognized.add(converter);
            else notes.add(converter.descriptor().key() + ": " + detection.reason());
        }
        if (recognized.size() == 1) return recognized.getFirst();
        if (recognized.isEmpty()) throw new IOException("No import converter recognized " + source.fileName() + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")"));
        throw new IOException("Several converters recognize " + source.fileName() + ": " + recognized.stream().map((ModuleHooks.Converter c) -> c.descriptor().key().toString()).toList()
                + "; choose one with converter=<namespace:name>");
    }

    public static ImportConverters.Source source(Path file) throws IOException {
        return new ImportConverters.Source(file.getFileName().toString(), JsonDocuments.read(file));
    }

    public static CitizensImporter.Preview preview(DocumentStore store, ImportConverters.Source source, ModuleHooks.Converter converter) throws IOException {
        String key = converter.descriptor().key().toString(), prefix = prefix(key);
        String id = JsonDocuments.hash("animus-module-import-v1\n" + key + "\n" + converter.moduleVersion() + "\n" + JsonDocuments.hash(source.text()));
        Path imports = store.resolve("imports");
        Files.createDirectories(imports);
        Path staged = store.resolve("imports/" + id);
        if (Files.exists(staged)) return new CitizensImporter().readPreview(staged, id);
        Outcome<ImportConverters.Conversion> converted = await(converter.convert().apply(source));
        if (converted.status() != Outcome.Status.OK) throw new IOException("Import converter " + key + " failed: " + describe(converted));
        ImportConverters.Conversion conversion = converted.value().orElseThrow();
        Path temporary = Files.createTempDirectory(imports, ".preview-");
        boolean published = false;
        try {
            DocumentStore.atomic(temporary.resolve("source.txt"), source.text());
            JsonObject manifest = new JsonObject(), mapping = new JsonObject();
            JsonArray issues = new JsonArray(), warnings = new JsonArray();
            conversion.warnings().forEach(warnings::add);
            Set<Integer> numbers = new HashSet<>();
            Set<UUID> uuids = new HashSet<>();
            int valid = 0;
            for (ImportConverters.Record record : conversion.records()) {
                if (!numbers.add(record.sourceId())) throw new IOException("Converter " + key + " returned duplicate source id " + record.sourceId());
                String npcId = prefix + "-" + record.sourceId();
                List<String> errors = new ArrayList<>();
                JsonObject definition;
                try { definition = JsonDocuments.object(record.definitionJson()); }
                catch (IOException invalid) { definition = new JsonObject(); errors.add("$: converter returned invalid JSON: " + invalid.getMessage()); }
                JsonElement originalSpawned = definition.has("spawned") ? definition.get("spawned").deepCopy() : JsonNull.INSTANCE;
                if (!definition.has("schemaVersion")) definition.addProperty("schemaVersion", 1);
                definition.addProperty("revision", 1);
                definition.addProperty("id", npcId);
                if (!definition.has("group")) definition.addProperty("group", prefix.length() > 64 ? prefix.substring(0, 64) : prefix);
                definition.addProperty("uuid", uuid(definition, key, record.sourceId()).toString());
                definition.addProperty("spawned", false);
                if (definition.has("skin")) errors.add("$.skin: module imports cannot bundle skins");
                dormant(definition);
                JsonObject migration = new JsonObject();
                migration.addProperty("importId", id);
                migration.addProperty("converter", key);
                migration.addProperty("sourceId", record.sourceId());
                migration.add("originalSpawned", originalSpawned);
                definition.add("migration", migration);
                String uuid = definition.get("uuid").getAsString();
                mapping.addProperty(Integer.toString(record.sourceId()), uuid);
                if (errors.isEmpty()) errors.addAll(CitizensImporter.validate(definition));
                if (!uuids.add(UUID.fromString(uuid))) errors.add("$.uuid: duplicate NPC UUID");
                JsonElement groupValue = definition.get("group");
                String group = groupValue.isJsonPrimitive() ? groupValue.getAsString() : prefix;
                String path = NpcPaths.path(group, npcId);
                if (!path.matches(CitizensImporter.NPC_PATH)) {
                    if (errors.isEmpty()) errors.add("$.id: " + npcId + " in group " + group + " is not a valid import path");
                    path = NpcPaths.path(prefix, npcId);
                }
                JsonObject report = new JsonObject();
                report.addProperty("sourceId", record.sourceId());
                report.addProperty("converter", key);
                JsonArray recordWarnings = new JsonArray();
                record.warnings().forEach(recordWarnings::add);
                record.warnings().forEach((String warning) -> warnings.add(npcId + ": " + warning));
                report.add("warnings", recordWarnings);
                JsonArray errorList = new JsonArray();
                errors.forEach(errorList::add);
                report.add("validationErrors", errorList);
                if (errors.isEmpty()) valid++;
                else issues.add(npcId + ": " + String.join("; ", errors));
                if (!path.matches(CitizensImporter.NPC_PATH)) throw new IOException("Converter key " + key + " is too long for imported NPC ids");
                CitizensImporter.writeBundle(temporary, path, JsonDocuments.write(definition), manifest);
                DocumentStore.atomic(temporary.resolve("reports/" + record.sourceId() + ".json"), JsonDocuments.write(report));
            }
            JsonObject summary = new JsonObject();
            summary.addProperty("schemaVersion", 1);
            summary.addProperty("converterVersion", CitizensImporter.CONVERTER_VERSION);
            summary.addProperty("converter", key);
            summary.addProperty("converterModuleVersion", converter.moduleVersion().toString());
            summary.addProperty("sourcePlugin", converter.descriptor().sourcePlugin());
            summary.addProperty("fieldPathFormat", "json-pointer");
            summary.addProperty("importId", id);
            summary.addProperty("sourceFile", source.fileName());
            summary.addProperty("sourceSha256", JsonDocuments.hash(source.text()));
            summary.addProperty("notice", "Module conversion by " + key + ". Imported NPCs are staged with spawned=false; imported combat and patrols stay disabled until reviewed.");
            summary.addProperty("records", conversion.records().size());
            summary.addProperty("valid", valid);
            summary.addProperty("skins", 0);
            summary.addProperty("behaviors", 0);
            summary.add("issues", issues);
            summary.add("migrationWarnings", warnings);
            summary.add("idMap", mapping);
            summary.add("documents", manifest);
            DocumentStore.atomic(temporary.resolve("report.json"), JsonDocuments.write(summary));
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new CitizensImporter().readPreview(staged, id);
        } finally {
            if (!published) CitizensImporter.deleteTemporary(temporary);
        }
    }

    private static UUID uuid(JsonObject definition, String key, int sourceId) {
        JsonElement given = definition.get("uuid");
        if (given != null && given.isJsonPrimitive() && given.getAsJsonPrimitive().isString()) {
            try { return UUID.fromString(given.getAsString()); }
            catch (IllegalArgumentException invalid) { /* derive a stable one below */ }
        }
        return UUID.nameUUIDFromBytes(("animus:module-import:v1:" + key + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }
    /** Imported combat and patrols stay off until an administrator reviews them, as for Citizens imports. */
    private static void dormant(JsonObject definition) {
        if (!definition.has("modules") || !definition.get("modules").isJsonObject()) return;
        JsonObject modules = definition.getAsJsonObject("modules");
        for (String module : List.of("animus:combat", "animus:navigation")) {
            if (!modules.has(module) || !modules.get(module).isJsonObject()) continue;
            JsonObject value = modules.getAsJsonObject(module);
            value.add("enabled", new JsonPrimitive(false));
            value.add("reviewAccepted", new JsonPrimitive(false));
        }
    }
    private static <T> Outcome<T> await(CompletionStage<Outcome<T>> stage) throws IOException {
        try { return stage.toCompletableFuture().get(70, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for an import converter", interrupted); }
        catch (ExecutionException | TimeoutException failure) { throw new IOException("Import converter did not settle: " + failure.getClass().getSimpleName(), failure); }
    }
    private static String describe(Outcome<?> outcome) {
        return outcome.status() + (outcome.diagnostics().isEmpty() ? "" : " " + outcome.diagnostics().getFirst().message());
    }
}
