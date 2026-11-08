package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The NPC's modules, a switch above an Edit button for each. A module with an {@code enabled} field (or {@code killable},
 * which is off until set) turns off by setting it false, which keeps its settings; one without (shop, GUI) is on while its object exists, so turning it off
 * removes the object and asks twice, and turning it on opens its fields.
 */
final class ModuleScreen<P, S> extends ValueScreen<P, S> {
    private final String label;
    private final JsonObject schema;

    ModuleScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope) {
        super(toolkit, viewer, commands, scope, List.of("modules"));
        this.label = label;
        this.schema = schema;
    }

    @Override String title() { return clip(label, 32); }

    /** The slot of the n-th module's switch; its Edit button sits right below. */
    static int toggle(int index) { return WIDTH + (index / WIDTH) * 2 * WIDTH + index % WIDTH; }

    @Override void draw() {
        JsonElement here = current();
        JsonObject modules = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        List<String> keys = new ArrayList<>(schema.getAsJsonObject("properties").keySet());
        for (int index = 0; index < keys.size() && toggle(index) + WIDTH < BACK; index++) {
            String key = keys.get(index);
            JsonObject node = shape(schema.getAsJsonObject("properties").getAsJsonObject(key));
            JsonElement module = modules.get(key);
            draw(toggle(index), key, node, module != null && module.isJsonObject() ? module.getAsJsonObject() : null);
        }
        back();
    }

    private void draw(int slot, String key, JsonObject node, JsonObject module) {
        JsonObject properties = node.has("properties") ? node.getAsJsonObject("properties") : new JsonObject();
        String toggle = properties.has("enabled") ? "enabled" : properties.has(KILLABLE) ? KILLABLE : null;
        boolean switched = toggle != null, literal = switched && !properties.getAsJsonObject(toggle).has("type");
        JsonElement enabled = module == null || toggle == null ? null : module.get(toggle);
        // An untyped enabled (combat) reads only a literal true, and killable is off by default, so leaving either out means off.
        boolean on = module != null && (enabled != null ? enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && enabled.getAsBoolean()
                : !literal && !KILLABLE.equals(toggle));
        List<String> target = append(path, key), lines = new ArrayList<>(describe(node));
        lines.add("&7Now &f" + (on ? "on" : "off"));
        String name = title(key + (on ? ": on" : ": off"));
        if (on && !switched) guarded(slot, FieldScreen.ON, name, "Removes its settings", scope.affected().applyAsInt(target), true, () -> set(target, Optional.empty()));
        else field(slot, on ? FieldScreen.ON : FieldScreen.OFF, name, lines, any(on ? "turn off, keeping its settings" : switched ? "turn on" : "open its fields to turn it on"), target, (GuiModel.Click click) -> {
            if (!switched) { viewer.push(edit(key, node)); return; }
            if (arm(slot, target)) return;
            apply((JsonObject root) -> {
                boolean fresh = at(root, target) == null;
                with(root, append(target, toggle), Optional.of(new JsonPrimitive(!on)));
                JsonObject made = at(root, target).getAsJsonObject();
                if (fresh) for (Map.Entry<String, JsonElement> seeded : seeded(node).entrySet()) if (!made.has(seeded.getKey())) made.add(seeded.getKey(), seeded.getValue());
                return root;
            });
        });
        button(slot + WIDTH, FieldScreen.FIELDS, title("Edit " + key), lore(List.of("&7" + (module == null ? "Not set" : text(module))), any("open its fields")),
                (GuiModel.Click click) -> viewer.push(edit(key, node)));
    }

    /** The switch of {@code animus:health}. */
    private static final String KILLABLE = "killable";

    private FieldScreen<P, S> edit(String key, JsonObject node) { return new FieldScreen<>(toolkit, viewer, commands, label(label, key), node, scope, append(path, key)); }

    /** Every module field the switches and Edit screens reach. */
    static Set<String> covered(JsonObject modules) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, JsonElement> module : modules.getAsJsonObject("properties").entrySet())
            ValueScreen.covered(shape(module.getValue().getAsJsonObject()), "modules." + module.getKey() + ".", out);
        return out;
    }
}
