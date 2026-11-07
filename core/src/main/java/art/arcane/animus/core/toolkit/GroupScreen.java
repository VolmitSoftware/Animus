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
import java.util.Optional;
import java.util.Set;

/** Trait groups with their member counts. A click assigns the group to the whole selection in one transaction. */
final class GroupScreen<P, S> extends ToolScreen<P, S> {
    static final int FOLDERS = 53;

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
        list(groups, "No trait groups; save one as groups/<id>.json", (Integer slot, GroupDocument group) -> {
            List<String> lines = new ArrayList<>();
            if (!group.description().isEmpty()) lines.add("&7" + group.description());
            lines.add("&7Members &f" + members.getOrDefault(group.id(), 0));
            lines.add("&7In the selection &f" + picked.getOrDefault(group.id(), 0) + "&7 of &f" + selected.size());
            button(slot, "minecraft:book", title(group.id()), lore(lines, hints("assign to the selection", "unassign", "unassign")), (GuiModel.Click click) -> {
                boolean assign = !right(click) && !sneak(click);
                chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles files) -> files.groups(group.id(), ids, assign)));
            });
        });
        back();
        icon(46, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"), List.of("&7" + names(selected, 6)), Optional.empty());
        button(FOLDERS, "minecraft:chest", title("Folders"), any("move the selection to a folder"), (GuiModel.Click click) -> viewer.push(new FolderScreen<>(toolkit, viewer, commands)));
    }
}
