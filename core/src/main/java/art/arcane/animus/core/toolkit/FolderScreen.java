package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The folders under {@code npcs/} that hold NPCs. A click moves the selection there in one transaction. */
final class FolderScreen<P, S> extends ToolScreen<P, S> {
    FolderScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Folders"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Map<String, Integer> folders = new TreeMap<>();
        folders.put("", 0);
        for (NpcDefinition definition : toolkit.npcs().stored()) folders.merge(definition.group(), 1, Integer::sum);
        list(new ArrayList<>(folders.entrySet()), "No folders", (Integer slot, Map.Entry<String, Integer> folder) ->
                button(slot, "minecraft:chest", title("npcs/" + (folder.getKey().isEmpty() ? "" : folder.getKey() + "/")),
                        lore(List.of("&7" + ToolkitFiles.count(folder.getValue())), any("move the selection here")), (GuiModel.Click click) -> {
                            chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles files) -> files.folder(folder.getKey(), ids)));
                        }));
        back();
        icon(46, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"),
                List.of("&7" + names(selected, 6), "&7New folders are made in the editor"), Optional.empty());
    }
}
