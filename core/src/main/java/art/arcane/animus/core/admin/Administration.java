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
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Shared, filesystem-only administration. The platform is responsible for permission and scheduling. */
public final class Administration {
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

    /**
     * The headline first: version, platform, definition and group counts and any file problems with the command that names
     * them. Then the settings in effect and the runtime line with the configured status message, for the details.
     */
    public List<String> status() {
        int problems = runtime.npcs().diagnostics().size() + runtime.npcs().groupProblems().size(), groups = runtime.npcs().groups().groups().size();
        String headline = "v" + art.arcane.animus.core.BuildIdentity.VERSION + " on " + runtime.environment().platform() + " " + runtime.environment().minecraftVersion()
                + " | " + runtime.npcs().all().size() + " NPCs" + (groups == 0 ? "" : ", " + groups + " groups")
                + (problems == 0 ? "" : " | " + problems + " file problem" + (problems == 1 ? "" : "s") + ", see /animus validate");
        return List.of(headline, settingsInEffect(), runtime.status());
    }

    /** Every definition as {@code id | name | revision}, in repository order. */
    public List<String> rows() {
        return runtime.npcs().all().stream().map((NpcDefinition definition) -> definition.id() + " | " + definition.name() + " | revision " + definition.revision()).toList();
    }

    /**
     * What {@code /animus inspect} shows: the effective definition, its stored and effective JSON, where each field came
     * from when trait groups apply, and the group problem, if any.
     */
    public record Inspection(NpcDefinition effective, String stored, String merged, List<String> provenance, Optional<String> problem) {
        /** {@code id | name | type | revision | spawned or dormant | groups} for the head line. */
        public String summary() {
            JsonObject data = effective.json();
            return effective.qualifiedId() + " | " + effective.name() + " | " + text(data, "type") + " | rev " + effective.revision()
                    + " | " + (effective.spawned() ? "spawned" : "dormant") + (effective.groups().isEmpty() ? "" : " | groups " + String.join(", ", effective.groups()));
        }

        /** {@code world x y z} of the definition's position, or empty when it has none. */
        public String position() {
            JsonElement position = effective.json().get("position");
            if (position == null || !position.isJsonObject()) return "";
            JsonObject at = position.getAsJsonObject();
            return text(at, "world") + " " + number(at, "x") + " " + number(at, "y") + " " + number(at, "z");
        }

        private static String text(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "?"; }

        private static String number(JsonObject object, String key) {
            if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "?";
            double value = object.get(key).getAsDouble();
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    public Inspection inspect(String id) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        NpcDefinition effective = runtime.npcs().find(id).orElseThrow(() -> new IOException("Unknown NPC " + id));
        List<String> provenance = effective.groups().isEmpty() ? List.of() : runtime.npcs().provenance(id).entrySet().stream()
                .map((Map.Entry<String, String> entry) -> entry.getKey() + " from " + entry.getValue()).toList();
        return new Inspection(effective, draft.document().toString(), effective.json().toString(), provenance,
                Optional.ofNullable(runtime.npcs().groupProblems().get(draft.path())));
    }

    public String create(String id, String type, String world, double x, double y, double z, String group, String name) throws IOException {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IOException("Coordinates must be numbers");
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", 1); object.addProperty("revision", 1);
        object.addProperty("uuid", UUID.randomUUID().toString()); object.addProperty("id", id);
        object.addProperty("type", type); object.addProperty("group", group);
        object.addProperty("name", name);
        object.addProperty("spawned", false);
        JsonObject position = new JsonObject(); position.addProperty("world", world);
        position.addProperty("x", x); position.addProperty("y", y); position.addProperty("z", z);
        position.addProperty("yaw", 0); position.addProperty("pitch", 0); object.add("position", position);
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        String tx = runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object));
        return "Created dormant " + definition.id() + "; tx " + tx;
    }

    /** Merges the top-level fields of a JSON object into the definition. */
    public String edit(String id, String patch) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject fields = JsonDocuments.object(patch);
        JsonObject edited = draft.document(); fields.entrySet().forEach(e -> edited.add(e.getKey(), e.getValue()));
        return "Saved " + id + "; tx " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
    }

    public String spawned(String id, boolean spawn) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject document = draft.document();
        document.addProperty("spawned", spawn);
        String tx = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return (spawn ? "Spawn requested for " : "Despawn requested for ") + id + "; tx " + tx;
    }

    public String duplicate(String id, String copy) throws IOException {
        JsonObject object = runtime.npcs().draft(id).document();
        object.addProperty("id", copy); object.addProperty("uuid", UUID.randomUUID().toString());
        object.addProperty("revision", 1); object.addProperty("spawned", false); object.remove("migration");
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        return "Cloned " + id + " as dormant " + copy + "; tx " + runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object));
    }

    public String delete(String id) throws IOException { return "Deleted " + id + "; tx " + runtime.npcs().delete(id); }

    public List<String> validate() throws IOException { return checked("All definitions valid", runtime.npcs().validate()); }

    public List<String> reload() throws IOException { return checked("Reloaded", runtime.npcs().reload()); }

    private List<String> checked(String operation, List<String> errors) {
        return errors.isEmpty() ? List.of(operation + "; " + runtime.npcs().all().size() + " active") : errors;
    }

    /** One step of the Citizens drop-in flow; the catalogs its transactions touched reload even when it fails part way. */
    public List<String> citizens(CitizensStep step) throws IOException {
        CitizensDropIn dropIn = new CitizensDropIn(runtime.documents(), runtime.npcs(), runtime.importHost()).combatLimit(runtime.combatBudgets().maximumNpcs());
        List<String> lines;
        try { lines = new ArrayList<>(step.run(dropIn)); }
        catch (IOException | RuntimeException failure) { catalogs(dropIn.committed(), dropIn.rolledBack()); throw failure; }
        lines.addAll(catalogs(dropIn.committed(), dropIn.rolledBack()));
        if (dropIn.settingsChanged()) {
            // accept commands and rollback edit config.toml; apply it now instead of waiting for a hotload.
            try { runtime.applySettings(art.arcane.animus.core.config.PluginSettings.load(runtime.documents())); }
            catch (IOException | RuntimeException failure) { lines.add("config.toml changed but not applied: " + failure.getMessage() + "; it applies at the next hotload or restart"); }
        }
        return lines;
    }
    @FunctionalInterface public interface CitizensStep { List<String> run(CitizensDropIn dropIn) throws IOException; }

    /**
     * Converts a file under {@code imports/inbox/}: a Citizens save, or any format a module converter reads.
     * {@code worlds} renames Citizens worlds; a converter is chosen by key or by the file.
     */
    public List<String> preview(String file, Optional<String> converter, Map<String, String> worlds) throws IOException {
        if (!file.matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]{1,8}")) throw new IOException("Give a plain file name under imports/inbox/, such as saves.yml");
        CitizensImporter importer = new CitizensImporter();
        Path path = runtime.documents().resolve("imports/inbox/" + file);
        CitizensImporter.Preview preview;
        if (converter.isEmpty() && file.endsWith(".yml") && CitizensImporter.recognizes(JsonDocuments.read(path)))
            preview = importer.preview(path, runtime.documents(), worlds);
        else {
            if (!worlds.isEmpty()) throw new IOException("worlds= applies only to Citizens saves");
            ImportConverters.Source source = ModuleImports.source(path);
            ModuleHooks.Converter selected = ModuleImports.select(runtime.moduleHooks(), source, converter);
            preview = ModuleImports.preview(runtime.documents(), source, selected);
            if (preview.valid() != preview.records())
                return List.of(Palette.error("Preview " + preview.id() + " by " + selected.descriptor().key() + ": " + (preview.records() - preview.valid()) + " of " + preview.records() + " records invalid"),
                        "Fix the source or converter and preview again; details in " + report(preview.id()));
        }
        return List.of("Preview " + preview.id() + ": " + preview.records() + " records, " + preview.valid() + " valid, " + preview.skins() + " skins; nothing installed",
                "Review " + report(preview.id()) + ", then /animus import apply " + preview.id() + " " + DocsLink.IMPORTS.url());
    }

    /** Stages how a preview lands next to existing definitions: {@code reject}, {@code replace} or {@code duplicate} with a copy label. */
    public List<String> stage(String importId, String mode, Optional<String> label) throws IOException {
        ImportApplications.Preview application = ImportApplications.stage(runtime.documents(), importId, mode, label.orElse(null));
        return List.of("Application " + application.applicationId() + ": " + application.mode() + ", " + application.records() + " records, " + application.replacements() + " replacements",
                "Review " + relative(application.report()) + ", then /animus import apply-plan " + importId + " " + application.applicationId());
    }

    public List<String> apply(String importId) throws IOException { return installed(new CitizensImporter().apply(runtime.documents(), importId)); }

    public List<String> applyPlan(String importId, String applicationId) throws IOException { return installed(ImportApplications.apply(runtime.documents(), importId, applicationId)); }

    private List<String> installed(String tx) throws IOException {
        List<String> errors = runtime.npcs().reload();
        List<String> notes = catalogs(List.of(tx), false);
        if (!errors.isEmpty()) throw new IOException("Import stored, but these files were refused and keep their last valid version: " + String.join("; ", errors));
        if (tx.equals("already-applied")) return List.of("Already installed; nothing changed");
        List<String> lines = new ArrayList<>(List.of(Palette.success("Installed dormant NPCs; tx " + tx)));
        lines.addAll(notes);
        return lines;
    }

    /** Where a preview's report lives, under the Animus data folder. */
    private static String report(String importId) { return "imports/" + importId + "/report.json"; }

    /** A path under the Animus data folder as the operator reads it there. */
    private String relative(Path path) throws IOException {
        Path root = runtime.documents().resolve("imports").getParent();
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }

    public List<String> rollback(String transaction) throws IOException {
        String tx = runtime.documents().rollback(transaction);
        // Rebuild after an explicit undo, which intentionally restores older revisions.
        List<String> errors = runtime.npcs().reloadAfterRollback();
        List<String> notes = catalogs(List.of(tx), true);
        if (!errors.isEmpty()) throw new IOException("Rollback stored, but reload failed: " + String.join("; ", errors));
        List<String> lines = new ArrayList<>(List.of(Palette.success("Rolled back " + transaction + "; tx " + tx)));
        lines.addAll(notes);
        return lines;
    }

    /**
     * Reloads the shops, GUIs, holograms, rigs and behaviors these committed transactions touched. The caller has already
     * reloaded NPC definitions. Returns a line only when the transactions could not be read.
     */
    private List<String> catalogs(List<String> transactions, boolean rollback) {
        if (transactions.isEmpty()) return List.of();
        CatalogChange change;
        try { change = CatalogChange.of(runtime.documents(), transactions, rollback).without(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE, DocumentKind.RECORDING, DocumentKind.GROUP); }
        catch (IOException unreadable) { return List.of(Palette.error("Catalogs not reloaded: " + unreadable.getMessage() + "; run /animus shop reload, gui reload and behaviors reload")); }
        if (!change.empty()) runtime.catalogs().reload(change);
        return List.of();
    }
}
