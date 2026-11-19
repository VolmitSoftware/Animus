package art.arcane.animus.mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** World keys on mod servers are dimension identifiers. */
final class ModWorldKeys {
    private ModWorldKeys() {}

    static String key(Level level) { return level.dimension().identifier().toString(); }

    static ServerLevel level(MinecraftServer server, String key) {
        Identifier id = Identifier.tryParse(key);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    static Optional<String> resolve(MinecraftServer server, String reference) {
        return ModWorlds.resolve(reference, server.getWorldData().getLevelName(), loaded(server));
    }

    /**
     * Every name {@link #resolve} turns into one of the {@code loaded} keys, key by key in order: the key, its bare vanilla
     * path and its Bukkit folder name. The editor checks a definition's world against this list before it asks for terrain.
     */
    static List<String> names(Set<String> loaded, String levelName) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : loaded.stream().sorted().toList()) {
            names.add(key);
            names.addAll(ModWorlds.aliases(key, levelName).stream().filter((String alias) -> !alias.equals(key)).sorted().toList());
        }
        return new ArrayList<>(names);
    }

    /** The keys of the loaded dimensions. */
    static Set<String> loaded(MinecraftServer server) {
        Set<String> loaded = new HashSet<>();
        for (ResourceKey<Level> dimension : server.levelKeys()) loaded.add(dimension.identifier().toString());
        return Set.copyOf(loaded);
    }
}
