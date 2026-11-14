package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Edits one JSON object against its schema object, one row per property with a humanised name. The one click rule:
 * toggles flip on a click; short option lists step forward on a click and back on a right-click, long ones open a
 * searchable picker; numbers add a step on a click, take one off on a right-click and ask for a value on a sneak-click;
 * text asks for a value; lists, maps and nested objects open. A blank typed value resets a field to its default, Reset a
 * field resets any other, and Reset in the action row removes the whole object. Tick fields read and take seconds.
 *
 * <p>Reuse it for any schema-shaped object, for example a trait group's values: pass the schema object, a
 * {@code Supplier<JsonObject>} that reads the current values on the player's owner, and a {@link ValueScreen.Save} that
 * runs on the file lane. Save gets the whole object as it was read and the whole object with one change made, and returns
 * the chat line, ending in {@code "; tx " + id} when it wrote something so the line carries the [Undo] chip. A thrown
 * {@code IOException} becomes a refusal and nothing is written. Nested screens share the same supplier and save, so
 * every change, however deep, reaches save as the top object. A module's screen adds its On/Off switch and Remove settings.
 */
final class FieldScreen<P, S> extends ValueScreen<P, S> {
    static final String ON = "minecraft:lime_dye", OFF = "minecraft:gray_dye", TEXT = "minecraft:paper", NUMBER = "minecraft:clock",
            CHOICE = "minecraft:book", LIST = "minecraft:bundle", FIELDS = "minecraft:chest", FRAME = "minecraft:item_frame";
    /** Option lists longer than this open a picker instead of cycling. */
    static final int CYCLE = 8;
    static final int SWITCH = ACTIONS, RESET_FIELD = ACTIONS + 7, RESET = ACTIONS + 8;
    private final String label;
    private final JsonObject schema;
    private final Optional<ModuleScreen.Module> module;

    FieldScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema,
                Supplier<JsonObject> values, Save save) {
        this(toolkit, viewer, commands, label, schema, new Scope(values, save, (List<String> target) -> 1), List.of());
    }

    FieldScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope, List<String> path) {
        this(toolkit, viewer, commands, label, schema, scope, path, Optional.empty());
    }

    FieldScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope, List<String> path,
                Optional<ModuleScreen.Module> module) {
        super(toolkit, viewer, commands, scope, path);
        this.label = label;
        this.schema = schema;
        this.module = module;
    }

    @Override String title() {
        return clip(module.map((ModuleScreen.Module shown) -> shown.name() + ": " + (shown.on(current()) ? "on" : "off")).orElse(label), 32);
    }

    /** Root NPC fields use the words players see in the menus, such as Body for {@code type}. */
    private boolean root() { return path.isEmpty(); }

    @Override void draw() {
        JsonElement here = current();
        JsonObject values = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        List<String> keys = new ArrayList<>(properties.keySet());
        module.flatMap(ModuleScreen.Module::toggle).ifPresent(keys::remove);
        list(keys, (String key) -> key + " " + name(key, root()), "No fields to edit", (Integer slot, String key) -> draw(slot, key, properties.getAsJsonObject(key), values.get(key)));
        module.ifPresent((ModuleScreen.Module shown) -> toggle(shown, here));
        Set<String> required = required();
        List<String> set = keys.stream().filter((String key) -> values.has(key) && !required.contains(key)).toList();
        if (!set.isEmpty())
            button(RESET_FIELD, "minecraft:brush", title("Reset a field"), lore(List.of("&7Back to its default"), any("pick the field")), (GuiModel.Click click) -> pickReset(set, values));
        if (!root() && here != null) {
            String what = module.map((ModuleScreen.Module shown) -> "Removes every " + shown.name() + " setting").orElse("Removes all of these settings");
            confirm(RESET, "minecraft:lava_bucket", module.isPresent() ? "Remove settings" : "Reset", what + "; Undo in chat brings them back",
                    affected(path), true, () -> set(path, Optional.empty()));
        }
        back();
        if (module.isPresent()) help("The switch turns the module on or off and keeps its settings; each field saves at once. Remove settings takes them all off.", "21-in-game-toolkit", "modules");
        else help("Click a field to change it; it saves at once and the chat line has Undo. Sneak-click a number to type it, and type nothing to reset a field.", "21-in-game-toolkit", "fields");
    }

    /** The fields an object must keep: its schema's required list, and the NPC's own required fields at the root. */
    private Set<String> required() {
        Set<String> required = new HashSet<>();
        if (schema.has("required")) for (JsonElement key : schema.getAsJsonArray("required")) required.add(key.getAsString());
        if (root()) for (JsonElement key : PropertyScreen.SCHEMA.getAsJsonArray("required")) required.add(key.getAsString());
        return required;
    }

    private void draw(int slot, String key, JsonObject node, JsonElement value) {
        JsonObject shaped = shape(node);
        Kind kind = kind(key, node);
        boolean ticks = kind == Kind.NUMBER && ticks(key, shaped);
        List<String> target = append(path, key), lines = new ArrayList<>(describe(shaped, ticks, advanced() ? LINES : 1));
        String name = name(key, root());
        switch (kind) {
            case TOGGLE -> {
                boolean on = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : onByDefault(shaped);
                lines.add(now(on ? "on" : "off", value == null));
                field(slot, on ? ON : OFF, name, lines, any("turn " + (on ? "off" : "on")), key,
                        (GuiModel.Click click) -> change(slot, target, "Turns " + name + (on ? " off" : " on"), () -> put(key, Optional.of(new JsonPrimitive(!on)))));
            }
            case CHOICE -> {
                List<JsonElement> options = shaped.getAsJsonArray("enum").asList();
                JsonElement shown = value != null ? value : stated(shaped).map((String text) -> (JsonElement) new JsonPrimitive(text)).filter(options::contains).orElse(null);
                lines.add(now(option(shown), value == null && shown != null));
                boolean picker = options.size() > CYCLE;
                field(slot, CHOICE, name, lines, picker ? any("pick from " + options.size()) : hints("next", "previous", ""), key, (GuiModel.Click click) -> {
                    if (picker) push(new ChoiceScreen<>(toolkit, viewer, commands, label(label, name), ChoiceScreen.values(options), value,
                            (JsonElement chosen) -> change(slot, target, "Sets " + name, () -> put(key, Optional.of(chosen)))));
                    else change(slot, target, "Sets " + name, () -> put(key, Optional.of(cycle(click, options, shown))));
                });
            }
            case NUMBER -> number(slot, key, name, shaped, value, ticks, lines);
            case TEXT -> {
                List<JsonElement> examples = shaped.has("examples") ? shaped.getAsJsonArray("examples").asList() : List.of();
                lines.add(now(examples.isEmpty() ? text(value) : option(value), false));
                String initial = value != null && value.isJsonPrimitive() ? value.getAsString() : key.equals("owner") ? viewer.id().toString() : "";
                Runnable type = () -> change(slot, target, "Sets " + name, () -> prompt(asking(name), initial,
                        (String typed) -> put(key, typed.isBlank() ? Optional.empty() : Optional.of(new JsonPrimitive(typed.strip())))));
                field(slot, TEXT, name, lines, any(examples.isEmpty() ? "type a value" : "pick or type one"), key, (GuiModel.Click click) -> {
                    if (examples.isEmpty()) type.run();
                    else push(new ChoiceScreen<>(toolkit, viewer, commands, label(label, name), ChoiceScreen.values(examples), value,
                            (JsonElement chosen) -> change(slot, target, "Sets " + name, () -> put(key, Optional.of(chosen)))).typing(type));
                });
            }
            case LIST, FIELDS, MAP -> {
                lines.add(now(text(value), false));
                field(slot, kind == Kind.LIST ? LIST : FIELDS, name, lines, any("open"), key, (GuiModel.Click click) -> {
                    String title = label(label, name);
                    push(switch (kind) {
                        case LIST -> new ListScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                        case MAP -> new MapScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                        default -> new FieldScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                    });
                });
            }
            case ITEM -> {
                if (!item(node)) {
                    lines.add("&7The Equipment screen sets items");
                    info(slot, FRAME, name, lines, key);
                    break;
                }
                Optional<NpcItem> now = value == null ? Optional.empty() : NpcItem.parse(value, key, new ArrayList<>());
                lines.add(now(now.map(ToolScreen::itemName).orElse("not set"), false));
                field(slot, now.orElse(new NpcItem(FRAME, 1, Optional.empty(), Optional.empty())), name, lines, hints("put the held item here", now.isPresent() ? "clear it" : "", ""), key,
                        (GuiModel.Click click) -> {
                            if (right(click)) {
                                if (value != null) change(slot, target, "Clears " + name, () -> put(key, Optional.empty()));
                                return;
                            }
                            Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
                            if (held.isEmpty()) { refuse("Hold the item in your main hand first"); return; }
                            change(slot, target, "Sets " + name, () -> put(key, Optional.of(held.get().toJson())));
                        });
            }
            case UNKNOWN -> {
                lines.add(now(text(value), false));
                lines.add("&7Edit it in the web editor");
                info(slot, "minecraft:structure_void", name, lines, key);
            }
        }
    }

    /** A number row: a click adds a step, a right-click takes one off, a sneak-click asks for a value (blank resets). */
    private void number(int slot, String key, String name, JsonObject shaped, JsonElement value, boolean ticks, List<String> lines) {
        List<String> target = append(path, key);
        double now = numberOf(value, shaped), step = ticks ? tickStep(shaped) : stepOf(shaped);
        boolean bounded = shaped.has("minimum") || shaped.has("maximum");
        String range = !bounded ? "" : ticks ? "&7, from " + plain(minimum(shaped) / TICKS) + " to " + seconds(maximum(shaped)) : "&7, from " + plain(minimum(shaped)) + " to " + plain(maximum(shaped));
        lines.add(now(ticks ? seconds(now) : plain(now), value == null) + range);
        String stepped = ticks ? seconds(step) : plain(step);
        field(slot, NUMBER, name, lines, hints("+" + stepped, "-" + stepped, "type a value"), key, (GuiModel.Click click) -> {
            String asked = ticks ? (name + " (seconds)").length() <= 20 ? name + " (seconds)" : "Seconds" : asking(name);
            if (sneak(click)) change(slot, target, "Sets " + name, () -> prompt(asked, ticks ? plain(now / TICKS) : plain(now), (String typed) -> {
                if (typed.isBlank()) put(key, Optional.empty());
                else typed(typed, shaped, ticks).ifPresent((JsonPrimitive number) -> put(key, Optional.of(number)));
            }));
            else change(slot, target, "Sets " + name, () -> put(key, Optional.of(number(step(click, now, step, minimum(shaped), maximum(shaped)), shaped))));
        });
    }

    /** Half a second a click for a tick field up to a minute long, five seconds for longer ones. */
    static double tickStep(JsonObject node) { return maximum(node) <= 60 * TICKS ? TICKS / 2 : 5 * TICKS; }

    /** {@code Now: x}, with a dark grey {@code (default)} when the value is not set. */
    static String now(String text, boolean byDefault) { return "&fNow: " + text + (byDefault ? " &8(default)" : ""); }

    /** A row that cannot be clicked, with the raw key in Advanced. */
    private void info(int slot, String icon, String name, List<String> lines, String key) {
        List<String> lore = new ArrayList<>(lines);
        if (advanced()) lore.add("&8" + key);
        icon(slot, icon, title(name), lore, Optional.empty());
    }

    /** The On/Off switch of a module screen; a module without a switch is on while it has settings. */
    private void toggle(ModuleScreen.Module shown, JsonElement here) {
        boolean on = shown.on(here);
        if (shown.toggle().isEmpty()) {
            icon(SWITCH, on ? ON : OFF, title(on ? "On" : "Off"), List.of(on ? "&7On while it has settings" : "&7Set a field to turn it on"), Optional.empty());
            return;
        }
        String toggle = shown.toggle().get();
        button(SWITCH, on ? ON : OFF, title(on ? "On" : "Off"), lore(List.of(on ? "&7Off keeps its settings" : "&7Turns " + shown.name() + " on"), any(on ? "turn off" : "turn on")),
                (GuiModel.Click click) -> change(SWITCH, path, "Turns " + shown.name() + (on ? " off" : " on"), () -> apply((JsonObject root) -> {
                    boolean fresh = at(root, path) == null;
                    with(root, append(path, toggle), Optional.of(new JsonPrimitive(!on)));
                    JsonObject made = at(root, path).getAsJsonObject();
                    if (fresh) for (Map.Entry<String, JsonElement> seeded : seeded(schema).entrySet()) if (!made.has(seeded.getKey())) made.add(seeded.getKey(), seeded.getValue());
                    return root;
                })));
    }

    /** Reset a field: a picker of the fields set now; the chosen one goes back to its default. */
    private void pickReset(List<String> set, JsonObject values) {
        List<ChoiceScreen.Option> options = new ArrayList<>();
        for (String key : set) options.add(new ChoiceScreen.Option(new JsonPrimitive(key), name(key, root()), List.of(now(text(values.get(key)), false)), key));
        push(new ChoiceScreen<>(toolkit, viewer, commands, "Reset a field", options, null, (JsonElement chosen) -> {
            String key = chosen.getAsString();
            change(RESET_FIELD, append(path, key), "Resets " + name(key, root()), () -> put(key, Optional.empty()));
        }));
    }

    /** Sets or removes one property. A first value in an object that was not there yet brings the object's required fields with it. */
    private void put(String key, Optional<JsonElement> value) {
        apply((JsonObject root) -> {
            boolean fresh = at(root, path) == null;
            with(root, append(path, key), value);
            JsonElement parent = at(root, path);
            if (fresh && value.isPresent() && parent != null && parent.isJsonObject())
                for (Map.Entry<String, JsonElement> seeded : seeded(schema).entrySet())
                    if (!parent.getAsJsonObject().has(seeded.getKey())) parent.getAsJsonObject().add(seeded.getKey(), seeded.getValue());
            return root;
        });
    }
}
