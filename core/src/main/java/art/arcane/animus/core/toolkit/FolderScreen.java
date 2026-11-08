package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The folders under {@code npcs/} that hold NPCs. A click moves the selection there and a sneak click renames the
 * folder, each in one transaction. A folder exists only while it holds an NPC, so New folder moves the selection into it.
 */
final class FolderScreen<P, S> extends ToolScreen<P, S> {
    static final int SELECTION = 46, CREATE = 53;
    private final BulkGuard guard = new BulkGuard();

    FolderScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Folders"; }
    @Override int rows() { return 6; }

    /** Every folder that holds an NPC, and {@code npcs/} itself, with how many NPCs sit directly in it. */
    static Map<String, Integer> folders(NpcRepository npcs) {
        Map<String, Integer> folders = new TreeMap<>();
        folders.put("", 0);
        for (NpcDefinition definition : npcs.stored()) folders.merge(definition.group(), 1, Integer::sum);
        return folders;
    }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        list(new ArrayList<>(folders(toolkit.npcs()).entrySet()), "No folders", (Integer slot, Map.Entry<String, Integer> folder) -> {
            String name = folder.getKey(), move = "move:" + name, rename = "rename:" + name;
            int inside = FolderFiles.members(toolkit.npcs(), name).size();
            List<String> lines = new ArrayList<>(List.of("&7" + ToolkitFiles.count(folder.getValue()) + (inside > folder.getValue() ? ", " + inside + " with subfolders" : "")));
            if (guard.armed(move)) lines.addAll(List.of("&c" + ToolkitFiles.count(selected.size()) + " affected", "&cClick again to move them"));
            if (guard.armed(rename)) lines.addAll(List.of("&c" + ToolkitFiles.count(inside) + " affected", "&cSneak again to rename"));
            boolean armed = guard.armed(move) || guard.armed(rename);
            button(slot, "minecraft:chest", title((armed ? "&c" : "") + SelectionScreen.where(name)),
                    lore(lines, hints("move the selection here", "move the selection here", name.isEmpty() ? "nothing; npcs/ keeps its name" : "rename it")), (GuiModel.Click click) -> {
                        if (!sneak(click)) {
                            chosen().ifPresent((List<String> ids) -> {
                                if (guard.ready(move, ids.size())) toolkit.write(viewer, (ToolkitFiles files) -> files.folder(name, ids));
                            });
                            return;
                        }
                        if (name.isEmpty()) { guard.clear(); toolkit.say(viewer.player(), ChatLine.error("npcs/ itself cannot be renamed")); return; }
                        if (guard.ready(rename, inside)) prompt("Rename folder", name,
                                (String to) -> toolkit.write(viewer, (ToolkitFiles files) -> new FolderFiles(toolkit.npcs(), toolkit.documents()).rename(name, to.trim())));
                    });
        });
        back();
        SelectionScreen.summary(this, SELECTION, selected);
        guarded(CREATE, "minecraft:ender_chest", "New folder", "Moves the selection into it", selected.size(), false, () -> {
            guard.clear();
            if (chosen().isEmpty()) return;
            prompt("Folder in npcs/", "", (String typed) -> {
                String folder = typed.trim();
                if (folder.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("A new folder needs a name")); return; }
                List<String> ids = toolkit.selected(viewer.id());
                toolkit.write(viewer, (ToolkitFiles files) -> files.folder(folder, ids));
            });
        });
    }
}
