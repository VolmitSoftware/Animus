package art.arcane.animus.core.npc;

import art.arcane.animus.core.command.AnimusCommands;
import art.arcane.animus.core.command.CommandActor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /animus groups list|inspect|members|assign|unassign}, the same on every platform. Assign and unassign edit the
 * matching NPC files as one transaction with one rollback receipt, refused whole if any result is invalid. File work runs
 * on the shared file lane; replies go back on the actor's owner.
 */
public final class GroupCommands implements AnimusCommands.Extra {
    public static final List<String> OPERATIONS = List.of("list", "inspect", "members", "assign", "unassign");
    private static final String USAGE = "groups list | inspect <group-id> | members <group-id> | assign|unassign <group-id> folder=<npc-folder>|tag=<tag>|ids=a,b|selection";
    private final Supplier<NpcRepository> npcs;
    private final Executor files;
    private final Function<UUID, Optional<String>> selection;
    private final Runnable refresh;

    /**
     * @param selection the NPC a player has selected, read on the player's owner
     * @param refresh asks the NPC runtime to pick up changed definitions
     */
    public GroupCommands(Supplier<NpcRepository> npcs, Executor files, Function<UUID, Optional<String>> selection, Runnable refresh) {
        this.npcs = Objects.requireNonNull(npcs);
        this.files = Objects.requireNonNull(files);
        this.selection = Objects.requireNonNull(selection);
        this.refresh = Objects.requireNonNull(refresh);
    }

    @Override
    public void execute(CommandActor actor, String[] arguments) {
        if (!actor.permitted("animus.admin")) { actor.tell("You do not have permission to use this command."); return; }
        String operation = arguments.length < 2 ? "list" : arguments[1].toLowerCase(Locale.ROOT);
        try {
            switch (operation) {
                case "list" -> {
                    if (arguments.length > 2) throw new IllegalArgumentException(USAGE);
                    work(actor, this::list);
                }
                case "inspect", "members" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException("groups " + operation + " <group-id>");
                    String id = arguments[2];
                    work(actor, () -> operation.equals("inspect") ? inspect(id) : members(id));
                }
                case "assign", "unassign" -> {
                    if (arguments.length != 4) throw new IllegalArgumentException("groups " + operation + " <group-id> folder=<npc-folder>|tag=<tag>|ids=a,b|selection");
                    String id = arguments[2];
                    Predicate<NpcDefinition> matches = selector(actor, arguments[3]);
                    boolean assign = operation.equals("assign");
                    work(actor, () -> change(id, matches, assign));
                }
                default -> throw new IllegalArgumentException(USAGE);
            }
        } catch (IllegalArgumentException invalid) { actor.tell("Animus groups: " + invalid.getMessage()); }
    }

    @Override
    public List<String> complete(CommandActor actor, String[] arguments) {
        if (!actor.permitted("animus.admin")) return List.of();
        if (arguments.length == 2) return matching(OPERATIONS, arguments[1]);
        NpcRepository repository = npcs.get();
        if (arguments.length == 3 && !arguments[1].equalsIgnoreCase("list") && repository != null) return matching(List.copyOf(repository.groups().groups().keySet()), arguments[2]);
        if (arguments.length == 4 && List.of("assign", "unassign").contains(arguments[1].toLowerCase(Locale.ROOT))) return matching(List.of("folder=", "tag=", "ids=", "selection"), arguments[3]);
        return List.of();
    }

    /** Which NPCs a selector names. {@code selection} is read now, on the actor's owner. */
    private Predicate<NpcDefinition> selector(CommandActor actor, String selector) {
        if (selector.equalsIgnoreCase("selection")) {
            UUID player = actor.player().orElseThrow(() -> new IllegalArgumentException("selection belongs to a player; use ids= from the console"));
            String selected = selection.apply(player).orElseThrow(() -> new IllegalArgumentException("No NPC selected; use /animus select <id> or ids="));
            return (NpcDefinition definition) -> definition.id().equals(selected);
        }
        int equals = selector.indexOf('=');
        String key = equals < 0 ? "" : selector.substring(0, equals).toLowerCase(Locale.ROOT), value = equals < 0 ? "" : selector.substring(equals + 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Select NPCs with folder=<npc-folder>, tag=<tag>, ids=a,b or selection");
        return switch (key) {
            case "folder" -> (NpcDefinition definition) -> definition.group().equals(value);
            case "tag" -> (NpcDefinition definition) -> npcs.get().find(definition.id()).map((NpcDefinition effective) -> effective.tags().contains(value)).orElse(false);
            case "ids" -> {
                Set<String> ids = Set.copyOf(Arrays.stream(value.split(",")).map(String::strip).filter((String id) -> !id.isEmpty()).toList());
                yield (NpcDefinition definition) -> ids.contains(definition.id());
            }
            default -> throw new IllegalArgumentException("Unknown selector " + key + "; use folder=, tag=, ids= or selection");
        };
    }

    private List<String> list() throws IOException {
        NpcRepository repository = repository();
        Map<String, GroupDocument> groups = repository.groups().groups();
        List<String> lines = new ArrayList<>(List.of("Trait groups: " + groups.size() + "; group-problems=" + repository.groupProblems().size()));
        for (GroupDocument group : groups.values())
            lines.add(group.id() + " | revision " + group.revision() + " | members " + memberIds(repository, group.id()).size()
                    + (group.description().isEmpty() ? "" : " | " + group.description()));
        lines.addAll(repository.groups().problems());
        return lines;
    }

    private List<String> inspect(String id) throws IOException {
        NpcRepository repository = repository();
        GroupDocument group = repository.groups().find(id).orElseThrow(() -> new IOException("Unknown group " + id));
        List<String> lines = new ArrayList<>(List.of("Group " + group.id() + " | revision " + group.revision() + " | members " + memberIds(repository, id).size()
                + (group.description().isEmpty() ? "" : " | " + group.description())));
        lines.add(group.values().toString());
        repository.groups().problems().stream().filter((String problem) -> problem.startsWith(group.path() + ": ")).forEach(lines::add);
        return lines;
    }

    private List<String> members(String id) throws IOException {
        List<String> members = memberIds(repository(), id);
        List<String> lines = new ArrayList<>(List.of("Members of " + id + ": " + members.size()));
        if (!members.isEmpty()) lines.add(String.join(", ", members));
        return lines;
    }

    private List<String> change(String id, Predicate<NpcDefinition> matches, boolean assign) throws IOException {
        NpcRepository repository = repository();
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
        if (drafts.isEmpty()) return List.of(verb + "0, unchanged " + unchanged);
        String transaction = repository.applyAll(drafts);
        refresh.run();
        return List.of(verb + drafts.size() + ", unchanged " + unchanged + "; transaction " + transaction);
    }

    private static List<String> memberIds(NpcRepository repository, String id) {
        return repository.stored().stream().filter((NpcDefinition definition) -> definition.groups().contains(id)).map(NpcDefinition::id).sorted().toList();
    }

    private NpcRepository repository() throws IOException {
        NpcRepository repository = npcs.get();
        if (repository == null) throw new IOException("Animus is not running.");
        return repository;
    }

    @FunctionalInterface private interface Work { List<String> run() throws IOException; }

    private void work(CommandActor actor, Work work) {
        try {
            files.execute(() -> {
                List<String> lines;
                try { lines = work.run(); }
                catch (IOException | RuntimeException failure) { lines = List.of("Animus groups: " + failure.getMessage()); }
                List<String> reply = lines.stream().limit(30).toList();
                actor.reply(() -> reply.forEach(actor::tell));
            });
        } catch (RejectedExecutionException full) { actor.tell("Animus file queue is full; retry shortly."); }
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter((String option) -> option.startsWith(lowered)).sorted().toList();
    }
}
