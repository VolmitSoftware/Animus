package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Shopkeepers part of {@code /animus import citizens}: finds a Shopkeepers save, stages it next to the Citizens
 * import, applies it and binds Citizens shopkeepers to their NPCs as those NPCs arrive. Its record is the
 * {@code shopkeepers} section of the drop-in state; its transactions join the drop-in's list, so one rollback undoes
 * both imports in order.
 */
final class ShopkeepersDropIn {
    static final String RUNNING = "Shopkeepers is still enabled. Animus will not apply imported Shopkeepers shops while it is, so no shop trades twice. Stop the server, remove Shopkeepers from plugins/, start it again and rerun this command.";

    /** A found save and the {@code config.yml} that belongs to it, when there is one. */
    record Found(CitizensDropIn.Found save, Optional<Path> config) {}

    private ShopkeepersDropIn() {}

    /** The first of imports/shopkeepers/save.yml, imports/inbox/shopkeepers/save.yml and plugins/Shopkeepers/data/save.yml. */
    static Optional<Found> find(DocumentStore store, Optional<Path> plugins) throws IOException {
        List<Found> candidates = new ArrayList<>();
        for (String folder : List.of("imports/shopkeepers", "imports/inbox/shopkeepers")) {
            Path directory = store.resolve(folder);
            candidates.add(new Found(new CitizensDropIn.Found(directory.resolve("save.yml"), folder + "/save.yml"), Optional.of(directory.resolve("config.yml"))));
        }
        if (plugins.isPresent()) {
            Path directory = plugins.get().resolve("Shopkeepers");
            candidates.add(new Found(new CitizensDropIn.Found(directory.resolve("data/save.yml"), "plugins/Shopkeepers/data/save.yml"), Optional.of(directory.resolve("config.yml"))));
        }
        for (Found found : candidates) {
            if (!Files.isRegularFile(found.save().path(), LinkOption.NOFOLLOW_LINKS)) continue;
            Optional<Path> config = found.config().filter((Path path) -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
            return Optional.of(new Found(found.save(), config));
        }
        return Optional.empty();
    }

    static boolean applied(JsonObject state) { return section(state).map((JsonObject part) -> part.get("state").getAsString().equals("applied")).orElse(false); }
    /** Staged, or rolled back and ready to apply again. */
    static boolean staged(JsonObject state) { return section(state).map((JsonObject part) -> !part.get("state").getAsString().equals("applied")).orElse(false); }
    static Optional<String> importId(JsonObject state) { return section(state).map((JsonObject part) -> part.get("importId").getAsString()); }

    private static Optional<JsonObject> section(JsonObject state) {
        return state.has("shopkeepers") && state.get("shopkeepers").isJsonObject() ? Optional.of(state.getAsJsonObject("shopkeepers")) : Optional.empty();
    }

    /** Stages the save into {@code state.shopkeepers} and returns the lines to show. Links are checked against the Citizens save when one is given. */
    static List<String> stage(DocumentStore store, Found found, Optional<Path> citizens, JsonObject state) throws IOException {
        ShopkeepersImporter.Preview preview = new ShopkeepersImporter().preview(found.save().path(), found.config(), citizens, store);
        JsonObject part = new JsonObject();
        part.addProperty("source", found.save().label());
        part.addProperty("sourceSha256", preview.summary().get("sourceSha256").getAsString());
        found.config().ifPresent((Path config) -> part.addProperty("config", true));
        part.addProperty("importId", preview.id());
        part.addProperty("state", "staged");
        part.addProperty("report", ShopkeepersImporter.IMPORTS + "/" + preview.id() + "/report.json");
        state.add("shopkeepers", part);
        JsonObject offers = preview.offers();
        JsonObject links = preview.summary().getAsJsonObject("links");
        List<String> lines = new ArrayList<>();
        lines.add("Shopkeepers " + found.save().label() + (found.config().isPresent() ? " with its config.yml" : "") + ": " + preview.shops() + " shops, "
                + offers.get("trade").getAsInt() + " trading offers, " + offers.get("static").getAsInt() + " shown only, " + offers.get("skipped").getAsInt() + " kept in source.yml; "
                + preview.npcs() + " new mob NPCs; " + preview.issues() + " issues");
        lines.add("Shopkeepers Citizens links: " + links.get("inCitizensSave").getAsInt() + " found, " + links.get("notInCitizensSave").getAsInt() + " not found, "
                + links.get("unchecked").getAsInt() + " unchecked; report " + part.get("report").getAsString());
        return lines;
    }

    /** Applies a staged save, binding every Citizens shopkeeper whose NPC is installed. Adds its transaction to {@code transactions}. */
    static List<String> apply(DocumentStore store, JsonObject state, NpcRepository.Installed installed, JsonArray transactions) throws IOException {
        JsonObject part = state.getAsJsonObject("shopkeepers");
        ShopkeepersImporter.Applied applied = new ShopkeepersImporter().apply(store, part.get("importId").getAsString(), installed);
        transactions.add(applied.transaction());
        part.addProperty("state", "applied");
        part.add("pending", applied.pending());
        List<String> lines = new ArrayList<>();
        lines.add("Shopkeepers shops installed; transaction " + applied.transaction() + "; " + applied.bound() + " bound to Citizens NPCs, " + applied.pending().size() + " wait for their NPC");
        applied.problems().stream().limit(10).forEach((String problem) -> lines.add("Not bound: " + problem));
        return lines;
    }

    /** Binds the pending Citizens shopkeepers of an applied save to NPCs installed since. */
    static List<String> bindPending(DocumentStore store, JsonObject state, NpcRepository.Installed installed, JsonArray transactions) throws IOException {
        if (!applied(state)) return List.of();
        JsonObject part = state.getAsJsonObject("shopkeepers");
        JsonArray pending = part.has("pending") ? part.getAsJsonArray("pending") : new JsonArray();
        if (pending.isEmpty()) return List.of();
        Optional<ShopkeepersImporter.Applied> applied = ShopkeepersImporter.bind(store, pending, installed);
        if (applied.isEmpty()) return List.of(pending.size() + " Shopkeepers shops still wait for their Citizens NPC");
        transactions.add(applied.get().transaction());
        part.add("pending", applied.get().pending());
        List<String> lines = new ArrayList<>();
        lines.add("Bound " + applied.get().bound() + " Shopkeepers shops to their Citizens NPCs; transaction " + applied.get().transaction() + "; " + applied.get().pending().size() + " still wait");
        applied.get().problems().stream().limit(10).forEach((String problem) -> lines.add("Not bound: " + problem));
        return lines;
    }

    static Optional<String> reviewLine(JsonObject state) {
        if (!applied(state)) return Optional.empty();
        JsonObject part = state.getAsJsonObject("shopkeepers");
        int pending = part.has("pending") ? part.getAsJsonArray("pending").size() : 0;
        return Optional.of("Shopkeepers import " + part.get("importId").getAsString().substring(0, 12) + ": shops under shops/shopkeepers/; " + pending
                + " Citizens shopkeepers wait for their NPC; report " + part.get("report").getAsString());
    }

    static void rolledBack(JsonObject state) {
        section(state).ifPresent((JsonObject part) -> { if (part.get("state").getAsString().equals("applied")) { part.addProperty("state", "rolled-back"); part.remove("pending"); } });
    }
}
