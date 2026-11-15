package art.arcane.animus.core.combat;

import art.arcane.animus.core.platform.MinecraftIds;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The status effect a held potion applies, read from its {@code potion_contents} component: the first custom effect, else
 * the vanilla potion's first effect with its vanilla amplifier and duration. Empty for an item that applies none.
 */
public final class PotionEffects {
    private static final Map<String, CombatSettings.Effect> POTIONS = Map.ofEntries(
            potion("night_vision", "night_vision", 0, 3600), potion("long_night_vision", "night_vision", 0, 9600),
            potion("invisibility", "invisibility", 0, 3600), potion("long_invisibility", "invisibility", 0, 9600),
            potion("leaping", "jump_boost", 0, 3600), potion("long_leaping", "jump_boost", 0, 9600), potion("strong_leaping", "jump_boost", 1, 1800),
            potion("fire_resistance", "fire_resistance", 0, 3600), potion("long_fire_resistance", "fire_resistance", 0, 9600),
            potion("swiftness", "speed", 0, 3600), potion("long_swiftness", "speed", 0, 9600), potion("strong_swiftness", "speed", 1, 1800),
            potion("slowness", "slowness", 0, 1800), potion("long_slowness", "slowness", 0, 4800), potion("strong_slowness", "slowness", 3, 400),
            potion("turtle_master", "slowness", 3, 400), potion("long_turtle_master", "slowness", 3, 800), potion("strong_turtle_master", "slowness", 5, 400),
            potion("water_breathing", "water_breathing", 0, 3600), potion("long_water_breathing", "water_breathing", 0, 9600),
            potion("healing", "instant_health", 0, 1), potion("strong_healing", "instant_health", 1, 1),
            potion("harming", "instant_damage", 0, 1), potion("strong_harming", "instant_damage", 1, 1),
            potion("poison", "poison", 0, 900), potion("long_poison", "poison", 0, 1800), potion("strong_poison", "poison", 1, 432),
            potion("regeneration", "regeneration", 0, 900), potion("long_regeneration", "regeneration", 0, 1800), potion("strong_regeneration", "regeneration", 1, 450),
            potion("strength", "strength", 0, 3600), potion("long_strength", "strength", 0, 9600), potion("strong_strength", "strength", 1, 1800),
            potion("weakness", "weakness", 0, 1800), potion("long_weakness", "weakness", 0, 4800), potion("luck", "luck", 0, 6000),
            potion("slow_falling", "slow_falling", 0, 1800), potion("long_slow_falling", "slow_falling", 0, 4800),
            potion("wind_charged", "wind_charged", 0, 3600), potion("weaving", "weaving", 0, 3600), potion("oozing", "oozing", 0, 3600),
            potion("infested", "infested", 0, 3600));
    private static final Pattern CONTENTS = Pattern.compile("(?:minecraft:)?potion_contents\\s*=\\s*");
    private static final Pattern NAMED = Pattern.compile("\\G[\"']?((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)[\"']?");
    private static final Pattern BASE = Pattern.compile("[{,]\\s*[\"']?potion[\"']?\\s*:\\s*[\"']?((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)[\"']?");
    private static final Pattern CUSTOM = Pattern.compile("[\"']?custom_effects[\"']?\\s*:\\s*\\[\\s*\\{([^}]*)}");
    private static final Pattern ID = Pattern.compile("[\"']?id[\"']?\\s*:\\s*[\"']?((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)[\"']?");
    private static final Pattern AMPLIFIER = Pattern.compile("[\"']?amplifier[\"']?\\s*:\\s*(\\d+)");
    private static final Pattern DURATION = Pattern.compile("[\"']?duration[\"']?\\s*:\\s*(-?\\d+)");

    private PotionEffects() {}

    private static Map.Entry<String, CombatSettings.Effect> potion(String name, String effect, int amplifier, int ticks) {
        return Map.entry("minecraft:" + name, new CombatSettings.Effect("minecraft:" + effect, amplifier, ticks));
    }

    /** The effect of a vanilla potion type such as {@code minecraft:strong_poison}. */
    public static Optional<CombatSettings.Effect> of(String potion) { return Optional.ofNullable(POTIONS.get(MinecraftIds.normalize(potion))); }

    public static Optional<CombatSettings.Effect> of(Optional<String> components) {
        if (components.isEmpty()) return Optional.empty();
        String text = components.get();
        Matcher start = CONTENTS.matcher(text);
        if (!start.find()) return Optional.empty();
        String rest = text.substring(start.end());
        if (!rest.startsWith("{")) {
            Matcher named = NAMED.matcher(rest);
            return named.find() && MinecraftIds.valid(named.group(1)) ? of(named.group(1)) : Optional.empty();
        }
        Matcher custom = CUSTOM.matcher(rest);
        if (custom.find()) {
            Matcher id = ID.matcher(custom.group(1));
            if (id.find() && MinecraftIds.valid(id.group(1))) {
                Matcher amplifier = AMPLIFIER.matcher(custom.group(1)), duration = DURATION.matcher(custom.group(1));
                int level = amplifier.find() ? Math.min(9, Integer.parseInt(amplifier.group(1))) : 0;
                int ticks = duration.find() ? Integer.parseInt(duration.group(1)) : 1;
                return Optional.of(new CombatSettings.Effect(MinecraftIds.normalize(id.group(1)), level, Math.max(1, Math.min(72000, ticks))));
            }
        }
        Matcher base = BASE.matcher(rest);
        return base.find() && MinecraftIds.valid(base.group(1)) ? of(base.group(1)) : Optional.empty();
    }
}
