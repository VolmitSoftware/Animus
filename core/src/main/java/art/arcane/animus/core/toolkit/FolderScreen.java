package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The folders that hold NPC files. A click moves the selection there; a right-click opens the folder's details, where
 * Rename moves every NPC in it and below it. A folder exists only while it holds an NPC, so New folder moves the
 * selection into a new one. Each move is one transaction.
 */
final class FolderScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS, SELECTION = ACTIONS + 8;
    /** Slots of a folder's details. */
    static final int HEADER = 4, RENAME = 11;

    FolderScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Folders"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    /** Every folder that holds an NPC, and the top level itself, with how many NPCs sit directly in it. */
    static Map<String, Integer> folders(NpcRepository npcs) {
        Map<String, Integer> folders = new TreeMap<>();
        folders.put("", 0);
        for (NpcDefinition definition : npcs.stored()) folders.merge(definition.group(), 1, Integer::sum);
        return folders;
    }

    /** {@code 3 NPCs}, and how many more the folders below it hold. */
    private String count(String folder, int direct) {
        int inside = FolderFiles.members(toolkit.npcs(), folder).size();
        return ToolkitFiles.count(direct) + (inside > direct ? ", " + inside + " with subfolders" : "");
    }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        String who = selected.isEmpty() ? "" : named(selected);
        list(new ArrayList<>(folders(toolkit.npcs()).entrySet()), (Map.Entry<String, Integer> folder) -> FolderFiles.shown(folder.getKey()), "No folders",
                (Integer slot, Map.Entry<String, Integer> folder) -> {
                    String name = folder.getKey();
                    List<String> lines = new ArrayList<>(List.of("&7" + count(name, folder.getValue())));
                    long here = selected.stream().filter((String id) -> toolkit.npcs().find(id).map((NpcDefinition npc) -> npc.group().equals(name)).orElse(false)).count();
                    if (here > 0) lines.add(FieldScreen.now(here == selected.size() ? who + (selected.size() == 1 ? " is here" : " are here") : here + " of " + ToolkitFiles.count(selected.size()) + " here", false));
                    button(slot, "minecraft:chest", title(FolderFiles.shown(name)), lore(lines, hints(selected.isEmpty() ? "move the selection here" : "move " + who + " here", "details", "")),
                            (GuiModel.Click click) -> {
                                if (right(click)) { push(new Detail(name)); return; }
                                chosen().ifPresent((List<String> ids) -> ask(slot, "Moves " + named(ids) + " to " + FolderFiles.shown(name), ids.size(), false,
                                        () -> toolkit.write(viewer, (ToolkitFiles files) -> files.folder(name, ids))));
                            });
                });
        NpcsScreen.summary(this, SELECTION, selected);
        button(CREATE, "minecraft:ender_chest", title("New folder"), lore(List.of("&7Moves the selection into it"), any("type its name")), (GuiModel.Click click) ->
                chosen().ifPresent((List<String> ids) -> ask(CREATE, "Moves " + named(ids) + " to a new folder", ids.size(), false, () -> prompt("Folder name", "", (String typed) -> {
                    String folder = FolderFiles.typed(typed);
                    if (folder.isEmpty()) { refuse("A new folder needs a name"); return; }
                    toolkit.write(viewer, (ToolkitFiles files) -> files.folder(folder, ids));
                }))));
        back();
        help("A folder only sorts NPC files; it changes nothing in game. Click a folder to move the selection into it.", "21-in-game-toolkit", "folders");
    }

    /** One folder: how many NPCs it holds and Rename. Follows a rename, so it keeps showing the same folder. */
    private final class Detail extends ToolScreen<P, S> {
        private volatile String folder;

        Detail(String folder) {
            super(FolderScreen.this.toolkit, FolderScreen.this.viewer, FolderScreen.this.commands);
            this.folder = folder;
        }

        @Override String title() { return ValueScreen.clip("Folder " + FolderFiles.shown(folder), 32); }
        @Override String node() { return PermissionNodes.NPC_EDIT; }
        @Override int rows() { return 3; }

        @Override void draw() {
            back();
            help("Rename moves every NPC in the folder, and in the folders below it, to the new name.", "21-in-game-toolkit", "folders");
            String name = folder;
            List<String> members = FolderFiles.members(toolkit.npcs(), name);
            int direct = folders(toolkit.npcs()).getOrDefault(name, 0);
            if (!name.isEmpty() && members.isEmpty()) {
                icon(HEADER, "minecraft:paper", "&7Folder " + FolderFiles.shown(name) + " is empty", List.of("&7Its NPCs moved away"), Optional.empty());
                return;
            }
            icon(HEADER, "minecraft:chest", title(FolderFiles.shown(name)), List.of(FieldScreen.now(count(name, direct), false), "&7" + names(members, 6)), Optional.empty());
            if (name.isEmpty()) return;
            button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&7Moves its " + ToolkitFiles.count(members.size()) + " to the new name"), any("type a new name")),
                    (GuiModel.Click click) -> ask(RENAME, "Renames " + FolderFiles.shown(name) + " and moves its " + ToolkitFiles.count(members.size()), members.size(), false,
                            () -> prompt("New folder name", name, (String typed) -> {
                                String to = FolderFiles.typed(typed);
                                toolkit.write(viewer, (ToolkitFiles files) -> {
                                    String line = new FolderFiles(toolkit.npcs(), toolkit.documents()).rename(name, to);
                                    folder = to;
                                    return line;
                                });
                            })));
        }
    }
}
