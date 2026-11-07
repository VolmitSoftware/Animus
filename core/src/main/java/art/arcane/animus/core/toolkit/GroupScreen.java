package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trait groups with their member counts. A left click assigns the group to the whole selection and a right click
 * unassigns it, each in one transaction; a sneak click opens the group. New group saves an empty group file.
 */
final class GroupScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECTION = 46, CREATE = 47, FOLDERS = 53;
    private final BulkGuard guard = new BulkGuard();

    GroupScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Trait groups"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        NpcRepository npcs = toolkit.npcs();
        List<String> selected = toolkit.selected(viewer.id());
        Set<String> chosen = new HashSet<>(selected);
        Map<String, Integer> members = new HashMap<>(), picked = new HashMap<>();
        for (NpcDefinition definition : npcs.stored())
            for (String group : definition.groups()) {
                members.merge(group, 1, Integer::sum);
                if (chosen.contains(definition.id())) picked.merge(group, 1, Integer::sum);
            }
        List<GroupDocument> groups = new ArrayList<>(npcs.groups().groups().values());
        groups.sort((GroupDocument a, GroupDocument b) -> a.id().compareTo(b.id()));
        list(groups, "No trait groups; click New group", (Integer slot, GroupDocument group) -> {
            String assign = "assign:" + group.id(), unassign = "unassign:" + group.id();
            boolean armed = guard.armed(assign) || guard.armed(unassign);
            List<String> lines = new ArrayList<>();
            if (armed) {
                lines.add("&c" + ToolkitFiles.count(selected.size()) + " affected");
                lines.add("&c" + (guard.armed(assign) ? "Left" : "Right") + " click again to " + (guard.armed(assign) ? "assign" : "unassign"));
            } else if (!group.description().isEmpty()) lines.add("&7" + group.description());
            lines.add("&7Members &f" + members.getOrDefault(group.id(), 0));
            lines.add("&7In the selection &f" + picked.getOrDefault(group.id(), 0) + "&7 of &f" + selected.size());
            button(slot, "minecraft:book", title((armed ? "&c" : "") + group.id()), lore(lines, hints("assign to the selection", "unassign from the selection", "open the group")),
                    (GuiModel.Click click) -> {
                        if (sneak(click)) {
                            guard.clear();
                            viewer.push(new GroupDetailScreen<>(toolkit, viewer, commands, group.id()));
                            return;
                        }
                        boolean adding = !right(click);
                        chosen().ifPresent((List<String> ids) -> {
                            if (guard.ready(adding ? assign : unassign, ids.size()))
                                toolkit.write(viewer, (ToolkitFiles files) -> files.groups(group.id(), ids, adding));
                        });
                    });
        });
        back();
        SelectionScreen.summary(this, SELECTION, selected);
        button(CREATE, "minecraft:writable_book", title("New group"),
                lore(List.of("&7Saves an empty groups/<id>.json", "&7and assigns it to the selection"), any("type its id")),
                (GuiModel.Click click) -> {
                    guard.clear();
                    prompt("New group id", "", (String id) -> {
                        List<String> ids = toolkit.selected(viewer.id());
                        toolkit.write(viewer, (ToolkitFiles files) -> new GroupFiles(toolkit.npcs(), toolkit.documents()).create(id.trim(), ids));
                    });
                });
        button(FOLDERS, "minecraft:chest", title("Folders"), any("move the selection to a folder"), (GuiModel.Click click) -> viewer.push(new FolderScreen<>(toolkit, viewer, commands)));
    }
}
