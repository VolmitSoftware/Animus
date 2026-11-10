package art.arcane.animus.core.quest;

import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Every quest under {@code quests/}, read off the server thread. A file at {@code <namespace>/<name>.json} has the id
 * {@code namespace:name}. A quest with any problem is left out and its problems are listed; one whose rewards run
 * commands needs its id or namespace on the command allow-list.
 */
public record QuestCatalog(Map<String, QuestDocument> quests, List<String> problems, Map<String, List<String>> byNpc) {
    public static final QuestCatalog EMPTY = new QuestCatalog(Map.of(), List.of());
    public static final String FOLDER = "quests";

    public QuestCatalog { quests = Map.copyOf(quests); problems = List.copyOf(problems); byNpc = Map.copyOf(byNpc); }

    public QuestCatalog(Map<String, QuestDocument> quests, List<String> problems) { this(quests, problems, index(quests)); }

    public Optional<QuestDocument> quest(String id) { return Optional.ofNullable(quests.get(id)); }

    /** Quests that name the NPC as a giver, turn-in or talk or bring target, in id order. */
    public List<String> touching(String npc) { return byNpc.getOrDefault(npc, List.of()); }

    /** Quests the NPC offers, in id order. */
    public List<QuestDocument> offeredBy(String npc) {
        List<QuestDocument> offered = new ArrayList<>();
        for (String id : touching(npc)) quest(id).filter((QuestDocument quest) -> quest.givers().contains(npc)).ifPresent(offered::add);
        return offered;
    }

    private static Map<String, List<String>> index(Map<String, QuestDocument> quests) {
        Map<String, List<String>> byNpc = new HashMap<>();
        for (QuestDocument quest : new TreeMap<>(quests).values())
            for (String npc : quest.npcs()) byNpc.computeIfAbsent(npc, (String ignored) -> new ArrayList<>()).add(quest.id());
        Map<String, List<String>> frozen = new HashMap<>();
        byNpc.forEach((String npc, List<String> ids) -> frozen.put(npc, List.copyOf(ids)));
        return frozen;
    }

    public static QuestCatalog load(Path data, Predicate<String> commandsAllowed) throws IOException {
        Map<String, QuestDocument> quests = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Path root = data.resolve(FOLDER);
        for (Path file : JsonDocumentTree.files(root)) {
            Path relative = root.relativize(file);
            String label = FOLDER + "/" + relative.toString().replace('\\', '/');
            if (relative.getNameCount() != 2) { problems.add(label + ": place quests directly in a namespace folder, such as quests/town/herbs.json"); continue; }
            String id = relative.getName(0) + ":" + relative.getName(1).toString().replaceFirst("\\.json$", "");
            if (!id.matches(QuestDocument.ID)) { problems.add(label + ": file names use lowercase letters, digits, _ and -"); continue; }
            String text;
            try { text = JsonDocuments.read(file); }
            catch (IOException unreadable) { problems.add(label + ": " + unreadable.getMessage()); continue; }
            QuestDocument.Result result = QuestDocument.parse(id, text);
            result.problems().forEach((String problem) -> problems.add(label + " " + problem));
            result.quest().ifPresent((QuestDocument quest) -> {
                if (quest.hasCommands() && !commandsAllowed.test(quest.id())) problems.add(label + " $.rewards.commands: runs commands, so add " + quest.id()
                        + " to commandBehaviors or its namespace to commandNamespaces under [behaviors.actions] in config.toml");
                else quests.put(quest.id(), quest);
            });
        }
        linked(quests, problems);
        return new QuestCatalog(quests, problems);
    }

    /**
     * Refuses each quest that requires a quest not loaded, or that requires one which, through its own requirements,
     * requires it back: neither could ever be taken. A refusal can leave further quests requiring a missing one.
     */
    private static void linked(Map<String, QuestDocument> quests, List<String> problems) {
        Map<String, String> refused = new TreeMap<>();
        for (Map.Entry<String, QuestDocument> entry : quests.entrySet())
            for (String before : entry.getValue().requirements().quests())
                if (!entry.getKey().equals(before) && reaches(quests, before, entry.getKey())) { refused.putIfAbsent(entry.getKey(), "requires " + before + ", which requires it back"); break; }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, QuestDocument> entry : quests.entrySet()) {
                if (refused.containsKey(entry.getKey())) continue;
                for (String before : entry.getValue().requirements().quests())
                    if (!quests.containsKey(before) || refused.containsKey(before)) { refused.put(entry.getKey(), "requires " + before + ", which is not loaded"); changed = true; break; }
            }
        }
        refused.forEach((String id, String reason) -> {
            quests.remove(id);
            problems.add(FOLDER + "/" + id.replace(':', '/') + ".json $.requirements.quests: " + reason);
        });
    }

    /** Whether {@code from}'s requirements lead, step by step, to {@code target}. */
    private static boolean reaches(Map<String, QuestDocument> quests, String from, String target) {
        java.util.Deque<String> open = new java.util.ArrayDeque<>(List.of(from));
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!open.isEmpty()) {
            String at = open.pop();
            if (at.equals(target)) return true;
            if (!seen.add(at)) continue;
            QuestDocument quest = quests.get(at);
            if (quest != null) open.addAll(quest.requirements().quests());
        }
        return false;
    }

    /**
     * This catalog plus each quest of {@code previous} that no longer loads while its file is still there, so a broken
     * edit keeps the last valid version. Deleted files drop their quests; command refusals are never kept.
     */
    public QuestCatalog retaining(QuestCatalog previous, Path data, Predicate<String> commandsAllowed) {
        Map<String, QuestDocument> kept = new TreeMap<>(quests);
        for (Map.Entry<String, QuestDocument> entry : previous.quests.entrySet())
            if (!kept.containsKey(entry.getKey()) && present(data, entry.getKey()) && (!entry.getValue().hasCommands() || commandsAllowed.test(entry.getKey())))
                kept.put(entry.getKey(), entry.getValue());
        return new QuestCatalog(kept, problems);
    }

    private static boolean present(Path data, String id) {
        int colon = id.indexOf(':');
        return Files.isRegularFile(data.resolve(FOLDER).resolve(id.substring(0, colon)).resolve(id.substring(colon + 1) + ".json"), LinkOption.NOFOLLOW_LINKS);
    }
}
