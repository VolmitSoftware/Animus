package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The valid trait groups of one reload. An invalid group file keeps its last valid version; a deleted one is gone. */
public final class GroupCatalog {
    public static final GroupCatalog EMPTY = new GroupCatalog(Map.of(), Map.of());
    private final Map<String, GroupDocument> groups;
    private final Map<String, String> problems;

    private GroupCatalog(Map<String, GroupDocument> groups, Map<String, String> problems) {
        this.groups = Collections.unmodifiableMap(new TreeMap<>(groups));
        this.problems = Collections.unmodifiableMap(new TreeMap<>(problems));
    }

    public static GroupCatalog load(DocumentStore store, GroupCatalog previous) throws IOException {
        Map<String, GroupDocument> groups = new TreeMap<>();
        Map<String, String> problems = new TreeMap<>();
        for (String id : GroupDocuments.ids(store)) {
            String path = GroupDocuments.FOLDER + "/" + id + ".json";
            List<String> errors;
            try {
                GroupDocument.Result result = GroupDocument.parse(id, GroupDocuments.read(store, id));
                if (result.group().isPresent()) { groups.put(id, result.group().get()); continue; }
                errors = result.problems();
            } catch (IOException unreadable) { errors = List.of(String.valueOf(unreadable.getMessage())); }
            String message = errors.getFirst() + (errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : "");
            GroupDocument kept = previous.groups.get(id);
            if (kept != null) {
                groups.put(id, kept);
                message += "; kept the last valid revision " + kept.revision();
            }
            problems.put(path, message);
        }
        return new GroupCatalog(groups, problems);
    }

    public Optional<GroupDocument> find(String id) { return Optional.ofNullable(groups.get(id)); }
    public Map<String, GroupDocument> groups() { return groups; }
    /** Refused group files, as {@code groups/<id>.json: message}. */
    public List<String> problems() { return problems.entrySet().stream().map((Map.Entry<String, String> entry) -> entry.getKey() + ": " + entry.getValue()).toList(); }
}
