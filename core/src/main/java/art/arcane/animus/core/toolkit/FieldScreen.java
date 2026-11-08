package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Edits one JSON object against its schema object, one button per property, every description in the lore. Toggles flip
 * on a click, short option lists cycle and long ones open a picker, numbers step (left up, right down, sneak-left types a
 * value), text asks in chat, lists, maps and nested objects open their own screen. Sneak-right on a number, and sneak on
 * anything else, removes the key so the default applies. Items show where the Equipment screen sets them; shapes these
 * screens cannot edit say to use the web editor.
 *
 * <p>Reuse it for any schema-shaped object, for example a trait group's values: pass the schema object, a
 * {@code Supplier<JsonObject>} that reads the current values on the player's owner, and a {@link ValueScreen.Save} that
 * runs on the file lane. Save gets the whole object as it was read and the whole object with one change made, and returns
 * the chat line, ending in {@code "; tx " + id} when it wrote something so the line carries the [Undo] chip. A thrown
 * {@code IOException} becomes a red chat line and nothing is written. Nested screens share the same supplier and save, so
 * every change, however deep, reaches save as the top object.
 */
final class FieldScreen<P, S> extends ValueScreen<P, S> {
    static final String ON = "minecraft:lime_dye", OFF = "minecraft:gray_dye", TEXT = "minecraft:paper", NUMBER = "minecraft:clock",
            CHOICE = "minecraft:book", LIST = "minecraft:bundle", FIELDS = "minecraft:chest";
    /** Option lists longer than this open a picker instead of cycling. */
    static final int CYCLE = 8;
    private final String label;
    private final JsonObject schema;

    FieldScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema,
                Supplier<JsonObject> values, Save save) {
        this(toolkit, viewer, commands, label, schema, new Scope(values, save, (List<String> target) -> 1), List.of());
    }

    FieldScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope, List<String> path) {
        super(toolkit, viewer, commands, scope, path);
        this.label = label;
        this.schema = schema;
    }

    @Override String title() { return clip(label, 32); }

    @Override void draw() {
        pageCheck();
        JsonElement here = current();
        JsonObject values = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        List<String> keys = new ArrayList<>(properties.keySet());
        list(keys, "No fields to edit", (Integer slot, String key) -> draw(slot, key, properties.getAsJsonObject(key), values.get(key)));
        back();
        icon(ADD, "minecraft:paper", "&7" + (path.isEmpty() ? label : String.join(".", path)), List.of("&7Changes save at once", "&7Undo from the chat line"), Optional.empty());
    }

    private void draw(int slot, String key, JsonObject node, JsonElement value) {
        JsonObject shaped = shape(node);
        List<String> target = append(path, key), lines = new ArrayList<>(describe(shaped));
        String name = title(key);
        switch (kind(key, node)) {
            case TOGGLE -> {
                boolean on = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : onByDefault(shaped);
                lines.add("&7Now &f" + (on ? "on" : "off") + (value == null ? "&8 (default)" : ""));
                String flip = "turn " + (on ? "off" : "on");
                field(slot, on ? ON : OFF, name, lines, hints(flip, flip, "reset to default"), target, (GuiModel.Click click) -> {
                    if (arm(slot, target)) return;
                    put(key, sneak(click) ? Optional.empty() : Optional.of(new JsonPrimitive(!on)));
                });
            }
            case CHOICE -> {
                List<JsonElement> options = shaped.getAsJsonArray("enum").asList();
                lines.add("&7Now &f" + text(value));
                boolean picker = options.size() > CYCLE;
                field(slot, CHOICE, name, lines, picker ? hints("pick from " + options.size(), "pick from " + options.size(), "reset to default")
                        : hints("next option", "previous option", "reset to default"), target, (GuiModel.Click click) -> {
                    if (arm(slot, target)) return;
                    if (sneak(click)) put(key, Optional.empty());
                    else if (picker) viewer.push(new ChoiceScreen<>(toolkit, viewer, commands, label(label, key), options, value, (JsonElement chosen) -> put(key, Optional.of(chosen))));
                    else put(key, Optional.of(cycle(click, options, value)));
                });
            }
            case NUMBER -> {
                double now = numberOf(value, shaped), step = stepOf(shaped);
                lines.add("&7Now &f" + plain(now) + (value == null ? "&8 (default)" : ""));
                if (shaped.has("minimum") || shaped.has("maximum")) lines.add("&7From &f" + plain(minimum(shaped)) + "&7 to &f" + plain(maximum(shaped)));
                field(slot, NUMBER, name, lines, hints("+" + plain(step), "-" + plain(step), "left types a value, right resets"), target, (GuiModel.Click click) -> {
                    if (arm(slot, target)) return;
                    if (click == GuiModel.Click.SHIFT_RIGHT) put(key, Optional.empty());
                    else if (click == GuiModel.Click.SHIFT_LEFT) prompt(key, plain(now), (String typed) -> typed(typed, shaped).ifPresent((JsonPrimitive number) -> put(key, Optional.of(number))));
                    else put(key, Optional.of(number(step(click, now, step, minimum(shaped), maximum(shaped)), shaped)));
                });
            }
            case TEXT -> {
                List<JsonElement> examples = shaped.has("examples") ? shaped.getAsJsonArray("examples").asList() : List.of();
                lines.add("&7Now &f" + text(value));
                String initial = value != null && value.isJsonPrimitive() ? value.getAsString() : key.equals("owner") ? viewer.id().toString() : "";
                field(slot, TEXT, name, lines, examples.isEmpty() ? hints("type a value", "type a value", "reset to default")
                        : hints("next suggestion", "type a value", "reset to default"), target, (GuiModel.Click click) -> {
                    if (arm(slot, target)) return;
                    if (sneak(click)) put(key, Optional.empty());
                    else if (!examples.isEmpty() && !right(click)) put(key, Optional.of(cycle(click, examples, value)));
                    else prompt(key, initial, (String typed) -> put(key, typed.isBlank() ? Optional.empty() : Optional.of(new JsonPrimitive(typed.trim()))));
                });
            }
            case LIST, FIELDS, MAP -> {
                Kind kind = kind(key, node);
                lines.add("&7Now &f" + text(value));
                field(slot, kind == Kind.LIST ? LIST : FIELDS, name, lines, hints("open", "open", "reset to default"), target, (GuiModel.Click click) -> {
                    if (sneak(click)) { if (!arm(slot, target)) put(key, Optional.empty()); return; }
                    String title = label(label, key);
                    viewer.push(switch (kind) {
                        case LIST -> new ListScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                        case MAP -> new MapScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                        default -> new FieldScreen<>(toolkit, viewer, commands, title, shaped, scope, target);
                    });
                });
            }
            case ITEM -> {
                if (!item(node)) {
                    lines.add("&7The Equipment screen sets items");
                    icon(slot, "minecraft:item_frame", name, lines, Optional.empty());
                    break;
                }
                Optional<NpcItem> now = value == null ? Optional.empty() : NpcItem.parse(value, key, new ArrayList<>());
                lines.add("&7Now &f" + now.map(ToolScreen::itemName).orElse("not set"));
                field(slot, now.orElse(new NpcItem("minecraft:item_frame", 1, Optional.empty(), Optional.empty())), name, lines, hints("put the item in your hand here", "put the item in your hand here", "clear it"), target, (GuiModel.Click click) -> {
                    if (sneak(click)) { if (!arm(slot, target)) put(key, Optional.empty()); return; }
                    Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
                    if (held.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("Hold the item in your main hand first")); return; }
                    put(key, Optional.of(held.get().toJson()));
                });
            }
            case UNKNOWN -> {
                lines.add("&7Now &f" + text(value));
                lines.add("&7Edit in the web editor");
                icon(slot, "minecraft:structure_void", name, lines, Optional.empty());
            }
        }
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
