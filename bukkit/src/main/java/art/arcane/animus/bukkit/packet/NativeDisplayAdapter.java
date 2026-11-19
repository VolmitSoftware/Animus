package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.AnimusLog;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Load;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.bukkit.gui.BukkitScreens;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.presentation.DisplayTransport;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
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

import static art.arcane.animus.bukkit.packet.NativeBindings.accessor;
import static art.arcane.animus.bukkit.packet.NativeBindings.type;

/**
 * Hologram and menu displays as exact-version packets. No server entity is constructed or registered. Packets are
 * built on the viewer's owner and written on the viewer's channel event loop. Clicks on an ID the viewer sees are
 * consumed before vanilla reads them and handed to the viewer's owner; the consumer validates them.
 */
public final class NativeDisplayAdapter implements DisplayTransport<Player> {
    /** Updates beyond this many unwritten batches are dropped; spawns and removals always queue. */
    private static final int MAXIMUM_PENDING = 256;
    private final Plugin plugin;
    private final NativeBindings shared;
    private final Bindings api;
    private final BiPredicate<Player, Runnable> owner;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean reportedFailure = new AtomicBoolean();
    private final AnimusMetrics metrics = AnimusMetrics.current();
    private volatile boolean closed;

    private NativeDisplayAdapter(Plugin plugin, String version, BiPredicate<Player, Runnable> owner) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.owner = owner;
        shared = NativeBindings.bind(plugin, version);
        api = new Bindings(shared);
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
    @Override public int reserve() { return shared.ids.reserve(1); }

    @Override public void watch(Player viewer, Consumer<Click> clicks) {
        if (closed) return;
        Channel channel;
        try { channel = shared.channel(viewer); }
        catch (ReflectiveOperationException | RuntimeException failure) { report(failure); return; }
        Session session = new Session(viewer, channel, clicks, metrics.viewer(viewer.getUniqueId(), viewer.getName()));
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) retire(previous, true);
        try {
            channel.eventLoop().execute(() -> {
                if (!current(session) || !channel.isActive()) return;
                try { ViewerPipeline.attach(channel, this, new ClickHandler(session)); }
                catch (RuntimeException failure) { sessions.remove(session.viewerId, session); report(failure); }
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
                Object type = api.nativeType(entity.content());
                if (type == null) continue;
                packets.add(shared.addEntity.newInstance(entity.id(), entity.uuid(), entity.x(), entity.y(), entity.z(), entity.pitch(), entity.yaw(), type, 0, shared.zeroVelocity, (double) entity.yaw()));
                List<Object> values = api.values(viewer, entity);
                if (!values.isEmpty()) packets.add(shared.metadataPacket.newInstance(entity.id(), values));
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
                if (!values.isEmpty()) packets.add(shared.metadataPacket.newInstance(entity.id(), values));
                packets.add(shared.move(entity.id(), entity.x(), entity.y(), entity.z(), entity.yaw(), entity.pitch(), false));
            } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
        }
        write(session, packets, false);
    }

    @Override public void remove(Player viewer, int[] ids) {
        Session session = session(viewer);
        if (session == null || ids.length == 0) return;
        for (int id : ids) session.visible.remove(id);
        try { write(session, List.of(shared.removeEntities.newInstance((Object) ids.clone())), true); }
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

    /** Removes this viewer's tap and, when the client can still see them, every display it was shown. */
    private void retire(Session session, boolean despawn) {
        if (!session.active.compareAndSet(true, false)) return;
        int[] ids = session.visible.stream().mapToInt(Integer::intValue).toArray();
        session.visible.clear();
        try {
            session.channel.eventLoop().execute(() -> {
                if (despawn && ids.length > 0 && session.channel.isActive()) {
                    try { session.channel.writeAndFlush(shared.removeEntities.newInstance((Object) ids)); metrics.sent(session.traffic, null, PacketKind.HOLOGRAM, 1); }
                    catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                }
                ViewerPipeline.detach(session.channel, (ViewerPipeline.Tap tap) -> tap instanceof ClickHandler clicks && clicks.session == session);
            });
        } catch (RejectedExecutionException ignored) { /* A closed channel has already discarded its client presentation. */ }
    }

    private void report(Throwable failure) {
        if (reportedFailure.compareAndSet(false, true))
            AnimusLog.shared().routine("viewers had display packet failures", String.valueOf(System.identityHashCode(this)),
                    "Display packet failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage(), plugin.getLogger()::warning);
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

    /** This adapter's tap on the viewer's connection: clicks on its displays. */
    private final class ClickHandler implements ViewerPipeline.Tap {
        private final Session session;
        private ClickHandler(Session session) { this.session = session; }

        @Override public boolean read(Object message) throws Exception {
            boolean attack = shared.attackPacket.isInstance(message);
            if (!attack && !shared.interactPacket.isInstance(message)) return false;
            int entityId = ((Number) (attack ? shared.attackId : shared.interactId).invoke(message)).intValue();
            if (!current(session) || !session.visible.contains(entityId)) return false;
            try {
                if (!attack && String.valueOf(shared.interactHand.invoke(message)).equals("OFF_HAND")) return true;
                long now = System.nanoTime();
                if (now - session.lastClickNanos < BodyRules.MIN_INPUT_INTERVAL_NANOS || !session.clickScheduled.compareAndSet(false, true)) { metrics.inbound(session.traffic, false); return true; }
                session.lastClickNanos = now;
                metrics.inbound(session.traffic, true);
                Click click = new Click(entityId, attack, !attack && (boolean) shared.interactSecondary.invoke(message), now);
                try {
                    boolean accepted = owner.test(session.viewer, () -> {
                        try { if (current(session) && session.visible.contains(entityId)) session.clicks.accept(click); }
                        catch (RuntimeException failure) { report(failure); }
                        finally { session.clickScheduled.set(false); }
                    });
                    if (!accepted) session.clickScheduled.set(false);
                } catch (RuntimeException failure) { session.clickScheduled.set(false); report(failure); }
            } finally { ReferenceCountUtil.release(message); }
            return true;
        }

        @Override public void closed() { if (sessions.remove(session.viewerId, session)) retire(session, false); }
    }

    /** What only displays bind, on top of the shared handles. */
    private static final class Bindings {
        private final NativeBindings shared;
        private final Object blockRegistry, textType, itemType, blockType, interactionType, barrier;
        private final Method defaultState, literal, empty, append, setStyle;
        private final Method withColor, withBold, withItalic, withUnderlined, withStrikethrough, withObfuscated, nativeStack;
        private final Object emptyStyle;
        private final Constructor<?> vector3f;
        private final Object sharedFlags, posInterpolation, transformInterpolation, translation, scale, billboard, brightness, viewRange,
                shadowRadius, shadowStrength, glowColor, text, lineWidth, background, opacity, styleFlags, itemStack, itemContext, blockState,
                width, height, response;

        private Bindings(NativeBindings shared) throws ReflectiveOperationException {
            this.shared = shared;
            blockRegistry = type("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
            defaultState = type("net.minecraft.world.level.block.Block").getMethod("defaultBlockState");
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
            textType = shared.entityType("minecraft:text_display"); itemType = shared.entityType("minecraft:item_display");
            blockType = shared.entityType("minecraft:block_display"); interactionType = shared.entityType("minecraft:interaction");
            barrier = defaultState.invoke(shared.registryValue.invoke(blockRegistry, shared.identifierParse.invoke(null, "minecraft:barrier")));
            nativeStack = shared.nativeStack();
        }

        /** The native type, or null when a mob type is unknown here and nothing is sent. */
        private Object nativeType(DisplayEntity.Content content) throws ReflectiveOperationException {
            return switch (content) {
                case DisplayEntity.Text ignored -> textType;
                case DisplayEntity.Item ignored -> itemType;
                case DisplayEntity.Block ignored -> blockType;
                case DisplayEntity.Hitbox ignored -> interactionType;
                case DisplayEntity.Mob mob -> {
                    try { yield shared.entityType(mob.type()); }
                    catch (IllegalArgumentException unknown) { yield null; }
                }
            };
        }

        private List<Object> values(Player viewer, DisplayEntity entity) throws ReflectiveOperationException {
            List<Object> values = new ArrayList<>();
            DisplayEntity.Content content = entity.content();
            if (content instanceof DisplayEntity.Mob) return values;
            if (content instanceof DisplayEntity.Hitbox box) {
                values.add(shared.dataValue.invoke(null, width, box.width()));
                values.add(shared.dataValue.invoke(null, height, box.height()));
                values.add(shared.dataValue.invoke(null, response, Boolean.TRUE));
                return values;
            }
            DisplayEntity.Style style = entity.style();
            values.add(shared.dataValue.invoke(null, sharedFlags, style.glow() == -1 ? (byte) 0 : (byte) 0x40));
            values.add(shared.dataValue.invoke(null, glowColor, style.glow()));
            values.add(shared.dataValue.invoke(null, posInterpolation, style.moveTicks()));
            values.add(shared.dataValue.invoke(null, transformInterpolation, style.moveTicks()));
            values.add(shared.dataValue.invoke(null, translation, vector3f.newInstance(style.translateX(), style.translateY(), style.translateZ())));
            values.add(shared.dataValue.invoke(null, scale, vector3f.newInstance(style.scaleX(), style.scaleY(), style.scaleZ())));
            values.add(shared.dataValue.invoke(null, billboard, style.billboard().wire()));
            values.add(shared.dataValue.invoke(null, brightness, style.brightness()));
            values.add(shared.dataValue.invoke(null, viewRange, style.viewRange()));
            values.add(shared.dataValue.invoke(null, shadowRadius, style.shadowRadius()));
            values.add(shared.dataValue.invoke(null, shadowStrength, style.shadowStrength()));
            switch (content) {
                case DisplayEntity.Text line -> {
                    values.add(shared.dataValue.invoke(null, text, component(line.text())));
                    values.add(shared.dataValue.invoke(null, lineWidth, line.lineWidth()));
                    values.add(shared.dataValue.invoke(null, background, line.background()));
                    values.add(shared.dataValue.invoke(null, opacity, (byte) line.opacity()));
                    values.add(shared.dataValue.invoke(null, styleFlags, line.flags()));
                }
                case DisplayEntity.Item item -> {
                    ItemStack stack = BukkitScreens.displayStack(viewer.getServer(), item.item());
                    values.add(shared.dataValue.invoke(null, itemStack, nativeStack.invoke(null, stack)));
                    values.add(shared.dataValue.invoke(null, itemContext, (byte) item.context()));
                }
                case DisplayEntity.Block block -> values.add(shared.dataValue.invoke(null, blockState, state(block.block())));
                default -> {}
            }
            return values;
        }

        private Object state(String key) throws ReflectiveOperationException {
            Object id;
            try { id = shared.identifierParse.invoke(null, MinecraftIds.normalize(key)); }
            catch (java.lang.reflect.InvocationTargetException invalid) { return barrier; }
            if (!(boolean) shared.registryContains.invoke(blockRegistry, id)) return barrier;
            return defaultState.invoke(shared.registryValue.invoke(blockRegistry, id));
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
    }
}
