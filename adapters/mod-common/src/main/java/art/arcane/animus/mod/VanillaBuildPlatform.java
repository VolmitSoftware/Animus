package art.arcane.animus.mod;

import art.arcane.animus.core.building.BlockState;
import art.arcane.animus.core.building.BuildService;
import art.arcane.animus.core.building.PaletteResolver;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.navigation.MotionValidator;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Block work for builds on a mod server's thread. Mod servers have no standard protection API, so the service runs
 * with no protection adapter. Never loads a chunk.
 */
final class VanillaBuildPlatform implements BuildService.Platform<ServerPlayer, ItemStack> {
    private static final int CACHE = 4096;
    /** Send the change to clients and skip neighbour shape updates, so nothing pops off while a build is half done. */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private final MinecraftServer server;
    private final VanillaTerrain terrain;
    private final Map<String, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();

    VanillaBuildPlatform(MinecraftServer server, VanillaTerrain terrain) {
        this.server = Objects.requireNonNull(server);
        this.terrain = Objects.requireNonNull(terrain);
    }

    @Override public boolean worldLoaded(String world) { return terrain.worldLoaded(world); }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return terrain.chunkLoaded(world, chunkX, chunkZ); }

    @Override public Optional<String> block(String world, BlockPos at) {
        ServerLevel level = loaded(world, at);
        return level == null ? Optional.empty() : Optional.of(canonical(BlockStateParser.serialize(level.getBlockState(position(at)))));
    }

    @Override public Optional<String> resolve(BlockState state) {
        net.minecraft.world.level.block.state.BlockState parsed = parse(state.toString());
        return parsed == null ? Optional.empty() : Optional.of(canonical(BlockStateParser.serialize(parsed)));
    }

    @Override public Optional<String> item(String state) {
        net.minecraft.world.level.block.state.BlockState parsed = parse(state);
        if (parsed == null) return Optional.empty();
        Item item = parsed.getBlock().asItem();
        return item == Items.AIR ? Optional.empty() : Optional.of(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    @Override public boolean place(String world, BlockPos at, String state) {
        ServerLevel level = loaded(world, at);
        net.minecraft.world.level.block.state.BlockState parsed = parse(state);
        if (level == null || parsed == null) return false;
        level.setBlock(position(at), parsed, PLACE_FLAGS);
        return true;
    }

    @Override public void placed(String world, BlockPos at, String state, boolean sound) {
        ServerLevel level = loaded(world, at);
        net.minecraft.world.level.block.state.BlockState parsed = parse(state);
        if (!sound || level == null || parsed == null) return;
        SoundType type = parsed.getSoundType();
        level.playSound(null, position(at), type.getPlaceSound(), SoundSource.BLOCKS, (type.getVolume() + 1) / 2, type.getPitch() * .8f);
    }

    @Override public long containerCount(String world, BlockPos at, String item) {
        Container container = container(world, at);
        if (container == null) return -1;
        Item wanted = itemNamed(item);
        if (wanted == null) return 0;
        long count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == wanted) count += stack.getCount();
        }
        return count;
    }

    @Override public boolean containerTake(String world, BlockPos at, String item, int count) {
        Container container = container(world, at);
        Item wanted = itemNamed(item);
        if (container == null || wanted == null || containerCount(world, at, item) < count) return false;
        int left = count;
        for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != wanted) continue;
            int taken = Math.min(left, stack.getCount());
            stack.shrink(taken);
            left -= taken;
        }
        container.setChanged();
        return true;
    }

    @Override public MotionValidator.Sampler collision(String world) { return terrain.collision(world); }

    @Override public ItemStack stack(Optional<String> item) {
        Item found = item.map(VanillaBuildPlatform::itemNamed).orElse(null);
        return found == null ? ItemStack.EMPTY : new ItemStack(found);
    }

    @Override public Optional<Held> held(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return Optional.empty();
        return Optional.of(new Held(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
    }

    @Override public void takeHeld(ServerPlayer player, int count) { player.getMainHandItem().shrink(count); }

    @Override public void tell(ServerPlayer player, String message) { player.sendSystemMessage(ModText.component(message)); }

    private ServerLevel loaded(String world, BlockPos at) {
        ServerLevel level = terrain.level(world);
        if (level == null || level.isOutsideBuildHeight(at.y()) || !level.getChunkSource().hasChunk(Math.floorDiv(at.x(), 16), Math.floorDiv(at.z(), 16))) return null;
        return level;
    }
    private Container container(String world, BlockPos at) {
        ServerLevel level = loaded(world, at);
        if (level == null) return null;
        BlockEntity entity = level.getBlockEntity(position(at));
        return entity instanceof Container container ? container : null;
    }
    private net.minecraft.world.level.block.state.BlockState parse(String text) {
        net.minecraft.world.level.block.state.BlockState known = states.get(text);
        if (known != null) return known;
        net.minecraft.world.level.block.state.BlockState parsed;
        try { parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState(); }
        catch (CommandSyntaxException | RuntimeException unknown) { return null; }
        if (states.size() >= CACHE) states.clear();
        states.put(text, parsed);
        return parsed;
    }
    private static Item itemNamed(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }
    private static net.minecraft.core.BlockPos position(BlockPos at) { return new net.minecraft.core.BlockPos(at.x(), at.y(), at.z()); }
    private static String canonical(String text) { return PaletteResolver.parse(text).map(BlockState::toString).orElse(text); }
}
