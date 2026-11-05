package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.bukkit.gui.BukkitScreens;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import art.arcane.animus.core.presentation.NativeEntityIds;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Hologram and menu displays as exact-version packets. No server entity is constructed or registered. Packets are
 * built on the viewer's owner and written on the viewer's channel event loop. Clicks on an ID the viewer sees are
 * consumed before vanilla reads them and handed to the viewer's owner; the consumer validates them.
 */
public final class NativeDisplayAdapter implements DisplayTransport<Player> {
    /** Updates beyond this many unwritten batches are dropped; spawns and removals always queue. */
    private static final int MAXIMUM_PENDING = 256;
    private final Plugin plugin;
    private final Bindings api;
    private final BiPredicate<Player, Runnable> owner;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final String handlerName = "animus_display_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean reportedFailure = new AtomicBoolean();
    private final AnimusMetrics metrics = AnimusMetrics.current();
    private volatile boolean closed;

    private NativeDisplayAdapter(Plugin plugin, String version, BiPredicate<Player, Runnable> owner) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.owner = owner;
        this.api = new Bindings(plugin, version);
    }

    /**
     * @param owner runs work on the player's owner; false when it cannot be scheduled
     * @return an unavailable transport, with one warning, when this server build cannot be bound
     */
    public static DisplayTransport<Player> create(Plugin plugin, String exactMinecraftVersion, BiPredicate<Player, Runnable> owner) {
        if (!Set.of("26.1.2", "26.2", "26.3").contains(exactMinecraftVersion))
            return DisplayTransport.unavailable("no display packet adapter for Minecraft " + exactMinecraftVersion);
        try { return new NativeDisplayAdapter(plugin, exactMinecraftVersion, owner); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            String reason = "display packets are unavailable on this server build: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            plugin.getLogger().warning("Holograms and holographic menus unavailable: " + reason);
            return DisplayTransport.unavailable(reason);
        }
    }

    @Override public String unavailable() { return closed ? "display transport is closed" : null; }
    @Override public int reserve() { return api.ids.reserve(1); }

    @Override public void watch(Player viewer, Consumer<Click> clicks) {
        if (closed) return;
        Channel channel;
        try { channel = api.channel(viewer); }
        catch (ReflectiveOperationException | RuntimeException failure) { report(failure); return; }
        Session session = new Session(viewer, channel, clicks, metrics.viewer(viewer.getUniqueId(), viewer.getName()));
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) retire(previous, true);
        try {
            channel.eventLoop().execute(() -> {
                if (!current(session) || !channel.isActive()) return;
                try {
                    if (channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName);
                    if (channel.pipeline().get("packet_handler") == null) throw new IllegalStateException("Vanilla packet_handler is unavailable");
                    channel.pipeline().addBefore("packet_handler", handlerName, new ClickHandler(session));
                } catch (RuntimeException failure) { sessions.remove(session.viewerId, session); report(failure); }
            });
        } catch (RejectedExecutionException ignored) { sessions.remove(session.viewerId, session); }
    }

    /** A no-op when the viewer is not watched, so a disconnect race never leaves half a presentation. */
    @Override public void spawn(Player viewer, List<DisplayEntity> entities) {
        Session session = session(viewer);
        if (session == null || entities.isEmpty()) return;
        List<Object> packets = new ArrayList<>(entities.size() * 2);
        for (DisplayEntity entity : entities) {
            try {
                Object type = api.type(entity.content());
                if (type == null) continue;
                packets.add(api.addEntity.newInstance(entity.id(), entity.uuid(), entity.x(), entity.y(), entity.z(), entity.pitch(), entity.yaw(), type, 0, api.zeroVelocity, (double) entity.yaw()));
                List<Object> values = api.values(viewer, entity);
                if (!values.isEmpty()) packets.add(api.metadataPacket.newInstance(entity.id(), values));
                session.visible.add(entity.id());
            } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
        }
        write(session, packets, true);
    }

    @Override public void update(Player viewer, List<DisplayEntity> entities) {
        Session session = session(viewer);
        if (session == null || entities.isEmpty()) return;
        List<Object> packets = new ArrayList<>(entities.size() * 2);
        for (DisplayEntity entity : entities) {
            if (!session.visible.contains(entity.id())) continue;
            try {
                List<Object> values = api.values(viewer, entity);
                if (!values.isEmpty()) packets.add(api.metadataPacket.newInstance(entity.id(), values));
                packets.add(api.move(entity));
            } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
        }
        write(session, packets, false);
    }

    @Override public void remove(Player viewer, int[] ids) {
        Session session = session(viewer);
        if (session == null || ids.length == 0) return;
        for (int id : ids) session.visible.remove(id);
        try { write(session, List.of(api.removeEntities.newInstance((Object) ids.clone())), true); }
        catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
    }

    @Override public void forget(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session != null && session.viewer == viewer && sessions.remove(session.viewerId, session)) retire(session, true);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Session session : List.copyOf(sessions.values())) retire(session, true);
        sessions.clear();
    }

    private Session session(Player viewer) {
        if (closed) return null;
        Session session = sessions.get(viewer.getUniqueId());
        return session != null && session.viewer == viewer && current(session) ? session : null;
    }
    private boolean current(Session session) { return !closed && session.active.get() && sessions.get(session.viewerId) == session; }

    private void write(Session session, List<Object> packets, boolean authoritative) {
        if (packets.isEmpty() || !session.channel.isActive()) return;
        if (!authoritative && session.pending.get() >= MAXIMUM_PENDING) return;
        session.pending.incrementAndGet();
        try {
            session.channel.eventLoop().execute(() -> {
                session.pending.decrementAndGet();
                if (!session.active.get() || !session.channel.isActive()) return;
                metrics.sent(session.traffic, null, PacketKind.HOLOGRAM, packets.size());
                for (Object packet : packets) session.channel.write(packet);
                session.channel.flush();
            });
        } catch (RejectedExecutionException ignored) { session.pending.decrementAndGet(); }
    }

    /** Removes this viewer's handler and, when the client can still see them, every display it was shown. */
    private void retire(Session session, boolean despawn) {
        if (!session.active.compareAndSet(true, false)) return;
        int[] ids = session.visible.stream().mapToInt(Integer::intValue).toArray();
        session.visible.clear();
        try {
            session.channel.eventLoop().execute(() -> {
                if (despawn && ids.length > 0 && session.channel.isActive()) {
                    try { session.channel.writeAndFlush(api.removeEntities.newInstance((Object) ids)); metrics.sent(session.traffic, null, PacketKind.HOLOGRAM, 1); }
                    catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                }
                ChannelHandler handler = session.channel.pipeline().get(handlerName);
                if (handler instanceof ClickHandler clicks && clicks.session == session) session.channel.pipeline().remove(handler);
            });
        } catch (RejectedExecutionException ignored) { /* A closed channel has already discarded its client presentation. */ }
    }

    private void report(Throwable failure) {
        if (reportedFailure.compareAndSet(false, true))
            plugin.getLogger().warning("Display packet failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private static final class Session {
        private final UUID viewerId;
        private final Player viewer;
        private final Channel channel;
        private final Consumer<Click> clicks;
        private final Set<Integer> visible = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean active = new AtomicBoolean(true), clickScheduled = new AtomicBoolean();
        private final AtomicInteger pending = new AtomicInteger();
        private final Load traffic;
        private long lastClickNanos = Long.MIN_VALUE / 2; // Accessed only by the channel event loop.
        private Session(Player viewer, Channel channel, Consumer<Click> clicks, Load traffic) {
            this.viewerId = viewer.getUniqueId(); this.viewer = viewer; this.channel = channel; this.clicks = clicks; this.traffic = traffic;
        }
    }

    private final class ClickHandler extends ChannelInboundHandlerAdapter {
        private final Session session;
        private ClickHandler(Session session) { this.session = session; }

        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            boolean attack = api.attackPacket.isInstance(message);
            if (!attack && !api.interactPacket.isInstance(message)) { context.fireChannelRead(message); return; }
            int entityId = ((Number) (attack ? api.attackId : api.interactId).invoke(message)).intValue();
            if (!current(session) || !session.visible.contains(entityId)) { context.fireChannelRead(message); return; }
            try {
                if (!attack && String.valueOf(api.interactHand.invoke(message)).equals("OFF_HAND")) return;
                long now = System.nanoTime();
                if (now - session.lastClickNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS || !session.clickScheduled.compareAndSet(false, true)) { metrics.inbound(session.traffic, false); return; }
                session.lastClickNanos = now;
                metrics.inbound(session.traffic, true);
                Click click = new Click(entityId, attack, !attack && (boolean) api.interactSecondary.invoke(message), now);
                try {
                    boolean accepted = owner.test(session.viewer, () -> {
                        try { if (current(session) && session.visible.contains(entityId)) session.clicks.accept(click); }
                        catch (RuntimeException failure) { report(failure); }
                        finally { session.clickScheduled.set(false); }
                    });
                    if (!accepted) session.clickScheduled.set(false);
                } catch (RuntimeException failure) { session.clickScheduled.set(false); report(failure); }
            } finally { ReferenceCountUtil.release(message); }
        }

        @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
            if (sessions.remove(session.viewerId, session)) retire(session, false);
            context.fireChannelInactive();
        }
    }

    private static final class Bindings {
        private final NativeEntityIds ids;
        private final Object entityRegistry, blockRegistry, zeroVelocity, textType, itemType, blockType, interactionType, barrier;
        private final Method identifierParse, registryContains, registryValue, defaultState, dataValue, literal, empty, append, setStyle;
        private final Method withColor, withBold, withItalic, withUnderlined, withStrikethrough, withObfuscated, nativeStack;
        private final Object emptyStyle;
        private final Constructor<?> addEntity, removeEntities, metadataPacket, vector, movement, positionValues, vector3f;
        private final Method positionPath;
        private final boolean steppedMovement;
        private final Method craftPlayerHandle;
        private final Field playerConnection, networkConnection, channel;
        private final Class<?> attackPacket, interactPacket;
        private final Method attackId, interactId, interactHand, interactSecondary;
        private final Object sharedFlags, posInterpolation, transformInterpolation, translation, scale, billboard, brightness, viewRange,
                shadowRadius, shadowStrength, glowColor, text, lineWidth, background, opacity, styleFlags, itemStack, itemContext, blockState,
                width, height, response;

        private Bindings(Plugin plugin, String version) throws ReflectiveOperationException {
            Class<?> entityTypeClass = type("net.minecraft.world.entity.EntityType");
            Class<?> identifier = type("net.minecraft.resources.Identifier");
            Class<?> registry = type("net.minecraft.core.Registry");
            Class<?> registries = type("net.minecraft.core.registries.BuiltInRegistries");
            entityRegistry = registries.getField("ENTITY_TYPE").get(null);
            blockRegistry = registries.getField("BLOCK").get(null);
            identifierParse = identifier.getMethod("parse", String.class);
            registryContains = registry.getMethod("containsKey", identifier);
            registryValue = registry.getMethod("getValue", identifier);
            defaultState = type("net.minecraft.world.level.block.Block").getMethod("defaultBlockState");
            Class<?> allocatorOwner = type(version.equals("26.1.2") ? "net.minecraft.world.entity.Entity" : "net.minecraft.server.level.ServerLevel");
            ids = new NativeEntityIds((AtomicInteger) field(allocatorOwner, "ENTITY_COUNTER").get(null));
            Class<?> vec3 = type("net.minecraft.world.phys.Vec3");
            vector = vec3.getConstructor(double.class, double.class, double.class);
            zeroVelocity = vec3.getField("ZERO").get(null);
            addEntity = type("net.minecraft.network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, UUID.class, double.class, double.class,
                    double.class, float.class, float.class, entityTypeClass, int.class, vec3, double.class);
            removeEntities = type("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            metadataPacket = type("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
            Class<?> positionSync = type("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
            steppedMovement = version.equals("26.3");
            if (steppedMovement) {
                Class<?> path = type("net.minecraft.world.entity.PositionPath");
                positionPath = path.getMethod("of", vec3); positionValues = null;
                movement = positionSync.getConstructor(int.class, path, float.class, float.class, boolean.class);
            } else {
                Class<?> values = type("net.minecraft.world.entity.PositionMoveRotation");
                positionValues = values.getConstructor(vec3, vec3, float.class, float.class); positionPath = null;
                movement = positionSync.getConstructor(int.class, values, boolean.class);
            }
            Class<?> accessor = type("net.minecraft.network.syncher.EntityDataAccessor");
            dataValue = type("net.minecraft.network.syncher.SynchedEntityData$DataValue").getMethod("create", accessor, Object.class);
            Class<?> component = type("net.minecraft.network.chat.Component");
            Class<?> mutable = type("net.minecraft.network.chat.MutableComponent");
            Class<?> style = type("net.minecraft.network.chat.Style");
            literal = component.getMethod("literal", String.class);
            empty = component.getMethod("empty");
            append = mutable.getMethod("append", component);
            setStyle = mutable.getMethod("setStyle", style);
            emptyStyle = style.getField("EMPTY").get(null);
            withColor = style.getMethod("withColor", int.class);
            withBold = style.getMethod("withBold", Boolean.class);
            withItalic = style.getMethod("withItalic", Boolean.class);
            withUnderlined = style.getMethod("withUnderlined", Boolean.class);
            withStrikethrough = style.getMethod("withStrikethrough", Boolean.class);
            withObfuscated = style.getMethod("withObfuscated", Boolean.class);
            vector3f = type("org.joml.Vector3f").getConstructor(float.class, float.class, float.class);
            Class<?> entity = type("net.minecraft.world.entity.Entity");
            Class<?> display = type("net.minecraft.world.entity.Display");
            Class<?> textDisplay = type("net.minecraft.world.entity.Display$TextDisplay");
            Class<?> itemDisplay = type("net.minecraft.world.entity.Display$ItemDisplay");
            Class<?> blockDisplay = type("net.minecraft.world.entity.Display$BlockDisplay");
            Class<?> interaction = type("net.minecraft.world.entity.Interaction");
            sharedFlags = accessor(entity, "DATA_SHARED_FLAGS_ID");
            posInterpolation = accessor(display, "DATA_POS_ROT_INTERPOLATION_DURATION_ID");
            transformInterpolation = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID");
            translation = accessor(display, "DATA_TRANSLATION_ID"); scale = accessor(display, "DATA_SCALE_ID");
            billboard = accessor(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID"); brightness = accessor(display, "DATA_BRIGHTNESS_OVERRIDE_ID");
            viewRange = accessor(display, "DATA_VIEW_RANGE_ID"); shadowRadius = accessor(display, "DATA_SHADOW_RADIUS_ID");
            shadowStrength = accessor(display, "DATA_SHADOW_STRENGTH_ID"); glowColor = accessor(display, "DATA_GLOW_COLOR_OVERRIDE_ID");
            text = accessor(textDisplay, "DATA_TEXT_ID"); lineWidth = accessor(textDisplay, "DATA_LINE_WIDTH_ID");
            background = accessor(textDisplay, "DATA_BACKGROUND_COLOR_ID"); opacity = accessor(textDisplay, "DATA_TEXT_OPACITY_ID");
            styleFlags = accessor(textDisplay, "DATA_STYLE_FLAGS_ID");
            itemStack = accessor(itemDisplay, "DATA_ITEM_STACK_ID"); itemContext = accessor(itemDisplay, "DATA_ITEM_DISPLAY_ID");
            blockState = accessor(blockDisplay, "DATA_BLOCK_STATE_ID");
            width = accessor(interaction, "DATA_WIDTH_ID"); height = accessor(interaction, "DATA_HEIGHT_ID"); response = accessor(interaction, "DATA_RESPONSE_ID");
            textType = entityType("minecraft:text_display"); itemType = entityType("minecraft:item_display");
            blockType = entityType("minecraft:block_display"); interactionType = entityType("minecraft:interaction");
            barrier = defaultState.invoke(registryValue.invoke(blockRegistry, identifierParse.invoke(null, "minecraft:barrier")));
            String craftPackage = plugin.getServer().getClass().getPackageName();
            if (!craftPackage.startsWith("org.bukkit.craftbukkit")) throw new ClassNotFoundException("CraftBukkit implementation unavailable");
            craftPlayerHandle = type(craftPackage + ".entity.CraftPlayer").getMethod("getHandle");
            nativeStack = type(craftPackage + ".inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            playerConnection = type("net.minecraft.server.level.ServerPlayer").getField("connection");
            networkConnection = field(type("net.minecraft.server.network.ServerCommonPacketListenerImpl"), "connection");
            channel = field(type("net.minecraft.network.Connection"), "channel");
            attackPacket = type("net.minecraft.network.protocol.game.ServerboundAttackPacket");
            interactPacket = type("net.minecraft.network.protocol.game.ServerboundInteractPacket");
            attackId = attackPacket.getMethod("entityId"); interactId = interactPacket.getMethod("entityId");
            interactHand = interactPacket.getMethod("hand"); interactSecondary = interactPacket.getMethod("usingSecondaryAction");
        }

        /** The native type, or null when a mob type is unknown here and nothing is sent. */
        private Object type(DisplayEntity.Content content) throws ReflectiveOperationException {
            return switch (content) {
                case DisplayEntity.Text ignored -> textType;
                case DisplayEntity.Item ignored -> itemType;
                case DisplayEntity.Block ignored -> blockType;
                case DisplayEntity.Hitbox ignored -> interactionType;
                case DisplayEntity.Mob mob -> {
                    Object key = identifierParse.invoke(null, mob.type());
                    yield (boolean) registryContains.invoke(entityRegistry, key) ? registryValue.invoke(entityRegistry, key) : null;
                }
            };
        }

        private List<Object> values(Player viewer, DisplayEntity entity) throws ReflectiveOperationException {
            List<Object> values = new ArrayList<>();
            DisplayEntity.Content content = entity.content();
            if (content instanceof DisplayEntity.Mob) return values;
            if (content instanceof DisplayEntity.Hitbox box) {
                values.add(dataValue.invoke(null, width, box.width()));
                values.add(dataValue.invoke(null, height, box.height()));
                values.add(dataValue.invoke(null, response, Boolean.TRUE));
                return values;
            }
            DisplayEntity.Style style = entity.style();
            values.add(dataValue.invoke(null, sharedFlags, style.glow() == -1 ? (byte) 0 : (byte) 0x40));
            values.add(dataValue.invoke(null, glowColor, style.glow()));
            values.add(dataValue.invoke(null, posInterpolation, style.moveTicks()));
            values.add(dataValue.invoke(null, transformInterpolation, style.moveTicks()));
            values.add(dataValue.invoke(null, translation, vector3f.newInstance(style.translateX(), style.translateY(), style.translateZ())));
            values.add(dataValue.invoke(null, scale, vector3f.newInstance(style.scaleX(), style.scaleY(), style.scaleZ())));
            values.add(dataValue.invoke(null, billboard, style.billboard().wire()));
            values.add(dataValue.invoke(null, brightness, style.brightness()));
            values.add(dataValue.invoke(null, viewRange, style.viewRange()));
            values.add(dataValue.invoke(null, shadowRadius, style.shadowRadius()));
            values.add(dataValue.invoke(null, shadowStrength, style.shadowStrength()));
            switch (content) {
                case DisplayEntity.Text line -> {
                    values.add(dataValue.invoke(null, text, component(line.text())));
                    values.add(dataValue.invoke(null, lineWidth, line.lineWidth()));
                    values.add(dataValue.invoke(null, background, line.background()));
                    values.add(dataValue.invoke(null, opacity, (byte) line.opacity()));
                    values.add(dataValue.invoke(null, styleFlags, line.flags()));
                }
                case DisplayEntity.Item item -> {
                    ItemStack stack = BukkitScreens.displayStack(viewer.getServer(), item.item());
                    values.add(dataValue.invoke(null, itemStack, nativeStack.invoke(null, stack)));
                    values.add(dataValue.invoke(null, itemContext, (byte) item.context()));
                }
                case DisplayEntity.Block block -> values.add(dataValue.invoke(null, blockState, state(block.block())));
                default -> {}
            }
            return values;
        }

        private Object state(String key) throws ReflectiveOperationException {
            Object id;
            try { id = identifierParse.invoke(null, key.contains(":") ? key : "minecraft:" + key); }
            catch (java.lang.reflect.InvocationTargetException invalid) { return barrier; }
            if (!(boolean) registryContains.invoke(blockRegistry, id)) return barrier;
            return defaultState.invoke(registryValue.invoke(blockRegistry, id));
        }

        /** A chat component of the styled runs, so hex colours render on every client. */
        private Object component(String coloured) throws ReflectiveOperationException {
            Object root = empty.invoke(null);
            for (RichText.Run span : RichText.runs(coloured)) {
                Object style = emptyStyle;
                if (span.rgb().isPresent()) style = withColor.invoke(style, span.rgb().getAsInt());
                if (span.bold()) style = withBold.invoke(style, Boolean.TRUE);
                if (span.italic()) style = withItalic.invoke(style, Boolean.TRUE);
                if (span.underlined()) style = withUnderlined.invoke(style, Boolean.TRUE);
                if (span.strikethrough()) style = withStrikethrough.invoke(style, Boolean.TRUE);
                if (span.obfuscated()) style = withObfuscated.invoke(style, Boolean.TRUE);
                append.invoke(root, setStyle.invoke(literal.invoke(null, span.text()), style));
            }
            return root;
        }

        private Object move(DisplayEntity entity) throws ReflectiveOperationException {
            Object position = vector.newInstance(entity.x(), entity.y(), entity.z());
            if (steppedMovement) return movement.newInstance(entity.id(), positionPath.invoke(null, position), entity.yaw(), entity.pitch(), false);
            return movement.newInstance(entity.id(), positionValues.newInstance(position, zeroVelocity, entity.yaw(), entity.pitch()), false);
        }

        private Object entityType(String key) throws ReflectiveOperationException {
            Object id = identifierParse.invoke(null, key);
            if (!(boolean) registryContains.invoke(entityRegistry, id)) throw new IllegalArgumentException("Native entity type is unavailable: " + key);
            return registryValue.invoke(entityRegistry, id);
        }

        private Channel channel(Player viewer) throws ReflectiveOperationException {
            Object handle = craftPlayerHandle.invoke(viewer);
            Channel result = (Channel) channel.get(networkConnection.get(playerConnection.get(handle)));
            if (result == null || !result.isActive()) throw new IllegalStateException("Viewer is not connected");
            return result;
        }

        private static Object accessor(Class<?> owner, String name) throws ReflectiveOperationException { return field(owner, name).get(null); }
        private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field result = owner.getDeclaredField(name);
            if (!result.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner.getName() + "." + name);
            return result;
        }
    }
}
