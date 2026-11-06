package art.arcane.animus.bukkit.building;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.bukkit.navigation.BukkitTerrain;
import art.arcane.animus.core.building.BlockState;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.building.PaletteResolver;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.bukkit.navigation.LiveMovementProbe;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Block work for builds. World keys are world UUIDs. Never loads a chunk. On Bukkit and Paper it runs on the main
 * thread. On Folia ({@link Ownership}) it runs on whichever region has a build's turn, and every block, container and
 * collision read or write first checks that this region owns the chunk; a chunk it does not own reads as absent.
 */
public final class BukkitBuildPlatform implements BuildService.Platform<Player, ItemStack> {
    /** Folia: which chunks the calling region owns. */
    public interface Ownership {
        boolean owns(World world, int chunkX, int chunkZ);
        /** This region just saw the chunk loaded. */
        void loaded(World world, int chunkX, int chunkZ);
        /** Why a chunk the calling region cannot use is missing, for a waiting build's reason. */
        String missing(String world, int chunkX, int chunkZ);
        /** The chunk is known to be loaded and another region owns it. */
        boolean elsewhere(String world, int chunkX, int chunkZ);
    }
    private static final int CACHE = 4096;
    private final Server server;
    private final BukkitTerrain terrain;
    /** Null on Bukkit and Paper. */
    private final Ownership ownership;
    /** Shared by every region that runs a build turn. */
    private final Map<String, BlockData> states = new ConcurrentHashMap<>();

    public BukkitBuildPlatform(Server server, Ownership ownership) {
        this.server = Objects.requireNonNull(server);
        this.ownership = ownership;
        terrain = new BukkitTerrain(server);
    }

    @Override public boolean worldLoaded(String world) { return terrain.worldLoaded(world); }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        if (ownership == null) return terrain.chunkLoaded(world, chunkX, chunkZ);
        World loaded = terrain.world(world);
        return loaded != null && usable(loaded, chunkX, chunkZ);
    }
    @Override public boolean elsewhere(String world, int chunkX, int chunkZ) { return ownership != null && ownership.elsewhere(world, chunkX, chunkZ); }
    @Override public String missing(String world, int chunkX, int chunkZ) { return ownership == null ? "an unloaded chunk" : ownership.missing(world, chunkX, chunkZ); }

    @Override public Optional<String> block(String world, BlockPos at) {
        Block block = loaded(world, at);
        return block == null ? Optional.empty() : Optional.of(canonical(block.getBlockData().getAsString()));
    }

    @Override public Optional<String> resolve(BlockState state) {
        BlockData data = data(state.toString());
        return data == null ? Optional.empty() : Optional.of(canonical(data.getAsString()));
    }

    @Override public Optional<String> item(String state) {
        BlockData data = data(state);
        if (data == null) return Optional.empty();
        Material item = data.getPlacementMaterial();
        return item == null || item.isAir() || !item.isItem() ? Optional.empty() : Optional.of(item.getKey().toString());
    }

    @Override public boolean place(String world, BlockPos at, String state) {
        Block block = loaded(world, at);
        BlockData data = data(state);
        if (block == null || data == null) return false;
        block.setBlockData(data, false);
        return true;
    }

    @Override public void placed(String world, BlockPos at, String state, boolean sound) {
        if (!sound) return;
        World loaded = terrain.world(world);
        BlockData data = data(state);
        if (loaded == null || data == null) return;
        org.bukkit.SoundGroup group = data.getSoundGroup();
        loaded.playSound(new Location(loaded, at.x() + .5, at.y() + .5, at.z() + .5), group.getPlaceSound(), SoundCategory.BLOCKS,
                (group.getVolume() + 1) / 2, group.getPitch() * .8f);
    }

    @Override public long containerCount(String world, BlockPos at, String item) {
        Inventory inventory = inventory(world, at);
        Material material = Material.matchMaterial(item);
        if (inventory == null) return -1;
        if (material == null) return 0;
        long count = 0;
        for (ItemStack stack : inventory.getContents()) if (stack != null && stack.getType() == material) count += stack.getAmount();
        return count;
    }

    @Override public boolean containerTake(String world, BlockPos at, String item, int count) {
        Inventory inventory = inventory(world, at);
        Material material = Material.matchMaterial(item);
        if (inventory == null || material == null || containerCount(world, at, item) < count) return false;
        return inventory.removeItem(new ItemStack(material, count)).isEmpty();
    }

    @Override public MotionValidator.Sampler collision(String world) {
        if (ownership == null) return terrain.collision(world);
        World loaded = terrain.world(world);
        if (loaded == null) return (int x, int y, int z) -> VoxelSnapshot.Cell.UNAVAILABLE;
        return LiveMovementProbe.owned(loaded, (int chunkX, int chunkZ) -> ownership.owns(loaded, chunkX, chunkZ));
    }

    @Override public ItemStack stack(Optional<String> item) {
        Material material = item.map(Material::matchMaterial).orElse(null);
        return material == null || !material.isItem() ? new ItemStack(Material.AIR) : new ItemStack(material);
    }

    @Override public Optional<Held> held(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return Optional.empty();
        return Optional.of(new Held(stack.getType().getKey().toString(), stack.getAmount()));
    }

    @Override public void takeHeld(Player player, int count) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        int left = stack.getAmount() - count;
        if (left <= 0) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else stack.setAmount(left);
    }

    /** On the player's own owner: into the inventory, and what does not fit drops at the player's feet. */
    @Override public void give(Player player, String item, long count) {
        Material material = Material.matchMaterial(item);
        if (material == null || !material.isItem()) return;
        for (long left = count; left > 0; ) {
            int stack = (int) Math.min(left, material.getMaxStackSize());
            left -= stack;
            for (ItemStack over : player.getInventory().addItem(new ItemStack(material, stack)).values())
                player.getWorld().dropItem(player.getLocation(), over);
        }
    }

    @Override public void tell(Player player, String message) { player.sendMessage(RichText.colorize(message)); }

    private Block loaded(String world, BlockPos at) {
        World loaded = terrain.world(world);
        if (loaded == null || at.y() < loaded.getMinHeight() || at.y() >= loaded.getMaxHeight()) return null;
        if (ownership == null ? !loaded.isChunkLoaded(Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16)) : !usable(loaded, Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return null;
        return loaded.getBlockAt(at.x(), at.y(), at.z());
    }
    /** Folia: owned by the calling region first, so an unowned chunk is never asked about, then loaded. */
    private boolean usable(World world, int chunkX, int chunkZ) {
        if (!ownership.owns(world, chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ)) return false;
        ownership.loaded(world, chunkX, chunkZ);
        return true;
    }
    private Inventory inventory(String world, BlockPos at) {
        Block block = loaded(world, at);
        return block != null && block.getState() instanceof Container container ? container.getInventory() : null;
    }
    private BlockData data(String text) {
        BlockData known = states.get(text);
        if (known != null) return known.clone();
        BlockData created;
        try { created = server.createBlockData(text); }
        catch (IllegalArgumentException unknown) { return null; }
        if (states.size() >= CACHE) states.clear();
        states.putIfAbsent(text, created);
        return created.clone();
    }
    private static String canonical(String text) { return PaletteResolver.parse(text).map(BlockState::toString).orElse(text); }
}
