package art.arcane.animus.core.admin;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.migration.CitizensImporter;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Shared, filesystem-only administration. The platform is responsible for permission and scheduling. */
public final class Administration {
    public static final List<String> COMMANDS = List.of("status", "list", "create", "inspect", "edit", "clone", "delete", "validate", "reload", "import", "rollback");
    private final AnimusRuntime runtime;
    public Administration(AnimusRuntime runtime) { this.runtime = runtime; }
    public List<String> execute(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("status")) return List.of(runtime.status(), "Definitions: " + runtime.npcs().all().size() + "; packet renderer unavailable (Milestone 0 proof pending).");
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
                if (patch.has("spawned") && !patch.get("spawned").equals(new JsonPrimitive(false))) throw new IOException("Packet rendering is not available; spawning is disabled");
                JsonObject edited = draft.document(); patch.entrySet().forEach(e -> edited.add(e.getKey(), e.getValue()));
                yield List.of("Saved; transaction " + runtime.npcs().apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited)));
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
                if (args.length < 3) throw new IOException("import preview <inbox-file.yml> [oldWorld=newWorld ...] | import apply <import-id>");
                CitizensImporter importer = new CitizensImporter();
                if (args[1].equals("preview")) {
                    if (!args[2].matches("[a-zA-Z0-9_-]+\\.yml")) throw new IOException("Use a .yml filename under imports/inbox/");
                    Map<String, String> worlds = new TreeMap<>();
                    for (int i = 3; i < args.length; i++) {
                        String[] pair = args[i].split("=", 2);
                        if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || worlds.putIfAbsent(pair[0], pair[1]) != null) throw new IOException("Invalid world mapping: " + args[i]);
                    }
                    var preview = importer.preview(runtime.documents().resolve("imports/inbox/" + args[2]), runtime.documents(), worlds);
                    yield List.of("Preview " + preview.id(), "Records: " + preview.records() + ", valid: " + preview.valid() + ", skins: " + preview.skins(), "Review " + preview.directory().resolve("report.json") + " before import apply.");
                }
                if (!args[1].equals("apply")) throw new IOException("Expected import preview or apply");
                count(args, 3);
                String tx = importer.apply(runtime.documents(), args[2]);
                List<String> errors = runtime.npcs().reload();
                if (!errors.isEmpty()) throw new IOException("Import stored; registry kept last valid snapshot: " + String.join("; ", errors));
                yield List.of("Installed dormant definitions; transaction " + tx);
            }
            case "rollback" -> {
                count(args, 2);
                String tx = runtime.documents().rollback(args[1]);
                // Rebuild after an explicit undo, which intentionally restores older revisions.
                List<String> errors = runtime.npcs().reloadAfterRollback();
                if (!errors.isEmpty()) throw new IOException("Rollback stored; reload failed: " + String.join("; ", errors));
                yield List.of("Rolled back; inverse transaction " + tx);
            }
            default -> throw new IOException("Commands: " + String.join(", ", COMMANDS));
        };
    }
    private static void count(String[] args, int expected) throws IOException { if (args.length != expected) throw new IOException("Incorrect argument count for " + args[0]); }
}
