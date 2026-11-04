package art.arcane.animus.core.config;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.storage.*;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Plugin-wide settings are TOML. NPC definitions, behaviors, state and generated data remain JSON. */
public record PluginSettings(String statusMessage, boolean packetProofEnabled, NpcRuntimeSettings npcs,
                             FileWorkSettings files, ModuleSettings modules, BehaviorSettings behaviors) {
    public static PluginSettings load(DocumentStore store) throws IOException {
        Path toml = store.resolve("config.toml");
        if (Files.exists(toml)) return readToml(toml);
        Path legacy = store.resolve("config.json");
        JsonObject document;
        if (Files.exists(legacy)) {
            String original = JsonDocuments.read(legacy);
            try { document = JsonDocuments.object(original); }
            catch (IOException e) { throw new IOException("Cannot load " + legacy + ": " + e.getMessage(), e); }
            validate(document, legacy);
            // Leave the old file intact and archive it before creating the authoritative TOML file.
            DocumentStore.atomic(store.resolve("backups/config-" + JsonDocuments.hash(original) + ".json"), original);
        } else {
            document = new JsonObject();
            document.addProperty("statusMessage", "Hello from " + BuildIdentity.NAME);
            document.addProperty("packetProofEnabled", false);
            document.add("npcs", new Gson().toJsonTree(NpcRuntimeSettings.DEFAULT));
            document.add("files", new Gson().toJsonTree(FileWorkSettings.DEFAULT));
            document.add("modules", new Gson().toJsonTree(ModuleSettings.DEFAULT));
            document.add("behaviors", new Gson().toJsonTree(BehaviorSettings.DEFAULT));
        }
        String encoded;
        try { encoded = TomlCodec.toToml(document); }
        catch (RuntimeException e) { throw new IOException("Cannot migrate config.json to TOML; original retained", e); }
        if (!document.equals(TomlCodec.toJsonElement(encoded)))
            throw new IOException("config.json cannot be represented losslessly in TOML; original retained");
        DocumentStore.atomic(toml, "# Animus plugin settings. NPC and behavior documents use JSON.\n" + encoded);
        return readToml(toml);
    }

    private static PluginSettings readToml(Path path) throws IOException {
        try {
            String text = JsonDocuments.read(path);
            if (text.length() > 256 * 1024) throw new IOException("Plugin settings exceed 256 KiB");
            return validate(TomlCodec.toJsonElement(text), path);
        } catch (IOException | RuntimeException e) { throw new IOException("Cannot load " + path + ": " + e.getMessage(), e); }
    }

    private static PluginSettings validate(JsonElement data, Path path) throws IOException {
        if (data == null || !data.isJsonObject()) throw new IOException(path + ": expected settings table");
        JsonElement message = data.getAsJsonObject().get("statusMessage");
        if (message == null || !message.isJsonPrimitive() || !message.getAsJsonPrimitive().isString() || message.getAsString().isBlank())
            throw new IOException(path + ": statusMessage must be a nonblank string");
        JsonElement proof = data.getAsJsonObject().get("packetProofEnabled");
        if (proof != null && (!proof.isJsonPrimitive() || !proof.getAsJsonPrimitive().isBoolean()))
            throw new IOException(path + ": packetProofEnabled must be a boolean");
        NpcRuntimeSettings defaults = NpcRuntimeSettings.DEFAULT;
        JsonObject npc = new JsonObject();
        if (data.getAsJsonObject().has("npcs")) {
            if (!data.getAsJsonObject().get("npcs").isJsonObject()) throw new IOException(path + ": npcs must be a table");
            npc = data.getAsJsonObject().getAsJsonObject("npcs");
        }
        boolean enabled = defaults.enabled();
        if (npc.has("enabled")) {
            if (!npc.get("enabled").isJsonPrimitive() || !npc.getAsJsonPrimitive("enabled").isBoolean())
                throw new IOException(path + ": npcs.enabled must be a boolean");
            enabled = npc.get("enabled").getAsBoolean();
        }
        NpcRuntimeSettings npcSettings;
        try {
            npcSettings = new NpcRuntimeSettings(enabled,
                    integer(npc, "viewDistance", defaults.viewDistance()), integer(npc, "maximumVisible", defaults.maximumVisible()),
                    integer(npc, "maximumViewers", defaults.maximumViewers()), integer(npc, "updatesPerTick", defaults.updatesPerTick()));
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": npcs: " + e.getMessage(), e); }
        FileWorkSettings fileSettings;
        try {
            JsonObject files = table(data.getAsJsonObject(), "files");
            fileSettings = new FileWorkSettings(integer(files, "maximumOperations", FileWorkSettings.DEFAULT.maximumOperations()),
                    integer(files, "deliveryBudgetMillis", FileWorkSettings.DEFAULT.deliveryBudgetMillis()));
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": files: " + e.getMessage(), e); }
        ModuleSettings moduleSettings;
        try {
            JsonObject modules = table(data.getAsJsonObject(), "modules");
            moduleSettings = new ModuleSettings(bool(modules, "enabled", ModuleSettings.DEFAULT.enabled()),
                    integer(modules, "maximumInvocations", ModuleSettings.DEFAULT.maximumInvocations()),
                    integer(modules, "invocationTimeoutMillis", ModuleSettings.DEFAULT.invocationTimeoutMillis()),
                    integer(modules, "deliveryBudgetMillis", ModuleSettings.DEFAULT.deliveryBudgetMillis()));
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": modules: " + e.getMessage(), e); }
        BehaviorSettings behaviorSettings;
        try { behaviorSettings = behaviors(table(data.getAsJsonObject(), "behaviors")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": behaviors: " + e.getMessage(), e); }
        return new PluginSettings(message.getAsString(), proof != null && proof.getAsBoolean(), npcSettings, fileSettings, moduleSettings, behaviorSettings);
    }

    private static BehaviorSettings behaviors(JsonObject document) {
        BehaviorSettings defaults = BehaviorSettings.DEFAULT;
        JsonObject engine = table(document, "engine"), catalog = table(document, "catalog"), trigger = table(document, "triggers"), state = table(document, "state");
        BehaviorEngine.Limits execution = defaults.engine();
        BehaviorEngine.Limits engineLimits = new BehaviorEngine.Limits(
                budget(engine, "executions", execution.executions(), 1, 128, "engine"),
                budget(engine, "fibers", execution.fibers(), 1, 512, "engine"),
                budget(engine, "inFlight", execution.inFlight(), 1, 64, "engine"),
                budget(engine, "workPerTick", execution.workPerTick(), 1, 4096, "engine"),
                budget(engine, "totalSteps", execution.totalSteps(), 1, 1_000_000, "engine"),
                budget(engine, "lifetimeTicks", execution.lifetimeTicks(), 1, 72000, "engine"),
                budget(engine, "callDepth", execution.callDepth(), 1, 32, "engine"),
                budget(engine, "traceEntries", execution.traceEntries(), 1, 256, "engine"),
                budget(engine, "historyEntries", execution.historyEntries(), 1, 256, "engine"));
        BehaviorCatalog.Limits sources = defaults.catalog();
        BehaviorCatalog.Limits catalogLimits = new BehaviorCatalog.Limits(
                budget(catalog, "maximumDocuments", sources.maximumDocuments(), 1, 4096, "catalog"),
                budget(catalog, "maximumEntries", sources.maximumEntries(), 1, 32768, "catalog"),
                budget(catalog, "maximumDepth", sources.maximumDepth(), 2, 16, "catalog"),
                budget(catalog, "maximumDocumentBytes", sources.maximumDocumentBytes(), 1, 262144, "catalog"),
                longBudget(catalog, "maximumAggregateBytes", sources.maximumAggregateBytes(), 1, 64L * 1024 * 1024, "catalog"),
                budget(catalog, "maximumCapabilityChecks", sources.maximumCapabilityChecks(), 1, 16384, "catalog"));
        TriggerSettings triggers = defaults.triggers();
        TriggerSettings triggerBudgets = new TriggerSettings(bool(trigger, "enabled", triggers.enabled()),
                budget(trigger, "eventQueue", triggers.eventQueue(), 1, 4096, "triggers"),
                budget(trigger, "eventsPerTick", triggers.eventsPerTick(), 1, 1024, "triggers"),
                budget(trigger, "startsPerTick", triggers.startsPerTick(), 1, 64, "triggers"),
                budget(trigger, "maximumQueued", triggers.maximumQueued(), 1, 4096, "triggers"),
                budget(trigger, "queuePerNpc", triggers.queuePerNpc(), 1, 64, "triggers"),
                budget(trigger, "queuePerTrigger", triggers.queuePerTrigger(), 1, 64, "triggers"),
                budget(trigger, "queueLifetimeTicks", triggers.queueLifetimeTicks(), 1, 72000, "triggers"),
                budget(trigger, "concurrentTotal", triggers.concurrentTotal(), 1, 128, "triggers"),
                budget(trigger, "concurrentPerNpc", triggers.concurrentPerNpc(), 1, 16, "triggers"),
                budget(trigger, "concurrentPerTrigger", triggers.concurrentPerTrigger(), 1, 16, "triggers"),
                budget(trigger, "defaultCooldownTicks", triggers.defaultCooldownTicks(), 0, 72000, "triggers"),
                budget(trigger, "npcCooldownTicks", triggers.npcCooldownTicks(), 0, 72000, "triggers"),
                budget(trigger, "maximumBindings", triggers.maximumBindings(), 1, 65536, "triggers"),
                budget(trigger, "maximumTimers", triggers.maximumTimers(), 1, 16384, "triggers"),
                budget(trigger, "minimumTimerTicks", triggers.minimumTimerTicks(), 1, 72000, "triggers"),
                budget(trigger, "maximumProximityRadius", triggers.maximumProximityRadius(), 1, 64, "triggers"),
                budget(trigger, "proximityIntervalTicks", triggers.proximityIntervalTicks(), 1, 200, "triggers"),
                budget(trigger, "proximityViewersPerTick", triggers.proximityViewersPerTick(), 1, 256, "triggers"),
                budget(trigger, "proximityCandidates", triggers.proximityCandidates(), 1, 1024, "triggers"),
                budget(trigger, "proximityTrackedPerViewer", triggers.proximityTrackedPerViewer(), 1, 1024, "triggers"));
        BehaviorStateStore.Limits stored = defaults.state();
        BehaviorStateStore.Limits stateLimits = new BehaviorStateStore.Limits(
                budget(state, "maximumKeys", stored.maximumKeys(), 1, 1_048_576, "state"),
                budget(state, "maximumTransientKeys", stored.maximumTransientKeys(), 1, 1_048_576, "state"),
                budget(state, "maximumValueBytes", stored.maximumValueBytes(), 1, 4096, "state"),
                longBudget(state, "maximumBytes", stored.maximumBytes(), 1, 256L * 1024 * 1024, "state"),
                budget(state, "flushMillis", stored.flushMillis(), 100, 300_000, "state"));
        return new BehaviorSettings(bool(document, "enabled", defaults.enabled()),
                integer(document, "maximumOperations", defaults.maximumOperations()),
                integer(document, "operationsPerTick", defaults.operationsPerTick()),
                integer(document, "tickMillis", defaults.tickMillis()),
                integer(document, "deliveryBudgetMillis", defaults.deliveryBudgetMillis()), engineLimits, catalogLimits, triggerBudgets, stateLimits);
    }

    private static int budget(JsonObject object, String field, int fallback, int minimum, int maximum, String table) {
        try {
            int value = integer(object, field, fallback);
            if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
            return value;
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException(table + "." + e.getMessage(), e); }
    }

    private static long longBudget(JsonObject object, String field, long fallback, long minimum, long maximum, String table) {
        if (!object.has(field)) return fallback;
        try {
            JsonElement raw = object.get(field);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be an integer");
            long value = raw.getAsBigDecimal().longValueExact();
            if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(table + "." + field + " must be an integer within its budget", e);
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException(table + "." + e.getMessage(), e); }
    }

    private static JsonObject table(JsonObject document, String key) {
        if (!document.has(key)) return new JsonObject();
        if (!document.get(key).isJsonObject()) throw new IllegalArgumentException(key + " must be a table");
        return document.getAsJsonObject(key);
    }

    private static boolean bool(JsonObject document, String key, boolean fallback) {
        if (!document.has(key)) return fallback;
        if (!document.get(key).isJsonPrimitive() || !document.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException(key + " must be a boolean");
        return document.get(key).getAsBoolean();
    }

    private static int integer(JsonObject object, String field, int fallback) {
        if (!object.has(field)) return fallback;
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(field + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException(field + " must be an integer within its budget", e); }
    }
}
