package art.arcane.animus.mod;

import art.arcane.animus.core.navigation.CollisionCells;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.TerrainReader;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Server-thread reads of loaded vanilla terrain with real collision shapes. Never loads a chunk. */
final class VanillaTerrain implements TerrainReader {
    private final MinecraftServer server;

    VanillaTerrain(MinecraftServer server) { this.server = Objects.requireNonNull(server); }

    ServerLevel level(String world) { return ModWorldKeys.level(server, world); }

    @Override public boolean worldLoaded(String world) { return level(world) != null; }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        ServerLevel level = level(world);
        return level != null && level.getChunkSource().hasChunk(chunkX, chunkZ);
    }
    @Override public MotionValidator.Sampler collision(String world) {
        ServerLevel level = level(world);
        if (level == null) return (int x, int y, int z) -> VoxelSnapshot.Cell.UNAVAILABLE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        return (int x, int y, int z) -> {
            if (level.isOutsideBuildHeight(y) || !level.getChunkSource().hasChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))
                return VoxelSnapshot.Cell.UNAVAILABLE;
            cursor.set(x, y, z);
            return cell(level, cursor, level.getBlockState(cursor));
        };
    }

    /** The block's collision boxes, its fluid and whether it hurts. A shape that cannot be read is a full block. */
    static VoxelSnapshot.Cell cell(ServerLevel level, BlockPos position, BlockState state) {
        List<VoxelSnapshot.Cell.Box> boxes = new ArrayList<>();
        try {
            for (AABB box : state.getCollisionShape(level, position).toAabbs()) {
                VoxelSnapshot.Cell.Box clamped = CollisionCells.clamp(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                if (clamped != null) boxes.add(clamped);
            }
        } catch (RuntimeException unreadable) {
            boxes = List.of(VoxelSnapshot.Cell.SOLID.boxes().getFirst());
        }
        String material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toUpperCase(Locale.ROOT);
        boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
        return CollisionCells.classify(material, boxes, waterlogged);
    }
}
