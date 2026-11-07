package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The entries of one list field: tags, behaviors, hologram lines, drops, a pose's [x, y, z]. Text entries are retyped on
 * a left click, objects open as fields, numbers step; right removes an entry and sneak moves it up (left) or down
 * (right). A list of fixed length, such as [x, y, z], keeps its length: its entries only step and nothing is added.
 */
final class ListScreen<P, S> extends ValueScreen<P, S> {
    private final String label;
    private final JsonObject schema, items;
    private final Kind kind;

    ListScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope, List<String> path) {
        super(toolkit, viewer, commands, scope, path);
        this.label = label;
        this.schema = schema;
        this.items = shape(schema.getAsJsonObject("items"));
        this.kind = kind("", schema.getAsJsonObject("items"));
    }

    @Override String title() { return clip(label, 32); }

    private int fixed() {
        boolean tuple = schema.has("minItems") && schema.has("maxItems") && schema.get("minItems").getAsInt() == schema.get("maxItems").getAsInt();
        return tuple ? schema.get("maxItems").getAsInt() : -1;
    }

    private int most() { return schema.has("maxItems") ? schema.get("maxItems").getAsInt() : Integer.MAX_VALUE; }

    /** The list as stored; a fixed-length list that is not set reads as its starting values. */
    private JsonArray entries() {
        JsonElement here = current();
        JsonArray entries = here != null && here.isJsonArray() ? here.getAsJsonArray().deepCopy() : new JsonArray();
        while (fixed() > 0 && entries.size() < fixed()) entries.add(initial(items).orElse(new JsonPrimitive(0)));
        return entries;
    }

    @Override void draw() {
        pageCheck();
        JsonArray entries = entries();
        List<String> lines = describe(schema);
        list(IntStream.range(0, entries.size()).boxed().toList(), "Empty; add one below", (Integer slot, Integer index) -> entry(slot, index, entries.get(index), lines));
        back();
        if (fixed() < 0 && entries.size() < most()) add(entries);
    }

    private void entry(int slot, int index, JsonElement value, List<String> about) {
        List<String> lines = new ArrayList<>(about);
        String name = title("#" + (index + 1) + " " + text(value));
        String move = "left moves up, right moves down";
        switch (kind) {
            case TEXT -> field(slot, FieldScreen.TEXT, name, lines, hints("type a new value", "remove", move), path, (GuiModel.Click click) -> {
                if (arm(slot, path)) return;
                if (click == GuiModel.Click.LEFT) prompt("Entry " + (index + 1), value.getAsString(), (String typed) -> replace(index, typed.isBlank() ? Optional.empty() : Optional.of(new JsonPrimitive(typed.trim()))));
                else edit(click, index);
            });
            case CHOICE -> field(slot, FieldScreen.CHOICE, name, lines, hints("next option", "remove", move), path, (GuiModel.Click click) -> {
                if (arm(slot, path)) return;
                if (click == GuiModel.Click.LEFT) replace(index, Optional.of(cycle(click, items.getAsJsonArray("enum").asList(), value)));
                else edit(click, index);
            });
            case NUMBER -> {
                double now = numberOf(value, items), step = stepOf(items);
                if (items.has("minimum") || items.has("maximum")) lines.add("&7From &f" + plain(minimum(items)) + "&7 to &f" + plain(maximum(items)));
                boolean tuple = fixed() > 0;
                field(slot, FieldScreen.NUMBER, name, lines, hints("+" + plain(step), "-" + plain(step), "left types a value, right " + (tuple ? "resets the list" : "removes")), path, (GuiModel.Click click) -> {
                    if (arm(slot, path)) return;
                    if (click == GuiModel.Click.SHIFT_RIGHT) { if (tuple) set(path, Optional.empty()); else replace(index, Optional.empty()); }
                    else if (click == GuiModel.Click.SHIFT_LEFT) prompt("Entry " + (index + 1), plain(now), (String typed) -> typed(typed, items).ifPresent((JsonPrimitive number) -> replace(index, Optional.of(number))));
                    else replace(index, Optional.of(number(step(click, now, step, minimum(items), maximum(items)), items)));
                });
            }
            case FIELDS -> field(slot, FieldScreen.FIELDS, name, lines, hints("open", "remove", move), path, (GuiModel.Click click) -> {
                if (click == GuiModel.Click.LEFT) { viewer.push(new FieldScreen<>(toolkit, viewer, commands, label(label, "#" + (index + 1)), items, scope, append(path, Integer.toString(index)))); return; }
                if (arm(slot, path)) return;
                edit(click, index);
            });
            default -> icon(slot, "minecraft:structure_void", name, List.of("&7Edit in the web editor"), Optional.empty());
        }
    }

    /** Right removes the entry; sneak-left moves it up, sneak-right down. */
    private void edit(GuiModel.Click click, int index) {
        if (click == GuiModel.Click.RIGHT) { replace(index, Optional.empty()); return; }
        int other = click == GuiModel.Click.SHIFT_LEFT ? index - 1 : index + 1;
        JsonArray entries = entries();
        if (other < 0 || other >= entries.size()) return;
        JsonElement moved = entries.get(index);
        entries.set(index, entries.get(other));
        entries.set(other, moved);
        save(entries);
    }

    /** Sets or removes the entry at {@code index}. */
    private void replace(int index, Optional<JsonElement> value) {
        JsonArray entries = entries();
        if (index >= entries.size()) return;
        if (value.isPresent()) entries.set(index, value.get()); else entries.remove(index);
        save(entries);
    }

    /** Writes the list; an empty list that must have entries is removed instead. */
    private void save(JsonArray entries) {
        boolean needed = schema.has("minItems") && schema.get("minItems").getAsInt() > 0;
        set(path, entries.isEmpty() && needed ? Optional.empty() : Optional.of(entries));
    }

    private void add(JsonArray entries) {
        String what = switch (kind) {
            case TEXT -> "type the new entry";
            case CHOICE -> "pick the new entry";
            case FIELDS -> firstText(items).map((String key) -> "type its " + key).orElse("add one");
            default -> "add one";
        };
        field(ADD, FieldScreen.ON, title("Add an entry"), List.of("&7" + entries.size() + " of " + (most() == Integer.MAX_VALUE ? "any number" : most())), any(what), path, (GuiModel.Click click) -> {
            if (arm(ADD, path)) return;
            switch (kind) {
                case TEXT -> prompt("New entry", "", (String typed) -> { if (!typed.isBlank()) append(new JsonPrimitive(typed.trim())); });
                case CHOICE -> viewer.push(new ChoiceScreen<>(toolkit, viewer, commands, label, items.getAsJsonArray("enum").asList(), null, this::append));
                case FIELDS -> {
                    JsonObject seeded = seeded(items);
                    Optional<String> asked = firstText(items);
                    if (asked.isEmpty()) { append(seeded); return; }
                    prompt(asked.get(), "", (String typed) -> {
                        if (typed.isBlank()) return;
                        seeded.addProperty(asked.get(), typed.trim());
                        append(seeded);
                    });
                }
                default -> append(initial(items).orElse(new JsonPrimitive(0)));
            }
        });
    }

    private void append(JsonElement value) {
        JsonArray entries = entries();
        entries.add(value);
        save(entries);
    }
}
