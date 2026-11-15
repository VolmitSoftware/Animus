package art.arcane.animus.core.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A passive seat players take on top of an NPC with a click, as GSit seats one player on another: no steering, and
 * sneaking gets off. Without the module the {@code [seats] defaultEnabled} setting decides; {@code enabled: false} turns
 * a seat off whatever that setting says.
 *
 * @param click the click that sits down; a click the NPC's behaviors, shop, GUI, quest or ride take never seats anyone
 * @param stack how many players may sit stacked on the NPC, each on the one below
 * @param emptyHand only a player with an empty main hand sits
 */
public record SeatModule(boolean enabled, Click click, String permission, int stack, boolean emptyHand) {
    public static final String MODULE = "animus:seat";
    public static final String DEFAULT_PERMISSION = "animus.seat";
    public static final int DEFAULT_STACK = 4, MAXIMUM_STACK = 8;
    private static final String FIELD = "$.modules." + MODULE;
    private static final Set<String> KEYS = Set.of("enabled", "click", "permission", "stack", "emptyHand");

    /** The click that sits down: a plain right-click, or one while sneaking. */
    public enum Click {
        RIGHT, SNEAK_RIGHT;
        public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
        static Optional<Click> fromKey(String key) {
            for (Click click : values()) if (click.key().equals(key)) return Optional.of(click);
            return Optional.empty();
        }
    }

    public SeatModule {
        Objects.requireNonNull(click, "click"); Objects.requireNonNull(permission, "permission");
        if (!permission.matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)*") || permission.length() > 128) throw new IllegalArgumentException(FIELD + ".permission: expected a lowercase permission node");
        if (stack < 1 || stack > MAXIMUM_STACK) throw new IllegalArgumentException(FIELD + ".stack: expected 1-" + MAXIMUM_STACK);
    }

    /** The seat an NPC offers when it declares no module. */
    public static SeatModule byDefault(boolean enabled) { return new SeatModule(enabled, Click.RIGHT, DEFAULT_PERMISSION, DEFAULT_STACK, true); }

    /** Whether a main-hand click, sneaking or not, sits down here. */
    public boolean sitsOn(boolean sneaking) { return enabled && (click == Click.SNEAK_RIGHT) == sneaking; }

    /** The definition's own module, or empty without one. @throws IllegalArgumentException naming the exact field */
    public static Optional<SeatModule> parse(JsonObject document) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(FIELD + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException(FIELD + "." + key + ": unknown field");
        boolean enabled = flag(module, "enabled", true), emptyHand = flag(module, "emptyHand", true);
        Click click = Click.RIGHT;
        if (module.has("click")) {
            JsonElement key = module.get("click");
            Optional<Click> known = key.isJsonPrimitive() && key.getAsJsonPrimitive().isString() ? Click.fromKey(key.getAsString()) : Optional.empty();
            click = known.orElseThrow(() -> new IllegalArgumentException(FIELD + ".click: expected right or sneak-right"));
        }
        String permission = DEFAULT_PERMISSION;
        if (module.has("permission")) {
            JsonElement node = module.get("permission");
            if (!node.isJsonPrimitive() || !node.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(FIELD + ".permission: expected string");
            permission = node.getAsString();
        }
        int stack = DEFAULT_STACK;
        if (module.has("stack")) {
            JsonElement size = module.get("stack");
            if (!size.isJsonPrimitive() || !size.getAsJsonPrimitive().isNumber() || size.getAsDouble() != Math.rint(size.getAsDouble()))
                throw new IllegalArgumentException(FIELD + ".stack: expected 1-" + MAXIMUM_STACK);
            stack = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, size.getAsDouble()));
        }
        if (enabled && click == Click.RIGHT && RideSettings.parse(document).isPresent())
            throw new IllegalArgumentException(FIELD + ".click: a right-click mounts this NPC through riding; use sneak-right for the seat");
        return Optional.of(new SeatModule(enabled, click, permission, stack, emptyHand));
    }

    /** The seat this definition offers, with {@code [seats] defaultEnabled} for one that declares no module. */
    public static SeatModule of(JsonObject document, boolean defaultEnabled) { return parse(document).orElseGet(() -> byDefault(defaultEnabled)); }

    private static boolean flag(JsonObject module, String key, boolean fallback) {
        if (!module.has(key)) return fallback;
        JsonElement value = module.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(FIELD + "." + key + ": expected boolean");
        return value.getAsBoolean();
    }
}
