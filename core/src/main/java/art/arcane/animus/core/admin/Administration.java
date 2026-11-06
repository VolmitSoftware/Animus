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

    public List<String> status() {
        List<String> lines = new ArrayList<>(List.of(runtime.status(), "NPC definitions: " + runtime.npcs().all().size(), settingsInEffect()));
        if (!runtime.npcs().diagnostics().isEmpty()) lines.add("Definition file problems: " + runtime.npcs().diagnostics().size() + "; validate for field errors; retained definitions remain active");
        return List.copyOf(lines);
    }

    /** Every definition as {@code id | name | revision}, in repository order. */
    public List<String> rows() {
        return runtime.npcs().all().stream().map((NpcDefinition definition) -> definition.id() + " | " + definition.name() + " | revision " + definition.revision()).toList();
    }

    public String inspect(String id) throws IOException { return runtime.npcs().draft(id).document().toString(); }

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
        return "Created dormant " + definition.id() + "; transaction " + tx;
    }

    /** Merges the top-level fields of a JSON object into the definition. */
    public String edit(String id, String patch) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject fields = JsonDocuments.object(patch);
        JsonObject edited = draft.document(); fields.entrySet().forEach(e -> edited.add(e.getKey(), e.getValue()));
        return "Saved; transaction " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
    }

    public String spawned(String id, boolean spawn) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject document = draft.document();
        document.addProperty("spawned", spawn);
        String tx = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return (spawn ? "Spawn requested for " : "Despawn requested for ") + id + "; transaction " + tx;
    }

    public String duplicate(String id, String copy) throws IOException {
        JsonObject object = runtime.npcs().draft(id).document();
        object.addProperty("id", copy); object.addProperty("uuid", UUID.randomUUID().toString());
        object.addProperty("revision", 1); object.addProperty("spawned", false); object.remove("migration");
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        return "Cloned; transaction " + runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object));
    }

    public String delete(String id) throws IOException { return "Deleted; rollback transaction " + runtime.npcs().delete(id); }

    public List<String> validate() throws IOException { return checked("validate", runtime.npcs().validate()); }

    public List<String> reload() throws IOException { return checked("reload", runtime.npcs().reload()); }

    private List<String> checked(String operation, List<String> errors) {
        return errors.isEmpty() ? List.of(operation + " successful; " + runtime.npcs().all().size() + " active definitions") : errors;
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
            catch (IOException | RuntimeException failure) { lines.add("config.toml changed but was not applied (" + failure.getMessage() + "); it applies at the next hotload or restart"); }
        }
        return lines;
    }
    @FunctionalInterface public interface CitizensStep { List<String> run(CitizensDropIn dropIn) throws IOException; }

    /**
     * Converts a file under {@code imports/inbox/}: a Citizens save, or any format a module converter reads.
     * {@code worlds} renames Citizens worlds; a converter is chosen by key or by the file.
     */
    public List<String> preview(String file, Optional<String> converter, Map<String, String> worlds) throws IOException {
        if (!file.matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]{1,8}")) throw new IOException("Use a plain filename under imports/inbox/");
        CitizensImporter importer = new CitizensImporter();
        Path path = runtime.documents().resolve("imports/inbox/" + file);
        CitizensImporter.Preview preview;
        if (converter.isEmpty() && file.endsWith(".yml") && CitizensImporter.recognizes(JsonDocuments.read(path)))
            preview = importer.preview(path, runtime.documents(), worlds);
        else {
            if (!worlds.isEmpty()) throw new IOException("World mappings apply only to Citizens imports");
            ImportConverters.Source source = ModuleImports.source(path);
            ModuleHooks.Converter selected = ModuleImports.select(runtime.moduleHooks(), source, converter);
            preview = ModuleImports.preview(runtime.documents(), source, selected);
            if (preview.valid() != preview.records())
                return List.of("Preview " + preview.id() + " by " + selected.descriptor().key(), "Records: " + preview.records() + ", valid: " + preview.valid(),
                        "Review " + preview.directory().resolve("report.json") + "; fix the source or converter before import apply.");
        }
        return List.of("Preview " + preview.id(), "Records: " + preview.records() + ", valid: " + preview.valid() + ", skins: " + preview.skins(), "Review " + preview.directory().resolve("report.json") + " before import apply.");
    }

    /** Stages how a preview lands next to existing definitions: {@code reject}, {@code replace} or {@code duplicate} with a copy label. */
    public List<String> stage(String importId, String mode, Optional<String> label) throws IOException {
        ImportApplications.Preview application = ImportApplications.stage(runtime.documents(), importId, mode, label.orElse(null));
        return List.of("Application " + application.applicationId(),
                "Mode: " + application.mode() + "; records: " + application.records() + "; replacements: " + application.replacements(),
                "Review " + application.report(),
                "Apply with /animus import apply-plan " + importId + " " + application.applicationId());
    }

    public List<String> apply(String importId) throws IOException { return installed(new CitizensImporter().apply(runtime.documents(), importId)); }

    public List<String> applyPlan(String importId, String applicationId) throws IOException { return installed(ImportApplications.apply(runtime.documents(), importId, applicationId)); }

    private List<String> installed(String tx) throws IOException {
        List<String> errors = runtime.npcs().reload();
        List<String> notes = catalogs(List.of(tx), false);
        if (!errors.isEmpty()) throw new IOException("Import stored; these definition files were refused and keep their last valid version: " + String.join("; ", errors));
        List<String> lines = new ArrayList<>(List.of("Installed dormant definitions; transaction " + tx));
        lines.addAll(notes);
        return lines;
    }

    public List<String> rollback(String transaction) throws IOException {
        String tx = runtime.documents().rollback(transaction);
        // Rebuild after an explicit undo, which intentionally restores older revisions.
        List<String> errors = runtime.npcs().reloadAfterRollback();
        List<String> notes = catalogs(List.of(tx), true);
        if (!errors.isEmpty()) throw new IOException("Rollback stored; reload failed: " + String.join("; ", errors));
        List<String> lines = new ArrayList<>(List.of("Rolled back; inverse transaction " + tx));
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
        try { change = CatalogChange.of(runtime.documents(), transactions, rollback).without(DocumentKind.NPC, DocumentKind.SKIN, DocumentKind.ROUTE); }
        catch (IOException unreadable) { return List.of("Catalogs not reloaded (" + unreadable.getMessage() + "); run /animus shop reload, /animus gui reload and /animus behaviors reload"); }
        if (!change.empty()) runtime.catalogs().reload(change);
        return List.of();
    }
}
