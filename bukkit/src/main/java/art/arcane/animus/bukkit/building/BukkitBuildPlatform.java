package art.arcane.animus.bukkit.building;

import art.arcane.animus.bukkit.navigation.BukkitTerrain;
import art.arcane.animus.core.building.BlockState;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.building.PaletteResolver;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

/** Block work for builds on the Bukkit and Paper main thread. World keys are world UUIDs. Never loads a chunk. */
public final class BukkitBuildPlatform implements BuildService.Platform<Player, ItemStack> {
    private static final int CACHE = 4096;
    private final Server server;
    private final BukkitTerrain terrain;
    private final Map<String, BlockData> states = new HashMap<>();

    public BukkitBuildPlatform(Server server) {
        this.server = Objects.requireNonNull(server);
        terrain = new BukkitTerrain(server);
    }

    @Override public boolean worldLoaded(String world) { return terrain.worldLoaded(world); }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return terrain.chunkLoaded(world, chunkX, chunkZ); }

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

    @Override public MotionValidator.Sampler collision(String world) { return terrain.collision(world); }

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

    @Override public void tell(Player player, String message) { player.sendMessage(message); }

    private Block loaded(String world, BlockPos at) {
        World loaded = terrain.world(world);
        if (loaded == null || at.y() < loaded.getMinHeight() || at.y() >= loaded.getMaxHeight()) return null;
        if (!loaded.isChunkLoaded(Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return null;
        return loaded.getBlockAt(at.x(), at.y(), at.z());
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
        states.put(text, created);
        return created.clone();
    }
    private static String canonical(String text) { return PaletteResolver.parse(text).map(BlockState::toString).orElse(text); }
}
