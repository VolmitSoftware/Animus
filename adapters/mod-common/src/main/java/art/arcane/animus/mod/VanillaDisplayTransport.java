package art.arcane.animus.mod;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.presentation.NativeEntityIds;
import art.arcane.animus.mod.mixin.BlockDisplayDataAccess;
import art.arcane.animus.mod.mixin.DisplayDataAccess;
import art.arcane.animus.mod.mixin.EntityCounterAccessor;
import art.arcane.animus.mod.mixin.EntityDataAccess;
import art.arcane.animus.mod.mixin.InteractionDataAccess;
import art.arcane.animus.mod.mixin.ItemDisplayDataAccess;
import art.arcane.animus.mod.mixin.TextDisplayDataAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Hologram and menu displays built from vanilla classes on the server thread. Packets are written straight to each
 * viewer's connection. No server entity is constructed or registered. Clicks reach {@link #interact} from the shared
 * packet mixin.
 */
final class VanillaDisplayTransport implements DisplayTransport<ServerPlayer> {
    private static final class Session {
        final ServerPlayer viewer;
        final Consumer<Click> clicks;
        final Set<Integer> visible = new HashSet<>();
        long lastClickNanos = Long.MIN_VALUE / 2;
        final Load traffic;
        Session(ServerPlayer viewer, Consumer<Click> clicks, Load traffic) { this.viewer = viewer; this.clicks = clicks; this.traffic = traffic; }
    }

    private final NativeEntityIds ids = new NativeEntityIds(EntityCounterAccessor.animus$counter());
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Function<ScreenFrame.Item, ItemStack> stacks;
    private final Consumer<String> warnings;
    private final EntityType<?> textType = entityType("minecraft:text_display"), itemType = entityType("minecraft:item_display"),
            blockType = entityType("minecraft:block_display"), interactionType = entityType("minecraft:interaction");
    private final AnimusMetrics metrics = AnimusMetrics.current();
    private boolean closed, reportedFailure;

    /** @param stacks builds the stack an item display shows */
    VanillaDisplayTransport(Function<ScreenFrame.Item, ItemStack> stacks, Consumer<String> warnings) {
        this.stacks = Objects.requireNonNull(stacks);
        this.warnings = Objects.requireNonNull(warnings);
    }

    @Override public String unavailable() { return closed ? "display transport is closed" : null; }
    @Override public int reserve() { return ids.reserve(1); }

    @Override public void watch(ServerPlayer viewer, Consumer<Click> clicks) {
        if (closed || !viewer.connection.isAcceptingMessages()) return;
        Session previous = sessions.put(viewer.getUUID(), new Session(viewer, Objects.requireNonNull(clicks), metrics.viewer(viewer.getUUID(), viewer.getGameProfile().name())));
        if (previous != null && previous.viewer.connection.isAcceptingMessages() && !previous.visible.isEmpty())
            send(previous, List.of(new ClientboundRemoveEntitiesPacket(previous.visible.stream().mapToInt(Integer::intValue).toArray())));
    }

    /** A no-op when the viewer is not watched. */
    @Override public void spawn(ServerPlayer viewer, List<DisplayEntity> entities) {
        Session session = existing(viewer);
        if (session == null || entities.isEmpty()) return;
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(entities.size() * 2);
        for (DisplayEntity entity : entities) {
            try {
                EntityType<?> type = type(entity.content());
                if (type == null) continue;
                packets.add(new ClientboundAddEntityPacket(entity.id(), entity.uuid(), entity.x(), entity.y(), entity.z(), entity.pitch(), entity.yaw(), type, 0, Vec3.ZERO, entity.yaw()));
                List<SynchedEntityData.DataValue<?>> values = values(entity);
                if (!values.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(entity.id(), values));
                session.visible.add(entity.id());
            } catch (RuntimeException failure) { report(failure); }
        }
        send(session, packets);
    }

    @Override public void update(ServerPlayer viewer, List<DisplayEntity> entities) {
        Session session = existing(viewer);
        if (session == null || entities.isEmpty()) return;
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>(entities.size() * 2);
        for (DisplayEntity entity : entities) {
            if (!session.visible.contains(entity.id())) continue;
            try {
                List<SynchedEntityData.DataValue<?>> values = values(entity);
                if (!values.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(entity.id(), values));
                packets.add(BodyMotion.move(entity.id(), entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch(), false));
            } catch (RuntimeException failure) { report(failure); }
        }
        send(session, packets);
    }

    @Override public void remove(ServerPlayer viewer, int[] removed) {
        Session session = existing(viewer);
        if (session == null || removed.length == 0) return;
        for (int id : removed) session.visible.remove(id);
        send(session, List.of(new ClientboundRemoveEntitiesPacket(removed.clone())));
    }

    @Override public void forget(ServerPlayer viewer) {
        Session session = sessions.get(viewer.getUUID());
        if (session == null || session.viewer != viewer) return;
        sessions.remove(viewer.getUUID());
        retire(session);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Session session : List.copyOf(sessions.values())) retire(session);
        sessions.clear();
    }

    /** Server thread: a click on an ID this viewer sees. True consumes the packet; an off-hand use is consumed without a click. */
    boolean interact(ServerPlayer viewer, int entityId, boolean attack, String hand, boolean secondary) {
        Session session = existing(viewer);
        if (session == null || !session.visible.contains(entityId)) return false;
        if (!attack && hand.equals("OFF_HAND")) return true;
        long now = System.nanoTime();
        if (now - session.lastClickNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS) { metrics.inbound(session.traffic, false); return true; }
        session.lastClickNanos = now;
        metrics.inbound(session.traffic, true);
        try { session.clicks.accept(new Click(entityId, attack, !attack && secondary, now)); }
        catch (RuntimeException failure) { report(failure); }
        return true;
    }

    private EntityType<?> type(DisplayEntity.Content content) {
        return switch (content) {
            case DisplayEntity.Text ignored -> textType;
            case DisplayEntity.Item ignored -> itemType;
            case DisplayEntity.Block ignored -> blockType;
            case DisplayEntity.Hitbox ignored -> interactionType;
            case DisplayEntity.Mob mob -> {
                Identifier id = Identifier.tryParse(mob.type());
                yield id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id) ? BuiltInRegistries.ENTITY_TYPE.getValue(id) : null;
            }
        };
    }

    /** Constants of these types moved between versions; the registry key did not. */
    private static EntityType<?> entityType(String key) {
        Identifier id = Identifier.parse(key);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw new IllegalArgumentException("Native entity type is unavailable: " + key);
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    private List<SynchedEntityData.DataValue<?>> values(DisplayEntity entity) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        DisplayEntity.Content content = entity.content();
        if (content instanceof DisplayEntity.Mob) return values;
        if (content instanceof DisplayEntity.Hitbox box) {
            values.add(SynchedEntityData.DataValue.create(InteractionDataAccess.animus$width(), box.width()));
            values.add(SynchedEntityData.DataValue.create(InteractionDataAccess.animus$height(), box.height()));
            values.add(SynchedEntityData.DataValue.create(InteractionDataAccess.animus$response(), Boolean.TRUE));
            return values;
        }
        DisplayEntity.Style style = entity.style();
        values.add(SynchedEntityData.DataValue.create(EntityDataAccess.animus$sharedFlags(), style.glow() == -1 ? (byte) 0 : (byte) 0x40));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$glowColor(), style.glow()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$moveInterpolation(), style.moveTicks()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$transformInterpolation(), style.moveTicks()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$translation(), new Vector3f(style.translateX(), style.translateY(), style.translateZ())));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$scale(), new Vector3f(style.scaleX(), style.scaleY(), style.scaleZ())));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$billboard(), style.billboard().wire()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$brightness(), style.brightness()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$viewRange(), style.viewRange()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$shadowRadius(), style.shadowRadius()));
        values.add(SynchedEntityData.DataValue.create(DisplayDataAccess.animus$shadowStrength(), style.shadowStrength()));
        switch (content) {
            case DisplayEntity.Text line -> {
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$text(), component(line.text())));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$lineWidth(), line.lineWidth()));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$background(), line.background()));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$opacity(), (byte) line.opacity()));
                values.add(SynchedEntityData.DataValue.create(TextDisplayDataAccess.animus$styleFlags(), line.flags()));
            }
            case DisplayEntity.Item item -> {
                values.add(SynchedEntityData.DataValue.create(ItemDisplayDataAccess.animus$stack(), stacks.apply(item.item())));
                values.add(SynchedEntityData.DataValue.create(ItemDisplayDataAccess.animus$context(), (byte) item.context()));
            }
            case DisplayEntity.Block block -> values.add(SynchedEntityData.DataValue.create(BlockDisplayDataAccess.animus$state(), state(block.block())));
            default -> {}
        }
        return values;
    }

    private static BlockState state(String key) {
        Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.BARRIER.defaultBlockState();
        return BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
    }

    /** A chat component of the styled runs, so hex colours render on every client. */
    static Component component(String coloured) { return ModText.component(coloured); }

    private void send(Session session, List<Packet<ClientGamePacketListener>> packets) {
        if (!session.viewer.connection.isAcceptingMessages()) return;
        metrics.sent(session.traffic, null, PacketKind.HOLOGRAM, packets.size());
        for (Packet<ClientGamePacketListener> packet : packets) session.viewer.connection.send(packet);
    }
    private void retire(Session session) {
        int[] visible = session.visible.stream().mapToInt(Integer::intValue).toArray();
        session.visible.clear();
        if (visible.length > 0 && session.viewer.connection.isAcceptingMessages()) send(session, List.of(new ClientboundRemoveEntitiesPacket(visible)));
    }
    private Session existing(ServerPlayer viewer) {
        if (closed) return null;
        Session session = sessions.get(viewer.getUUID());
        return session != null && session.viewer == viewer ? session : null;
    }
    private void report(RuntimeException failure) {
        if (reportedFailure) return;
        reportedFailure = true;
        warnings.accept("Display packet failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
}
