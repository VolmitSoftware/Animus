package art.arcane.animus.mod;

import art.arcane.animus.core.api.ApiPlatform;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** The mod server side of the public NPC service. Everything runs on the server thread; world keys are dimension ids. */
final class ModApiPlatform implements ApiPlatform<ServerPlayer, ItemStack> {
    private final MinecraftServer server;
    private final PersistedNpcService<ServerPlayer, ItemStack> living;

    ModApiPlatform(MinecraftServer server, PersistedNpcService<ServerPlayer, ItemStack> living) { this.server = server; this.living = living; }

    @Override public ItemStack item(NpcItem item, String path, List<String> errors) { return VanillaRuntimeHost.stack(server, item, path, errors); }
    @Override public ItemStack emptyItem() { return ItemStack.EMPTY; }
    @Override public java.util.concurrent.CompletionStage<Boolean> teleport(String id, WorldPosition to, float yaw, float pitch) {
        boolean moved = living.relocate(id, to);
        if (moved) living.lookAt(id, ApiPlatform.facing(to, yaw, pitch));
        return java.util.concurrent.CompletableFuture.completedFuture(moved);
    }

    @Override public Optional<WorldPosition> entity(UUID entity) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(entity);
            if (found == null || !found.isAlive()) continue;
            net.minecraft.world.phys.Vec3 at = found.position();
            return Optional.of(new WorldPosition(ModWorldKeys.key(level), new Vec3(at.x(), at.y(), at.z())));
        }
        return Optional.empty();
    }

    @Override public WorldPosition position(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 at = player.position();
        return new WorldPosition(ModWorldKeys.key(player.level()), new Vec3(at.x(), at.y(), at.z()));
    }

    @Override public Optional<String> world(String reference) { return ModWorldKeys.resolve(server, reference); }
    @Override public String worldName(String key) { return key; }
}
