package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.folia.FoliaRegions;
import art.arcane.animus.core.api.ApiPlatform;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The Bukkit side of the public NPC service. World keys are world UUIDs, as the runtime holds them. */
public final class BukkitApiPlatform implements ApiPlatform<Player, ItemStack> {
    private final Server server;
    private final FoliaRegions regions;
    private final PersistedNpcService<Player, ItemStack> mainThread;
    private final RegionNpcRuntime regionized;

    /** Exactly one runtime: the main-thread one on Paper and Spigot, or Folia's with its schedulers. */
    public BukkitApiPlatform(Server server, PersistedNpcService<Player, ItemStack> mainThread, RegionNpcRuntime regionized, FoliaRegions regions) {
        this.server = Objects.requireNonNull(server);
        if ((mainThread == null) == (regionized == null)) throw new IllegalArgumentException("One NPC runtime");
        this.mainThread = mainThread;
        this.regionized = regionized;
        this.regions = regions;
    }

    @Override public ItemStack item(NpcItem item, String path, List<String> errors) { return DefinitionItems.stack(item, path, errors); }
    @Override public ItemStack emptyItem() { return new ItemStack(Material.AIR); }
    @Override public CompletionStage<Boolean> teleport(String id, WorldPosition to, float yaw, float pitch) {
        if (regionized != null) return regionized.teleport(id, to, yaw, pitch);
        boolean moved = mainThread.relocate(id, to);
        if (moved) mainThread.lookAt(id, ApiPlatform.facing(to, yaw, pitch));
        return CompletableFuture.completedFuture(moved);
    }

    /** On Folia only an entity this region owns is read; Bukkit's lookup is safe on Paper's main thread. */
    @Override public Optional<WorldPosition> entity(UUID entity) {
        try {
            Entity found = server.getEntity(entity);
            if (found == null || !found.isValid() || regions != null && !regions.owns(found)) return Optional.empty();
            return Optional.of(position(found.getLocation()));
        } catch (RuntimeException elsewhere) { return Optional.empty(); }
    }

    @Override public WorldPosition position(Player player) { return position(player.getLocation()); }

    @Override public Optional<String> world(String reference) {
        World world;
        try { world = server.getWorld(UUID.fromString(reference)); }
        catch (IllegalArgumentException name) { world = server.getWorld(reference); }
        return Optional.ofNullable(world).map((World loaded) -> loaded.getUID().toString());
    }

    @Override public String worldName(String key) {
        try {
            World world = server.getWorld(UUID.fromString(key));
            return world == null ? key : world.getName();
        } catch (IllegalArgumentException name) { return key; }
    }

    private static WorldPosition position(Location at) {
        return new WorldPosition(Objects.requireNonNull(at.getWorld()).getUID().toString(), new Vec3(at.getX(), at.getY(), at.getZ()));
    }
}
