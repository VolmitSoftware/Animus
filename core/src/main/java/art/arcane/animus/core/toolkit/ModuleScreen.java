package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The NPC's modules, one row each: its name, whether it is on, and a click that opens its fields with the On/Off
 * switch. A module with an {@code enabled} field (or {@code killable}, which is off until set) turns off by setting it
 * false, which keeps its settings; one without (shop, GUI) is on while its settings exist, so Remove settings turns it off.
 */
final class ModuleScreen<P, S> extends ValueScreen<P, S> {
    /** The switch of {@code animus:health}. */
    private static final String KILLABLE = "killable";
    /** Module names where the key alone reads wrong; the rest are humanised. */
    private static final Map<String, String> NAMES = Map.of("animus:navigation", "Movement", "animus:look-close", "Look at players", "animus:gui", "GUI",
            "animus:modelengine", "ModelEngine", "mythic:mob", "MythicMobs", "animus:mirror", "Mirror viewer");
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("animus:navigation", "leather_boots"), Map.entry("animus:combat", "iron_sword"), Map.entry("animus:health", "golden_apple"),
            Map.entry("animus:shop", "emerald"), Map.entry("animus:gui", "item_frame"), Map.entry("animus:hire", "gold_ingot"), Map.entry("animus:mount", "minecart"),
            Map.entry("animus:riding", "saddle"), Map.entry("animus:look-close", "ender_eye"), Map.entry("animus:schedule", "clock"),
            Map.entry("animus:modelengine", "armor_stand"), Map.entry("mythic:mob", "wither_skeleton_skull"), Map.entry("animus:home", "red_bed"),
            Map.entry("animus:mirror", "glass"));

    /**
     * One module as the screens show it: its key, name and icon, its schema, and the field that switches it, if any. An
     * untyped switch (combat's {@code enabled}) reads only a literal true.
     */
    record Module(String key, String name, String icon, JsonObject node, Optional<String> toggle) {
        /** Whether the module is on, read from its settings as stored ({@code null} when it has none). */
        boolean on(JsonElement settings) {
            if (settings == null || !settings.isJsonObject()) return false;
            if (toggle.isEmpty()) return true;
            JsonElement enabled = settings.getAsJsonObject().get(toggle.get());
            if (enabled != null) return enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && enabled.getAsBoolean();
            boolean literal = !node.getAsJsonObject("properties").getAsJsonObject(toggle.get()).has("type");
            return !literal && !KILLABLE.equals(toggle.get());
        }
    }

    private final String label;
    private final JsonObject schema;

    ModuleScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, JsonObject schema, Scope scope) {
        super(toolkit, viewer, commands, scope, List.of("modules"));
        this.label = label;
        this.schema = schema;
    }

    @Override String title() { return clip(label, 32); }
    @Override String node() { return PermissionNodes.NPC_EDIT; }

    /** Every module the schema knows, in its order. */
    static List<Module> modules(JsonObject schema) {
        List<Module> modules = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : schema.getAsJsonObject("properties").entrySet()) {
            String key = entry.getKey();
            JsonObject node = shape(entry.getValue().getAsJsonObject());
            JsonObject properties = node.has("properties") ? node.getAsJsonObject("properties") : new JsonObject();
            Optional<String> toggle = properties.has("enabled") ? Optional.of("enabled") : properties.has(KILLABLE) ? Optional.of(KILLABLE) : Optional.empty();
            modules.add(new Module(key, NAMES.getOrDefault(key, Words.readable(key)), "minecraft:" + ICONS.getOrDefault(key, "redstone"), node, toggle));
        }
        return modules;
    }

    @Override void draw() {
        JsonElement here = current();
        JsonObject modules = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        list(modules(schema), (Module module) -> module.name() + " " + module.key(), "No modules", (Integer slot, Module module) -> {
            List<String> lines = new ArrayList<>(describe(module.node()));
            lines.add(FieldScreen.now(module.on(modules.get(module.key())) ? "on" : "off", false));
            field(slot, module.icon(), module.name(), lines, any("open its fields"), module.key(),
                    (GuiModel.Click click) -> push(new FieldScreen<>(toolkit, viewer, commands, module.name(), module.node(), scope, append(path, module.key()), Optional.of(module))));
        });
        back();
        help("A module is one feature's settings on the NPC. Click one to turn it on or off and change its fields.", "21-in-game-toolkit", "modules");
    }

    /** Every module field the switches and field screens reach. */
    static Set<String> covered(JsonObject modules) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, JsonElement> module : modules.getAsJsonObject("properties").entrySet())
            ValueScreen.covered(shape(module.getValue().getAsJsonObject()), "modules." + module.getKey() + ".", out);
        return out;
    }
}
