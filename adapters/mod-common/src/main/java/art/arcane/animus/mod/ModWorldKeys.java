package art.arcane.animus.mod;

import art.arcane.animus.core.platform.ModWorlds;
import java.util.HashSet;
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

    /** The keys of the loaded dimensions. */
    static Set<String> loaded(MinecraftServer server) {
        Set<String> loaded = new HashSet<>();
        for (ResourceKey<Level> dimension : server.levelKeys()) loaded.add(dimension.identifier().toString());
        return Set.copyOf(loaded);
    }
}
