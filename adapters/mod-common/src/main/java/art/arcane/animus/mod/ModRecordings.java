package art.arcane.animus.mod;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.config.RecordingSettings;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.BodyVariant;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.HologramStyle;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.recording.GhostReplay;
import art.arcane.animus.core.recording.RecordingCapture;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.recording.RecordingEvent;
import art.arcane.animus.core.recording.RecordingHost;
import art.arcane.animus.core.recording.RecordingSessions;
import art.arcane.animus.core.virtual.Vec3;
import com.mojang.authlib.properties.Property;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Recording sessions on a mod server: every player's owner is the server thread, and ghosts are packets through a
 * body transport of their own, so their watches never replace the live runtime's. Swings and door use arrive from the
 * shared packet mixins through {@link ModHooks}.
 */
final class ModRecordings implements RecordingHost<ServerPlayer, ItemStack>, AutoCloseable {
    private static final String ADMIN = "animus.admin";
    private static final String PLAYER = "minecraft:player";
    private final MinecraftServer server;
    private final ModTicks ticks;
    private final Consumer<String> info, warnings;
    private final VanillaBodyTransport ghosts;
    private final Map<UUID, Watch> watches = new HashMap<>();
    private final RecordingSessions<ServerPlayer, ItemStack> sessions;

    /** A viewer watched by the ghost transport while it has at least one ghost. */
    private static final class Watch {
        final ServerPlayer player;
        int ghosts;
        Watch(ServerPlayer player) { this.player = player; }
    }

    ModRecordings(MinecraftServer server, ModTicks ticks, AnimusRuntime runtime, Executor files, Consumer<String> info, Consumer<String> warnings) {
        this.server = server;
        this.ticks = ticks;
        this.info = info;
        this.warnings = warnings;
        ghosts = new VanillaBodyTransport(warnings);
        sessions = new RecordingSessions<>(this, runtime, files);
    }

    RecordingSessions<ServerPlayer, ItemStack> sessions() { return sessions; }

    /** The swing packet's main-hand arm swing. Server thread. */
    void swung(ServerPlayer player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) sessions.swung(player);
    }

    /** A main-hand use of a door, trapdoor, fence gate or other block with an {@code open} state, within reach. Server thread. */
    void used(ServerPlayer player, InteractionHand hand, BlockPos at) {
        if (hand != InteractionHand.MAIN_HAND || !player.isWithinBlockInteractionRange(at, 1.0)) return;
        ServerLevel level = player.level();
        if (!level.isLoaded(at)) return;
        BlockState state = level.getBlockState(at);
        if (state.hasProperty(BlockStateProperties.OPEN))
            sessions.used(player, at.getX(), at.getY(), at.getZ(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override public UUID id(ServerPlayer player) { return player.getUUID(); }
    @Override public String name(ServerPlayer player) { return player.getGameProfile().name(); }
    @Override public boolean online(ServerPlayer player) { return !player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) == player; }
    @Override public boolean dead(ServerPlayer player) { return !player.isAlive(); }
    @Override public String world(ServerPlayer player) { return ModWorldKeys.key(player.level()); }
    @Override public Optional<String> resolveWorld(String reference) { return ModWorldKeys.resolve(server, reference); }
    @Override public Vec3 position(ServerPlayer player) { return new Vec3(player.getX(), player.getY(), player.getZ()); }
    @Override public boolean admin(ServerPlayer player) { return VanillaRuntimeHost.permitted(player.permissions(), ADMIN); }
    @Override public Optional<ServerPlayer> player(UUID id) { return Optional.ofNullable(server.getPlayerList().getPlayer(id)); }
    @Override public Optional<String> knownName(UUID id) { return Optional.empty(); }

    /** The signed {@code textures} property of the vanilla game profile; empty in offline mode. */
    @Override public Optional<BodySkin> skin(ServerPlayer player) {
        for (Property property : player.getGameProfile().properties().get("textures")) {
            if (property.value() == null || property.value().isBlank() || property.signature() == null || property.signature().isBlank()) return Optional.empty();
            try { return Optional.of(new BodySkin(property.value(), property.signature())); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
        }
        return Optional.empty();
    }

    /** Equipment converts only when a slot's stack changed since the last sample. */
    @Override public Supplier<RecordingCapture.Sample> sampler(ServerPlayer player) {
        Map<EquipmentSlot, ItemStack> stacks = new EnumMap<>(EquipmentSlot.class);
        Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
        return () -> {
            for (EquipmentSlot slot : RecordingEvent.SLOTS) {
                ItemStack stack = player.getItemBySlot(vanilla(slot));
                ItemStack before = stacks.get(slot);
                if (before != null && ItemStack.matches(before, stack)) continue;
                stacks.put(slot, stack.copy());
                items.put(slot, item(stack));
            }
            return new RecordingCapture.Sample(ModWorldKeys.key(player.level()), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                    player.onGround(), player.isShiftKeyDown(), player.isSprinting(), player.isSwimming(), player.isFallFlying(), items);
        };
    }

    private static net.minecraft.world.entity.EquipmentSlot vanilla(EquipmentSlot slot) {
        return switch (slot) {
            case MAIN_HAND -> net.minecraft.world.entity.EquipmentSlot.MAINHAND;
            case OFF_HAND -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
            case HEAD -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case CHEST -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case LEGS -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            case FEET -> net.minecraft.world.entity.EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("Not a player slot: " + slot);
        };
    }

    /** A stack in the definition item shape, its component patch written like the text after {@code /give}. Empty for air. */
    private Optional<NpcItem> item(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!NpcItem.validId(id)) return Optional.empty();
        DataComponentPatch patch = stack.getComponentsPatch();
        Optional<String> components = Optional.empty();
        if (!patch.isEmpty()) {
            DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            List<String> parts = new ArrayList<>();
            DataComponentPatch.SplitResult split = patch.split();
            for (TypedDataComponent<?> component : split.added()) {
                Identifier key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
                if (key != null) encode(component, ops).ifPresent((String value) -> parts.add(key + "=" + value));
            }
            for (DataComponentType<?> removed : split.removed()) {
                Identifier key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(removed);
                if (key != null) parts.add("!" + key);
            }
            String bracketed = "[" + String.join(",", parts) + "]";
            if (!parts.isEmpty() && bracketed.length() <= NpcItem.MAX_COMPONENTS) components = Optional.of(bracketed);
        }
        return Optional.of(new NpcItem(id, Math.min(NpcItem.MAX_COUNT, stack.getCount()), components, Optional.empty()));
    }

    private static <T> Optional<String> encode(TypedDataComponent<T> component, DynamicOps<Tag> ops) {
        Codec<T> codec = component.type().codec();
        if (codec == null) return Optional.empty();
        return codec.encodeStart(ops, component.value()).result().map(Tag::toString);
    }

    @Override public void later(ServerPlayer player, Runnable work, Runnable gone) { if (!ticks.later(work)) gone.run(); }
    @Override public AutoCloseable everyTick(ServerPlayer player, Runnable tick, Runnable retired) { return ticks.every(tick); }

    @Override public Optional<String> ghostsUnavailable() { return Optional.empty(); }

    @Override public GhostReplay<ServerPlayer, ItemStack> ghost(ServerPlayer viewer, RecordingDocument document, String name, Optional<BodySkin> skin, RecordingSettings settings) {
        Watch watch = watches.get(viewer.getUUID());
        if (watch == null || watch.player != viewer) {
            watch = new Watch(viewer);
            watches.put(viewer.getUUID(), watch);
            ghosts.watch(viewer, (BodyTransport.Interaction ignored) -> {});
        }
        watch.ghosts++;
        String world = ModWorldKeys.key(viewer.level());
        BodyTraits traits = new BodyTraits(settings.ghostGlow(), settings.ghostGlow() ? Optional.of(settings.ghostColor()) : Optional.empty(), false, false, false, true, NpcAttributes.NONE, BodyVariant.NONE);
        BodySkin textures = skin.orElse(null);
        GhostReplay.Bodies<ItemStack> bodies = new GhostReplay.Bodies<>() {
            @Override public BodyTransport.Body create(NpcPose pose) {
                NpcAppearance appearance = new NpcAppearance(Map.of(), List.of(), false, pose, Optional.empty(), HologramStyle.DEFAULT, traits);
                return ghosts.createBody(UUID.randomUUID(), PLAYER, name, appearance, textures);
            }
            @Override public ItemStack item(Optional<NpcItem> item) {
                if (item.isEmpty()) return ItemStack.EMPTY;
                ItemStack stack = VanillaRuntimeHost.stack(server, item.get(), "$.item", new ArrayList<>());
                return stack == null ? ItemStack.EMPTY : stack;
            }
            @Override public String world() { return world; }
        };
        return new GhostReplay<>(ghosts, viewer, document, bodies, (Runnable task) -> ticks.later(task));
    }

    @Override public void release(ServerPlayer viewer) {
        Watch watch = watches.get(viewer.getUUID());
        if (watch == null || watch.player != viewer || --watch.ghosts > 0) return;
        watches.remove(viewer.getUUID());
        ghosts.forget(viewer);
    }

    @Override public void closeGhosts() {
        watches.clear();
        ghosts.close();
    }

    @Override public void info(String message) { info.accept(message); }
    @Override public void warn(String message) { warnings.accept(message); }

    @Override public void close() { sessions.close(); }
}
