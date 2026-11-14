package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The NPCs that use one trait group. A click selects only that NPC; a right-click opens its details, which add it to
 * or take it out of the selection, edit it, or take it out of the trait group. Select all members replaces the selection.
 */
final class GroupMembersScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECT_ALL = ACTIONS;
    /** Slots of a member's details. */
    static final int MEMBER = 4, SELECTED = 10, EDIT = 11, LEAVE = 17;
    private final String group;
    private Map<String, NpcItem> skins = Map.of();

    GroupMembersScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String group) {
        super(toolkit, viewer, commands);
        this.group = group;
    }

    @Override void begin() {
        List<NpcDefinition> members = GroupFiles.members(toolkit.npcs(), group).stream().flatMap((String id) -> toolkit.npcs().find(id).stream()).toList();
        toolkit.later(viewer, (ToolkitFiles ignored) -> new SkinFiles(toolkit.npcs(), toolkit.documents()).worn(members),
                (Map<String, NpcItem> worn) -> { skins = worn; viewer.refresh(); });
    }

    @Override String title() { return ValueScreen.clip("Members of " + group, 32); }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    /** The member's skin head for a player body, else a name tag. */
    private NpcItem face(String id) {
        boolean player = toolkit.npcs().find(id).map((NpcDefinition npc) -> ToolkitFiles.PLAYER.equals(PropertyScreen.type(npc.json()))).orElse(false);
        return player ? Optional.ofNullable(skins.get(id)).orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty())) : new NpcItem("minecraft:name_tag", 1, Optional.empty(), Optional.empty());
    }

    /** {@code Folder town / market}, where the member's file is kept. */
    private String where(String id) {
        return toolkit.npcs().find(id).map((NpcDefinition npc) -> "&7Folder " + FolderFiles.shown(npc.group())).orElse("&7Not found");
    }

    @Override void draw() {
        List<String> members = GroupFiles.members(toolkit.npcs(), group);
        Set<String> selected = new HashSet<>(toolkit.selected(viewer.id()));
        list(members, ToolScreen::itself, "No members yet; use it on an NPC in Trait groups", (Integer slot, String id) -> {
            List<String> lines = new ArrayList<>(List.of(where(id)));
            if (selected.contains(id)) lines.add("&aSelected");
            button(slot, face(id), title(id), lore(lines, hints("select only it", "details", "")), (GuiModel.Click click) -> {
                if (right(click)) push(new Member(id));
                else toolkit.only(viewer.player(), id);
            });
        });
        button(SELECT_ALL, "minecraft:spyglass", title("Select all members"), lore(List.of("&7Replaces the selection with", "&7" + ToolkitFiles.count(members.size())), any("select them")),
                (GuiModel.Click click) -> {
                    int kept = toolkit.selection().set(viewer.id(), members);
                    toolkit.say(viewer.player(), ChatLine.reply("Selected " + ToolkitFiles.count(kept) + " in " + group + NpcsScreen.capped(kept, members.size())));
                });
        back();
        help("Click a member to select only it; right-click it to edit it or take it out of the trait group.", "21-in-game-toolkit", "one-trait-group");
    }

    /** One member: in or out of the selection, edit it, and Remove from trait group, confirmed. */
    private final class Member extends ToolScreen<P, S> {
        private final String id;

        Member(String id) {
            super(GroupMembersScreen.this.toolkit, GroupMembersScreen.this.viewer, GroupMembersScreen.this.commands);
            this.id = id;
        }

        @Override String title() { return ValueScreen.clip(ToolkitFiles.named(toolkit.npcs(), List.of(id)) + " in " + group, 32); }
        @Override String node() { return PermissionNodes.NPC_EDIT; }
        @Override int rows() { return 3; }

        @Override void draw() {
            back();
            help("Take off takes " + group + " off this NPC; its own settings stay.", "21-in-game-toolkit", "one-trait-group");
            if (!GroupFiles.members(toolkit.npcs(), group).contains(id)) {
                icon(MEMBER, "minecraft:paper", "&7" + id + " is not in " + group, List.of("&7Go back to see the members"), Optional.empty());
                return;
            }
            icon(MEMBER, face(id), title(id), List.of(where(id)), Optional.empty());
            boolean in = toolkit.selected(viewer.id()).contains(id);
            button(SELECTED, in ? FieldScreen.ON : FieldScreen.OFF, title("Selected: " + (in ? "yes" : "no")), lore(List.of("&7" + toolkit.selected(viewer.id()).size() + " selected now"),
                    any(in ? "take it out" : "add it")), (GuiModel.Click click) -> {
                try { toolkit.selection().toggle(viewer.id(), id); }
                catch (IllegalStateException full) { refuse(String.valueOf(full.getMessage())); }
            });
            button(EDIT, "minecraft:writable_book", title("Edit it"), lore(List.of("&7Selects only it and opens it"), any("edit it")), (GuiModel.Click click) -> {
                toolkit.only(viewer.player(), id);
                push(new NpcScreen<>(toolkit, viewer, commands));
            });
            confirm(LEAVE, "minecraft:shears", "Take off", "Takes " + group + " off " + id + "; Undo in chat brings it back", 1, true, () -> {
                toolkit.write(viewer, (ToolkitFiles files) -> files.groups(group, List.of(id), false));
                viewer.back();
            });
        }
    }
}
