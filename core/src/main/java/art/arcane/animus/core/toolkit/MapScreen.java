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
 * A field keyed by names the owner picks, such as named look directions or damage by held item. Number values step;
 * object values open as fields. Add asks for the new key; the schema's name rule is checked when it saves.
 */
final class MapScreen<P, S> extends ValueScreen<P, S> {
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
        pageCheck();
        JsonElement here = current();
        JsonObject map = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        list(keys, "Empty; add one below", (Integer slot, String key) -> entry(slot, key, map.get(key)));
        back();
        int most = schema.has("maxProperties") ? schema.get("maxProperties").getAsInt() : Integer.MAX_VALUE;
        if (map.size() < most) field(ADD, FieldScreen.ON, title("Add an entry"), describe(schema), any("type its name"), path, (GuiModel.Click click) -> {
            if (arm(ADD, path)) return;
            prompt("New name", "", (String typed) -> {
                if (typed.isBlank() || map.has(typed.trim())) return;
                set(append(path, typed.trim()), Optional.of(initial(values).orElse(new JsonPrimitive(0))));
            });
        });
    }

    private void entry(int slot, String key, JsonElement value) {
        List<String> target = append(path, key);
        List<String> lines = new ArrayList<>(describe(values));
        if (kind == Kind.NUMBER) {
            double now = numberOf(value, values), step = stepOf(values);
            lines.add("&7Now &f" + plain(now));
            field(slot, FieldScreen.NUMBER, title(key), lines, hints("+" + plain(step), "-" + plain(step), "left types a value, right removes"), target, (GuiModel.Click click) -> {
                if (arm(slot, target)) return;
                if (click == GuiModel.Click.SHIFT_RIGHT) set(target, Optional.empty());
                else if (click == GuiModel.Click.SHIFT_LEFT) prompt(key, plain(now), (String typed) -> typed(typed, values).ifPresent((JsonPrimitive number) -> set(target, Optional.of(number))));
                else set(target, Optional.of(number(step(click, now, step, minimum(values), maximum(values)), values)));
            });
            return;
        }
        lines.add("&7Now &f" + text(value));
        field(slot, FieldScreen.FIELDS, title(key), lines, hints("open", "remove", "remove"), target, (GuiModel.Click click) -> {
            if (click == GuiModel.Click.LEFT) { viewer.push(new FieldScreen<>(toolkit, viewer, commands, label(label, key), values, scope, target)); return; }
            if (!arm(slot, target)) set(target, Optional.empty());
        });
    }
}
