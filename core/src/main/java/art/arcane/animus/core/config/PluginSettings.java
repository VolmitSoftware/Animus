package art.arcane.animus.core.config;

import art.arcane.animus.core.text.ChatLine;
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
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Plugin-wide settings are TOML. NPC definitions, behaviors, state and generated data remain JSON. */
public record PluginSettings(boolean packetProofEnabled, boolean splashScreen, boolean metrics, NpcRuntimeSettings npcs,
                             FileWorkSettings files, ModuleSettings modules, BehaviorSettings behaviors, CombatBudgets combat, BuildSettings builds,
                             ShopSettings shops, EditorRelaySettings relay, EditorSettings editor, RigSettings rigs,
                             NavigationSettings navigation, HotloadSettings hotload, RecordingSettings recordings,
                             PlaceholderSettings placeholders) {
    /** As {@link #load(DocumentStore, Consumer)}, dropping the warnings; for tools and tests. */
    public static PluginSettings load(DocumentStore store) throws IOException { return load(store, (String ignored) -> {}); }

    /**
     * Reads {@code config.toml}, writing it with every default first when it is missing. {@code warnings} hears each key
     * Animus does not know, with its line; such keys are ignored. A wrong type or range refuses the file.
     */
    public static PluginSettings load(DocumentStore store, Consumer<String> warnings) throws IOException {
        Path toml = store.resolve("config.toml");
        if (Files.exists(toml)) return readToml(toml, warnings);
        Path legacy = store.resolve("config.json");
        JsonObject document;
        if (Files.exists(legacy)) {
            String original = JsonDocuments.read(legacy);
            try { document = JsonDocuments.object(original); }
            catch (IOException e) { throw new IOException("Cannot load " + legacy + ": " + e.getMessage(), e); }
            validate(document.deepCopy(), legacy, ConfigText.of(""), warnings);
            // Leave the old file intact and archive it before creating the authoritative TOML file.
            DocumentStore.atomic(store.resolve("backups/config-" + JsonDocuments.hash(original) + ".json"), original);
        } else document = defaults();
        String encoded;
        try { encoded = TomlCodec.toToml(document); }
        catch (RuntimeException e) { throw new IOException("Cannot migrate config.json to TOML; original retained", e); }
        if (!document.equals(TomlCodec.toJsonElement(encoded)))
            throw new IOException("config.json cannot be represented losslessly in TOML; original retained");
        DocumentStore.atomic(toml, "# Animus plugin settings. NPC and behavior documents use JSON. Every key: " + DOCS + "\n" + commented(encoded));
        return readToml(toml, warnings);
    }

    /** Every key with its default, as a new config.toml has it. Also the list of keys Animus knows. */
    static JsonObject defaults() {
        Gson gson = new Gson();
        JsonObject document = new JsonObject();
        document.addProperty("packetProofEnabled", false);
        document.addProperty("splashScreen", true);
        document.addProperty("metrics", true);
        document.add("npcs", gson.toJsonTree(NpcRuntimeSettings.DEFAULT));
        document.add("files", gson.toJsonTree(FileWorkSettings.DEFAULT));
        document.add("modules", gson.toJsonTree(ModuleSettings.DEFAULT));
        document.add("behaviors", gson.toJsonTree(BehaviorSettings.DEFAULT));
        document.add("combat", gson.toJsonTree(CombatBudgets.DEFAULT));
        document.add("builds", gson.toJsonTree(BuildSettings.DEFAULT));
        document.add("shops", shopDefaults());
        document.add("editor", editorDefaults());
        document.add("rigs", gson.toJsonTree(RigSettings.DEFAULT));
        document.add("navigation", gson.toJsonTree(NavigationSettings.DEFAULT));
        document.add("hotload", gson.toJsonTree(HotloadSettings.DEFAULT));
        document.add("recordings", gson.toJsonTree(RecordingSettings.DEFAULT));
        document.add("placeholders", gson.toJsonTree(PlaceholderSettings.DEFAULT));
        return document;
    }

    private static final JsonObject KNOWN = defaults();
    private static final String DOCS = ChatLine.docsUrl("03-configuration", "");
    /** The one-line note above each top-level table of a written config.toml; its docs section is named after the table. */
    private static final Map<String, String> TABLES = Map.ofEntries(Map.entry("npcs", "NPC presentation and viewer budgets"),
            Map.entry("files", "File work and backups"), Map.entry("modules", "Animus modules"), Map.entry("behaviors", "Behavior engine, triggers and actions"),
            Map.entry("combat", "Combat NPCs"), Map.entry("builds", "Builder NPCs"), Map.entry("shops", "Shops and their messages"),
            Map.entry("editor", "Web editor listener, relay and notices"), Map.entry("rigs", "Rigs and animations"), Map.entry("navigation", "Route planning"),
            Map.entry("hotload", "Live reload of hand edits"), Map.entry("recordings", "Recordings and ghost replays"),
            Map.entry("placeholders", "PlaceholderAPI refresh"));
    /** Notes above single keys and sub-tables that a table note does not explain. */
    private static final Map<String, String> NOTES = Map.ofEntries(
            Map.entry("files.backupsKept", "Rollback receipts kept regardless of age. Each edit keeps about two small files until both limits pass"),
            Map.entry("files.backupDays", "Receipts older than this many days, beyond backupsKept, are removed; 0 keeps only the newest backupsKept"),
            Map.entry("editor.relayEnabled", "The relay connects at the first /animus editor pair, then at each start while a token exists"),
            Map.entry("editor.relayUrl", "The Volmit relay; the server dials out to it, so no port needs to be open"),
            Map.entry("editor.relayReconnectMinSeconds", "Relay reconnect waits in seconds, shortest then longest"),
            Map.entry("editor.relayFrameBytes", "Largest relay frame in bytes, at most the relay's 1048576"),
            Map.entry("editor.relayConcurrentRequests", "Editor requests the relay runs at once"),
            Map.entry("shops.messages", "Shop texts with & colour codes; {name} is a placeholder. Delete a line to use the built-in text"),
            Map.entry("behaviors.triggers.playerCooldownTicks", "Spacing of one player's runs of an interaction trigger that sets no cooldownTicks; 0 turns it off"),
            Map.entry("behaviors.server.maximumAreas", "Areas tracked for player_area triggers"));

    private static String commented(String encoded) {
        StringBuilder text = new StringBuilder(encoded.length() + 2048);
        String table = "";
        for (String line : encoded.split("\n", -1)) {
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.substring(1, line.length() - 1);
                if (TABLES.containsKey(table)) text.append("# ").append(TABLES.get(table)).append(": ").append(DOCS).append('#').append(table).append('\n');
                else if (NOTES.containsKey(table)) text.append("# ").append(NOTES.get(table)).append('\n');
            } else {
                int equals = line.indexOf(" = ");
                String key = equals > 0 ? (table.isEmpty() ? "" : table + ".") + line.substring(0, equals).strip() : "";
                if (NOTES.containsKey(key)) text.append("# ").append(NOTES.get(key)).append('\n');
            }
            text.append(line).append('\n');
        }
        return text.substring(0, text.length() - 1);
    }

    private static PluginSettings readToml(Path path, Consumer<String> warnings) throws IOException {
        String text;
        try { text = JsonDocuments.read(path); }
        catch (IOException e) { throw new IOException("Cannot read " + path + ": " + e.getMessage(), e); }
        return parse(text, path, warnings);
    }

    /** As {@link #parse(String, Path, Consumer)}, dropping the warnings; for tools and tests. */
    public static PluginSettings parse(String text, Path label) throws IOException { return parse(text, label, (String ignored) -> {}); }

    /**
     * Settings from TOML text, as a start or a hotload reads them. Errors are one line that names {@code label}, the line
     * and the key; {@code warnings} hears each unknown key, which is ignored.
     */
    public static PluginSettings parse(String text, Path label, Consumer<String> warnings) throws IOException {
        if (text.length() > 256 * 1024) throw new IOException(label + ": plugin settings exceed 256 KiB");
        ConfigText lines = ConfigText.of(text);
        JsonElement data;
        try { data = TomlCodec.toJsonElement(text); }
        catch (IOException | RuntimeException invalid) {
            String where = lines.syntax(invalid);
            throw new IOException(label + (where.startsWith("line ") ? " " : ": ") + where, invalid);
        }
        String empty = lines.emptyValue();
        if (!empty.isEmpty()) throw new IOException(label + " " + empty);
        return validate(data, label, lines, warnings);
    }

    private static PluginSettings validate(JsonElement data, Path path, ConfigText lines, Consumer<String> warnings) throws IOException {
        if (data == null || !data.isJsonObject()) throw new IOException(path + ": expected settings table");
        JsonObject root = data.getAsJsonObject();
        lines.dropUnknown(path, root, KNOWN, "", warnings);
        boolean proof = topLevel(root, "packetProofEnabled", false, path, lines);
        boolean splash = topLevel(root, "splashScreen", true, path, lines);
        boolean metrics = topLevel(root, "metrics", true, path, lines);
        if (root.has("npcs") && !root.get("npcs").isJsonObject()) throw new IOException(path + lines.at("npcs") + ": npcs must be a table");
        NpcRuntimeSettings npcSettings = section(path, lines, "npcs", () -> npcs(table(root, "npcs")));
        FileWorkSettings fileSettings = section(path, lines, "files", () -> {
            JsonObject files = table(root, "files");
            FileWorkSettings d = FileWorkSettings.DEFAULT;
            return new FileWorkSettings(integer(files, "maximumOperations", d.maximumOperations()), integer(files, "deliveryBudgetMillis", d.deliveryBudgetMillis()),
                    integer(files, "backupsKept", d.backupsKept()), integer(files, "backupDays", d.backupDays()));
        });
        ModuleSettings moduleSettings = section(path, lines, "modules", () -> {
            JsonObject modules = table(root, "modules");
            ModuleSettings d = ModuleSettings.DEFAULT;
            return new ModuleSettings(bool(modules, "enabled", d.enabled()), integer(modules, "maximumInvocations", d.maximumInvocations()),
                    integer(modules, "invocationTimeoutMillis", d.invocationTimeoutMillis()), integer(modules, "deliveryBudgetMillis", d.deliveryBudgetMillis()));
        });
        BehaviorSettings behaviorSettings = section(path, lines, "behaviors", () -> behaviors(table(root, "behaviors")));
        CombatBudgets combatBudgets = section(path, lines, "combat", () -> combat(table(root, "combat")));
        BuildSettings buildSettings = section(path, lines, "builds", () -> builds(table(root, "builds")));
        ShopSettings shopSettings = section(path, lines, "shops", () -> shops(table(root, "shops")));
        RigSettings rigSettings = section(path, lines, "rigs", () -> rigs(table(root, "rigs")));
        NavigationSettings navigationSettings = section(path, lines, "navigation", () -> navigation(table(root, "navigation")));
        EditorRelaySettings relaySettings = section(path, lines, "editor", () -> relay(table(root, "editor")));
        EditorSettings editorSettings = section(path, lines, "editor", () -> EditorSettings.parse(table(root, "editor")));
        HotloadSettings hotloadSettings = section(path, lines, "hotload", () -> hotload(table(root, "hotload")));
        RecordingSettings recordingSettings = section(path, lines, "recordings", () -> recordings(table(root, "recordings")));
        PlaceholderSettings placeholderSettings = section(path, lines, "placeholders",
                () -> new PlaceholderSettings(integer(table(root, "placeholders"), "refreshTicks", PlaceholderSettings.DEFAULT.refreshTicks())));
        return new PluginSettings(proof, splash, metrics, npcSettings, fileSettings, moduleSettings, behaviorSettings, combatBudgets, buildSettings, shopSettings,
                relaySettings, editorSettings, rigSettings, navigationSettings, hotloadSettings, recordingSettings, placeholderSettings);
    }

    /** One table's reader; its range and type errors become one line naming the file, the line, the table and the key. */
    private static <T> T section(Path path, ConfigText lines, String table, Supplier<T> reader) throws IOException {
        try { return reader.get(); }
        catch (IllegalArgumentException | ArithmeticException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException(path + lines.atMessage(table, message) + ": " + table + ": " + message, e);
        }
    }

    private static boolean topLevel(JsonObject root, String key, boolean fallback, Path path, ConfigText lines) throws IOException {
        JsonElement value = root.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException(path + lines.at(key) + ": " + key + " must be a boolean");
        return value.getAsBoolean();
    }

    private static NpcRuntimeSettings npcs(JsonObject npc) {
        NpcRuntimeSettings d = NpcRuntimeSettings.DEFAULT;
        return new NpcRuntimeSettings(bool(npc, "enabled", d.enabled()),
                integer(npc, "viewDistance", d.viewDistance()), integer(npc, "maximumVisible", d.maximumVisible()),
                integer(npc, "maximumViewers", d.maximumViewers()), integer(npc, "updatesPerTick", d.updatesPerTick()),
                integer(npc, "packetsPerTick", d.packetsPerTick()), integer(npc, "pendingSpawnsPerViewer", d.pendingSpawnsPerViewer()),
                integer(npc, "lookUpdatesPerTick", d.lookUpdatesPerTick()), integer(npc, "simulationPerTick", d.simulationPerTick()),
                integer(npc, "queuePerViewer", d.queuePerViewer()), integer(npc, "degradedIntervalTicks", d.degradedIntervalTicks()));
    }

    private static HotloadSettings hotload(JsonObject document) {
        HotloadSettings defaults = HotloadSettings.DEFAULT;
        return new HotloadSettings(bool(document, "enabled", defaults.enabled()), bool(document, "sound", defaults.sound()),
                integer(document, "maximumFiles", defaults.maximumFiles()), integer(document, "readsPerPoll", defaults.readsPerPoll()),
                integer(document, "maximumFileBytes", defaults.maximumFileBytes()));
    }

    private static RecordingSettings recordings(JsonObject document) {
        RecordingSettings defaults = RecordingSettings.DEFAULT;
        return new RecordingSettings(bool(document, "enabled", defaults.enabled()), integer(document, "sampleTicks", defaults.sampleTicks()),
                integer(document, "maximumTicks", defaults.maximumTicks()), integer(document, "maximumFileBytes", defaults.maximumFileBytes()),
                integer(document, "maximumSessions", defaults.maximumSessions()), bool(document, "ghostEnabled", defaults.ghostEnabled()),
                integer(document, "ghostIntervalTicks", defaults.ghostIntervalTicks()), integer(document, "ghostStretchTicks", defaults.ghostStretchTicks()),
                bool(document, "ghostGlow", defaults.ghostGlow()), text(document, "ghostColor", defaults.ghostColor()),
                integer(document, "maximumPreviews", defaults.maximumPreviews()));
    }

    private static NavigationSettings navigation(JsonObject document) {
        NavigationSettings defaults = NavigationSettings.DEFAULT;
        return new NavigationSettings(integer(document, "routesPerTick", defaults.routesPerTick()),
                integer(document, "motionCellsPerTick", defaults.motionCellsPerTick()),
                integer(document, "planningStartsPerTick", defaults.planningStartsPerTick()),
                integer(document, "wakeChecksPerTick", defaults.wakeChecksPerTick()),
                integer(document, "planningJobs", defaults.planningJobs()),
                integer(document, "captureReadsPerTick", defaults.captureReadsPerTick()));
    }

    private static RigSettings rigs(JsonObject document) {
        RigSettings defaults = RigSettings.DEFAULT;
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
        editor.addProperty("relayReconnectMinSeconds", defaults.reconnectMinSeconds());
        editor.addProperty("relayReconnectMaxSeconds", defaults.reconnectMaxSeconds());
        editor.addProperty("relayFrameBytes", defaults.frameBytes());
        editor.addProperty("relayConcurrentRequests", defaults.concurrentRequests());
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
        shops.addProperty("style", defaults.style());
        shops.addProperty("priceFormat", defaults.priceFormat());
        shops.addProperty("flushMillis", defaults.flushMillis());
        JsonObject messages = new JsonObject();
        defaults.messages().forEach(messages::addProperty);
        shops.add("messages", messages);
        return shops;
    }

    private static ShopSettings shops(JsonObject document) {
        ShopSettings defaults = ShopSettings.DEFAULT;
        java.util.Map<String, String> messages = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JsonElement> entry : table(document, "messages").entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("messages." + entry.getKey() + " must be a string");
            messages.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new ShopSettings(bool(document, "enabled", defaults.enabled()), text(document, "economy", defaults.economy()),
                text(document, "style", defaults.style()), text(document, "priceFormat", defaults.priceFormat()), integer(document, "flushMillis", defaults.flushMillis()), messages);
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
                budget(trigger, "proximityTrackedPerViewer", triggers.proximityTrackedPerViewer(), 1, 1024, "triggers"),
                budget(trigger, "playerCooldownTicks", triggers.playerCooldownTicks(), 0, 72000, "triggers"));
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
                integer(actions, "navigationWaits", defaults.navigationWaits()),
                strings(actions, "rawTextBehaviors", defaults.rawTextBehaviors()));
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
            if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
            return value;
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException(table + "." + e.getMessage(), e); }
    }

    private static long longBudget(JsonObject object, String field, long fallback, long minimum, long maximum, String table) {
        if (!object.has(field)) return fallback;
        try {
            JsonElement raw = object.get(field);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be an integer");
            long value = raw.getAsBigDecimal().longValueExact();
            if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
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
