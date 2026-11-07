package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.function.Consumer;

/** A picker for an option list too long to cycle through. A click goes back and hands the option to {@code chosen}. */
final class ChoiceScreen<P, S> extends ToolScreen<P, S> {
    private final String label;
    private final List<JsonElement> options;
    private final JsonElement current;
    private final Consumer<JsonElement> chosen;

    ChoiceScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, List<JsonElement> options, JsonElement current, Consumer<JsonElement> chosen) {
        super(toolkit, viewer, commands);
        this.label = label;
        this.options = List.copyOf(options);
        this.current = current;
        this.chosen = chosen;
    }

    @Override String title() { return ValueScreen.clip(label, 32); }
    @Override int rows() { return 6; }

    @Override void draw() {
        list(options, "No options", (Integer slot, JsonElement option) -> {
            boolean now = option.equals(current);
            button(slot, now ? "minecraft:enchanted_book" : FieldScreen.CHOICE, title(ValueScreen.text(option)), lore(now ? List.of("&7Set now") : List.of(), any("pick it")),
                    (GuiModel.Click click) -> {
                        viewer.back();
                        chosen.accept(option);
                    });
        });
        back();
    }
}
