package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A searchable picker: the options of a long list, a field's suggestions, or the fields to reset. A click goes back
 * and hands the option to {@code chosen}; Type a value, when offered, goes back and asks for text instead.
 */
final class ChoiceScreen<P, S> extends ToolScreen<P, S> {
    static final int TYPE = ACTIONS;
    static final String ICON = "minecraft:book", NOW = "minecraft:enchanted_book";

    /** One option: the value handed back, its name, its lines, and the raw value or key shown last in dark grey (empty for none). */
    record Option(JsonElement value, String name, List<String> lines, String raw) {}

    private final String label;
    private final List<Option> options;
    private final JsonElement current;
    private final Consumer<JsonElement> chosen;
    private Optional<Runnable> typing = Optional.empty();

    ChoiceScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, List<Option> options, JsonElement current, Consumer<JsonElement> chosen) {
        super(toolkit, viewer, commands);
        this.label = label;
        this.options = List.copyOf(options);
        this.current = current;
        this.chosen = chosen;
        int at = 0;
        while (at < this.options.size() && !this.options.get(at).value().equals(current)) at++;
        page = at < this.options.size() ? at / ENTRIES : 0;
    }

    /** Options for schema values, each named as people read it with the value as written below in dark grey. */
    static List<Option> values(List<JsonElement> values) {
        List<Option> options = new ArrayList<>();
        for (JsonElement value : values) options.add(new Option(value, ValueScreen.option(value), List.of(), ValueScreen.text(value)));
        return options;
    }

    /** Adds Type a value, which goes back and runs {@code type}, for options that are only suggestions. */
    ChoiceScreen<P, S> typing(Runnable type) {
        typing = Optional.of(type);
        return this;
    }

    @Override String title() { return ValueScreen.clip(label, 32); }
    /** It picks a value for a field editor, so it needs what they need. */
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        list(options, (Option option) -> option.name() + " " + option.raw(), "Nothing to pick", (Integer slot, Option option) -> {
            boolean now = option.value().equals(current);
            List<String> lines = new ArrayList<>(option.lines());
            if (now) lines.addFirst("&fNow");
            List<String> lore = lore(lines, any("pick it"));
            if (!option.raw().isEmpty()) lore.add("&8" + option.raw());
            button(slot, now ? NOW : ICON, title(option.name()), lore, (GuiModel.Click click) -> {
                viewer.back();
                chosen.accept(option.value());
                viewer.refresh();
            });
        });
        typing.ifPresent((Runnable type) -> button(TYPE, "minecraft:name_tag", title("Type a value"), lore(List.of("&7For one the list does not have"), any("type it")),
                (GuiModel.Click click) -> {
                    viewer.back();
                    type.run();
                }));
        back();
        help("Click an option to use it; Search finds one by name.", "21-in-game-toolkit", "fields");
    }
}
