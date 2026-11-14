package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** A searchable list of options; a click goes back to the screen that asked and hands it the option. */
final class PickScreen<P, S, T> extends ToolScreen<P, S> {
    /** How one option shows: its icon, name and the grey line under it. */
    record Look(String icon, String name, String detail) {}

    private final String node, label;
    private final List<T> options;
    private final Function<T, Look> look;
    private final Consumer<T> chosen;

    /** {@code node} is what the pick then does needs, normally the node of the screen that asks. */
    PickScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String node, String label, List<T> options, Function<T, Look> look,
               Consumer<T> chosen) {
        super(toolkit, viewer, commands);
        this.node = node;
        this.label = label;
        this.options = List.copyOf(options);
        this.look = look;
        this.chosen = chosen;
    }

    @Override String title() { return label; }
    @Override String node() { return node; }
    @Override int rows() { return 6; }

    @Override void draw() {
        list(options, (T option) -> look.apply(option).name(), "Nothing to pick", (Integer slot, T option) -> {
            Look shown = look.apply(option);
            button(slot, shown.icon(), title(shown.name()), lore(shown.detail().isEmpty() ? List.of() : List.of("&7" + shown.detail()), any("pick it")), (GuiModel.Click click) -> {
                viewer.back();
                chosen.accept(option);
            });
        });
        back();
        help("Click one to pick it; Back leaves without picking.", "21-in-game-toolkit", "");
    }

    /** The folders under {@code npcs/} that hold NPCs, to pick one. */
    static <P, S> PickScreen<P, S, String> folders(ToolScreen<P, S> from, String label, Consumer<String> chosen) {
        Map<String, Integer> counts = FolderScreen.folders(from.toolkit.npcs());
        return new PickScreen<>(from.toolkit, from.viewer, from.commands, from.node(), label, List.copyOf(counts.keySet()),
                (String folder) -> new Look("minecraft:chest", NpcsScreen.where(folder), ToolkitFiles.count(counts.getOrDefault(folder, 0))), chosen);
    }
}
