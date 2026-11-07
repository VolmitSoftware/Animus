package art.arcane.animus.core.migration;

import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Denizen part of {@code /animus import citizens}: finds a Denizen data folder with a {@code scripts} folder,
 * stages it with the Citizens save, applies it after the Citizens NPCs are installed, and keeps its record in the
 * {@code denizen} section of the drop-in state. Its transaction joins the drop-in's list, so one rollback undoes it.
 */
final class DenizenDropIn {
    static final String RUNNING = "Denizen is still enabled, so converted scripts stay off to avoid running twice; remove its jar from plugins/ (keep its folder), restart and run this again";

    private DenizenDropIn() {}

    /** The first of imports/denizen, imports/inbox/denizen and plugins/Denizen that holds a scripts folder. */
    static Optional<CitizensDropIn.Found> find(DocumentStore store, Optional<Path> plugins) throws IOException {
        List<CitizensDropIn.Found> candidates = new ArrayList<>(List.of(new CitizensDropIn.Found(store.resolve("imports/denizen"), "imports/denizen"),
                new CitizensDropIn.Found(store.resolve("imports/inbox/denizen"), "imports/inbox/denizen")));
        plugins.ifPresent((Path folder) -> candidates.add(new CitizensDropIn.Found(folder.resolve("Denizen"), "plugins/Denizen")));
        for (CitizensDropIn.Found found : candidates)
            if (Files.isDirectory(found.path().resolve("scripts"), LinkOption.NOFOLLOW_LINKS)) return Optional.of(found);
        return Optional.empty();
    }

    static boolean applied(JsonObject state) { return section(state).map((JsonObject part) -> part.get("state").getAsString().equals("applied")).orElse(false); }
    static boolean staged(JsonObject state) { return section(state).map((JsonObject part) -> !part.get("state").getAsString().equals("applied")).orElse(false); }

    private static Optional<JsonObject> section(JsonObject state) {
        return state.has("denizen") && state.get("denizen").isJsonObject() ? Optional.of(state.getAsJsonObject("denizen")) : Optional.empty();
    }

    /** Converts the folder against the Citizens save into {@code state.denizen} and returns the lines to show. */
    static List<String> stage(DocumentStore store, CitizensDropIn.Found found, Path citizens, JsonObject state) throws IOException {
        DenizenImporter.Preview preview = new DenizenImporter().preview(found.path(), Optional.of(citizens), store);
        JsonObject part = new JsonObject();
        part.addProperty("source", found.label());
        part.addProperty("importId", preview.id());
        part.addProperty("state", "staged");
        part.addProperty("report", DenizenImporter.IMPORTS + "/" + preview.id() + "/report.json");
        state.add("denizen", part);
        List<String> lines = new ArrayList<>();
        JsonObject report = preview.report();
        lines.add("Denizen " + found.label() + ": " + scripts(report) + " scripts; " + preview.behaviors() + " behaviors for " + preview.bindings() + " NPCs; "
                + preview.flagsImported() + " flag values imported, " + preview.flagsPreserved() + " kept; " + preview.preserved() + " items kept, reasons in " + part.get("report").getAsString());
        commandLine(report, store).ifPresent(lines::add);
        return lines;
    }

    private static int scripts(JsonObject report) {
        int total = 0;
        for (Map.Entry<String, JsonElement> type : report.getAsJsonObject("containers").entrySet()) total += type.getValue().getAsJsonObject().get("total").getAsInt();
        return total;
    }

    static Optional<String> commandLine(JsonObject report, DocumentStore store) throws IOException {
        JsonArray commands = report.getAsJsonArray("commandBehaviors");
        if (commands == null || commands.isEmpty()) return Optional.empty();
        List<String> ids = new ArrayList<>();
        for (JsonElement id : commands) ids.add(id.getAsString());
        return Optional.of("Denizen commands: " + commands.size() + " behaviors stay denied (such as " + commands.get(0).getAsString()
                + "); review behaviors/denizen/, then list them in commandBehaviors or set commandNamespaces = [\"denizen\"]; "
                + CommandAllowList.advice(store, CommandAllowList.namespaces(ids)));
    }

    /** The applied import's behaviors that run server commands; none when no Denizen import is applied. */
    static List<String> commandBehaviors(DocumentStore store, JsonObject state) throws IOException {
        if (!applied(state)) return List.of();
        String report = store.read(state.getAsJsonObject("denizen").get("report").getAsString());
        if (report == null) return List.of();
        JsonArray commands = JsonDocuments.object(report).getAsJsonArray("commandBehaviors");
        List<String> ids = new ArrayList<>();
        if (commands != null) for (JsonElement id : commands) ids.add(id.getAsString());
        return ids;
    }

    /** Applies the staged import, binding behaviors to the installed NPCs. Adds its transaction to {@code transactions}. */
    static List<String> apply(DocumentStore store, JsonObject state, NpcRepository.Installed installed, JsonArray transactions) throws IOException {
        JsonObject part = state.getAsJsonObject("denizen");
        DenizenImporter.Applied applied = new DenizenImporter().apply(store, part.get("importId").getAsString(), installed);
        transactions.add(applied.transaction());
        part.addProperty("state", "applied");
        List<String> lines = new ArrayList<>();
        lines.add("Denizen behaviors installed in behaviors/denizen/; " + applied.states() + " flag state files; "
                + applied.bound() + " NPCs wait for accept denizen; tx " + applied.transaction());
        lines.add("Imported flag values load at the next restart; Denizen can stay removed");
        applied.problems().stream().limit(10).forEach((String problem) -> lines.add("Not bound: " + problem));
        return lines;
    }

    static Optional<String> reviewLine(JsonObject state) {
        if (!applied(state)) return Optional.empty();
        JsonObject part = state.getAsJsonObject("denizen");
        return Optional.of("Denizen import " + part.get("importId").getAsString().substring(0, 12) + ": behaviors in behaviors/denizen/, kept items in "
                + part.get("report").getAsString());
    }

    /**
     * The change that appends the applied import's server behaviors to {@code server-behaviors.json}, or empty when there are
     * none or every one is already listed. Rolling the accepting transaction back restores the file.
     */
    static Optional<DocumentStore.Change> serverBehaviors(DocumentStore store, JsonObject state) throws IOException {
        if (!applied(state)) return Optional.empty();
        String receipt = store.read(DenizenImporter.IMPORTS + "/" + state.getAsJsonObject("denizen").get("importId").getAsString() + "/receipt.json");
        if (receipt == null) return Optional.empty();
        JsonArray listed = DenizenImporter.serverBehaviors(JsonDocuments.object(receipt));
        if (listed.isEmpty()) return Optional.empty();
        List<String> ids = new ArrayList<>();
        for (JsonElement id : listed) ids.add(id.getAsString());
        String before = store.read(ServerBehaviorList.PATH);
        String after = ServerBehaviorList.append(before, ids);
        if (after.equals(before)) return Optional.empty();
        return Optional.of(new DocumentStore.Change(ServerBehaviorList.PATH, before == null ? "missing" : store.hash(ServerBehaviorList.PATH), after));
    }

    static void rolledBack(JsonObject state) {
        section(state).ifPresent((JsonObject part) -> { if (part.get("state").getAsString().equals("applied")) part.addProperty("state", "rolled-back"); });
    }

    static Optional<JsonObject> status(JsonObject state) {
        return section(state).map((JsonObject part) -> {
            JsonObject denizen = new JsonObject();
            denizen.addProperty("importId", part.get("importId").getAsString());
            denizen.addProperty("source", part.get("source").getAsString());
            denizen.addProperty("state", part.get("state").getAsString());
            denizen.addProperty("report", part.get("report").getAsString());
            return denizen;
        });
    }
}
