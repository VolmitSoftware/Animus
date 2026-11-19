package art.arcane.animus.core.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        String key = MinecraftIds.normalize(reference);
        for (Loaded world : loaded) if (world.key().equals(key) || world.uid().toString().equalsIgnoreCase(reference)) return Optional.of(world.name());
        String main = loaded.getFirst().name();
        String folder = switch (key) {
            case MinecraftIds.OVERWORLD -> main;
            case MinecraftIds.NETHER -> main + "_nether";
            case MinecraftIds.END -> main + "_the_end";
            default -> null;
        };
        if (folder == null) return Optional.empty();
        for (Loaded world : loaded) if (world.name().equals(folder)) return Optional.of(world.name());
        return Optional.empty();
    }

    /**
     * Every name {@link #resolve} turns into a loaded world, world by world in load order: its name, its dimension key,
     * the key's bare vanilla path and the vanilla dimension its folder stands for. The editor checks a definition's world
     * against this list before it asks for terrain. UUIDs resolve too but are left out, since the editor offers these names.
     */
    public static List<String> names(List<Loaded> loaded) {
        Set<String> names = new LinkedHashSet<>();
        String main = loaded.isEmpty() ? "" : loaded.getFirst().name();
        for (Loaded world : loaded) {
            List<String> candidates = new ArrayList<>(List.of(world.name(), world.key()));
            String vanilla = world.name().equals(main) ? MinecraftIds.OVERWORLD : world.name().equals(main + "_nether") ? MinecraftIds.NETHER
                    : world.name().equals(main + "_the_end") ? MinecraftIds.END : null;
            if (vanilla != null) candidates.add(vanilla);
            for (String key : List.copyOf(candidates))
                if (key.startsWith("minecraft:")) candidates.add(key.substring("minecraft:".length()));
            for (String candidate : candidates)
                if (resolve(candidate, loaded).filter(world.name()::equals).isPresent()) names.add(candidate);
        }
        return List.copyOf(names);
    }
}
