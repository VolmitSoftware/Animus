package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
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
 * Trait groups with their member counts. A click puts the trait group on the whole selection in one transaction, a
 * right-click opens its details, where it comes off again. New trait group saves an empty one.
 */
final class GroupScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS, SELECTION = ACTIONS + 8;

    GroupScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Trait groups"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

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
        String who = selected.isEmpty() ? "" : named(selected);
        list(groups, (GroupDocument group) -> group.id() + " " + group.description(), "No trait groups yet; New trait group makes one", (Integer slot, GroupDocument group) -> {
            int count = members.getOrDefault(group.id(), 0), on = picked.getOrDefault(group.id(), 0);
            List<String> lines = new ArrayList<>(List.of("&7" + (group.description().isEmpty() ? count + (count == 1 ? " member" : " members") : group.description())));
            if (on > 0) lines.add(FieldScreen.now(on == selected.size() ? "on " + who : "on " + on + " of " + ToolkitFiles.count(selected.size()), false));
            button(slot, "minecraft:book", title(group.id()), lore(lines, hints(selected.isEmpty() ? "details" : "use on " + who, "details", "")), (GuiModel.Click click) -> {
                if (right(click) || selected.isEmpty()) { push(new GroupDetailScreen<>(toolkit, viewer, commands, group.id())); return; }
                chosen().ifPresent((List<String> ids) -> ask(slot, "Puts " + group.id() + " on " + named(ids), ids.size(), false,
                        () -> toolkit.write(viewer, (ToolkitFiles files) -> files.groups(group.id(), ids, true))));
            });
        });
        NpcsScreen.summary(this, SELECTION, selected);
        button(CREATE, "minecraft:writable_book", title("New trait group"), lore(List.of("&7Starts empty; the selection gets it"), any("type its id")),
                (GuiModel.Click click) -> ask(CREATE, "Puts a new trait group on " + who, selected.size(), false, () -> prompt("Trait group id", "",
                        (String id) -> toolkit.write(viewer, (ToolkitFiles files) -> new GroupFiles(toolkit.npcs(), toolkit.documents()).create(id.strip(), selected)))));
        back();
        help("A trait group holds settings many NPCs share; change it once and every member follows. Click one to use it on the selection.", "21-in-game-toolkit", "trait-groups");
    }
}
