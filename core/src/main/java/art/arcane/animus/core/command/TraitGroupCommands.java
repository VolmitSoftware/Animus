package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.text.Palette;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * {@code /animus groups}: trait groups and the NPCs that list them. Assign and unassign edit every matching NPC file as one
 * transaction with one rollback receipt, refused whole if any result is invalid.
 */
@Director(name = "groups", description = "List trait groups and assign them to NPCs")
final class TraitGroupCommands extends CommandGroup {
    static final String SELECTORS = "Select NPCs with one of folder=<npc-folder>, tag=<tag> or ids=a,b (@selected is your selection)";

    TraitGroupCommands(CommandServices services) { super(services); }

    @Director(description = "List trait groups with their members and problems")
    void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        try {
            services.files().execute(() -> {
                List<String> rows = rows(repository);
                String title = "Trait groups (" + repository.groups().groups().size() + "), group-problems=" + repository.groupProblems().size();
                if (!stopping()) actor.reply(() -> listing(actor, title, "/" + BuildIdentity.ID + " groups list", rows, page, "No trait groups yet; save one as groups/<id>.json."));
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error("The Animus file queue is full; retry shortly.")); }
    }

    @Director(description = "Show a group's values and problems")
    void inspect(@Param(name = "group-id", description = "The trait group id") String group) {
        NpcRepository repository = runtime().npcs();
        files(actor(), () -> inspect(repository, group));
    }

    @Director(description = "List the NPCs that use a group")
    void members(@Param(name = "group-id", description = "The trait group id") String group) {
        NpcRepository repository = runtime().npcs();
        files(actor(), () -> members(repository, group));
    }

    @Director(description = "Add a group to every matching NPC in one transaction")
    void assign(@Param(name = "group-id", description = "The trait group id") String group,
                @Param(name = "folder", defaultValue = "", description = "NPCs whose folder under npcs/ is exactly this") String folder,
                @Param(name = "tag", defaultValue = "", description = "NPCs whose effective tags include this") String tag,
                @Param(name = "ids", defaultValue = "", description = "Comma-separated NPC ids; @selected is your selection") String ids) {
        change(group, folder, tag, ids, true);
    }

    @Director(description = "Remove a group from every matching NPC in one transaction")
    void unassign(@Param(name = "group-id", description = "The trait group id") String group,
                  @Param(name = "folder", defaultValue = "", description = "NPCs whose folder under npcs/ is exactly this") String folder,
                  @Param(name = "tag", defaultValue = "", description = "NPCs whose effective tags include this") String tag,
                  @Param(name = "ids", defaultValue = "", description = "Comma-separated NPC ids; @selected is your selection") String ids) {
        change(group, folder, tag, ids, false);
    }

    /** Reads the one selector on the actor's owner, where {@code @selected} resolves, then edits the files on the file lane. */
    private void change(String group, String folder, String tag, String ids, boolean assign) {
        CommandActor actor = actor();
        int given = (folder.isEmpty() ? 0 : 1) + (tag.isEmpty() ? 0 : 1) + (ids.isEmpty() ? 0 : 1);
        if (given != 1) { actor.tell(Palette.error(SELECTORS)); return; }
        Set<String> named = new LinkedHashSet<>();
        for (String word : ids.split(",")) {
            if (word.isBlank()) continue;
            Optional<String> id = npc(actor, word.strip());
            if (id.isEmpty()) return;
            named.add(id.get());
        }
        if (!ids.isEmpty() && named.isEmpty()) { actor.tell(Palette.error(SELECTORS)); return; }
        NpcRepository repository = runtime().npcs();
        Predicate<NpcDefinition> matches = selector(repository, folder, tag, named);
        files(actor, () -> {
            String line = change(repository, group, matches, assign);
            if (!line.contains("; transaction ")) return List.of(line);
            living().refresh();
            return List.of(Palette.success(line));
        });
    }

    /** NPCs in exactly {@code folder}, with {@code tag} among their effective tags, or named in {@code ids}; one of them is set. */
    static Predicate<NpcDefinition> selector(NpcRepository repository, String folder, String tag, Set<String> ids) {
        if (!folder.isEmpty()) return (NpcDefinition definition) -> definition.group().equals(folder);
        if (!tag.isEmpty()) return (NpcDefinition definition) -> repository.find(definition.id()).map((NpcDefinition effective) -> effective.tags().contains(tag)).orElse(false);
        Set<String> chosen = Set.copyOf(ids);
        return (NpcDefinition definition) -> chosen.contains(definition.id());
    }

    /** {@code id | revision | members | description}, then the group files that failed to load. File lane. */
    static List<String> rows(NpcRepository repository) {
        List<String> rows = new ArrayList<>();
        for (GroupDocument group : repository.groups().groups().values())
            rows.add(group.id() + " | revision " + group.revision() + " | members " + memberIds(repository, group.id()).size()
                    + (group.description().isEmpty() ? "" : " | " + group.description()));
        rows.addAll(repository.groups().problems());
        return rows;
    }

    static List<String> inspect(NpcRepository repository, String id) throws IOException {
        GroupDocument group = repository.groups().find(id).orElseThrow(() -> new IOException("Unknown group " + id));
        List<String> lines = new ArrayList<>(List.of("Group " + group.id() + " | revision " + group.revision() + " | members " + memberIds(repository, id).size()
                + (group.description().isEmpty() ? "" : " | " + group.description())));
        lines.add(group.values().toString());
        repository.groups().problems().stream().filter((String problem) -> problem.startsWith(group.path() + ": ")).forEach(lines::add);
        return lines;
    }

    static List<String> members(NpcRepository repository, String id) {
        List<String> members = memberIds(repository, id);
        List<String> lines = new ArrayList<>(List.of("Members of " + id + ": " + members.size()));
        if (!members.isEmpty()) lines.add(String.join(", ", members));
        return lines;
    }

    /** Adds or removes {@code id} in the {@code groups} of every matching stored NPC, as one transaction. File lane. */
    static String change(NpcRepository repository, String id, Predicate<NpcDefinition> matches, boolean assign) throws IOException {
        if (assign && repository.groups().find(id).isEmpty())
            throw new IOException("Unknown group " + id + "; save groups/" + id + ".json and wait for it to load, or run /animus reload");
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        int unchanged = 0;
        for (NpcDefinition definition : List.copyOf(repository.stored())) {
            if (!matches.test(definition)) continue;
            boolean listed = definition.groups().contains(id);
            if (listed == assign) { unchanged++; continue; }
            NpcRepository.Draft draft = repository.draft(definition.id());
            JsonObject document = draft.document();
            JsonArray groups = new JsonArray();
            for (String group : definition.groups()) if (!group.equals(id)) groups.add(group);
            if (assign) groups.add(id);
            if (groups.isEmpty()) document.remove("groups");
            else document.add("groups", groups);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String verb = assign ? "assigned " : "unassigned ";
        if (drafts.isEmpty()) return verb + "0, unchanged " + unchanged;
        return verb + drafts.size() + ", unchanged " + unchanged + "; transaction " + repository.applyAll(drafts);
    }

    private static List<String> memberIds(NpcRepository repository, String id) {
        return repository.stored().stream().filter((NpcDefinition definition) -> definition.groups().contains(id)).map(NpcDefinition::id).sorted().toList();
    }
}
