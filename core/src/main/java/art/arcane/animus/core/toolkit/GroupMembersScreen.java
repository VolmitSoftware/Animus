package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The NPCs that list one trait group: select them one at a time or all at once, or take them out of the group. */
final class GroupMembersScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECT_ALL = 53;
    private final String group;

    GroupMembersScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String group) {
        super(toolkit, viewer, commands);
        this.group = group;
    }

    @Override String title() { return "Members of " + group; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> members = GroupFiles.members(toolkit.npcs(), group);
        Set<String> selected = new HashSet<>(toolkit.selected(viewer.id()));
        list(members, "No NPCs list " + group, (Integer slot, String id) -> {
            Optional<NpcDefinition> definition = toolkit.npcs().find(id);
            boolean player = definition.map((NpcDefinition npc) -> ToolkitFiles.PLAYER.equals(npc.json().get("type").getAsString())).orElse(false);
            List<String> lines = new ArrayList<>();
            lines.add("&7Folder &f" + definition.map((NpcDefinition npc) -> npc.group().isEmpty() ? "npcs/" : npc.group()).orElse("?"));
            lines.add(selected.contains(id) ? "&aSelected" : "&7Not selected");
            button(slot, player ? HEAD : "minecraft:name_tag", title(id), lore(lines, hints("select only it", "remove it from " + group, "add to or remove from the selection")),
                    (GuiModel.Click click) -> {
                        if (sneak(click)) toggle(id);
                        else if (right(click)) toolkit.write(viewer, (ToolkitFiles files) -> files.groups(group, List.of(id), false));
                        else toolkit.only(viewer.player(), id);
                    });
        });
        back();
        button(SELECT_ALL, "minecraft:spyglass", title("Select all members"), lore(List.of("&7Replaces the selection with", "&7" + ToolkitFiles.count(members.size())), any("select them")),
                (GuiModel.Click click) -> {
                    int kept = toolkit.selection().set(viewer.id(), members);
                    toolkit.say(viewer.player(), ChatLine.reply("Selected " + ToolkitFiles.count(kept) + " in " + group + SelectionScreen.capped(kept, members.size())));
                });
    }

    private void toggle(String id) {
        try {
            boolean added = toolkit.selection().toggle(viewer.id(), id);
            toolkit.say(viewer.player(), ChatLine.reply((added ? "Added " : "Removed ") + id + ", " + toolkit.selected(viewer.id()).size() + " selected"));
        } catch (IllegalStateException full) { toolkit.say(viewer.player(), ChatLine.error(full.getMessage())); }
    }
}
