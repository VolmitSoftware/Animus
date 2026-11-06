package art.arcane.animus.core.admin;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.migration.CitizensImporter;
import art.arcane.animus.core.migration.ImportApplications;
import art.arcane.animus.core.migration.ModuleImports;
import art.arcane.animus.core.extension.ModuleHooks;
import art.arcane.animus.api.ImportConverters;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Shared, filesystem-only administration. The platform is responsible for permission and scheduling. */
public final class Administration {
    public static final List<String> COMMANDS = List.of("status", "list", "create", "inspect", "edit", "clone", "delete", "spawn", "despawn", "validate", "reload", "import", "rollback");
    private final AnimusRuntime runtime;
    public Administration(AnimusRuntime runtime) { this.runtime = runtime; }
    /** The main budgets of config.toml as they run now; a hotload changes this line without a restart. */
    private String settingsInEffect() {
        art.arcane.animus.core.config.NpcRuntimeSettings npcs = runtime.npcSettings();
        art.arcane.animus.core.config.EditorSettings editor = runtime.editorSettings();
        art.arcane.animus.core.config.CombatBudgets combat = runtime.combatBudgets();
        return "Settings in effect: npcs " + (npcs.enabled() ? "on" : "off") + " viewDistance=" + npcs.viewDistance() + " packetsPerTick=" + npcs.packetsPerTick()
                + " | navigation routesPerTick=" + runtime.navigationSettings().routesPerTick()
                + " | combat " + (combat.enabled() ? "on" : "off") + " brainsPerTick=" + combat.brainsPerTick() + " maximumNpcs=" + combat.maximumNpcs()
                + " | behaviors " + (runtime.behaviorSettings().enabled() ? "on" : "off") + " operationsPerTick=" + runtime.behaviorSettings().operationsPerTick()
                + " | builds " + (runtime.buildSettings().enabled() ? "on" : "off") + " maximumBuilds=" + runtime.buildSettings().maximumBuilds()
                + " | files maximumOperations=" + runtime.fileSettings().maximumOperations()
                + " | editor listener " + (editor.enabled() && editor.listenerEnabled() ? editor.listenAddress() + ":" + editor.port() : "off")
                + " relay " + (runtime.editorRelaySettings().enabled() ? "on" : "off");
    }

    public List<String> execute(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("status")) {
            List<String> lines = new ArrayList<>(List.of(runtime.status(), "NPC definitions: " + runtime.npcs().all().size(), settingsInEffect()));
            if (!runtime.npcs().diagnostics().isEmpty()) lines.add("Definition file problems: " + runtime.npcs().diagnostics().size() + "; validate for field errors; retained definitions remain active");
            return List.copyOf(lines);
        }
        return switch (args[0]) {
            case "list" -> {
                if (args.length > 2) throw new IOException("list [page]");
                int page;
                try { page = args.length == 2 ? Integer.parseInt(args[1]) : 1; }
                catch (NumberFormatException e) { throw new IOException("Page must be a positive integer"); }
                if (page < 1) throw new IOException("Page must be a positive integer");
                List<String> lines = new ArrayList<>();
                lines.add("NPC definitions: " + runtime.npcs().all().size() + "; page " + page);
                runtime.npcs().all().stream().skip((page - 1L) * 20).limit(20)
                        .map(d -> d.id() + " | " + d.name() + " | revision " + d.revision()).forEach(lines::add);
                yield lines;
            }
            case "inspect" -> { count(args, 2); yield List.of(runtime.npcs().draft(args[1]).document().toString()); }
            case "create" -> {
                if (args.length < 9) throw new IOException("create <id> <namespace:type> <world> <x> <y> <z> <group> <name...>");
                JsonObject object = new JsonObject();
                object.addProperty("schemaVersion", 1); object.addProperty("revision", 1);
                object.addProperty("uuid", UUID.randomUUID().toString()); object.addProperty("id", args[1]);
                object.addProperty("type", args[2]); object.addProperty("group", args[7]);
                object.addProperty("name", String.join(" ", Arrays.copyOfRange(args, 8, args.length)));
                object.addProperty("spawned", false);
                JsonObject position = new JsonObject(); position.addProperty("world", args[3]);
                try { position.addProperty("x", Double.parseDouble(args[4])); position.addProperty("y", Double.parseDouble(args[5])); position.addProperty("z", Double.parseDouble(args[6])); }
                catch (NumberFormatException e) { throw new IOException("Coordinates must be numbers"); }
                position.addProperty("yaw", 0); position.addProperty("pitch", 0); object.add("position", position);
                NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
                String tx = runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object));
                yield List.of("Created dormant " + definition.id() + "; transaction " + tx);
            }
            case "edit" -> {
                if (args.length < 3) throw new IOException("edit <id> <JSON object patch>");
                NpcRepository.Draft draft = runtime.npcs().draft(args[1]);
                JsonObject patch = JsonDocuments.object(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                JsonObject edited = draft.document(); patch.entrySet().forEach(e -> edited.add(e.getKey(), e.getValue()));
                yield List.of("Saved; transaction " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited)));
            }
            case "spawn", "despawn" -> {
                count(args, 2);
                NpcRepository.Draft draft = runtime.npcs().draft(args[1]);
                JsonObject document = draft.document();
                boolean spawn = args[0].equals("spawn");
                document.addProperty("spawned", spawn);
                String tx = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
                yield List.of((spawn ? "Spawn requested for " : "Despawn requested for ") + args[1] + "; transaction " + tx);
            }
            case "clone" -> {
                count(args, 3);
                JsonObject object = runtime.npcs().draft(args[1]).document();
                object.addProperty("id", args[2]); object.addProperty("uuid", UUID.randomUUID().toString());
                object.addProperty("revision", 1); object.addProperty("spawned", false); object.remove("migration");
                NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
                yield List.of("Cloned; transaction " + runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object)));
            }
            case "delete" -> { count(args, 2); yield List.of("Deleted; rollback transaction " + runtime.npcs().delete(args[1])); }
            case "validate", "reload" -> {
                count(args, 1);
                List<String> errors = args[0].equals("reload") ? runtime.npcs().reload() : runtime.npcs().validate();
                yield errors.isEmpty() ? List.of(args[0] + " successful; " + runtime.npcs().all().size() + " active definitions") : errors;
            }
            case "import" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("citizens")) {
                    CitizensDropIn dropIn = new CitizensDropIn(runtime.documents(), runtime.npcs(), runtime.importHost()).combatLimit(runtime.combatBudgets().maximumNpcs());
                    List<String> lines;
                    try { lines = new ArrayList<>(dropIn.execute(Arrays.copyOfRange(args, 2, args.length))); }
                    catch (IOException | RuntimeException failure) { catalogs(dropIn.committed(), dropIn.rolledBack()); throw failure; }
                    lines.addAll(catalogs(dropIn.committed(), dropIn.rolledBack()));
                    yield lines;
                }
                if (args.length < 3) throw new IOException("import citizens [apply|review|accept|rollback] | import preview <inbox-file> [converter=<namespace:name>] [oldWorld=newWorld ...] | apply <import-id> | stage <import-id> [reject|replace|duplicate <label>] | apply-plan <import-id> <application-id>");
                CitizensImporter importer = new CitizensImporter();
                if (args[1].equals("preview")) {
                    if (!args[2].matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]{1,8}")) throw new IOException("Use a plain filename under imports/inbox/");
                    Map<String, String> worlds = new TreeMap<>();
                    Optional<String> converter = Optional.empty();
                    for (int i = 3; i < args.length; i++) {
                        if (args[i].startsWith("converter=") && converter.isEmpty()) { converter = Optional.of(args[i].substring(10)); continue; }
                        String[] pair = args[i].split("=", 2);
                        if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || worlds.putIfAbsent(pair[0], pair[1]) != null) throw new IOException("Invalid world mapping: " + args[i]);
                    }
                    Path file = runtime.documents().resolve("imports/inbox/" + args[2]);
                    CitizensImporter.Preview preview;
                    if (converter.isEmpty() && args[2].endsWith(".yml") && CitizensImporter.recognizes(JsonDocuments.read(file)))
                        preview = importer.preview(file, runtime.documents(), worlds);
                    else {
                        if (!worlds.isEmpty()) throw new IOException("World mappings apply only to Citizens imports");
                        ImportConverters.Source source = ModuleImports.source(file);
                        ModuleHooks.Converter selected = ModuleImports.select(runtime.moduleHooks(), source, converter);
                        preview = ModuleImports.preview(runtime.documents(), source, selected);
                        if (preview.valid() != preview.records())
                            yield List.of("Preview " + preview.id() + " by " + selected.descriptor().key(), "Records: " + preview.records() + ", valid: " + preview.valid(),
                                    "Review " + preview.directory().resolve("report.json") + "; fix the source or converter before import apply.");
                    }
                    yield List.of("Preview " + preview.id(), "Records: " + preview.records() + ", valid: " + preview.valid() + ", skins: " + preview.skins(), "Review " + preview.directory().resolve("report.json") + " before import apply.");
                }
                if (args[1].equals("stage")) {
                    if (args.length > 5) throw new IOException("import stage <import-id> [reject|replace|duplicate <label>]");
                    var application = ImportApplications.stage(runtime.documents(), args[2], args.length > 3 ? args[3] : "reject", args.length > 4 ? args[4] : null);
                    yield List.of("Application " + application.applicationId(),
                            "Mode: " + application.mode() + "; records: " + application.records() + "; replacements: " + application.replacements(),
                            "Review " + application.report(),
                            "Apply with /animus import apply-plan " + args[2] + " " + application.applicationId());
                }
                String tx;
                if (args[1].equals("apply")) { count(args, 3); tx = importer.apply(runtime.documents(), args[2]); }
                else if (args[1].equals("apply-plan")) { count(args, 4); tx = ImportApplications.apply(runtime.documents(), args[2], args[3]); }
                else throw new IOException("Expected import preview, stage, apply or apply-plan");
                List<String> errors = runtime.npcs().reload();
                List<String> notes = catalogs(List.of(tx), false);
                if (!errors.isEmpty()) throw new IOException("Import stored; these definition files were refused and keep their last valid version: " + String.join("; ", errors));
                List<String> lines = new ArrayList<>(List.of("Installed dormant definitions; transaction " + tx));
                lines.addAll(notes);
                yield lines;
            }
            case "rollback" -> {
                count(args, 2);
                String tx = runtime.documents().rollback(args[1]);
                // Rebuild after an explicit undo, which intentionally restores older revisions.
                List<String> errors = runtime.npcs().reloadAfterRollback();
                List<String> notes = catalogs(List.of(tx), true);
                if (!errors.isEmpty()) throw new IOException("Rollback stored; reload failed: " + String.join("; ", errors));
                List<String> lines = new ArrayList<>(List.of("Rolled back; inverse transaction " + tx));
                lines.addAll(notes);
                yield lines;
            }
            default -> throw new IOException("Commands: " + String.join(", ", COMMANDS));
        };
    }
    /**
     * Reloads the shops, GUIs, holograms, rigs and behaviors these committed transactions touched. The caller has already
     * reloaded NPC definitions. Returns a line only when the transactions could not be read.
     */
    private List<String> catalogs(List<String> transactions, boolean rollback) {
        if (transactions.isEmpty()) return List.of();
        CatalogChange change;
        try { change = CatalogChange.of(runtime.documents(), transactions, rollback).without(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE); }
        catch (IOException unreadable) { return List.of("Catalogs not reloaded (" + unreadable.getMessage() + "); run /animus shop reload, /animus gui reload and /animus behaviors reload"); }
        if (!change.empty()) runtime.catalogs().reload(change);
        return List.of();
    }
    private static void count(String[] args, int expected) throws IOException { if (args.length != expected) throw new IOException("Incorrect argument count for " + args[0]); }
}
