package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.GroupDocument;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One trait group: rename, describe, members, values and delete. Every change is one transaction with its members. */
final class GroupDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, RENAME = 19, DESCRIPTION = 20, MEMBERS = 22, VALUES = 24, DELETE = 25;
    /** Follows a rename, so the screen keeps showing the same group. */
    private volatile String group;

    GroupDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String group) {
        super(toolkit, viewer, commands);
        this.group = group;
    }

    @Override String title() { return "Group " + group; }
    @Override int rows() { return 6; }

    @Override void draw() {
        back();
        String id = group;
        Optional<GroupDocument> found = toolkit.npcs().groups().find(id);
        if (found.isEmpty()) {
            icon(SUMMARY, "minecraft:paper", "&7No group " + id, List.of("&7It was deleted or renamed"), Optional.empty());
            return;
        }
        GroupDocument document = found.get();
        List<String> members = GroupFiles.members(toolkit.npcs(), id);
        List<String> keys = new ArrayList<>(document.values().keySet());
        icon(SUMMARY, "minecraft:book", title(id), List.of("&7" + (document.description().isEmpty() ? "No description" : document.description()),
                "&7Members &f" + members.size(), "&7Values &f" + names(keys, 6), "&7File groups/" + id + ".json"), Optional.empty());
        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Renames the file and repoints", "&7" + ToolkitFiles.count(members.size()) + " in one transaction"), any("type the new id")),
                (GuiModel.Click click) -> prompt("New group id", id, (String to) -> toolkit.write(viewer, (ToolkitFiles files) -> {
                    String line = files().rename(id, to.trim());
                    group = to.trim();
                    return line;
                })));
        button(DESCRIPTION, "minecraft:writable_book", title("Description"), lore(List.of("&7" + (document.description().isEmpty() ? "None yet" : document.description()), "&7Empty text removes it"),
                any("type a description")),
                (GuiModel.Click click) -> prompt("Group description", document.description(),
                        (String text) -> toolkit.write(viewer, (ToolkitFiles files) -> files().describe(id, text.trim()))));
        button(MEMBERS, HEAD, title("Members"), lore(List.of("&7" + ToolkitFiles.count(members.size()), "&7" + names(members, 6)), any("list them")),
                (GuiModel.Click click) -> viewer.push(new GroupMembersScreen<>(toolkit, viewer, commands, id)));
        button(VALUES, "minecraft:comparator", title("Values"), lore(List.of("&7The NPC fields the group supplies", "&7Set now: " + names(keys, 6)), any("edit them")),
                (GuiModel.Click click) -> viewer.push(new FieldScreen<>(toolkit, viewer, commands, "Values of " + id, PropertyScreen.SCHEMA,
                        () -> toolkit.npcs().groups().find(id).map(GroupDocument::values).map(JsonObject::deepCopy).orElseGet(JsonObject::new),
                        (JsonObject before, JsonObject after) -> files().values(id, after))));
        guarded(DELETE, "minecraft:red_concrete", "Delete", "Unassigns it from " + ToolkitFiles.count(members.size()), members.size(), true,
                () -> toolkit.write(viewer, (ToolkitFiles files) -> files().delete(id)));
    }

    private GroupFiles files() { return new GroupFiles(toolkit.npcs(), toolkit.documents()); }
}
