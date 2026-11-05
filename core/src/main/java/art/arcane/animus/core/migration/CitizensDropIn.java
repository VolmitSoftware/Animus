package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * The drop-in Citizens migration: find a Citizens save (and dtlTraders and Builder files beside it) with no path,
 * stage a reviewed application, apply it, accept reviewed categories and roll the whole migration back.
 * Every step goes through {@link CitizensImporter}, {@link ImportApplications} and {@link ShopImporter}; this class
 * only finds files and keeps {@code imports/citizens/state.json}, the record of what the drop-in flow did.
 * Call it from the serial file lane.
 */
public final class CitizensDropIn {
    /** What the platform knows: its plugins folder, if it has one, and whether Citizens and Shopkeepers are running. */
    public record Host(Optional<Path> pluginsDirectory, BooleanSupplier citizensEnabled, BooleanSupplier shopkeepersEnabled) {
        public static final Host NONE = new Host(Optional.empty(), () -> false, () -> false);
    }
    /** A file or folder the flow found. {@code label} is how it is shown to the operator. */
    public record Found(Path path, String label) {}
    public record Sources(Optional<Found> saves, Optional<Found> traders, List<Found> schematics) {}
    /** The categories {@code /animus import citizens accept} turns on, in the order they are listed. */
    public static final List<String> CATEGORIES = List.of("spawn", "combat", "riding", "shops", "builder");
    public static final String STATE = "imports/citizens/state.json";
    static final String CITIZENS_RUNNING = "Citizens is still enabled. Animus will not apply, accept or run imported Citizens NPCs while it is, so no NPC runs twice. Stop the server, remove Citizens from plugins/, start it again and rerun this command.";
    private static final Set<String> DENIZEN = Set.of("assignment", "triggers", "constants", "denizen_flags", "denizenflags");

    private final DocumentStore store;
    private final NpcRepository npcs;
    private final Host host;

    public CitizensDropIn(DocumentStore store, NpcRepository npcs, Host host) { this.store = store; this.npcs = npcs; this.host = host; }

    /** The Citizens save first found among imports/saves.yml, imports/inbox/saves.yml and a left-over plugins/Citizens/saves.yml. */
    public Sources find() throws IOException {
        List<Found> saves = new ArrayList<>(List.of(new Found(store.resolve("imports/saves.yml"), "imports/saves.yml"),
                new Found(store.resolve("imports/inbox/saves.yml"), "imports/inbox/saves.yml")));
        List<Found> traders = new ArrayList<>(List.of(new Found(store.resolve("imports/dtltraders"), "imports/dtltraders"),
                new Found(store.resolve("imports/inbox/dtltraders"), "imports/inbox/dtltraders")));
        List<Found> schematics = new ArrayList<>(List.of(new Found(store.resolve("imports/schematics"), "imports/schematics")));
        if (host.pluginsDirectory().isPresent()) {
            Path plugins = host.pluginsDirectory().get();
            saves.add(new Found(plugins.resolve("Citizens/saves.yml"), "plugins/Citizens/saves.yml"));
            traders.add(new Found(plugins.resolve("dtlTraders"), "plugins/dtlTraders"));
            schematics.add(new Found(plugins.resolve("Builder/schematics"), "plugins/Builder/schematics"));
            schematics.add(new Found(plugins.resolve("Constructor/schematics"), "plugins/Constructor/schematics"));
        }
        Optional<Found> save = saves.stream().filter((Found found) -> Files.isRegularFile(found.path(), LinkOption.NOFOLLOW_LINKS)).findFirst();
        Optional<Found> trader = traders.stream().filter((Found found) -> Files.isDirectory(found.path(), LinkOption.NOFOLLOW_LINKS)).findFirst();
        List<Found> folders = schematics.stream().filter((Found found) -> Files.isDirectory(found.path(), LinkOption.NOFOLLOW_LINKS)).toList();
        return new Sources(save, trader, folders);
    }

    /** One startup line, or nothing when there is no Citizens save or its import is applied and reviewed. */
    public Optional<String> startupNotice() throws IOException {
        Sources sources = find();
        JsonObject state = state();
        boolean citizens = host.citizensEnabled().getAsBoolean();
        if (citizens && (sources.saves().isPresent() || applied(state))) return Optional.of("Citizens migration: " + CITIZENS_RUNNING);
        Optional<ShopkeepersDropIn.Found> shopkeepers = ShopkeepersDropIn.find(store, host.pluginsDirectory());
        if (shopkeepers.isPresent() && !ShopkeepersDropIn.applied(state)) {
            if (host.shopkeepersEnabled().getAsBoolean()) return Optional.of("Shopkeepers migration: " + ShopkeepersDropIn.RUNNING);
            if (sources.saves().isEmpty() || applied(state))
                return Optional.of("Shopkeepers migration: found " + shopkeepers.get().save().label() + ". Run /animus import citizens to preview it. Nothing is installed until you apply it.");
        }
        if (applied(state)) {
            int pending = pending(state).values().stream().mapToInt(Integer::intValue).sum();
            return pending == 0 ? Optional.empty() : Optional.of("Citizens migration: applied; " + pending + " reviewed settings are still off. Check them with /animus import citizens review");
        }
        if (sources.saves().isEmpty()) return Optional.empty();
        String source = JsonDocuments.hash(Files.readString(sources.saves().get().path()));
        if (state.has("sourceSha256") && state.get("sourceSha256").getAsString().equals(source) && state.has("applicationId"))
            return Optional.of("Citizens migration: " + sources.saves().get().label() + " is staged and not applied. Review " + state.get("report").getAsString() + ", then run /animus import citizens apply");
        return Optional.of("Citizens migration: found " + sources.saves().get().label() + (sources.traders().isPresent() ? " and " + sources.traders().get().label() : "")
                + ". Run /animus import citizens to preview it. Nothing is installed until you apply it.");
    }

    public List<String> execute(String[] args) throws IOException {
        String operation = args.length == 0 ? "stage" : args[0].toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "apply" -> { count(args, 1, "import citizens apply"); yield apply(); }
            case "rollback" -> { count(args, 1, "import citizens rollback"); yield rollback(); }
            case "review" -> { count(args, 1, "import citizens review"); yield review(); }
            case "accept" -> {
                if (args.length < 2) throw new IOException("import citizens accept <" + String.join("|", CATEGORIES) + "|all> ...");
                yield accept(List.of(args).subList(1, args.length));
            }
            default -> stage(args);
        };
    }

    /** Preview the save, the dtlTraders folder and the schematics, and stage one application plan. Nothing is installed. */
    List<String> stage(String[] args) throws IOException {
        String mode = "reject";
        String label = null;
        Map<String, String> worlds = new TreeMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.contains("=")) {
                String[] pair = argument.split("=", 2);
                if (pair[0].isBlank() || pair[1].isBlank() || worlds.putIfAbsent(pair[0], pair[1]) != null) throw new IOException("Invalid or repeated world mapping: " + argument);
            } else if (argument.equals("reject") || argument.equals("replace")) mode = argument;
            else if (argument.equals("duplicate")) {
                if (index + 1 >= args.length) throw new IOException("duplicate needs a copy label, such as duplicate town-copy");
                mode = argument;
                label = args[++index];
            } else throw new IOException("import citizens [reject|replace|duplicate <label>] [oldWorld=newWorld ...] | apply | review | accept <category...|all> | rollback");
        }
        JsonObject previous = state();
        Sources sources = find();
        Optional<ShopkeepersDropIn.Found> shopkeepers = ShopkeepersDropIn.find(store, host.pluginsDirectory());
        boolean shopkeepersApplied = ShopkeepersDropIn.applied(previous);
        if (applied(previous) || sources.saves().isEmpty()) {
            if (shopkeepers.isEmpty() || shopkeepersApplied) {
                if (applied(previous) || shopkeepersApplied) throw new IOException("A Citizens import is already applied. Roll it back with /animus import citizens rollback before staging another.");
                throw new IOException("No Citizens save found and no Shopkeepers save found. Put saves.yml in " + store.resolve("imports") + " or a Shopkeepers save.yml in "
                        + store.resolve("imports/shopkeepers") + ", or leave plugins/Citizens/saves.yml or plugins/Shopkeepers/data/save.yml in place after removing the plugin.");
            }
            JsonObject state = applied(previous) ? previous : new JsonObject();
            state.addProperty("schemaVersion", 1);
            List<String> lines = new ArrayList<>(ShopkeepersDropIn.stage(store, shopkeepers.get(), sources.saves().map(Found::path), state));
            lines.add(applied(previous) ? "The shops bind to the Citizens NPCs already installed. Nothing installed yet." : "No Citizens save: Citizens shopkeepers bind when a Citizens import is applied later. Nothing installed yet.");
            lines.add(host.shopkeepersEnabled().getAsBoolean() ? ShopkeepersDropIn.RUNNING : "Next: /animus import citizens apply");
            writeState(state);
            return lines;
        }
        Found save = sources.saves().get();
        CitizensImporter.Preview preview = new CitizensImporter().preview(save.path(), store, worlds);
        if (preview.valid() != preview.records())
            throw new IOException(preview.records() - preview.valid() + " of " + preview.records() + " records do not convert; see " + preview.directory().resolve("report.json"));
        ImportApplications.Preview application = ImportApplications.stage(store, preview.id(), mode, label);
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", 1);
        state.addProperty("source", save.label());
        state.addProperty("sourceSha256", preview.summary().get("sourceSha256").getAsString());
        state.addProperty("importId", preview.id());
        state.addProperty("applicationId", application.applicationId());
        state.addProperty("mode", application.mode());
        if (label != null) state.addProperty("copyLabel", label);
        state.add("worldMap", preview.summary().get("worldMap"));
        state.addProperty("report", "imports/" + preview.id() + "/report.json");
        List<String> lines = new ArrayList<>();
        lines.add("Citizens save " + save.label() + ": " + preview.records() + " NPCs, " + preview.valid() + " valid, " + preview.skins() + " unique skins; converter v" + preview.converterVersion());
        lines.add("Folders: " + groups(preview.summary()));
        lines.addAll(summaryLines(preview));
        lines.add("Application " + application.mode() + ": " + application.records() + " definitions, " + application.replacements() + " replacements");
        if (sources.traders().isPresent()) {
            ShopImporter.Preview shops = new ShopImporter().preview(sources.traders().get().path(), Optional.of(save.path()), store);
            state.addProperty("shopImportId", shops.id());
            state.addProperty("shopSource", sources.traders().get().label());
            lines.add("dtlTraders " + sources.traders().get().label() + ": " + shops.shops() + " shops, " + shops.entries() + " entries, " + shops.issues() + " issues; report imports/shops/" + shops.id() + "/report.json");
        }
        JsonArray schematics = new JsonArray();
        int legacy = 0;
        for (Found folder : sources.schematics()) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder.path())) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (name.endsWith(".schematic")) { legacy++; continue; }
                    if (!name.matches("[A-Za-z0-9_.-]{1,64}\\.(?:schem|nbt)")) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("source", folder.path().resolve(name).toString());
                    entry.addProperty("name", name);
                    schematics.add(entry);
                }
            }
        }
        state.add("schematics", schematics);
        if (!sources.schematics().isEmpty())
            lines.add("Schematics: " + schematics.size() + " .schem/.nbt files copy into schematics/ on apply" + (legacy > 0 ? "; " + legacy + " old .schematic files must be saved again as .schem first" : ""));
        if (shopkeepersApplied) {
            state.add("shopkeepers", previous.get("shopkeepers").deepCopy());
            state.add("transactions", previous.get("transactions").deepCopy());
            lines.add("Shopkeepers shops are already installed; their Citizens shopkeepers bind when this import is applied.");
        } else if (shopkeepers.isPresent()) lines.addAll(ShopkeepersDropIn.stage(store, shopkeepers.get(), Optional.of(save.path()), state));
        lines.add("Nothing installed. Full report: " + preview.directory().resolve("report.json"));
        if (host.citizensEnabled().getAsBoolean()) lines.add(CITIZENS_RUNNING);
        else if (ShopkeepersDropIn.staged(state) && host.shopkeepersEnabled().getAsBoolean()) lines.add(ShopkeepersDropIn.RUNNING);
        else lines.add("Next: /animus import citizens apply");
        writeState(state);
        return lines;
    }

    List<String> apply() throws IOException {
        JsonObject state = state();
        boolean citizensPending = state.has("applicationId") && !applied(state);
        boolean shopkeepersPending = ShopkeepersDropIn.staged(state);
        if (!citizensPending && !shopkeepersPending) {
            if (applied(state) || ShopkeepersDropIn.applied(state)) throw new IOException("Already applied. Use /animus import citizens review, accept or rollback.");
            throw new IOException("Nothing staged. Run /animus import citizens first.");
        }
        if (citizensPending) refuseWhileCitizensRuns();
        if (shopkeepersPending && host.shopkeepersEnabled().getAsBoolean()) throw new IOException(ShopkeepersDropIn.RUNNING);
        List<String> lines = citizensPending ? applyCitizens(state) : new ArrayList<>();
        JsonArray transactions = state.has("transactions") ? state.getAsJsonArray("transactions") : new JsonArray();
        try {
            if (shopkeepersPending) lines.addAll(ShopkeepersDropIn.apply(store, state, npcs.all(), transactions));
            else lines.addAll(ShopkeepersDropIn.bindPending(store, state, npcs.all(), transactions));
        } catch (IOException | RuntimeException failure) {
            if (!citizensPending) throw failure;
            lines.add("Shopkeepers import failed and nothing of it was installed: " + failure.getMessage() + ". The Citizens import stays applied; fix the cause and run /animus import citizens apply again.");
            return lines;
        }
        state.add("transactions", transactions);
        writeState(state);
        List<String> errors = npcs.reload();
        if (!errors.isEmpty()) lines.add("Definition problems after reload: " + String.join("; ", errors));
        if (!citizensPending) lines.add("Shops stay closed on their NPCs and new mob NPCs stay despawned. Next: /animus import citizens review, then accept shops and spawn");
        return lines;
    }

    private List<String> applyCitizens(JsonObject state) throws IOException {
        String importId = state.get("importId").getAsString();
        JsonArray transactions = state.has("transactions") ? state.getAsJsonArray("transactions") : new JsonArray();
        String definitions = ImportApplications.apply(store, importId, state.get("applicationId").getAsString());
        transactions.add(definitions);
        List<String> lines = new ArrayList<>();
        try {
            if (state.has("shopImportId")) {
                String shops = new ShopImporter().apply(store, state.get("shopImportId").getAsString());
                transactions.add(shops);
                lines.add("Shops installed; transaction " + shops);
            }
        } catch (IOException | RuntimeException failure) {
            store.rollback(definitions);
            npcs.reloadAfterRollback();
            throw new IOException("Shop import failed, so the NPC import was rolled back: " + failure.getMessage(), failure);
        }
        JsonArray copied = new JsonArray();
        for (JsonElement value : state.getAsJsonArray("schematics")) {
            JsonObject schematic = value.getAsJsonObject();
            Path target = store.resolve("schematics/" + schematic.get("name").getAsString());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.createDirectories(target.getParent());
            Files.copy(Path.of(schematic.get("source").getAsString()), target);
            JsonObject entry = new JsonObject();
            entry.addProperty("path", "schematics/" + schematic.get("name").getAsString());
            entry.addProperty("sha256", sha256(target));
            copied.add(entry);
        }
        state.add("transactions", transactions);
        state.add("copiedSchematics", copied);
        state.addProperty("state", "applied");
        writeState(state);
        List<String> errors = npcs.reload();
        lines.addFirst("Installed " + state.get("importId").getAsString().substring(0, 12) + " dormant; transaction " + definitions);
        if (!copied.isEmpty()) lines.add("Copied " + copied.size() + " schematics into schematics/");
        if (!errors.isEmpty()) lines.add("Definition problems after reload: " + String.join("; ", errors));
        lines.add("Every NPC is installed at its Citizens position and stays despawned; combat, riding, shops and builds stay off.");
        lines.add("Next: /animus import citizens review, then /animus import citizens accept all (or one category at a time)");
        return lines;
    }

    List<String> rollback() throws IOException {
        JsonObject state = state();
        boolean citizens = applied(state);
        if (!citizens && !ShopkeepersDropIn.applied(state)) throw new IOException("No applied Citizens import to roll back.");
        List<String> lines = new ArrayList<>();
        JsonArray transactions = state.getAsJsonArray("transactions");
        for (int index = transactions.size() - 1; index >= 0; index--) {
            String transaction = transactions.get(index).getAsString();
            lines.add("Rolled back " + transaction + "; inverse " + store.rollback(transaction));
        }
        ShopkeepersDropIn.rolledBack(state);
        int kept = 0;
        for (JsonElement value : state.has("copiedSchematics") ? state.getAsJsonArray("copiedSchematics") : new JsonArray()) {
            Path file = store.resolve(value.getAsJsonObject().get("path").getAsString());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && sha256(file).equals(value.getAsJsonObject().get("sha256").getAsString())) Files.delete(file);
            else kept++;
        }
        if (kept > 0) lines.add(kept + " copied schematics changed since the import and were kept");
        state.remove("transactions");
        state.remove("copiedSchematics");
        if (citizens) state.addProperty("state", "rolled-back");
        writeState(state);
        List<String> errors = npcs.reloadAfterRollback();
        if (!errors.isEmpty()) lines.add("Definition problems after reload: " + String.join("; ", errors));
        lines.add("The Citizens import is undone. The staged preview stays; /animus import citizens apply installs it again.");
        return lines;
    }

    List<String> review() throws IOException {
        JsonObject state = state();
        boolean citizens = applied(state);
        if (!citizens && !ShopkeepersDropIn.applied(state)) throw new IOException("No applied Citizens import. Run /animus import citizens, then apply.");
        List<String> lines = new ArrayList<>();
        Map<String, Integer> pending = pending(state);
        if (citizens) lines.add("Citizens import " + state.get("importId").getAsString().substring(0, 12) + ": " + imported(state).size() + " NPCs installed");
        ShopkeepersDropIn.reviewLine(state).ifPresent(lines::add);
        for (String category : CATEGORIES)
            lines.add(category + ": " + pending.get(category) + " waiting" + switch (category) {
                case "spawn" -> " (NPCs Citizens had spawned; others stay despawned)";
                case "combat" -> " (Sentinel settings in animus:combat)";
                case "riding" -> " (controllable NPCs in animus:riding)";
                case "shops" -> " (dtlTraders and Shopkeepers shops in animus:shop)";
                default -> " (Builder sections in animus:builder; builds still start with /animus build start)";
            });
        Map<String, Integer> preserved = preserved(state);
        lines.add("Kept but not run: " + (preserved.isEmpty() ? "nothing" : counts(preserved)));
        if (citizens) lines.add("Report: " + state.get("report").getAsString() + "; per-NPC reports under imports/" + state.get("importId").getAsString() + "/reports/");
        lines.add("Accept after checking: /animus import citizens accept <" + String.join("|", CATEGORIES) + "|all>");
        return lines;
    }

    List<String> accept(List<String> requested) throws IOException {
        refuseWhileCitizensRuns();
        JsonObject state = state();
        if (!applied(state) && !ShopkeepersDropIn.applied(state)) throw new IOException("No applied Citizens import to review.");
        Set<String> categories = new java.util.LinkedHashSet<>();
        for (String category : requested) {
            String lowered = category.toLowerCase(Locale.ROOT);
            if (lowered.equals("all")) categories.addAll(CATEGORIES);
            else if (CATEGORIES.contains(lowered)) categories.add(lowered);
            else throw new IOException("Unknown review category: " + category + "; expected " + String.join(", ", CATEGORIES) + " or all");
        }
        List<NpcDefinition> changed = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, Integer> accepted = new LinkedHashMap<>();
        categories.forEach((String category) -> accepted.put(category, 0));
        List<String> problems = new ArrayList<>();
        for (NpcDefinition definition : imported(state)) {
            JsonObject json = definition.json();
            boolean edited = false;
            for (String category : categories) {
                if (!waiting(json, category)) continue;
                acceptCategory(json, category);
                accepted.merge(category, 1, Integer::sum);
                edited = true;
            }
            if (!edited) continue;
            if (definition.revision() == Long.MAX_VALUE) { problems.add(definition.id() + ": revision exhausted"); continue; }
            json.addProperty("revision", definition.revision() + 1);
            try {
                changed.add(NpcDefinition.parse(JsonDocuments.write(json)));
                expected.put(definition.path(), store.hash(definition.path()));
            } catch (IOException invalid) { problems.add(definition.id() + ": " + invalid.getMessage()); }
        }
        List<String> lines = new ArrayList<>();
        if (changed.isEmpty()) lines.add("Nothing waiting in " + String.join(", ", categories));
        else {
            String transaction = DefinitionBatch.commit(store, changed, expected, List.of());
            state.getAsJsonArray("transactions").add(transaction);
            writeState(state);
            List<String> errors = npcs.reload();
            lines.add("Accepted " + String.join(", ", accepted.entrySet().stream().map((Map.Entry<String, Integer> entry) -> entry.getKey() + " " + entry.getValue()).toList())
                    + " on " + changed.size() + " NPCs; transaction " + transaction);
            if (!errors.isEmpty()) lines.add("Definition problems after reload: " + String.join("; ", errors));
        }
        problems.stream().limit(10).forEach((String problem) -> lines.add("Not accepted: " + problem));
        if (problems.size() > 10) lines.add((problems.size() - 10) + " more not accepted");
        lines.add("/animus import citizens rollback undoes the import and every acceptance.");
        return lines;
    }

    /** True when the category's setting on this imported definition is still off and would be turned on by accepting it. */
    static boolean waiting(JsonObject json, String category) {
        JsonObject modules = json.has("modules") && json.get("modules").isJsonObject() ? json.getAsJsonObject("modules") : new JsonObject();
        return switch (category) {
            case "spawn" -> {
                JsonObject migration = json.getAsJsonObject("migration");
                JsonElement original = migration.get("originalSpawned");
                boolean despawned = original != null && original.isJsonPrimitive() && original.getAsJsonPrimitive().isBoolean() && !original.getAsBoolean();
                yield !despawned && !json.get("spawned").getAsBoolean();
            }
            case "combat" -> unreviewed(modules, "animus:combat");
            case "riding" -> unreviewed(modules, "animus:riding");
            case "shops" -> unreviewed(modules, "animus:shop");
            case "builder" -> unreviewed(modules, "animus:builder");
            default -> false;
        };
    }

    private static void acceptCategory(JsonObject json, String category) {
        if (category.equals("spawn")) { json.addProperty("spawned", true); return; }
        String module = switch (category) {
            case "combat" -> "animus:combat";
            case "riding" -> "animus:riding";
            case "shops" -> "animus:shop";
            default -> "animus:builder";
        };
        JsonObject section = json.getAsJsonObject("modules").getAsJsonObject(module);
        section.addProperty("reviewAccepted", true);
        if (section.has("enabled")) section.addProperty("enabled", true);
    }

    private static boolean unreviewed(JsonObject modules, String module) {
        JsonElement value = modules.get(module);
        return value != null && value.isJsonObject() && !new JsonPrimitive(true).equals(value.getAsJsonObject().get("reviewAccepted"));
    }

    private Map<String, Integer> pending(JsonObject state) { return pending(imported(state).stream().map(NpcDefinition::json).toList()); }
    private Map<String, Integer> preserved(JsonObject state) { return preserved(imported(state).stream().map(NpcDefinition::json).toList()); }

    static Map<String, Integer> pending(List<JsonObject> definitions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        CATEGORIES.forEach((String category) -> counts.put(category, 0));
        for (JsonObject json : definitions)
            for (String category : CATEGORIES) if (waiting(json, category)) counts.merge(category, 1, Integer::sum);
        return counts;
    }

    /** Citizens data that converts to nothing Animus runs: counted by the traits kept in citizens:review. */
    static Map<String, Integer> preserved(List<JsonObject> definitions) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonObject definition : definitions) {
            JsonObject modules = definition.getAsJsonObject("modules");
            if (modules == null || !modules.has("citizens:review") || !modules.get("citizens:review").isJsonObject()) continue;
            JsonObject traits = modules.getAsJsonObject("citizens:review");
            if (traits.has("waypoints")) counts.merge("waypoints", 1, Integer::sum);
            if (traits.has("commandtrait")) counts.merge("commands", 1, Integer::sum);
            if (traits.has("text")) counts.merge("text", 1, Integer::sum);
            if (traits.has("lookclose")) counts.merge("lookclose", 1, Integer::sum);
            if (traits.keySet().stream().anyMatch((String trait) -> DENIZEN.contains(trait) || trait.startsWith("denizen"))) counts.merge("denizen", 1, Integer::sum);
        }
        return counts;
    }

    private List<NpcDefinition> imported(JsonObject state) {
        Set<String> importIds = new java.util.HashSet<>();
        if (state.has("importId")) importIds.add(state.get("importId").getAsString());
        if (ShopkeepersDropIn.applied(state)) ShopkeepersDropIn.importId(state).ifPresent(importIds::add);
        return npcs.all().stream().filter((NpcDefinition definition) -> {
            JsonObject json = definition.json();
            return json.has("migration") && json.get("migration").isJsonObject() && json.getAsJsonObject("migration").has("importId")
                    && importIds.contains(json.getAsJsonObject("migration").get("importId").getAsString());
        }).toList();
    }

    static String groups(JsonObject summary) {
        Map<String, Integer> folders = new TreeMap<>();
        for (String path : summary.getAsJsonObject("documents").keySet())
            if (path.startsWith("npcs/")) folders.merge(path.substring(5, path.indexOf('/', 5)), 1, Integer::sum);
        return String.join(", ", folders.entrySet().stream().map((Map.Entry<String, Integer> entry) -> "npcs/" + entry.getKey() + "/ " + entry.getValue()).toList());
    }

    /** The review counts of a staged preview, the same numbers {@code review} shows after apply. */
    static List<String> summaryLines(CitizensImporter.Preview preview) throws IOException {
        JsonObject summary = preview.summary();
        List<JsonObject> staged = new ArrayList<>();
        for (String path : summary.getAsJsonObject("documents").keySet())
            if (path.startsWith("npcs/")) staged.add(JsonDocuments.object(JsonDocuments.read(preview.directory().resolve(path))));
        List<String> lines = new ArrayList<>();
        lines.add("Off until accepted: " + counts(pending(staged)) + "; the rest stay despawned");
        Map<String, Integer> preserved = preserved(staged);
        lines.add("Kept, not run: " + (preserved.isEmpty() ? "nothing" : counts(preserved)));
        lines.add("Distinct traits: " + summary.getAsJsonObject("traits").size() + "; warnings: " + summary.getAsJsonArray("migrationWarnings").size()
                + "; unresolved NPC references: " + summary.get("unresolvedReferences").getAsInt());
        return lines;
    }

    private static String counts(Map<String, Integer> counts) {
        return String.join(", ", counts.entrySet().stream().map((Map.Entry<String, Integer> entry) -> entry.getKey() + " " + entry.getValue()).toList());
    }

    private void refuseWhileCitizensRuns() throws IOException { if (host.citizensEnabled().getAsBoolean()) throw new IOException(CITIZENS_RUNNING); }
    private static boolean applied(JsonObject state) { return state.has("state") && state.get("state").getAsString().equals("applied"); }
    JsonObject state() throws IOException {
        String text = store.read(STATE);
        return text == null ? new JsonObject() : JsonDocuments.object(text);
    }
    private void writeState(JsonObject state) throws IOException { DocumentStore.atomic(store.resolve(STATE), JsonDocuments.write(state)); }
    private static String sha256(Path file) throws IOException {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void count(String[] args, int expected, String usage) throws IOException { if (args.length != expected) throw new IOException(usage); }
}
