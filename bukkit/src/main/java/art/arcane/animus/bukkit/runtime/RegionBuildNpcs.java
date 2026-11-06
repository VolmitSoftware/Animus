package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.building.BukkitBuildPlatform;
import art.arcane.animus.core.building.BuildNpcs;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.virtual.ChunkPosition;
import org.bukkit.World;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Builder NPCs on Folia. Reads come from each NPC's published state; moves and changes run on the NPC's own region. A
 * build's turn moves only NPCs its region holds ({@link #here}); stopping and holding an item from anywhere else go
 * through the NPC's bounded mailbox, so a build command on the global region never touches an NPC directly.
 */
public final class RegionBuildNpcs implements BuildNpcs<Player, ItemStack> {
    private final RegionNpcRuntime host;

    public RegionBuildNpcs(RegionNpcRuntime host) { this.host = Objects.requireNonNull(host); }

    @Override public boolean available() { return host.available(); }
    @Override public String status() { return host.status(); }
    @Override public NpcRuntime.PlayerAccess<Player> players() { return host.players(); }
    @Override public Optional<NpcRuntime.ActiveNpc> active(UUID npc) { return host.active(npc); }
    @Override public boolean here(String id) {
        RegionActor actor = host.available() ? host.actors.get(id) : null;
        return actor != null && actor.owner.owned();
    }
    @Override public boolean routing(String id) { return owned(id).routing(); }
    @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
        return host.navigate(id, destination, mode, blocksPerSecond);
    }
    /** At once on the NPC's region; from anywhere else through its mailbox. False when it is not active. */
    @Override public boolean stopNavigation(String id) {
        if (here(id)) return host.stopNavigation(id);
        return host.onOwner(id, () -> { RegionActor actor = host.actors.get(id); if (actor != null) actor.stopNavigation(); }, (String refused) -> {});
    }
    @Override public boolean relocate(String id, WorldPosition destination) { return owned(id).relocate(destination); }
    @Override public void lookAt(String id, WorldPosition target) { host.lookAt(id, target); }
    @Override public void swing(String id) { owned(id).swing(); }
    /** At once on the NPC's region; from anywhere else through its mailbox. */
    @Override public void setEquipment(String id, EquipmentSlot slot, ItemStack item) {
        if (here(id)) { host.setEquipment(id, slot, item); return; }
        ItemStack copy = item.clone();
        host.onOwner(id, () -> { RegionActor actor = host.actors.get(id); if (actor != null && actor.navigable()) actor.setEquipment(slot, copy); }, (String refused) -> {});
    }
    /** On the changed block's region: NPCs standing on it check their footing on their own next turn. */
    @Override public void terrainChanged(String world, BlockPos at) { host.terrainChanged(world, at); }

    /**
     * Where build turns run: the region that owns the chunk, and only for a chunk a load event or an owner reported
     * loaded, so a turn never makes Folia create a region or ticket for absent terrain.
     */
    public BuildService.Regions turns() {
        return new BuildService.Regions() {
            @Override public boolean run(String world, int chunkX, int chunkZ, Runnable work) { return host.available() && host.regions.execute(new ChunkPosition(world, chunkX, chunkZ), work); }
            @Override public String unreachable(String world, int chunkX, int chunkZ) {
                if (!host.available()) return "the NPC runtime is " + host.status();
                return host.knownLoaded(new ChunkPosition(world, chunkX, chunkZ)) ? null : "waiting for chunk " + chunkX + " " + chunkZ + " to load";
            }
        };
    }
    /** Block access for build turns: a chunk counts only when the calling region owns it and it is loaded. */
    public BukkitBuildPlatform.Ownership ownership() {
        return new BukkitBuildPlatform.Ownership() {
            @Override public boolean owns(World world, int chunkX, int chunkZ) { return host.regions.owns(world, chunkX, chunkZ, chunkX, chunkZ); }
            @Override public void loaded(World world, int chunkX, int chunkZ) { host.observedLoaded(new ChunkPosition(world.getUID().toString(), chunkX, chunkZ)); }
            @Override public boolean elsewhere(String world, int chunkX, int chunkZ) {
                return host.knownLoaded(new ChunkPosition(world, chunkX, chunkZ)) && !host.regions.owns(world, chunkX, chunkZ, chunkX, chunkZ);
            }
            @Override public String missing(String world, int chunkX, int chunkZ) {
                return host.knownLoaded(new ChunkPosition(world, chunkX, chunkZ)) ? "chunk " + chunkX + " " + chunkZ + ", which another region owns" : "an unloaded chunk";
            }
        };
    }

    private RegionActor owned(String id) {
        if (!host.available()) throw new IllegalStateException("NPC runtime " + host.status());
        RegionActor actor = host.actors.get(id);
        if (actor == null || !actor.owner.owned()) throw new IllegalStateException("NPC " + id + " is not active on this region");
        return actor;
    }
}
