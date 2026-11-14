package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.GroupDocument;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One trait group: members, values, rename, description, taking it off the selection and delete. Every change is one
 * transaction with its members.
 */
final class GroupDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, MEMBERS = 19, VALUES = 20, RENAME = 21, DESCRIPTION = 23, UNASSIGN = 33, DELETE = 35;
    /** The NPC schema without the fields a trait group may not set. */
    static final JsonObject VALUE_SCHEMA = values();
    /** Follows a rename, so the screen keeps showing the same trait group. */
    private volatile String group;

    GroupDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String group) {
        super(toolkit, viewer, commands);
        this.group = group;
    }

    @Override String title() { return ValueScreen.clip("Trait group " + group, 32); }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    private static JsonObject values() {
        JsonObject schema = PropertyScreen.SCHEMA.deepCopy();
        for (String refused : GroupDocument.REFUSED) schema.getAsJsonObject("properties").remove(refused);
        schema.remove("required");
        return schema;
    }

    @Override void draw() {
        back();
        help("Members use its values unless they set their own. Delete takes it off every member; Undo in chat brings it back.", "21-in-game-toolkit", "one-trait-group");
        String id = group;
        Optional<GroupDocument> found = toolkit.npcs().groups().find(id);
        if (found.isEmpty()) {
            icon(SUMMARY, "minecraft:paper", "&7No trait group " + id, List.of("&7It was deleted or renamed"), Optional.empty());
            return;
        }
        GroupDocument document = found.get();
        List<String> members = GroupFiles.members(toolkit.npcs(), id);
        List<String> keys = new ArrayList<>(document.values().keySet());
        keys.replaceAll((String key) -> ValueScreen.name(key, true));
        String count = members.size() + (members.size() == 1 ? " member" : " members");
        icon(SUMMARY, "minecraft:book", title(id), List.of("&7" + (document.description().isEmpty() ? "No description" : document.description()), FieldScreen.now(count, false)), Optional.empty());
        button(MEMBERS, HEAD, title("Members"), lore(List.of("&7" + names(members, 6), FieldScreen.now(count, false)), any("list them")),
                (GuiModel.Click click) -> push(new GroupMembersScreen<>(toolkit, viewer, commands, id)));
        button(VALUES, "minecraft:comparator", title("Values"), lore(List.of("&7The fields it sets on its members", FieldScreen.now(keys.isEmpty() ? "none" : names(keys, 4), false)), any("change them")),
                (GuiModel.Click click) -> push(new FieldScreen<>(toolkit, viewer, commands, "Values of " + id, VALUE_SCHEMA,
                        () -> toolkit.npcs().groups().find(id).map(GroupDocument::values).map(JsonObject::deepCopy).orElseGet(JsonObject::new),
                        (JsonObject before, JsonObject after) -> files().values(id, after))));
        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Its members follow the new id"), any("type a new id")),
                (GuiModel.Click click) -> prompt("New id", id, (String to) -> toolkit.write(viewer, (ToolkitFiles files) -> {
                    String line = files().rename(id, to.strip());
                    group = to.strip();
                    return line;
                })));
        button(DESCRIPTION, "minecraft:writable_book", title("Description"), lore(List.of("&7" + (document.description().isEmpty() ? "None yet" : document.description())), any("type one, or nothing to remove it")),
                (GuiModel.Click click) -> prompt("Description", document.description(), (String text) -> toolkit.write(viewer, (ToolkitFiles files) -> files().describe(id, text.strip()))));
        unassign(id, members);
        confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes " + id + " and takes it off " + ToolkitFiles.count(members.size()) + "; Undo in chat brings it back", members.size(), true,
                () -> toolkit.write(viewer, (ToolkitFiles files) -> files().delete(id)));
    }

    /** Remove from NPC: takes the trait group off the selected NPCs that have it. */
    private void unassign(String id, List<String> members) {
        List<String> having = toolkit.selected(viewer.id()).stream().filter(members::contains).toList();
        if (having.isEmpty()) {
            icon(UNASSIGN, "minecraft:shears", "&7Remove from NPC", List.of("&7No selected NPC has it"), Optional.empty());
            return;
        }
        confirm(UNASSIGN, "minecraft:shears", "Remove from NPC", "Takes " + id + " off " + named(having) + "; Undo in chat brings it back", having.size(), true,
                () -> toolkit.write(viewer, (ToolkitFiles files) -> files.groups(id, having, false)));
    }

    private GroupFiles files() { return new GroupFiles(toolkit.npcs(), toolkit.documents()); }
}
