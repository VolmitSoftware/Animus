package art.arcane.animus.core.platform;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minecraft registry ids: items, blocks, sounds, particles, entity types, effects, enchantments, attributes, biomes and
 * dimensions. A bare id with no colon means {@code minecraft:<id>}. Loading never rewrites a file; parsers hold the
 * {@link #normalize normalized} form in memory, so a document Animus saves later carries the full form.
 */
public final class MinecraftIds {
    public static final int MAX_LENGTH = 128;
    /** A registry id with an optional namespace, for schemas and plain regex checks. */
    public static final String PATTERN = "([a-z0-9_.-]+:)?[a-z0-9_./-]+";
    private static final Pattern FORM = Pattern.compile(PATTERN);

    private MinecraftIds() {}

    /** Trimmed, lowercase and namespaced: {@code Netherite_Axe} becomes {@code minecraft:netherite_axe}. Blank gives empty. */
    public static String normalize(String id) {
        String trimmed = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.indexOf(':') >= 0) return trimmed;
        return "minecraft:" + trimmed;
    }

    /** True when the id as written is lowercase {@code [namespace:]path} and at most {@link #MAX_LENGTH} characters once namespaced. */
    public static boolean valid(String id) {
        return id != null && FORM.matcher(id).matches() && normalize(id).length() <= MAX_LENGTH;
    }
}
