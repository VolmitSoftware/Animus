package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The entries of one list field: tags, behaviors, hologram lines, a pose's [x, y, z]. A click edits an entry (retypes
 * text, steps an option, opens an object) and a right-click opens its details with Move up, Move down and Remove. A list
 * of fixed length, such as [x, y, z], keeps its length: its entries are number rows and nothing is added or removed.
 */
final class ListScreen<P, S> extends ValueScreen<P, S> {
    static final int ADD = ACTIONS, RESET = ACTIONS + 8;
    /** Slots of an entry's details: the entry, Move up, Move down and Remove at the right end of the row. */
    static final int ENTRY = 4, UP = 11, DOWN = 12, REMOVE = 17;
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
        JsonArray entries = entries();
        list(IntStream.range(0, entries.size()).boxed().toList(), (Integer index) -> text(entries.get(index)), "No entries yet; Add an entry adds one",
                (Integer slot, Integer index) -> entry(slot, index, entries.get(index)));
        if (fixed() < 0 && entries.size() < most()) add(entries);
        if (current() != null) confirm(RESET, "minecraft:lava_bucket", "Reset", "Removes the whole list; Undo in chat brings it back", affected(path), true, () -> set(path, Optional.empty()));
        back();
        help("Click an entry to change it, right-click it to move or remove it. Each change saves at once; Undo is in the chat line.", "21-in-game-toolkit", "fields");
    }

    /** {@code #2 vip}: an option as people read it, an object by its first text field. */
    private String entryName(int index, JsonElement value) {
        String shown = value.isJsonObject() ? firstText(items).map((String key) -> value.getAsJsonObject().get(key)).map(ValueScreen::text).orElse(text(value))
                : kind == Kind.CHOICE ? option(value) : text(value);
        return "#" + (index + 1) + " " + shown;
    }

    private void entry(int slot, int index, JsonElement value) {
        String name = entryName(index, value), what = "Changes " + name;
        if (fixed() > 0) { tuple(slot, index, value, name); return; }
        switch (kind) {
            case TEXT -> field(slot, FieldScreen.TEXT, name, List.of(), hints("type a new value", "move or remove", ""), "", (GuiModel.Click click) -> {
                if (right(click)) { push(new EntryScreen(index)); return; }
                change(slot, path, what, () -> prompt("Entry " + (index + 1), value.getAsString(), (String typed) -> {
                    if (typed.isBlank()) refuse("An entry needs text; right-click it to remove it");
                    else replace(index, new JsonPrimitive(typed.strip()));
                }));
            });
            case CHOICE -> {
                List<JsonElement> options = items.getAsJsonArray("enum").asList();
                boolean picker = options.size() > FieldScreen.CYCLE;
                field(slot, FieldScreen.CHOICE, name, List.of(), hints(picker ? "pick from " + options.size() : "next", "move or remove", ""), "", (GuiModel.Click click) -> {
                    if (right(click)) push(new EntryScreen(index));
                    else if (picker) push(new ChoiceScreen<>(toolkit, viewer, commands, label, ChoiceScreen.values(options), value,
                            (JsonElement chosen) -> change(slot, path, what, () -> replace(index, chosen))));
                    else change(slot, path, what, () -> replace(index, cycle(click, options, value)));
                });
            }
            case NUMBER -> field(slot, FieldScreen.NUMBER, name, List.of(), hints("type a value", "move or remove", ""), "", (GuiModel.Click click) -> {
                if (right(click)) { push(new EntryScreen(index)); return; }
                change(slot, path, what, () -> prompt("Entry " + (index + 1), plain(numberOf(value, items)),
                        (String typed) -> typed(typed, items, false).ifPresent((JsonPrimitive number) -> replace(index, number))));
            });
            case FIELDS -> field(slot, FieldScreen.FIELDS, name, List.of(), hints("open", "move or remove", ""), "", (GuiModel.Click click) -> {
                if (right(click)) push(new EntryScreen(index));
                else push(new FieldScreen<>(toolkit, viewer, commands, label(label, "#" + (index + 1)), items, scope, append(path, Integer.toString(index))));
            });
            default -> icon(slot, "minecraft:structure_void", title(name), List.of("&7Edit it in the web editor"), Optional.empty());
        }
    }

    /** An entry of a fixed-length list: a number row, as the list keeps its length. */
    private void tuple(int slot, int index, JsonElement value, String name) {
        double now = numberOf(value, items), step = stepOf(items);
        List<String> lines = new ArrayList<>(describe(schema));
        lines.add(FieldScreen.now(plain(now), false));
        field(slot, FieldScreen.NUMBER, name, lines, hints("+" + plain(step), "-" + plain(step), "type a value"), "", (GuiModel.Click click) -> {
            if (sneak(click)) change(slot, path, "Changes " + name, () -> prompt("Entry " + (index + 1), plain(now), (String typed) -> {
                if (typed.isBlank()) replace(index, initial(items).orElse(new JsonPrimitive(0)));
                else typed(typed, items, false).ifPresent((JsonPrimitive number) -> replace(index, number));
            }));
            else change(slot, path, "Changes " + name, () -> replace(index, number(step(click, now, step, minimum(items), maximum(items)), items)));
        });
    }

    /** Sets the entry at {@code index}. */
    private void replace(int index, JsonElement value) {
        JsonArray entries = entries();
        if (index >= entries.size()) return;
        entries.set(index, value);
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
            case FIELDS -> firstText(items).map((String key) -> "type its " + Words.readable(key).toLowerCase(java.util.Locale.ROOT)).orElse("add one");
            default -> "add one";
        };
        List<String> lines = new ArrayList<>(describe(schema));
        lines.add(FieldScreen.now(entries.size() + " of " + (most() == Integer.MAX_VALUE ? "any number" : most()), false));
        button(ADD, FieldScreen.ON, title("Add an entry"), lore(lines, any(what)), (GuiModel.Click click) -> change(ADD, path, "Adds an entry", () -> {
            switch (kind) {
                case TEXT -> prompt("New entry", "", (String typed) -> { if (!typed.isBlank()) append(new JsonPrimitive(typed.strip())); });
                case CHOICE -> push(new ChoiceScreen<>(toolkit, viewer, commands, label, ChoiceScreen.values(items.getAsJsonArray("enum").asList()), null, this::append));
                case FIELDS -> {
                    JsonObject seeded = seeded(items);
                    Optional<String> asked = firstText(items);
                    if (asked.isEmpty()) { append(seeded); return; }
                    prompt(asking(Words.readable(asked.get())), "", (String typed) -> {
                        if (typed.isBlank()) return;
                        seeded.addProperty(asked.get(), typed.strip());
                        append(seeded);
                    });
                }
                default -> append(initial(items).orElse(new JsonPrimitive(0)));
            }
        }));
    }

    private void append(JsonElement value) {
        JsonArray entries = entries();
        entries.add(value);
        save(entries);
    }

    /** One entry's details: Move up, Move down and Remove, named, so nothing hides on a right-click. Follows the entry as it moves. */
    private final class EntryScreen extends ToolScreen<P, S> {
        private volatile int index;

        EntryScreen(int index) {
            super(ListScreen.this.toolkit, ListScreen.this.viewer, ListScreen.this.commands);
            this.index = index;
        }

        @Override String title() { return clip(label + " #" + (index + 1), 32); }
        @Override String node() { return ListScreen.this.node(); }
        @Override int rows() { return 3; }

        @Override void draw() {
            back();
            help("Move up and Move down change its place in the list. Remove takes it out; Undo in chat brings it back.", "21-in-game-toolkit", "fields");
            int at = index;
            JsonArray entries = entries();
            if (at >= entries.size()) { icon(ENTRY, "minecraft:paper", "&7This entry is gone", List.of("&7Go back to see the list"), Optional.empty()); return; }
            JsonElement value = entries.get(at);
            icon(ENTRY, FieldScreen.TEXT, title(entryName(at, value)), List.of("&7Entry " + (at + 1) + " of " + entries.size()), Optional.empty());
            if (at > 0) button(UP, "minecraft:arrow", title("Move up"), any("move it up"), (GuiModel.Click click) -> move(UP, at, -1));
            if (at < entries.size() - 1) button(DOWN, "minecraft:arrow", title("Move down"), any("move it down"), (GuiModel.Click click) -> move(DOWN, at, 1));
            confirm(REMOVE, "minecraft:lava_bucket", "Remove", "Removes " + entryName(at, value) + "; Undo in chat brings it back", affected(path), true, () -> {
                JsonArray next = entries();
                if (at < next.size()) next.remove(at);
                save(next);
                viewer.back();
            });
        }

        private void move(int slot, int at, int by) {
            ask(slot, "Moves " + entryName(at, entries().get(at)), affected(path), false, () -> {
                JsonArray next = entries();
                if (at + by < 0 || at + by >= next.size()) return;
                JsonElement moved = next.get(at);
                next.set(at, next.get(at + by));
                next.set(at + by, moved);
                apply((JsonObject root) -> with(root, path, Optional.of(next)), () -> index = at + by);
            });
        }
    }
}
