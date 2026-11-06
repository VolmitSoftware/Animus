package art.arcane.animus.core.config;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.behavior.BehaviorEngine;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.state.BehaviorStateStore;
import art.arcane.animus.core.behavior.trigger.ServerTriggerSettings;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.storage.*;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Plugin-wide settings are TOML. NPC definitions, behaviors, state and generated data remain JSON. */
public record PluginSettings(String statusMessage, boolean packetProofEnabled, NpcRuntimeSettings npcs,
                             FileWorkSettings files, ModuleSettings modules, BehaviorSettings behaviors, CombatBudgets combat, BuildSettings builds,
                             ShopSettings shops, EditorRelaySettings relay, EditorSettings editor, RigSettings rigs,
                             NavigationSettings navigation, HotloadSettings hotload, RecordingSettings recordings) {
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
            document.add("combat", new Gson().toJsonTree(CombatBudgets.DEFAULT));
            document.add("builds", new Gson().toJsonTree(BuildSettings.DEFAULT));
            document.add("shops", shopDefaults());
            document.add("editor", editorDefaults());
            document.add("rigs", new Gson().toJsonTree(RigSettings.DEFAULT));
            document.add("navigation", new Gson().toJsonTree(NavigationSettings.DEFAULT));
            document.add("hotload", new Gson().toJsonTree(HotloadSettings.DEFAULT));
            document.add("recordings", new Gson().toJsonTree(RecordingSettings.DEFAULT));
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
        try { return parse(JsonDocuments.read(path), path); }
        catch (IOException | RuntimeException e) { throw new IOException("Cannot load " + path + ": " + e.getMessage(), e); }
    }

    /** Settings from TOML text, as a hotload reads them; errors name {@code label} and the field. */
    public static PluginSettings parse(String text, Path label) throws IOException {
        if (text.length() > 256 * 1024) throw new IOException("Plugin settings exceed 256 KiB");
        JsonElement data;
        try { data = TomlCodec.toJsonElement(text); }
        catch (IOException | RuntimeException invalid) { throw new IOException(label + ": invalid TOML: " + invalid.getMessage(), invalid); }
        return validate(data, label);
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
                    integer(npc, "maximumViewers", defaults.maximumViewers()), integer(npc, "updatesPerTick", defaults.updatesPerTick()),
                    integer(npc, "packetsPerTick", defaults.packetsPerTick()), integer(npc, "pendingSpawnsPerViewer", defaults.pendingSpawnsPerViewer()),
                    integer(npc, "lookUpdatesPerTick", defaults.lookUpdatesPerTick()), integer(npc, "simulationPerTick", defaults.simulationPerTick()),
                    integer(npc, "queuePerViewer", defaults.queuePerViewer()), integer(npc, "degradedIntervalTicks", defaults.degradedIntervalTicks()));
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": npcs: " + e.getMessage(), e); }
        FileWorkSettings fileSettings;
        try {
            JsonObject files = table(data.getAsJsonObject(), "files");
            fileSettings = new FileWorkSettings(integer(files, "maximumOperations", FileWorkSettings.DEFAULT.maximumOperations()),
                    integer(files, "deliveryBudgetMillis", FileWorkSettings.DEFAULT.deliveryBudgetMillis()),
                    integer(files, "backupsKept", FileWorkSettings.DEFAULT.backupsKept()), integer(files, "backupDays", FileWorkSettings.DEFAULT.backupDays()));
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
        CombatBudgets combatBudgets;
        try { combatBudgets = combat(table(data.getAsJsonObject(), "combat")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": combat: " + e.getMessage(), e); }
        BuildSettings buildSettings;
        try { buildSettings = builds(table(data.getAsJsonObject(), "builds")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": builds: " + e.getMessage(), e); }
        ShopSettings shopSettings;
        try { shopSettings = shops(table(data.getAsJsonObject(), "shops")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": shops: " + e.getMessage(), e); }
        RigSettings rigSettings;
        try { rigSettings = rigs(table(data.getAsJsonObject(), "rigs")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": rigs: " + e.getMessage(), e); }
        NavigationSettings navigationSettings;
        try { navigationSettings = navigation(table(data.getAsJsonObject(), "navigation")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": navigation: " + e.getMessage(), e); }
        EditorRelaySettings relaySettings;
        EditorSettings editorSettings;
        try {
            relaySettings = relay(table(data.getAsJsonObject(), "editor"));
            editorSettings = EditorSettings.parse(table(data.getAsJsonObject(), "editor"));
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": editor: " + e.getMessage(), e); }
        HotloadSettings hotloadSettings;
        try { hotloadSettings = hotload(table(data.getAsJsonObject(), "hotload")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": hotload: " + e.getMessage(), e); }
        RecordingSettings recordingSettings;
        try { recordingSettings = recordings(table(data.getAsJsonObject(), "recordings")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw new IOException(path + ": recordings: " + e.getMessage(), e); }
        return new PluginSettings(message.getAsString(), proof != null && proof.getAsBoolean(), npcSettings, fileSettings, moduleSettings, behaviorSettings, combatBudgets, buildSettings, shopSettings,
                relaySettings, editorSettings, rigSettings, navigationSettings, hotloadSettings, recordingSettings);
    }

    private static HotloadSettings hotload(JsonObject document) {
        HotloadSettings defaults = HotloadSettings.DEFAULT;
        for (String key : document.keySet())
            if (!java.util.Set.of("enabled", "sound", "maximumFiles", "readsPerPoll", "maximumFileBytes").contains(key))
                throw new IllegalArgumentException(key + " is not a hotload setting");
        return new HotloadSettings(bool(document, "enabled", defaults.enabled()), bool(document, "sound", defaults.sound()),
                integer(document, "maximumFiles", defaults.maximumFiles()), integer(document, "readsPerPoll", defaults.readsPerPoll()),
                integer(document, "maximumFileBytes", defaults.maximumFileBytes()));
    }

    private static RecordingSettings recordings(JsonObject document) {
        RecordingSettings defaults = RecordingSettings.DEFAULT;
        for (String key : document.keySet())
            if (!java.util.Set.of("enabled", "sampleTicks", "maximumTicks", "maximumFileBytes", "maximumSessions", "ghostEnabled", "ghostIntervalTicks",
                    "ghostStretchTicks", "ghostGlow", "ghostColor", "maximumPreviews").contains(key))
                throw new IllegalArgumentException(key + " is not a recording setting");
        return new RecordingSettings(bool(document, "enabled", defaults.enabled()), integer(document, "sampleTicks", defaults.sampleTicks()),
                integer(document, "maximumTicks", defaults.maximumTicks()), integer(document, "maximumFileBytes", defaults.maximumFileBytes()),
                integer(document, "maximumSessions", defaults.maximumSessions()), bool(document, "ghostEnabled", defaults.ghostEnabled()),
                integer(document, "ghostIntervalTicks", defaults.ghostIntervalTicks()), integer(document, "ghostStretchTicks", defaults.ghostStretchTicks()),
                bool(document, "ghostGlow", defaults.ghostGlow()), text(document, "ghostColor", defaults.ghostColor()),
                integer(document, "maximumPreviews", defaults.maximumPreviews()));
    }

    private static NavigationSettings navigation(JsonObject document) {
        NavigationSettings defaults = NavigationSettings.DEFAULT;
        for (String key : document.keySet())
            if (!java.util.Set.of("routesPerTick", "motionCellsPerTick", "planningStartsPerTick", "wakeChecksPerTick", "planningJobs", "captureReadsPerTick").contains(key))
                throw new IllegalArgumentException(key + " is not a navigation setting");
        return new NavigationSettings(integer(document, "routesPerTick", defaults.routesPerTick()),
                integer(document, "motionCellsPerTick", defaults.motionCellsPerTick()),
                integer(document, "planningStartsPerTick", defaults.planningStartsPerTick()),
                integer(document, "wakeChecksPerTick", defaults.wakeChecksPerTick()),
                integer(document, "planningJobs", defaults.planningJobs()),
                integer(document, "captureReadsPerTick", defaults.captureReadsPerTick()));
    }

    private static RigSettings rigs(JsonObject document) {
        RigSettings defaults = RigSettings.DEFAULT;
        for (String key : document.keySet())
            if (!java.util.Set.of("enabled", "maximumBones", "updatesPerTick", "sampleTicks", "plansPerTick").contains(key)) throw new IllegalArgumentException(key + " is not a rig setting");
        return new RigSettings(bool(document, "enabled", defaults.enabled()), integer(document, "maximumBones", defaults.maximumBones()),
                integer(document, "updatesPerTick", defaults.updatesPerTick()), integer(document, "sampleTicks", defaults.sampleTicks()),
                integer(document, "plansPerTick", defaults.plansPerTick()));
    }

    private static BuildSettings builds(JsonObject document) {
        BuildSettings defaults = BuildSettings.DEFAULT;
        return new BuildSettings(bool(document, "enabled", defaults.enabled()),
                integer(document, "maximumBuilds", defaults.maximumBuilds()),
                integer(document, "maximumTeam", defaults.maximumTeam()),
                integer(document, "placementsPerTick", defaults.placementsPerTick()),
                integer(document, "checksPerTick", defaults.checksPerTick()),
                integer(document, "undoPerTick", defaults.undoPerTick()),
                integer(document, "maximumVolume", defaults.maximumVolume()),
                integer(document, "maximumPalette", defaults.maximumPalette()),
                integer(document, "maximumSide", defaults.maximumSide()),
                integer(document, "maximumFileBytes", defaults.maximumFileBytes()),
                integer(document, "maximumUndoEntries", defaults.maximumUndoEntries()),
                integer(document, "saveSeconds", defaults.saveSeconds()));
    }

    private static JsonObject editorDefaults() {
        EditorRelaySettings defaults = EditorRelaySettings.DEFAULT;
        JsonObject editor = EditorSettings.DEFAULT.toJson();
        editor.addProperty("relayEnabled", defaults.enabled());
        editor.addProperty("relayUrl", defaults.url());
        return editor;
    }

    /** Reads only the relay keys of {@code [editor]}; the listener and pairing keys are read elsewhere. */
    private static EditorRelaySettings relay(JsonObject editor) {
        EditorRelaySettings defaults = EditorRelaySettings.DEFAULT;
        return new EditorRelaySettings(bool(editor, "relayEnabled", defaults.enabled()), text(editor, "relayUrl", defaults.url()),
                integer(editor, "relayReconnectMinSeconds", defaults.reconnectMinSeconds()),
                integer(editor, "relayReconnectMaxSeconds", defaults.reconnectMaxSeconds()),
                integer(editor, "relayFrameBytes", defaults.frameBytes()),
                integer(editor, "relayConcurrentRequests", defaults.concurrentRequests()));
    }

    private static JsonObject shopDefaults() {
        ShopSettings defaults = ShopSettings.DEFAULT;
        JsonObject shops = new JsonObject();
        shops.addProperty("enabled", defaults.enabled());
        shops.addProperty("economy", defaults.economy());
        shops.addProperty("priceFormat", defaults.priceFormat());
        shops.addProperty("flushMillis", defaults.flushMillis());
        return shops;
    }

    private static ShopSettings shops(JsonObject document) {
        ShopSettings defaults = ShopSettings.DEFAULT;
        for (String key : document.keySet())
            if (!java.util.Set.of("enabled", "economy", "priceFormat", "flushMillis", "messages").contains(key)) throw new IllegalArgumentException(key + " is not a shop setting");
        java.util.Map<String, String> messages = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JsonElement> entry : table(document, "messages").entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("messages." + entry.getKey() + " must be a string");
            messages.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new ShopSettings(bool(document, "enabled", defaults.enabled()), text(document, "economy", defaults.economy()),
                text(document, "priceFormat", defaults.priceFormat()), integer(document, "flushMillis", defaults.flushMillis()), messages);
    }

    private static String text(JsonObject document, String key, String fallback) {
        if (!document.has(key)) return fallback;
        if (!document.get(key).isJsonPrimitive() || !document.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key + " must be a string");
        return document.get(key).getAsString();
    }

    private static CombatBudgets combat(JsonObject document) {
        CombatBudgets defaults = CombatBudgets.DEFAULT;
        return new CombatBudgets(bool(document, "enabled", defaults.enabled()),
                integer(document, "maximumNpcs", defaults.maximumNpcs()),
                integer(document, "brainsPerTick", defaults.brainsPerTick()),
                integer(document, "targetScansPerTick", defaults.targetScansPerTick()),
                integer(document, "scanCandidates", defaults.scanCandidates()),
                integer(document, "scanCells", defaults.scanCells()),
                integer(document, "squadAlertsPerTick", defaults.squadAlertsPerTick()),
                integer(document, "pathRequestsPerTick", defaults.pathRequestsPerTick()),
                integer(document, "maximumHelpers", defaults.maximumHelpers()),
                integer(document, "helpersPerNpc", defaults.helpersPerNpc()),
                integer(document, "helperUpdatesPerTick", defaults.helperUpdatesPerTick()),
                integer(document, "helperLifetimeTicks", defaults.helperLifetimeTicks()),
                integer(document, "idleSleepTicks", defaults.idleSleepTicks()),
                integer(document, "eventsPerTick", defaults.eventsPerTick()),
                integer(document, "maximumScanRange", defaults.maximumScanRange()),
                integer(document, "maximumSpeed", defaults.maximumSpeed()));
    }

    private static BehaviorSettings behaviors(JsonObject document) {
        BehaviorSettings defaults = BehaviorSettings.DEFAULT;
        JsonObject engine = table(document, "engine"), catalog = table(document, "catalog"), trigger = table(document, "triggers"), state = table(document, "state"), server = table(document, "server");
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
                integer(document, "deliveryBudgetMillis", defaults.deliveryBudgetMillis()), engineLimits, catalogLimits, triggerBudgets, stateLimits,
                actions(table(document, "actions"), defaults.actions()), serverTriggers(server, defaults.server()));
    }

    private static ServerTriggerSettings serverTriggers(JsonObject server, ServerTriggerSettings defaults) {
        return new ServerTriggerSettings(bool(server, "enabled", defaults.enabled()),
                budget(server, "eventQueue", defaults.eventQueue(), 1, 4096, "server"),
                budget(server, "eventsPerTick", defaults.eventsPerTick(), 1, 1024, "server"),
                budget(server, "startsPerTick", defaults.startsPerTick(), 1, 64, "server"),
                budget(server, "queuePerTrigger", defaults.queuePerTrigger(), 1, 256, "server"),
                budget(server, "concurrentPerTrigger", defaults.concurrentPerTrigger(), 1, 64, "server"),
                budget(server, "concurrentTotal", defaults.concurrentTotal(), 1, 128, "server"),
                budget(server, "defaultCooldownTicks", defaults.defaultCooldownTicks(), 0, 72000, "server"),
                budget(server, "minimumTimerTicks", defaults.minimumTimerTicks(), 20, 72000, "server"),
                budget(server, "maximumCommands", defaults.maximumCommands(), 0, 64, "server"),
                budget(server, "maximumAreas", defaults.maximumAreas(), 0, 4096, "server"));
    }

    private static BuiltinActionSettings actions(JsonObject actions, BuiltinActionSettings defaults) {
        return new BuiltinActionSettings(integer(actions, "actionsPerTick", defaults.actionsPerTick()),
                integer(actions, "actionsPerNpcPerTick", defaults.actionsPerNpcPerTick()),
                integer(actions, "queuedActions", defaults.queuedActions()),
                integer(actions, "pendingChoices", defaults.pendingChoices()),
                integer(actions, "choiceLifetimeSeconds", defaults.choiceLifetimeSeconds()),
                strings(actions, "commandBehaviors", defaults.commandBehaviors()),
                strings(actions, "commandNamespaces", defaults.commandNamespaces()),
                integer(actions, "navigationWaits", defaults.navigationWaits()));
    }

    private static List<String> strings(JsonObject document, String key, List<String> fallback) {
        if (!document.has(key)) return fallback;
        if (!document.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array of strings");
        JsonArray source = document.getAsJsonArray(key);
        if (source.size() > 1024) throw new IllegalArgumentException(key + " has too many entries");
        List<String> values = new ArrayList<>(source.size());
        for (JsonElement value : source) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be an array of strings");
            values.add(value.getAsString());
        }
        return values;
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
