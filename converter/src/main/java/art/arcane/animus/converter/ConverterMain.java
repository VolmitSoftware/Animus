package art.arcane.animus.converter;

import art.arcane.animus.core.migration.CitizensImporter;
import art.arcane.animus.core.migration.ImportApplications;
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
        if (args.length < 2) throw new IllegalArgumentException("Usage: preview <saves.yml> <data-dir> [oldWorld=newWorld ...] | apply <data-dir> <import-id> | stage <data-dir> <import-id> [reject|replace|duplicate <label>] | apply-plan <data-dir> <import-id> <application-id> | rollback <data-dir> <transaction-id> | validate <data-dir>");
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
            return "Preview " + preview.id() + " (converter v" + preview.converterVersion() + ")" + "\nRecords: " + preview.records() + ", valid: " + preview.valid() + ", unique skins: " + preview.skins()
                    + "\nMigration warnings: " + preview.warningCount()
                    + "\nReport: " + preview.directory().resolve("report.json") + "\nNo NPCs activated. Inspect reports before explicit apply.";
        }
        try (DocumentStore store = args[0].equals("validate") ? DocumentStore.readOnly(Path.of(args[1])) : new DocumentStore(Path.of(args[1]))) {
            return switch (args[0]) {
                case "apply" -> { require(args, 3); yield "Import transaction: " + importer.apply(store, args[2]) + ". Definitions installed dormant."; }
                case "stage" -> {
                    if (args.length < 3 || args.length > 5) throw new IllegalArgumentException("stage <data-dir> <import-id> [reject|replace|duplicate <label>]");
                    var application = ImportApplications.stage(store, args[2], args.length > 3 ? args[3] : "reject", args.length > 4 ? args[4] : null);
                    yield "Application " + application.applicationId() + "\nMode: " + application.mode() + "; records: " + application.records() + "; replacements: " + application.replacements()
                            + "\nReview " + application.report() + "\nNo definitions installed; explicitly apply-plan after review.";
                }
                case "apply-plan" -> { require(args, 4); yield "Import transaction: " + ImportApplications.apply(store, args[2], args[3]) + ". Definitions installed dormant."; }
                case "rollback" -> { require(args, 3); yield "Rollback transaction: " + store.rollback(args[2]); }
                case "validate" -> {
                    require(args, 2);
                    NpcRepository repository = new NpcRepository(store);
                    NpcRepository.Validation validation = repository.validateStored();
                    if (!validation.errors().isEmpty()) throw new IllegalArgumentException(String.join("\n", validation.errors()));
                    yield "Valid NPC definitions: " + validation.definitions();
                }
                default -> throw new IllegalArgumentException("Unknown operation: " + args[0]);
            };
        }
    }
    private static void require(String[] args, int count) { if (args.length != count) throw new IllegalArgumentException("Incorrect argument count"); }
}
