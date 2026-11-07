package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Picks one folder under {@code npcs/} and goes back to the screen that asked. */
final class FolderPickScreen<P, S> extends ToolScreen<P, S> {
    private final String label;
    private final Consumer<String> chosen;

    FolderPickScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, Consumer<String> chosen) {
        super(toolkit, viewer, commands);
        this.label = label;
        this.chosen = chosen;
    }

    @Override String title() { return label; }
    @Override int rows() { return 6; }

    @Override void draw() {
        list(new ArrayList<>(FolderScreen.folders(toolkit.npcs()).entrySet()), "No folders", (Integer slot, Map.Entry<String, Integer> folder) ->
                button(slot, "minecraft:chest", title(SelectionScreen.where(folder.getKey())), lore(List.of("&7" + ToolkitFiles.count(folder.getValue())), any("pick it")),
                        (GuiModel.Click click) -> {
                            viewer.back();
                            chosen.accept(folder.getKey());
                        }));
        back();
    }
}
