package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
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
    static final String RUNNING = "Shopkeepers is still enabled, so imported shops stay off to avoid trading twice; remove it from plugins/, restart and run this again";

    /** A found save and the {@code config.yml} that belongs to it, when there is one. */
    record Found(CitizensDropIn.Found save, Optional<Path> config) {}

    private ShopkeepersDropIn() {}

    /** The first of imports/shopkeepers/save.yml, imports/inbox/shopkeepers/save.yml and plugins/Shopkeepers/data/save.yml. */
    static Optional<Found> find(DocumentStore store, Optional<Path> plugins) throws IOException {
        for (Found found : candidates(store, plugins)) {
            if (!Files.isRegularFile(found.save().path(), LinkOption.NOFOLLOW_LINKS)) continue;
            Optional<Path> config = found.config().filter((Path path) -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
            return Optional.of(new Found(found.save(), config));
        }
        return Optional.empty();
    }

    private static List<Found> candidates(DocumentStore store, Optional<Path> plugins) throws IOException {
        List<Found> candidates = new ArrayList<>();
        for (String folder : List.of("imports/shopkeepers", "imports/inbox/shopkeepers")) {
            Path directory = store.resolve(folder);
            candidates.add(new Found(new CitizensDropIn.Found(directory.resolve("save.yml"), folder + "/save.yml"), Optional.of(directory.resolve("config.yml"))));
        }
        if (plugins.isPresent()) {
            Path directory = plugins.get().resolve("Shopkeepers");
            candidates.add(new Found(new CitizensDropIn.Found(directory.resolve("data/save.yml"), "plugins/Shopkeepers/data/save.yml"), Optional.of(directory.resolve("config.yml"))));
        }
        return candidates;
    }

    /** Refuses to apply a staged save whose source file was saved again since the preview. A source removed since is fine. */
    static void unchanged(DocumentStore store, Optional<Path> plugins, JsonObject state) throws IOException {
        JsonObject part = state.getAsJsonObject("shopkeepers");
        String label = part.get("source").getAsString();
        for (Found found : candidates(store, plugins)) {
            if (!found.save().label().equals(label) || !Files.isRegularFile(found.save().path(), LinkOption.NOFOLLOW_LINKS)) continue;
            if (!JsonDocuments.hash(Files.readString(found.save().path())).equals(part.get("sourceSha256").getAsString()))
                throw new IOException(label + " changed since the preview; run /animus import citizens preview again, then apply");
        }
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
        StableSource.Read source = StableSource.read(found.save().path(), found.save().label());
        ShopkeepersImporter.Preview preview = new ShopkeepersImporter().preview(found.save().path(), found.config(), citizens, store);
        if (!preview.summary().get("sourceSha256").getAsString().equals(source.sha256()))
            throw new IOException(found.save().label() + " changed while the preview read it; if a plugin is saving it, wait and run preview again");
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

    /** Applies a staged save, binding every Citizens shopkeeper whose NPC is installed. Its transaction goes through {@code journal}. */
    static List<String> apply(DocumentStore store, JsonObject state, NpcRepository.Installed installed, ImportJournal journal) throws IOException {
        JsonObject part = state.getAsJsonObject("shopkeepers");
        ShopkeepersImporter.Applied[] result = new ShopkeepersImporter.Applied[1];
        journal.commit(state, (String transaction) -> (result[0] = new ShopkeepersImporter().apply(store, part.get("importId").getAsString(), installed, transaction)).transaction());
        ShopkeepersImporter.Applied applied = result[0];
        part.addProperty("state", "applied");
        part.add("pending", applied.pending());
        List<String> lines = new ArrayList<>();
        lines.add("Shopkeepers shops installed; " + applied.bound() + " bound to Citizens NPCs, " + applied.pending().size() + " wait for their NPC; tx " + applied.transaction());
        applied.problems().stream().limit(10).forEach((String problem) -> lines.add("Not bound: " + problem));
        return lines;
    }

    /** Binds the pending Citizens shopkeepers of an applied save to NPCs installed since. */
    static List<String> bindPending(DocumentStore store, JsonObject state, NpcRepository.Installed installed, ImportJournal journal) throws IOException {
        if (!applied(state)) return List.of();
        JsonObject part = state.getAsJsonObject("shopkeepers");
        JsonArray pending = part.has("pending") ? part.getAsJsonArray("pending") : new JsonArray();
        if (pending.isEmpty()) return List.of();
        List<Optional<ShopkeepersImporter.Applied>> result = new ArrayList<>(List.of(Optional.empty()));
        journal.commit(state, (String transaction) -> {
            result.set(0, ShopkeepersImporter.bind(store, pending, installed, transaction));
            return result.getFirst().map(ShopkeepersImporter.Applied::transaction).orElse(null);
        });
        Optional<ShopkeepersImporter.Applied> applied = result.getFirst();
        if (applied.isEmpty()) return List.of(pending.size() + " Shopkeepers shops still wait for their Citizens NPC");
        part.add("pending", applied.get().pending());
        List<String> lines = new ArrayList<>();
        lines.add("Bound " + applied.get().bound() + " Shopkeepers shops to their Citizens NPCs; " + applied.get().pending().size() + " still wait; tx " + applied.get().transaction());
        applied.get().problems().stream().limit(10).forEach((String problem) -> lines.add("Not bound: " + problem));
        return lines;
    }

    static Optional<String> reviewLine(JsonObject state) {
        if (!applied(state)) return Optional.empty();
        JsonObject part = state.getAsJsonObject("shopkeepers");
        int pending = part.has("pending") ? part.getAsJsonArray("pending").size() : 0;
        return Optional.of("Shopkeepers import " + part.get("importId").getAsString().substring(0, 12) + ": shops in shops/shopkeepers/; " + pending
                + " wait for their Citizens NPC; report " + part.get("report").getAsString());
    }

    static void rolledBack(JsonObject state) {
        section(state).ifPresent((JsonObject part) -> { if (part.get("state").getAsString().equals("applied")) { part.addProperty("state", "rolled-back"); part.remove("pending"); } });
    }
}
