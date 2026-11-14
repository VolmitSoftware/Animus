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
import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.runtime.SkinDocuments;
import art.arcane.animus.core.storage.*;
import art.arcane.animus.core.text.ChatLine;
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
    /**
     * The main budgets of config.toml as they run now; a hotload changes this line without a restart. {@code editorListener}
     * is the listener as it runs, since the configured port may not be bound.
     */
    private String settingsInEffect(String editorListener) {
        art.arcane.animus.core.config.NpcRuntimeSettings npcs = runtime.npcSettings();
        art.arcane.animus.core.config.CombatBudgets combat = runtime.combatBudgets();
        return "Settings in effect: npcs " + (npcs.enabled() ? "on" : "off") + " viewDistance=" + npcs.viewDistance() + " packetsPerTick=" + npcs.packetsPerTick()
                + " | navigation routesPerTick=" + runtime.navigationSettings().routesPerTick()
                + " | combat " + (combat.enabled() ? "on" : "off") + " brainsPerTick=" + combat.brainsPerTick() + " maximumNpcs=" + combat.maximumNpcs()
                + " | behaviors " + (runtime.behaviorSettings().enabled() ? "on" : "off") + " operationsPerTick=" + runtime.behaviorSettings().operationsPerTick()
                + " | builds " + (runtime.buildSettings().enabled() ? "on" : "off") + " maximumBuilds=" + runtime.buildSettings().maximumBuilds()
                + " | files maximumOperations=" + runtime.fileSettings().maximumOperations()
                + " | editor listener " + editorListener
                + " relay " + (runtime.editorRelaySettings().enabled() ? "on" : "off");
    }

    /**
     * The headline first: version, platform, definition and group counts and any file problems with the command that names
     * them. Then the settings in effect and the runtime line with the configured status message, for the details.
     */
    public List<String> status(String editorListener) {
        int problems = runtime.npcs().diagnostics().size() + runtime.npcs().groupProblems().size(), groups = runtime.npcs().groups().groups().size();
        String headline = "v" + art.arcane.animus.core.BuildIdentity.VERSION + " on " + runtime.environment().server() + " " + runtime.environment().minecraftVersion()
                + " | " + runtime.npcs().all().size() + " NPCs" + (groups == 0 ? "" : ", " + groups + " trait groups")
                + (problems == 0 ? "" : " | " + problems + " file problem" + (problems == 1 ? "" : "s") + ", see /animus validate");
        return List.of(headline, settingsInEffect(editorListener), runtime.status());
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
        /** {@code id | name | type | revision | spawned or despawned | trait groups} for the head line. */
        public String summary() {
            JsonObject data = effective.json();
            return effective.qualifiedId() + " | " + effective.name() + " | " + text(data, "type") + " | rev " + effective.revision()
                    + " | " + (effective.spawned() ? "spawned" : "despawned") + (effective.groups().isEmpty() ? "" : " | trait groups " + String.join(", ", effective.groups()));
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
        NpcDefinition effective = runtime.npcs().find(id).orElseThrow(() -> new IOException("No NPC named " + id + "; /animus list shows ids"));
        List<String> provenance = effective.groups().isEmpty() ? List.of() : runtime.npcs().provenance(id).entrySet().stream()
                .map((Map.Entry<String, String> entry) -> entry.getKey() + " from " + entry.getValue()).toList();
        return new Inspection(effective, draft.document().toString(), effective.json().toString(), provenance,
                Optional.ofNullable(runtime.npcs().groupProblems().get(draft.path())));
    }

    /** {@link #create(String, String, String, double, double, double, String, String, Optional)} with no owner, as from the console. */
    public String create(String id, String type, String world, double x, double y, double z, String group, String name) throws IOException {
        return create(id, type, world, x, y, z, group, name, Optional.empty());
    }

    /** Saves a new despawned NPC facing south; {@code owner} is the player who made it, written as {@code owner} and editable afterwards. */
    public String create(String id, String type, String world, double x, double y, double z, String group, String name, Optional<UUID> owner) throws IOException {
        Creation creation = creation(id, type, world, new art.arcane.animus.core.virtual.Vec3(x, y, z), 0, group, name, owner, false);
        return "Created " + creation.definition().id() + ", despawned; tx " + runtime.npcs().apply(creation.draft());
    }

    /** A new NPC's checked definition and the draft that writes it. */
    public record Creation(NpcDefinition definition, NpcRepository.Draft draft) {}

    /**
     * The file a new NPC gets, not yet written: at {@code point} facing {@code yaw} with pitch 0, spawned or dormant.
     * Refused when another NPC in any folder already has the id.
     */
    public Creation creation(String id, String type, String world, art.arcane.animus.core.virtual.Vec3 point, float yaw, String group, String name,
                             Optional<UUID> owner, boolean spawned) throws IOException {
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) throw new IOException("Coordinates must be numbers, such as x=0 y=64 z=0");
        Optional<String> body = bodyProblem(runtime, type);
        if (body.isPresent()) throw new IOException(body.get().substring("$.type: ".length()));
        taken(id);
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", NpcDefinition.SCHEMA_VERSION); object.addProperty("revision", 1);
        object.addProperty("uuid", UUID.randomUUID().toString()); object.addProperty("id", id);
        object.addProperty("type", art.arcane.animus.core.platform.MinecraftIds.normalize(type)); object.addProperty("group", group);
        object.addProperty("name", name);
        object.addProperty("spawned", spawned);
        owner.ifPresent((UUID player) -> object.addProperty("owner", player.toString()));
        object.add("position", position(world, point, yaw));
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        return new Creation(definition, new NpcRepository.Draft(definition.path(), "missing", 0, object));
    }

    /**
     * This creation with a template's fields laid over it, such as the look of a MythicMobs mob: each field replaces the
     * new NPC's own, and {@code modules} merges by module key. {@code keepName} keeps the name the command gave.
     */
    public Creation templated(Creation creation, JsonObject fields, boolean keepName) throws IOException {
        JsonObject object = creation.draft().document().deepCopy();
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            String key = field.getKey();
            if (keepName && key.equals("name")) continue;
            if (key.equals("modules") && field.getValue().isJsonObject() && object.has("modules") && object.get("modules").isJsonObject()) {
                for (Map.Entry<String, JsonElement> module : field.getValue().getAsJsonObject().entrySet())
                    object.getAsJsonObject("modules").add(module.getKey(), module.getValue().deepCopy());
            } else object.add(key, field.getValue().deepCopy());
        }
        Optional<String> body = bodyProblem(runtime, object.get("type").getAsString());
        if (body.isPresent()) throw new IOException(body.get().substring("$.type: ".length()));
        List<String> errors = NpcDefinition.validate(object);
        if (!errors.isEmpty()) throw new IOException(errors.getFirst());
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        return new Creation(definition, new NpcRepository.Draft(definition.path(), "missing", 0, object));
    }

    /**
     * Why an NPC of this body type could never be shown: Animus presents only the bodies the server's transport shows, and
     * any other type would be saved and then refused at spawn.
     */
    public static Optional<String> bodyProblem(AnimusRuntime runtime, String type) {
        java.util.Set<String> shown = runtime.bodyTypes();
        if (shown.contains(art.arcane.animus.core.platform.MinecraftIds.normalize(type))) return Optional.empty();
        return Optional.of("$.type: " + type + " cannot be shown; the bodies Animus shows are " + String.join(", ", new TreeSet<>(shown)));
    }

    /** Refuses an id another NPC already has, in any folder, with the toolkit's wording. */
    private void taken(String id) throws IOException {
        if (runtime.npcs().stored().stream().anyMatch((NpcDefinition existing) -> existing.id().equals(id)))
            throw new IOException("An NPC named " + id + " already exists; /animus inspect " + id + " shows it, or pick another id");
    }

    private static JsonObject position(String world, art.arcane.animus.core.virtual.Vec3 point, float yaw) {
        JsonObject position = new JsonObject();
        position.addProperty("world", world);
        position.addProperty("x", point.x()); position.addProperty("y", point.y()); position.addProperty("z", point.z());
        position.addProperty("yaw", Math.round(yaw * 100) / 100.0); position.addProperty("pitch", 0);
        return position;
    }

    /** Sets the display name; colour codes are kept. */
    public String rename(String id, String name) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject document = draft.document();
        document.addProperty("name", name);
        NpcDefinition.parse(JsonDocuments.write(document));
        return "Renamed " + id + " to " + name + "; tx " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
    }

    /** Saves a new position, in any world, facing {@code yaw} with pitch 0. */
    public String move(String id, art.arcane.animus.core.virtual.WorldPosition at, float yaw) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject document = draft.document();
        document.add("position", position(at.world(), at.point(), yaw));
        NpcDefinition.parse(JsonDocuments.write(document));
        String tx = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return "Moved " + id + " to " + at.world() + " " + art.arcane.animus.core.command.Coordinates.shown(at.point()) + "; tx " + tx;
    }

    /** Merges a JSON object into the definition as {@link #merge} does; the reply names what changed. */
    public String edit(String id, String patch) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject edited = draft.document();
        JsonObject fields = patch(patch);
        Optional<String> problem = patchProblem(runtime, fields);
        if (problem.isPresent()) throw new IOException(problem.get());
        List<String> changes = merge(edited, fields);
        return "Saved " + id + ": " + describe(changes) + "; tx " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
    }

    /** An edit's JSON object; text that is not one is refused in plain words, without the parser's own message. */
    public static JsonObject patch(String text) throws IOException {
        try { return JsonDocuments.object(text); }
        catch (IOException | RuntimeException unreadable) { throw new IOException("The patch is not a JSON object; write it like {\"name\": \"Captain\"}, with double quotes"); }
    }

    /** Top-level fields an edit may set: the definition schema's, plus the importers' {@code migration}. */
    private static final Set<String> FIELDS = fields();

    private static Set<String> fields() {
        Set<String> known = new java.util.HashSet<>(NpcSchemaGenerator.schema().getAsJsonObject("properties").keySet());
        known.add("migration");
        return Set.copyOf(known);
    }

    /**
     * Why an edit is refused before the definition rules run: a field no definition has, or a skin with no file under
     * {@code skins/}. Removing a field with {@code null} is always allowed. Read on the file lane.
     */
    public static Optional<String> patchProblem(AnimusRuntime runtime, JsonObject patch) {
        DocumentStore documents = runtime.documents();
        for (Map.Entry<String, JsonElement> entry : patch.entrySet())
            if (!entry.getValue().isJsonNull() && !FIELDS.contains(entry.getKey())) return Optional.of("$." + entry.getKey() + ": unknown field");
        JsonElement type = patch.get("type");
        if (type != null && type.isJsonPrimitive()) {
            Optional<String> body = bodyProblem(runtime, type.getAsString());
            if (body.isPresent()) return body;
        }
        JsonElement skin = patch.get("skin");
        if (skin != null && skin.isJsonPrimitive() && skin.getAsJsonPrimitive().isString() && skin.getAsString().matches("[a-f0-9]{64}")) {
            try { SkinDocuments.read(documents, skin.getAsString()); }
            catch (IOException | RuntimeException missing) { return Optional.of("$.skin: no skin file skins/" + skin.getAsString() + ".json"); }
        }
        return Optional.empty();
    }

    /**
     * Applies {@code patch} to {@code document} as a JSON merge patch (RFC 7386): an object merges into an object field by
     * field, a null removes the field, and any other value replaces it whole, arrays included. So editing one module keeps
     * the others, and one edit can change the body type and drop the skin or pose the new body cannot show. Returns each
     * changed path in order, as {@code set a.b} or {@code removed a.b}; values that were already so are left out.
     */
    public static List<String> merge(JsonObject document, JsonObject patch) {
        List<String> changes = new ArrayList<>();
        merge(document, patch, "", changes);
        return changes;
    }

    private static void merge(JsonObject document, JsonObject patch, String prefix, List<String> changes) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            String key = entry.getKey(), path = prefix + key;
            JsonElement value = entry.getValue(), current = document.get(key);
            if (value.isJsonNull()) {
                if (document.remove(key) != null) changes.add("removed " + path);
            } else if (value.isJsonObject() && current != null && current.isJsonObject()) merge(current.getAsJsonObject(), value.getAsJsonObject(), path + ".", changes);
            else {
                JsonElement next = value.isJsonObject() ? stripped(value.getAsJsonObject()) : value;
                if (next.equals(current)) continue;
                document.add(key, next);
                changes.add("set " + path);
            }
        }
    }

    /** A new object field as the patch gives it, without the nulls that would remove fields inside it. */
    private static JsonObject stripped(JsonObject patch) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            if (entry.getValue().isJsonNull()) continue;
            result.add(entry.getKey(), entry.getValue().isJsonObject() ? stripped(entry.getValue().getAsJsonObject()) : entry.getValue());
        }
        return result;
    }

    private static final int DESCRIBED = 6;

    /** {@code set name, modules.x.range; removed skin}, the first {@link #DESCRIBED} paths and how many more, or {@code no change}. */
    public static String describe(List<String> changes) {
        if (changes.isEmpty()) return "no change";
        List<String> shown = changes.subList(0, Math.min(DESCRIBED, changes.size()));
        List<String> set = new ArrayList<>(), removed = new ArrayList<>();
        for (String change : shown) (change.startsWith("set ") ? set : removed).add(change.substring(change.indexOf(' ') + 1));
        List<String> parts = new ArrayList<>();
        if (!set.isEmpty()) parts.add("set " + String.join(", ", set));
        if (!removed.isEmpty()) parts.add("removed " + String.join(", ", removed));
        return String.join("; ", parts) + (changes.size() > DESCRIBED ? " (+" + (changes.size() - DESCRIBED) + " more)" : "");
    }

    /**
     * The reply to an edit the definition rules refuse. When the edit changes the body type, it shows how to drop the
     * refused field in the same edit, since fields such as a skin or pose stay behind otherwise.
     */
    public static String refusal(String id, JsonObject patch, String message) {
        String line = "Cannot save " + id + ": " + message;
        int colon = message.indexOf(':');
        if (!patch.has("type") || !message.startsWith("$.") || colon < 0) return line;
        JsonObject example = new JsonObject();
        example.add("type", patch.get("type"));
        example.add(message.substring(2, colon).split("[.\\[]")[0], JsonNull.INSTANCE);
        return line + ". Set a field to null in the same edit to remove it, such as " + example;
    }

    public String spawned(String id, boolean spawn) throws IOException {
        NpcRepository.Draft draft = runtime.npcs().draft(id);
        JsonObject document = draft.document();
        document.addProperty("spawned", spawn);
        String tx = runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return (spawn ? "Spawn requested for " : "Despawned ") + id + "; tx " + tx;
    }

    public String duplicate(String id, String copy) throws IOException {
        taken(copy);
        JsonObject object = runtime.npcs().draft(id).document();
        object.addProperty("id", copy); object.addProperty("uuid", UUID.randomUUID().toString());
        object.addProperty("revision", 1); object.addProperty("spawned", false); object.remove("migration");
        NpcDefinition definition = NpcDefinition.parse(JsonDocuments.write(object));
        return "Cloned " + id + " as " + copy + ", despawned; tx " + runtime.npcs().apply(new NpcRepository.Draft(definition.path(), "missing", 0, object));
    }

    public String delete(String id) throws IOException { return "Deleted " + id + "; tx " + runtime.npcs().delete(id); }

    public List<String> validate() throws IOException { return checked("All definitions valid", runtime.npcs().validate()); }

    /**
     * Upgrades NPC and group files below the current schema first, as startup does: one journaled transaction whose
     * rollback receipt holds the originals. A file the upgrade refuses stays as it is for the reload to report.
     */
    public List<String> reload() throws IOException {
        SchemaMigrations.Report upgraded = SchemaMigrations.standard()
                .apply(runtime.documents(), List.of(SchemaMigrations.NPC, SchemaMigrations.GROUP));
        List<String> lines = new ArrayList<>();
        if (!upgraded.migrated().isEmpty())
            lines.add("Upgraded " + upgraded.migrated().size() + (upgraded.migrated().size() == 1 ? " file" : " files") + " to schemaVersion "
                    + NpcDefinition.SCHEMA_VERSION + "; rollback " + String.join(", ", upgraded.transactions()));
        lines.addAll(checked("Reloaded", runtime.npcs().reload()));
        return lines;
    }

    /** {@code All definitions valid; 3 saved, 2 spawned}, or the problems with the command that shows the rest. */
    private List<String> checked(String operation, List<String> errors) {
        if (!errors.isEmpty()) return errors;
        long spawned = runtime.npcs().all().stream().filter(NpcDefinition::spawned).count();
        return List.of(operation + "; " + runtime.npcs().all().size() + " saved, " + spawned + " spawned");
    }

    /** One step of the Citizens drop-in flow; the catalogs its transactions touched reload even when it fails part way. */
    public List<String> citizens(CitizensStep step) throws IOException {
        CitizensDropIn dropIn = new CitizensDropIn(runtime.documents(), runtime.npcs(), runtime.importHost()).combatLimit(runtime.combatBudgets().maximumNpcs());
        List<String> lines;
        try { lines = new ArrayList<>(step.run(dropIn)); }
        catch (IOException | RuntimeException failure) { catalogs(dropIn.committed(), dropIn.rolledBack()); throw failure; }
        lines.addAll(catalogs(dropIn.committed(), dropIn.rolledBack()));
        if (dropIn.settingsChanged()) {
            // apply and rollback edit config.toml; apply it now instead of waiting for a hotload.
            try {
                runtime.applySettings(art.arcane.animus.core.config.PluginSettings.load(runtime.documents()));
                // Behaviors and shops check the command allow-list when they load, so the changed list applies once they load again.
                runtime.catalogs().reload(new CatalogChange(java.util.Set.of(DocumentKind.BEHAVIOR, DocumentKind.SHOP, DocumentKind.INVENTORY), java.util.Set.of(), false));
            }
            catch (IOException | RuntimeException failure) { lines.add("config.toml changed but not applied: " + failure.getMessage() + "; it applies when the file is saved again, or at restart"); }
        }
        return lines;
    }
    @FunctionalInterface public interface CitizensStep { List<String> run(CitizensDropIn dropIn) throws IOException; }

    /**
     * Converts a file under {@code imports/inbox/}: a Citizens save, or any format a module converter reads.
     * {@code worlds} renames Citizens worlds; a converter is chosen by key or by the file.
     */
    public List<String> preview(String file, Optional<String> converter, Map<String, String> worlds) throws IOException {
        if (!file.matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]{1,8}")) throw new IOException("Give a plain file name under imports/inbox/, such as saves.yml; tab completion lists them");
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
    public List<String> stage(String given, String mode, Optional<String> label) throws IOException {
        String importId = imported("imports", "import", given);
        ImportApplications.Preview application = ImportApplications.stage(runtime.documents(), importId, mode, label.orElse(null));
        return List.of("Application " + application.applicationId() + ": " + application.mode() + ", " + application.records() + " records, " + application.replacements() + " replacements",
                "Review " + relative(application.report()) + ", then /animus import plan " + importId + " " + application.applicationId());
    }

    public List<String> apply(String given) throws IOException { return installed(new CitizensImporter().apply(runtime.documents(), imported("imports", "import", given))); }

    public List<String> applyPlan(String given, String application) throws IOException {
        String importId = imported("imports", "import", given);
        return installed(ImportApplications.apply(runtime.documents(), importId, imported("imports/" + importId + "/applications", "application", application)));
    }

    /**
     * The full id under {@code folder} that {@code given} starts with. Chat shows ids cut to their first characters with
     * the full id on click, so a typed short id answers when it names exactly one.
     */
    private String imported(String folder, String kind, String given) throws IOException {
        if (given.matches("[a-f0-9]{64}")) return given;
        if (!given.matches("[a-f0-9]{8,63}")) throw new IOException("Give the " + kind + " id from the preview, at least its first 8 characters");
        List<String> matches = new ArrayList<>();
        Path parent = runtime.documents().resolve(folder);
        if (java.nio.file.Files.isDirectory(parent)) {
            try (java.util.stream.Stream<Path> children = java.nio.file.Files.list(parent)) {
                for (Path child : children.toList()) {
                    String name = child.getFileName().toString();
                    if (name.matches("[a-f0-9]{64}") && name.startsWith(given) && java.nio.file.Files.isDirectory(child)) matches.add(name);
                }
            }
        }
        if (matches.isEmpty()) throw new IOException("No " + kind + " id starts with " + given);
        if (matches.size() > 1) throw new IOException(given + " starts " + matches.size() + " " + kind + " ids; give more of it");
        return matches.getFirst();
    }

    private List<String> installed(String tx) throws IOException {
        List<String> errors = runtime.npcs().reload();
        List<String> notes = catalogs(List.of(tx), false);
        if (!errors.isEmpty()) throw new IOException("Import stored, but these files were refused and keep their last valid version: " + String.join("; ", errors));
        if (tx.equals("already-applied")) return List.of("Already installed; nothing changed, /animus list shows the NPCs");
        List<String> lines = new ArrayList<>(List.of(Palette.success("Installed NPCs; tx " + tx + "; /animus list shows them, /animus spawn <id> shows one")));
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
        undoable(transaction);
        String tx = runtime.documents().rollback(transaction);
        // Rebuild after an explicit undo, which intentionally restores older revisions.
        List<String> errors = runtime.npcs().reloadAfterRollback();
        List<String> notes = catalogs(List.of(tx), true);
        if (!errors.isEmpty()) throw new IOException("Rollback stored, but reload failed: " + String.join("; ", errors));
        List<String> lines = new ArrayList<>(List.of(Palette.success("Rolled back " + ChatLine.shortId(transaction) + "; tx " + tx)));
        lines.addAll(notes);
        return lines;
    }

    /**
     * Refuses a rollback that cannot apply, in words: every file is already back as it was, or a later change wrote one
     * of its files since. Either would otherwise answer as a file conflict that a reload does not fix.
     */
    private void undoable(String transaction) throws IOException {
        DocumentStore documents = runtime.documents();
        String full = documents.transaction(transaction);
        List<DocumentStore.Change> inverse = documents.inverse(full);
        boolean undone = true;
        String later = null;
        for (DocumentStore.Change change : inverse) {
            String hash = JsonDocuments.hash(documents.read(change.path()));
            if (!hash.equals(JsonDocuments.hash(change.content()))) undone = false;
            if (later == null && !hash.equals(change.expectedHash())) later = change.path();
        }
        if (undone) throw new IOException("Change " + ChatLine.shortId(full) + " is already undone; nothing changed");
        if (later != null) throw new IOException("Cannot undo " + ChatLine.shortId(full) + ": " + later + " changed after it; undo the later change first");
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
