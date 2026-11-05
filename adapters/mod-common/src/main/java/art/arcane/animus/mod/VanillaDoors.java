package art.arcane.animus.mod;

import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.DoorPassage;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Server-thread doors, fence gates and upper trapdoors for walking NPCs. Opening asks the build protection gate for
 * {@code USE} first and fails closed; doors swing both halves the vanilla way. Never loads a chunk.
 */
final class VanillaDoors implements DoorPassage.Doors {
    private final VanillaTerrain terrain;
    private final BuildProtection.Gate protection;

    VanillaDoors(VanillaTerrain terrain, BuildProtection.Gate protection) {
        this.terrain = Objects.requireNonNull(terrain); this.protection = Objects.requireNonNull(protection);
    }

    @Override public boolean open(UUID npc, String world, art.arcane.animus.core.navigation.BlockPos at) {
        ServerLevel level = terrain.level(world);
        BlockPos position = new BlockPos(at.x(), at.y(), at.z());
        if (level == null || !reachable(level, position)) return false;
        BlockState state = level.getBlockState(position);
        Optional<CollisionCells.Opening> kind = kind(state);
        if (kind.isEmpty()) return false;
        if (state.getValue(BlockStateProperties.OPEN)) return true;
        if (!opens(kind.get(), state)) return false;
        if (!protection.allows(new BuildProtection.Request(npc, Optional.empty(), world, at, BuildProtection.Action.USE))) return false;
        set(level, position, state, kind.get(), true);
        return true;
    }

    @Override public void close(String world, art.arcane.animus.core.navigation.BlockPos at) {
        ServerLevel level = terrain.level(world);
        BlockPos position = new BlockPos(at.x(), at.y(), at.z());
        if (level == null || !reachable(level, position)) return;
        BlockState state = level.getBlockState(position);
        Optional<CollisionCells.Opening> kind = kind(state);
        if (kind.isPresent() && state.getValue(BlockStateProperties.OPEN)) set(level, position, state, kind.get(), false);
    }

    /** The open collision of a closed block a body may open by hand: a door, fence gate or upper trapdoor. */
    static Optional<BlockState> opened(BlockState state) {
        Optional<CollisionCells.Opening> kind = kind(state);
        if (kind.isEmpty() || state.getValue(BlockStateProperties.OPEN) || !opens(kind.get(), state)) return Optional.empty();
        return Optional.of(state.setValue(BlockStateProperties.OPEN, true));
    }

    private static Optional<CollisionCells.Opening> kind(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.OPEN)) return Optional.empty();
        return CollisionCells.opening(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toUpperCase(Locale.ROOT));
    }

    private static boolean opens(CollisionCells.Opening kind, BlockState state) {
        return kind != CollisionCells.Opening.TRAPDOOR || state.hasProperty(BlockStateProperties.HALF) && state.getValue(BlockStateProperties.HALF) == Half.TOP;
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state, CollisionCells.Opening kind, boolean open) {
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(null, level, state, position, open);
            return;
        }
        level.setBlock(position, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_CLIENTS);
        SoundEvent sound = kind == CollisionCells.Opening.GATE ? (open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE)
                : (open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE);
        level.playSound(null, position, sound, SoundSource.BLOCKS, 1, 1);
    }

    private static boolean reachable(ServerLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position.getY()) && level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }
}
