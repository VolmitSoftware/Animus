package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Points a quest plugin's files at the Animus NPCs a Citizens import created, the same way for every plugin: Quests,
 * BeautyQuests and BetonQuest. {@link #preview} only writes a report,
 * {@link #apply} keeps each original under {@code imports/links/<plugin>/originals/} before writing, and {@link #rollback}
 * puts the originals back unless a file was edited after the apply. Call from the file lane.
 */
public final class LinkRemap {
    /** A plugin whose files name Citizens NPCs, and the folder below {@code plugins/} its files live in. */
    public enum Plugin {
        QUESTS("Quests", "Quests", ".yml"),
        BEAUTYQUESTS("BeautyQuests", "BeautyQuests", ".yml"),
        BETONQUEST("BetonQuest", "BetonQuest/QuestPackages", ".yml");

        private final String label, folder, extension;
        Plugin(String label, String folder, String extension) { this.label = label; this.folder = folder; this.extension = extension; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public String label() { return label; }
    }

    /** One file rewritten: its text, the references changed, a line per change and the references left alone. */
    private record Rewritten(String text, int changes, List<String> shown, List<String> notes) {}
    /** The quest and condition files Quests reads, in {@code storage/} and, from an install Quests has not started on since, the folder root. */
    private static final List<String> QUESTS_FILES = List.of("storage/quests.yml", "storage/conditions.yml", "quests.yml", "conditions.yml");

    private record Planned(Path file, String relative, String before, String after, int changes, List<String> shown) {}
    private record Plan(List<Planned> files, List<String> notes) {
        int references() { return files.stream().mapToInt(Planned::changes).sum(); }
    }

    private static final int SHOWN = 12;
    private final DocumentStore store;
    private final Path root;
    private final Plugin plugin;
    private final NpcLinks links;

    public LinkRemap(DocumentStore store, Path plugins, Plugin plugin, NpcLinks links) {
        this.store = Objects.requireNonNull(store);
        this.root = Objects.requireNonNull(plugins).resolve(plugin.folder);
        this.plugin = Objects.requireNonNull(plugin);
        this.links = Objects.requireNonNull(links);
    }

    public List<String> preview() throws IOException {
        Plan plan = plan();
        report(plan, "preview");
        List<String> lines = new ArrayList<>();
        lines.add(plugin.label + ": " + counted(plan) + " would be relinked to " + links.size() + " imported NPCs"
                + (plan.files.isEmpty() ? "" : "; apply with /animus import " + plugin.key() + " apply"));
        details(plan, lines);
        return lines;
    }

    public List<String> apply() throws IOException {
        if (Files.exists(state("applied.json"))) throw new IOException(plugin.label + " files are already relinked; run /animus import " + plugin.key() + " rollback first");
        Plan plan = plan();
        if (plan.files.isEmpty()) { report(plan, "apply"); return List.of(plugin.label + ": nothing to relink" + (plan.notes.isEmpty() ? "" : "; " + plan.notes.size() + " notes in the report")); }
        JsonArray files = new JsonArray();
        for (Planned planned : plan.files) {
            DocumentStore.atomic(state("originals/" + planned.relative), planned.before);
            JsonObject file = new JsonObject();
            file.addProperty("path", planned.relative);
            file.addProperty("originalSha256", JsonDocuments.hash(planned.before));
            file.addProperty("writtenSha256", JsonDocuments.hash(planned.after));
            files.add(file);
        }
        JsonObject applied = new JsonObject();
        applied.addProperty("applied", Instant.now().toString());
        applied.add("files", files);
        DocumentStore.atomic(state("applied.json"), JsonDocuments.write(applied));
        for (Planned planned : plan.files) DocumentStore.atomic(planned.file, planned.after);
        report(plan, "apply");
        List<String> lines = new ArrayList<>();
        lines.add("Relinked " + counted(plan) + "; reload " + plugin.label + " to use them, or undo with /animus import " + plugin.key() + " rollback");
        details(plan, lines);
        return lines;
    }

    public List<String> rollback() throws IOException {
        Path manifest = state("applied.json");
        if (!Files.exists(manifest)) throw new IOException("No " + plugin.label + " relink to roll back");
        JsonArray files = JsonDocuments.object(JsonDocuments.read(manifest)).getAsJsonArray("files");
        List<String> edited = new ArrayList<>();
        List<String> restore = new ArrayList<>();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String relative = file.get("path").getAsString();
            Path target = inside(relative);
            String current = Files.exists(target) ? JsonDocuments.hash(Files.readString(target)) : "";
            if (current.equals(file.get("writtenSha256").getAsString())) restore.add(relative);
            else if (!current.equals(file.get("originalSha256").getAsString())) edited.add(relative);
        }
        if (!edited.isEmpty()) throw new IOException("Edited after the relink, so not rolled back: " + String.join(", ", edited) + "; restore them from imports/links/" + plugin.key() + "/originals/ by hand");
        for (String relative : restore) DocumentStore.atomic(inside(relative), Files.readString(state("originals/" + relative)));
        delete(state("originals"));
        Files.delete(manifest);
        return List.of("Restored " + files.size() + " file" + (files.size() == 1 ? "" : "s") + " to their Citizens references; reload " + plugin.label + " to use them");
    }

    private Plan plan() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No plugins/" + plugin.folder + " folder to relink");
        List<Path> found = files();
        List<String> texts = new ArrayList<>();
        for (Path file : found) texts.add(Files.readString(file));
        Set<String> linked = new HashSet<>();
        for (int i = 0; i < found.size(); i++) linked.addAll(firstPass(relative(found.get(i)), texts.get(i)));
        List<Planned> planned = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            String relative = relative(found.get(i)), before = texts.get(i);
            Rewritten rewritten = rewrite(relative, before, linked);
            notes.addAll(rewritten.notes());
            if (rewritten.changes() > 0) planned.add(new Planned(found.get(i), relative, before, rewritten.text(), rewritten.changes(), rewritten.shown()));
        }
        return new Plan(planned, notes);
    }

    /** The plugin's files that can name NPCs, in name order. */
    private List<Path> files() throws IOException {
        if (plugin == Plugin.QUESTS) {
            List<Path> found = new ArrayList<>();
            for (String name : QUESTS_FILES) if (Files.isRegularFile(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) found.add(root.resolve(name));
            return found;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter((Path path) -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().endsWith(plugin.extension))
                    .filter((Path path) -> plugin != Plugin.BEAUTYQUESTS || relative(path).equals("questPools.yml") || relative(path).startsWith("quests/"))
                    .sorted().toList();
        }
    }

    private Rewritten rewrite(String relative, String before, Set<String> linked) {
        return switch (plugin) {
            case QUESTS, BEAUTYQUESTS -> {
                QuestYaml.Rewrite result = plugin == Plugin.QUESTS ? QuestYaml.quests(before, links) : QuestYaml.beautyQuests(before, links);
                yield new Rewritten(result.text(), result.references(), result.changes().stream().map((String change) -> relative + " " + change).toList(),
                        result.problems().stream().map((String problem) -> relative + " " + problem).toList());
            }
            case BETONQUEST -> {
                BetonQuestLinks.Result result = BetonQuestLinks.rewrite(relative, before, links, linked);
                yield new Rewritten(result.text(), result.changes().size(), result.changes().stream()
                        .map((BetonQuestLinks.Change change) -> relative + ":" + change.line() + " " + change.before().strip() + " -> " + change.after().strip()).toList(), result.notes());
            }
        };
    }

    private Set<String> firstPass(String relative, String text) {
        return switch (plugin) {
            case BETONQUEST -> BetonQuestLinks.rewrite(relative, text, links, Set.of()).linkedNpcs();
            case QUESTS, BEAUTYQUESTS -> Set.of();
        };
    }

    private void report(Plan plan, String step) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("plugin", plugin.label);
        report.addProperty("step", step);
        report.addProperty("at", Instant.now().toString());
        report.addProperty("importedNpcs", links.size());
        JsonArray changes = new JsonArray();
        for (Planned planned : plan.files) planned.shown.forEach(changes::add);
        report.add("changes", changes);
        JsonArray notes = new JsonArray();
        plan.notes.forEach(notes::add);
        report.add("notes", notes);
        DocumentStore.atomic(state("report.json"), JsonDocuments.write(report));
    }

    private void details(Plan plan, List<String> lines) {
        List<String> shown = plan.files.stream().flatMap((Planned planned) -> planned.shown.stream()).toList();
        shown.stream().limit(SHOWN).forEach(lines::add);
        plan.notes.stream().limit(SHOWN).map((String note) -> "Review: " + note).forEach(lines::add);
        if (shown.size() > SHOWN || plan.notes.size() > SHOWN) lines.add("Full list: imports/links/" + plugin.key() + "/report.json");
    }

    private static String counted(Plan plan) {
        int references = plan.references(), files = plan.files.size();
        return references + " reference" + (references == 1 ? "" : "s") + " in " + files + " file" + (files == 1 ? "" : "s");
    }

    private String relative(Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    private Path inside(String relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Not inside plugins/" + plugin.folder + ": " + relative);
        return resolved;
    }

    private Path state(String relative) throws IOException { return store.resolve("imports/links/" + plugin.key() + "/" + relative); }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
