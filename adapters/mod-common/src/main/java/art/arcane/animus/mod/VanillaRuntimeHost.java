package art.arcane.animus.mod;

import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.platform.ModPermissions;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.runtime.RuntimeHost;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The mod server thread as the single owner of every persisted NPC. World keys are dimension identifiers. */
final class VanillaRuntimeHost implements RuntimeHost<ServerPlayer, ItemStack> {
    private final MinecraftServer server;
    private final ModTicks ticks;
    private final VanillaTerrain terrain;
    /** Sees every validated interaction before the runtime accepts it; false vetoes it. */
    @FunctionalInterface interface Interactions { boolean allowed(ServerPlayer player, UUID npc, Interaction action); }
    private final Consumer<String> warnings;
    private final Interactions interactions;
    private final VanillaDoors doors;
    private VanillaBodyTransport transport;

    /** {@code doorProtection} decides each door an NPC opens on its route; it fails closed. */
    VanillaRuntimeHost(MinecraftServer server, ModTicks ticks, Consumer<String> warnings, Interactions interactions, art.arcane.animus.core.building.BuildProtection doorProtection) {
        this.server = Objects.requireNonNull(server); this.ticks = Objects.requireNonNull(ticks); this.warnings = Objects.requireNonNull(warnings);
        this.interactions = Objects.requireNonNull(interactions);
        terrain = new VanillaTerrain(server);
        doors = new VanillaDoors(terrain, new art.arcane.animus.core.building.BuildProtection.Gate(doorProtection, (String message) -> warnings.accept("Doors: " + message)));
    }

    VanillaBodyTransport transport() { return transport; }
    VanillaTerrain terrain() { return terrain; }

    @Override public boolean ownerThread() { return server.isSameThread(); }
    @Override public boolean execute(Runnable work) { return ticks.later(work); }
    @Override public AutoCloseable everyTick(Runnable tick) { return ticks.every(tick); }
    @Override public void warn(String message) { warnings.accept(message); }
    @Override public BodyTransport<ServerPlayer, ItemStack> openTransport() {
        transport = new VanillaBodyTransport(warnings);
        return transport;
    }
    @Override public String chunkDelivery() { return "vanilla tracked-and-not-pending"; }

    @Override public Collection<? extends ServerPlayer> onlinePlayers() { return List.copyOf(server.getPlayerList().getPlayers()); }
    @Override public Optional<ServerPlayer> player(UUID id) { return Optional.ofNullable(server.getPlayerList().getPlayer(id)); }
    @Override public UUID id(ServerPlayer player) { return player.getUUID(); }
    @Override public String name(ServerPlayer player) { return player.getGameProfile().name(); }
    @Override public boolean online(ServerPlayer player) { return !player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) == player; }
    @Override public boolean dead(ServerPlayer player) { return !player.isAlive(); }
    @Override public boolean sleeping(ServerPlayer player) { return player.isSleeping(); }
    @Override public boolean spectator(ServerPlayer player) { return player.isSpectator(); }
    @Override public boolean sneaking(ServerPlayer player) { return player.isShiftKeyDown(); }
    @Override public boolean inVehicle(ServerPlayer player) { return player.isPassenger(); }
    @Override public int entityId(ServerPlayer player) { return player.getId(); }
    @Override public WorldPosition position(ServerPlayer player) {
        net.minecraft.world.phys.Vec3 at = player.position();
        return new WorldPosition(ModWorldKeys.key(player.level()), new Vec3(at.x(), at.y(), at.z()));
    }
    @Override public Vec3 eye(ServerPlayer player) { net.minecraft.world.phys.Vec3 eye = player.getEyePosition(); return new Vec3(eye.x(), eye.y(), eye.z()); }
    @Override public Vec3 direction(ServerPlayer player) { net.minecraft.world.phys.Vec3 look = player.getLookAngle(); return new Vec3(look.x(), look.y(), look.z()); }
    @Override public float yaw(ServerPlayer player) { return player.getYRot(); }
    @Override public float pitch(ServerPlayer player) { return player.getXRot(); }
    @Override public boolean permitted(ServerPlayer player, String node) { return permitted(player.permissions(), node); }
    @Override public void tell(ServerPlayer player, String message) { player.sendSystemMessage(ModText.component(message)); }
    @Override public boolean teleport(ServerPlayer player, WorldPosition destination, float yaw, float pitch) {
        ServerLevel level = terrain.level(destination.world());
        if (level == null) return false;
        return player.teleportTo(level, destination.point().x(), destination.point().y(), destination.point().z(), Set.of(), yaw, pitch, true);
    }

    @Override public Optional<String> resolveWorld(String reference) { return ModWorldKeys.resolve(server, reference); }
    @Override public boolean worldLoaded(String world) { return terrain.worldLoaded(world); }
    @Override public boolean chunkLoaded(String world, int chunkX, int chunkZ) { return terrain.chunkLoaded(world, chunkX, chunkZ); }
    @Override public MotionValidator.Sampler collision(String world) { return terrain.collision(world); }
    @Override public boolean chunkSent(ServerPlayer player, int chunkX, int chunkZ) {
        return player.getChunkTrackingView().contains(chunkX, chunkZ)
                && !player.connection.chunkSender.isPending(art.arcane.animus.core.virtual.ChunkPosition.key(chunkX, chunkZ));
    }
    @Override public boolean clearSight(ServerPlayer player, Vec3 eye, Vec3 direction, double distance) {
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(eye.x(), eye.y(), eye.z());
        net.minecraft.world.phys.Vec3 to = from.add(direction.x() * distance, direction.y() * distance, direction.z() * distance);
        BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }
    @Override public art.arcane.animus.core.navigation.DoorPassage.Doors doors() { return doors; }
    @Override public boolean solid(String world, int x, int y, int z) {
        ServerLevel level = terrain.level(world);
        if (level == null || level.isOutsideBuildHeight(y) || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) return false;
        return level.getBlockState(new BlockPos(x, y, z)).isSolid();
    }
    @Override public boolean registeredEntity(UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) if (level.getEntity(uuid) != null) return true;
        return false;
    }

    @Override public ItemStack item(NpcItem item, String path, List<String> errors) { return stack(server, item, path, errors); }
    @Override public ItemStack copy(ItemStack item) { return item.copy(); }
    @Override public Optional<ItemStack> emptyItem() { return Optional.of(ItemStack.EMPTY); }

    @Override public boolean interactionAllowed(ServerPlayer player, UUID npc, Interaction action, double distance) { return interactions.allowed(player, npc, action); }
    @Override public void waypointReached(UUID npc, String id, UUID route, int waypoint, WorldPosition at) {}

    /**
     * {@code animus.status} is open; every other node needs an operator or a granted permission atom, and the
     * {@code animus:admin} atom grants every node under it.
     */
    static boolean permitted(PermissionSet permissions, String node) {
        if (ModPermissions.everyone(node)) return true;
        if (art.arcane.animus.core.command.PermissionNodes.granted((String asked) -> atom(permissions, asked), node)) return true;
        return permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static boolean atom(PermissionSet permissions, String node) {
        Optional<String> atom = ModPermissions.atom(node);
        Identifier id = atom.map(Identifier::tryParse).orElse(null);
        return id != null && permissions.hasPermission(Permission.Atom.create(id));
    }

    /** A server item for a definition item, read like the text after {@code /give}; null after adding the field error. */
    static ItemStack stack(MinecraftServer server, NpcItem item, String path, List<String> errors) {
        Identifier id = Identifier.tryParse(item.id());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
            errors.add(path + ".id: unknown item " + item.id() + " on this server");
            return null;
        }
        try {
            return new ItemParser(server.registryAccess()).parse(new StringReader(item.id() + item.components().orElse(""))).createItemStack(item.count());
        } catch (CommandSyntaxException invalid) {
            errors.add(path + (item.components().isPresent() ? ".components: " : ".id: ") + invalid.getMessage());
            return null;
        }
    }
}
