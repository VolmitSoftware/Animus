package art.arcane.animus.core.platform;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * World references on mod servers. A world key is the dimension identifier, such as {@code minecraft:overworld}.
 * Definitions written for Bukkit name worlds by folder, so the level name and its {@code _nether} and
 * {@code _the_end} folders also resolve to the three vanilla dimensions.
 */
public final class ModWorlds {
    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private ModWorlds() {}

    /** The loaded world key the reference names, or empty. */
    public static Optional<String> resolve(String reference, String levelName, Set<String> loaded) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        String key;
        if (reference.equals(levelName)) key = OVERWORLD;
        else if (reference.equals(levelName + "_nether")) key = NETHER;
        else if (reference.equals(levelName + "_the_end")) key = END;
        else if (KEY.matcher(reference).matches()) key = reference;
        else if (PATH.matcher(reference).matches()) key = "minecraft:" + reference;
        else return Optional.empty();
        return loaded.contains(key) ? Optional.of(key) : Optional.empty();
    }
}
