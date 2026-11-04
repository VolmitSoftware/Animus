package art.arcane.animus.converter;

import art.arcane.animus.core.migration.CitizensImporter;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Path;
import java.util.*;

public final class ConverterMain {
    private ConverterMain() {}
    public static void main(String[] args) {
        try { System.out.println(run(args)); }
        catch (Exception e) { System.err.println("Animus: " + e.getMessage()); System.exit(1); }
    }
    static String run(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: preview <saves.yml> <data-dir> [oldWorld=newWorld ...] | apply <data-dir> <import-id> | rollback <data-dir> <transaction-id> | validate <data-dir>");
        CitizensImporter importer = new CitizensImporter();
        if (args[0].equals("preview")) {
            if (args.length < 3) throw new IllegalArgumentException("preview requires input and output paths");
            Map<String, String> worlds = new TreeMap<>();
            for (int i = 3; i < args.length; i++) {
                String[] mapping = args[i].split("=", 2);
                if (mapping.length != 2 || mapping[0].isBlank() || mapping[1].isBlank() || worlds.putIfAbsent(mapping[0], mapping[1]) != null)
                    throw new IllegalArgumentException("Invalid or duplicate world mapping: " + args[i]);
            }
            CitizensImporter.Preview preview = importer.preview(Path.of(args[1]), Path.of(args[2]), worlds);
            return "Preview " + preview.id() + "\nRecords: " + preview.records() + ", valid: " + preview.valid() + ", unique skins: " + preview.skins()
                    + "\nReport: " + preview.directory().resolve("report.json") + "\nNo NPCs activated. Inspect reports before explicit apply.";
        }
        try (DocumentStore store = new DocumentStore(Path.of(args[1]))) {
            return switch (args[0]) {
                case "apply" -> { require(args, 3); yield "Import transaction: " + importer.apply(store, args[2]) + ". Definitions installed dormant."; }
                case "rollback" -> { require(args, 3); yield "Rollback transaction: " + store.rollback(args[2]); }
                case "validate" -> {
                    require(args, 2);
                    NpcRepository repository = new NpcRepository(store);
                    List<String> errors = repository.reload();
                    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
                    yield "Valid NPC definitions: " + repository.all().size();
                }
                default -> throw new IllegalArgumentException("Unknown operation: " + args[0]);
            };
        }
    }
    private static void require(String[] args, int count) { if (args.length != count) throw new IllegalArgumentException("Incorrect argument count"); }
}
