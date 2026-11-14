package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A field keyed by names the owner picks, such as named look directions or damage by held item. Number values are
 * number rows; object values open as fields. Add an entry asks for the new name (the schema's name rule is checked when
 * it saves), Remove an entry picks one to take out, and Reset removes them all.
 */
final class MapScreen<P, S> extends ValueScreen<P, S> {
    static final int ADD = ACTIONS, REMOVE = ACTIONS + 1, RESET = ACTIONS + 8;
    private final String label;
    private final JsonObject schema, values;
    private final Kind kind;

    MapScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope, List<String> path) {
        super(toolkit, viewer, commands, scope, path);
        this.label = label;
        this.schema = schema;
        this.values = shape(schema.getAsJsonObject("additionalProperties"));
        this.kind = kind("", schema.getAsJsonObject("additionalProperties"));
    }

    @Override String title() { return clip(label, 32); }

    @Override void draw() {
        JsonElement here = current();
        JsonObject map = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        list(keys, ToolScreen::itself, "No entries yet; Add an entry adds one", (Integer slot, String key) -> entry(slot, key, map.get(key)));
        int most = schema.has("maxProperties") ? schema.get("maxProperties").getAsInt() : Integer.MAX_VALUE;
        if (map.size() < most) {
            List<String> lines = new ArrayList<>(describe(schema));
            lines.add(FieldScreen.now(map.size() + (map.size() == 1 ? " entry" : " entries"), false));
            button(ADD, FieldScreen.ON, title("Add an entry"), lore(lines, any("type its name")), (GuiModel.Click click) -> change(ADD, path, "Adds an entry", () -> prompt("New name", "", (String typed) -> {
                String name = typed.strip();
                if (name.isEmpty()) return;
                if (map.has(name)) { refuse(name + " is already there"); return; }
                set(append(path, name), Optional.of(initial(values).orElse(new JsonPrimitive(0))));
            })));
        }
        if (!keys.isEmpty())
            button(REMOVE, "minecraft:shears", title("Remove an entry"), any("pick the entry"), (GuiModel.Click click) -> {
                List<ChoiceScreen.Option> options = new ArrayList<>();
                for (String key : keys) options.add(new ChoiceScreen.Option(new JsonPrimitive(key), key, List.of(FieldScreen.now(text(map.get(key)), false)), ""));
                push(new ChoiceScreen<>(toolkit, viewer, commands, "Remove an entry", options, null, (JsonElement chosen) ->
                        ask(REMOVE, "Removes " + chosen.getAsString() + "; Undo in chat brings it back", affected(path), true, () -> set(append(path, chosen.getAsString()), Optional.empty()))));
            });
        if (here != null) confirm(RESET, "minecraft:lava_bucket", "Reset", "Removes every entry; Undo in chat brings them back", affected(path), true, () -> set(path, Optional.empty()));
        back();
        help("Each entry has a name you choose. Add an entry asks for the name; Remove an entry and Reset take entries out.", "21-in-game-toolkit", "fields");
    }

    private void entry(int slot, String key, JsonElement value) {
        List<String> target = append(path, key);
        if (kind == Kind.NUMBER) {
            double now = numberOf(value, values), step = stepOf(values);
            List<String> lines = new ArrayList<>(describe(values));
            lines.add(FieldScreen.now(plain(now), false));
            field(slot, FieldScreen.NUMBER, key, lines, hints("+" + plain(step), "-" + plain(step), "type a value"), "", (GuiModel.Click click) -> {
                if (sneak(click)) change(slot, target, "Changes " + key, () -> prompt(asking(key), plain(now),
                        (String typed) -> { if (!typed.isBlank()) typed(typed, values, false).ifPresent((JsonPrimitive number) -> set(target, Optional.of(number))); }));
                else change(slot, target, "Changes " + key, () -> set(target, Optional.of(number(step(click, now, step, minimum(values), maximum(values)), values))));
            });
            return;
        }
        field(slot, FieldScreen.FIELDS, key, List.of(FieldScreen.now(text(value), false)), any("open"), "",
                (GuiModel.Click click) -> push(new FieldScreen<>(toolkit, viewer, commands, label(label, key), values, scope, target)));
    }
}
