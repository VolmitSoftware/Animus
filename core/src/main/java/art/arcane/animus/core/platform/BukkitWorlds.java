package art.arcane.animus.core.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * World references on Bukkit servers, where a world is named by its folder. Definitions written for mod servers name
 * worlds by dimension key, so a world's key and UUID also resolve to its name, and the three vanilla dimension keys
 * fall back to the main world and its {@code _nether} and {@code _the_end} folders.
 */
public final class BukkitWorlds {
    /** A loaded world. The first loaded world is the main world. */
    public record Loaded(String name, UUID uid, String key) {}

    private BukkitWorlds() {}

    /** The name of the loaded world the reference names, or empty. Names match without regard to case, as Bukkit's do. */
    public static Optional<String> resolve(String reference, List<Loaded> loaded) {
        if (reference == null || reference.isBlank() || loaded.isEmpty()) return Optional.empty();
        for (Loaded world : loaded) if (world.name().equalsIgnoreCase(reference)) return Optional.of(world.name());
        for (Loaded world : loaded) if (world.key().equals(reference) || world.uid().toString().equalsIgnoreCase(reference)) return Optional.of(world.name());
        String main = loaded.getFirst().name();
        String folder = switch (reference) {
            case ModWorlds.OVERWORLD -> main;
            case ModWorlds.NETHER -> main + "_nether";
            case ModWorlds.END -> main + "_the_end";
            default -> null;
        };
        if (folder == null) return Optional.empty();
        for (Loaded world : loaded) if (world.name().equals(folder)) return Optional.of(world.name());
        return Optional.empty();
    }
}
