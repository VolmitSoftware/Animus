package art.arcane.animus.converter;

import art.arcane.animus.core.behavior.schema.BehaviorSchemaGenerator;
import art.arcane.animus.core.behavior.schema.OfflineBehaviorValidation;
import art.arcane.animus.core.behavior.schema.OfflineCapabilities;
import art.arcane.animus.core.migration.CitizensImporter;
import art.arcane.animus.core.migration.ImportApplications;
import art.arcane.animus.core.migration.ImportDigest;
import art.arcane.animus.core.migration.ShopImporter;
import art.arcane.animus.core.migration.ShopkeepersImporter;
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
        if (args.length < 2) throw new IllegalArgumentException("Usage: preview <saves.yml> <data-dir> [oldWorld=newWorld ...] | apply <data-dir> <import-id> | stage <data-dir> <import-id> [reject|replace|duplicate <label>] | apply-plan <data-dir> <import-id> <application-id> | rollback <data-dir> <transaction-id> | validate <data-dir> | validate-behaviors <directory> | schema <output-dir> | shops-preview <dtlTraders-folder> <data-dir> [saves.yml] | shops-apply <data-dir> <import-id> | shopkeepers-preview <save.yml> <data-dir> [config=<config.yml>] [citizens=<saves.yml>] | shopkeepers-apply <data-dir> <import-id> | digest <staged-dir>");
        // The canonical hash of a staged Citizens import, comparable with the browser converter's (ImportDigest).
        if (args[0].equals("digest")) { require(args, 2); return ImportDigest.outputHash(Path.of(args[1])); }
        if (args[0].equals("validate-behaviors")) { require(args, 2); return validateBehaviors(Path.of(args[1])); }
        if (args[0].equals("schema")) { require(args, 2); return schema(Path.of(args[1])); }
        if (args[0].equals("shops-preview")) {
            if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("shops-preview <dtlTraders-folder> <data-dir> [saves.yml]");
            try (DocumentStore store = new DocumentStore(Path.of(args[2]))) {
                ShopImporter.Preview preview = new ShopImporter().preview(Path.of(args[1]), args.length == 4 ? Optional.of(Path.of(args[3])) : Optional.empty(), store);
                return "Shop preview " + preview.id() + "\nShops: " + preview.shops() + ", entries: " + preview.entries()
                        + "\nIssues: " + preview.issues() + "; limit data: " + preview.limitStatus()
                        + "\nReport: " + preview.directory().resolve("report.json") + "\nNothing installed. Inspect the report before shops-apply.";
            }
        }
        if (args[0].equals("shopkeepers-preview")) return shopkeepersPreview(args);
        if (args[0].equals("shopkeepers-apply")) {
            require(args, 3);
            try (DocumentStore store = new DocumentStore(Path.of(args[1]))) {
                NpcRepository repository = new NpcRepository(store);
                repository.reload();
                ShopkeepersImporter.Applied applied = new ShopkeepersImporter().apply(store, args[2], repository.all());
                return "Shopkeepers import transaction: " + applied.transaction() + "\nBound to installed Citizens NPCs: " + applied.bound() + "; waiting for their NPC: " + applied.waiting()
                        + (applied.problems().isEmpty() ? "" : "\nNot bound: " + String.join("; ", applied.problems()));
            }
        }
        if (args[0].equals("shops-apply")) {
            require(args, 3);
            try (DocumentStore store = new DocumentStore(Path.of(args[1]))) { return "Shop import transaction: " + new ShopImporter().apply(store, args[2]); }
        }
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
                    + "\nConverted: " + preview.conversions()
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
    /** {@code shopkeepers-preview <save.yml> <data-dir> [config=<config.yml>] [citizens=<saves.yml>]}: aggregate counts only. */
    private static String shopkeepersPreview(String[] args) throws Exception {
        String usage = "shopkeepers-preview <save.yml> <data-dir> [config=<config.yml>] [citizens=<saves.yml>]";
        if (args.length < 3 || args.length > 5) throw new IllegalArgumentException(usage);
        Optional<Path> config = Optional.empty(), citizens = Optional.empty();
        for (int index = 3; index < args.length; index++) {
            String[] pair = args[index].split("=", 2);
            if (pair.length != 2 || pair[1].isBlank()) throw new IllegalArgumentException(usage);
            switch (pair[0]) {
                case "config" -> { if (config.isPresent()) throw new IllegalArgumentException(usage); config = Optional.of(Path.of(pair[1])); }
                case "citizens" -> { if (citizens.isPresent()) throw new IllegalArgumentException(usage); citizens = Optional.of(Path.of(pair[1])); }
                default -> throw new IllegalArgumentException(usage);
            }
        }
        try (DocumentStore store = new DocumentStore(Path.of(args[2]))) {
            ShopkeepersImporter.Preview preview = new ShopkeepersImporter().preview(Path.of(args[1]), config, citizens, store);
            return "Shopkeepers preview " + preview.id() + "\n" + String.join("\n", preview.aggregate())
                    + "\nOutput hash: " + ImportDigest.outputHash(preview.directory())
                    + "\nReport: " + preview.directory().resolve("report.json") + "\nNothing installed. Inspect the report before shopkeepers-apply.";
        }
    }

    /** Offline check of a data directory or pack. An invalid result is an error, so the process exits with status 1. */
    private static String validateBehaviors(Path directory) throws Exception {
        try (OfflineCapabilities capabilities = new OfflineCapabilities()) {
            OfflineBehaviorValidation.Report report = OfflineBehaviorValidation.validate(directory, capabilities);
            String text = String.join("\n", report.lines());
            if (!report.valid()) throw new IllegalArgumentException(text);
            return text;
        }
    }
    /** The generated schemas with built-in capabilities only; the server's own command adds installed modules. */
    private static String schema(Path output) throws Exception {
        try (OfflineCapabilities capabilities = new OfflineCapabilities()) {
            Map<String, String> documents = BehaviorSchemaGenerator.generate(capabilities.capabilities());
            for (Map.Entry<String, String> document : documents.entrySet()) DocumentStore.atomic(output.resolve(document.getKey()), document.getValue());
            return "Wrote " + documents.size() + " files under " + output.resolve("schemas") + " for " + capabilities.capabilities().size() + " built-in capabilities. Java module capabilities are not included.";
        }
    }
    private static void require(String[] args, int count) { if (args.length != count) throw new IllegalArgumentException("Incorrect argument count"); }
}
