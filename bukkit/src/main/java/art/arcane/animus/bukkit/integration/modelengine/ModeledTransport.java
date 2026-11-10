package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.presentation.BoneDisplay;
import art.arcane.animus.core.rig.DisplayTransform;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.ViewerSession;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Packet bodies with a model drawn over them. An NPC whose definition, or a runtime override, names a model gets it
 * from the {@link ModelDriver} for as long as any viewer sees its body; the body itself is hidden unless the module
 * asks to show it, and equipment and look updates for a hidden body are dropped. Walking, arm swings, hurts, deaths
 * and right clicks become the model's states, and a click on the model's hitbox reaches the runtime as a click on the
 * body. Everything else passes through. Call every method on the viewer's owner, as {@link BodyTransport} requires.
 */
public final class ModeledTransport<P, I> implements BodyTransport<P, I> {
    /** What an NPC shows: its model settings and the name a nametag bone carries. */
    public record Shape(ModelEngineModule module, String name) {}
    /** The platform's view of a viewer: its id, and running work on its owner. */
    public interface Owner<P> {
        UUID id(P viewer);
        void run(P viewer, Runnable work);
    }

    /** Ticks without a position change before a walking model goes back to idle. */
    static final int WALK_GRACE_TICKS = 3;

    private static final class Seen<P> {
        final P viewer; final BodyTransport.Body body;
        boolean spawned;
        boolean hidden;
        Seen(P viewer, BodyTransport.Body body) { this.viewer = viewer; this.body = body; }
    }
    private static final class Npc<P> {
        final Map<UUID, Seen<P>> seen = new LinkedHashMap<>();
        Shape shape;
        BodyPose pose;
        boolean walking;
        long movedAt;
        final Map<ModelEngineModule.State, Long> playedAt = new HashMap<>();
    }

    private final BodyTransport<P, I> inner;
    private final ModelDriver<P> driver;
    private final Function<UUID, Optional<Shape>> shapes;
    private final Owner<P> owner;
    private final Map<UUID, Npc<P>> npcs = new HashMap<>();
    private final Map<UUID, Consumer<Interaction>> watchers = new HashMap<>();
    private long tick;

    public ModeledTransport(BodyTransport<P, I> inner, ModelDriver<P> driver, Function<UUID, Optional<Shape>> shapes, Owner<P> owner) {
        this.inner = Objects.requireNonNull(inner); this.driver = Objects.requireNonNull(driver);
        this.shapes = Objects.requireNonNull(shapes); this.owner = Objects.requireNonNull(owner);
        driver.clicks(this::clicked);
    }

    /** Once per server tick: models that stopped moving go back to idle. */
    public void tick() {
        tick++;
        for (Map.Entry<UUID, Npc<P>> entry : npcs.entrySet()) {
            Npc<P> npc = entry.getValue();
            if (npc.shape != null && npc.walking && tick - npc.movedAt > WALK_GRACE_TICKS) {
                npc.walking = false;
                driver.walking(entry.getKey(), false);
            }
        }
    }

    /** Looks the NPC's shape up again and redraws it for everyone who sees its body, as a runtime override needs. */
    public void refresh(UUID npc) {
        Npc<P> known = npcs.get(npc);
        if (known == null) return;
        detach(npc, known);
        attach(npc, known, shapes.apply(npc));
    }

    /** Whether the NPC shows a model right now. */
    public boolean modelled(UUID npc) {
        Npc<P> known = npcs.get(npc);
        return known != null && known.shape != null;
    }

    @Override public CompletionStage<Void> watch(P viewer, Consumer<Interaction> interactions) {
        Objects.requireNonNull(interactions);
        UUID id = owner.id(viewer);
        Consumer<Interaction> wrapped = (Interaction interaction) -> {
            if (interaction.action() == Action.INTERACT) once(interaction.body().uuid(), ModelEngineModule.State.INTERACT);
            interactions.accept(interaction);
        };
        watchers.put(id, wrapped);
        return inner.watch(viewer, wrapped);
    }

    @Override public CompletionStage<Void> spawn(P viewer, BodyTransport.Body body, BodyPose pose) {
        UUID npcId = body.uuid(), viewerId = owner.id(viewer);
        Npc<P> npc = npcs.computeIfAbsent(npcId, (UUID ignored) -> new Npc<>());
        Seen<P> seen = new Seen<>(viewer, body);
        Seen<P> previous = npc.seen.put(viewerId, seen);
        if (previous != null && npc.shape != null) driver.view(npcId, previous.viewer, false);
        if (npc.seen.size() == 1 || npc.pose == null) npc.pose = pose;
        // A definition edit respawns the body, so each spawn picks up a changed or removed model.
        Optional<Shape> wanted = shapes.apply(npcId);
        if (!Objects.equals(wanted.orElse(null), npc.shape)) { detach(npcId, npc); attach(npcId, npc, wanted); }
        else if (npc.shape != null) driver.view(npcId, viewer, true);
        CompletionStage<Void> spawned = inner.spawn(viewer, body, pose);
        spawned.whenComplete((Void nothing, Throwable failure) -> {
            if (failure == null) owner.run(viewer, () -> {
                if (npcs.get(npcId) != npc || npc.seen.get(viewerId) != seen) return;
                seen.spawned = true;
                if (npc.shape != null && !npc.shape.module().showBody()) hide(seen);
            });
        });
        return spawned;
    }

    @Override public CompletionStage<Void> move(P viewer, BodyTransport.Body body, BodyPose pose) {
        Npc<P> npc = npcs.get(body.uuid());
        if (npc != null && npc.shape != null && !pose.equals(npc.pose)) {
            BodyPose before = npc.pose;
            npc.pose = pose;
            driver.move(body.uuid(), pose);
            if (before == null || before.x() != pose.x() || before.z() != pose.z() || before.y() != pose.y()) {
                npc.movedAt = tick;
                if (!npc.walking) { npc.walking = true; driver.walking(body.uuid(), true); }
            }
        }
        return inner.move(viewer, body, pose);
    }

    @Override public CompletionStage<Void> despawn(P viewer, BodyTransport.Body body) {
        UUID npcId = body.uuid();
        Npc<P> npc = npcs.get(npcId);
        if (npc != null) {
            UUID viewerId = owner.id(viewer);
            Seen<P> seen = npc.seen.get(viewerId);
            if (seen != null && seen.body == body) {
                npc.seen.remove(viewerId);
                if (npc.shape != null) driver.view(npcId, viewer, false);
                if (npc.seen.isEmpty()) {
                    if (npc.shape != null) driver.remove(npcId);
                    npcs.remove(npcId);
                }
            }
        }
        return inner.despawn(viewer, body);
    }

    @Override public void forget(P viewer) {
        UUID viewerId = owner.id(viewer);
        watchers.remove(viewerId);
        for (UUID npcId : List.copyOf(npcs.keySet())) {
            Npc<P> npc = npcs.get(npcId);
            if (npc.seen.remove(viewerId) == null) continue;
            if (npc.shape != null) driver.view(npcId, viewer, false);
            if (npc.seen.isEmpty()) {
                if (npc.shape != null) driver.remove(npcId);
                npcs.remove(npcId);
            }
        }
        inner.forget(viewer);
    }

    @Override public CompletionStage<Void> hurt(P viewer, BodyTransport.Body body) {
        if (!modelled(body.uuid())) return inner.hurt(viewer, body);
        once(body.uuid(), ModelEngineModule.State.HURT);
        return hidden(body.uuid()) ? done() : inner.hurt(viewer, body);
    }
    @Override public CompletionStage<Void> die(P viewer, BodyTransport.Body body) {
        if (!modelled(body.uuid())) return inner.die(viewer, body);
        once(body.uuid(), ModelEngineModule.State.DEATH);
        return hidden(body.uuid()) ? done() : inner.die(viewer, body);
    }
    @Override public CompletionStage<Void> swing(P viewer, BodyTransport.Body body) {
        if (!modelled(body.uuid())) return inner.swing(viewer, body);
        once(body.uuid(), ModelEngineModule.State.ATTACK);
        return hidden(body.uuid()) ? done() : inner.swing(viewer, body);
    }
    @Override public CompletionStage<Void> look(P viewer, BodyTransport.Body body, BodyLook look) {
        if (!hidden(body.uuid())) return inner.look(viewer, body, look);
        driver.glowing(body.uuid(), look.traits().glowing());
        return done();
    }
    /** A click counts on the model's hitbox as well as the body's own bounds, with the same reach and sight checks. */
    @Override public Optional<ViewerSession.HitShape> hitShape(UUID npc) {
        Npc<P> known = npcs.get(npc);
        if (known == null || known.shape == null || !known.shape.module().hitbox()) return Optional.empty();
        return driver.hitbox(npc).map((ModelDriver.Size size) -> (VirtualNpcState.Snapshot snapshot, Vec3 origin, Vec3 direction, double reach) -> {
            OptionalDouble model = Hitbox.atFeet(snapshot.position().point(), size.width(), size.height()).rayDistance(origin, direction, reach);
            OptionalDouble body = snapshot.bounds().rayDistance(origin, direction, reach);
            if (model.isEmpty()) return body;
            return body.isPresent() && body.getAsDouble() < model.getAsDouble() ? body : model;
        });
    }
    @Override public CompletionStage<Void> equipment(P viewer, BodyTransport.Body body, Map<EquipmentSlot, I> items) {
        return hidden(body.uuid()) ? done() : inner.equipment(viewer, body, items);
    }

    @Override public void close() {
        try { driver.close(); }
        finally { npcs.clear(); watchers.clear(); inner.close(); }
    }

    private void attach(UUID npcId, Npc<P> npc, Optional<Shape> shape) {
        // A model the plugin cannot draw leaves the body showing.
        if (shape.isEmpty() || npc.pose == null || !driver.show(npcId, shape.get().module(), shape.get().name(), npc.pose)) return;
        npc.shape = shape.get();
        npc.walking = false;
        npc.playedAt.clear();
        for (Seen<P> seen : npc.seen.values()) {
            driver.view(npcId, seen.viewer, true);
            if (seen.spawned && !npc.shape.module().showBody()) hide(seen);
        }
    }

    private void detach(UUID npcId, Npc<P> npc) {
        if (npc.shape == null) return;
        driver.remove(npcId);
        npc.shape = null;
        for (Seen<P> seen : npc.seen.values()) {
            if (!seen.hidden) continue;
            seen.hidden = false;
            inner.conceal(seen.viewer, seen.body, false);
        }
    }

    /** Hides the body from one viewer; a failed hide leaves it showing, so the NPC is never invisible without its model. */
    private void hide(Seen<P> seen) {
        if (seen.hidden) return;
        seen.hidden = true;
        inner.conceal(seen.viewer, seen.body, true).whenComplete((Void nothing, Throwable failure) -> {
            if (failure != null) owner.run(seen.viewer, () -> seen.hidden = false);
        });
    }


    private boolean hidden(UUID npc) {
        Npc<P> known = npcs.get(npc);
        return known != null && known.shape != null && !known.shape.module().showBody();
    }

    /** One play per NPC per tick, however many viewers report the same hurt or swing. */
    private void once(UUID npcId, ModelEngineModule.State state) {
        Npc<P> npc = npcs.get(npcId);
        if (npc == null || npc.shape == null) return;
        Long last = npc.playedAt.put(state, tick);
        if (last != null && last == tick) return;
        driver.play(npcId, state);
    }

    private void clicked(UUID npcId, P player, boolean attack, String hand, boolean secondary) {
        Npc<P> npc = npcs.get(npcId);
        if (npc == null || npc.shape == null || !npc.shape.module().hitbox()) return;
        UUID viewerId = owner.id(player);
        Seen<P> seen = npc.seen.get(viewerId);
        Consumer<Interaction> watcher = watchers.get(viewerId);
        if (seen == null || !seen.spawned || watcher == null) return;
        watcher.accept(new Interaction(seen.body, attack ? Action.ATTACK : Action.INTERACT, attack ? "none" : hand, !attack && secondary, System.nanoTime()));
    }

    private static CompletionStage<Void> done() { return CompletableFuture.completedFuture(null); }

    // ---- Pass-through ----

    @Override public Set<String> entityTypes() { return inner.entityTypes(); }
    @Override public String riderUnavailable() { return inner.riderUnavailable(); }
    @Override public boolean presentationAvailable() { return inner.presentationAvailable(); }
    @Override public boolean equipmentAvailable() { return inner.equipmentAvailable(); }
    @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) { return inner.createBody(uuid, typeKey, profileName, skin); }
    @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) { return inner.createBody(uuid, typeKey, name, appearance, skin); }
    @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) { return inner.presentationIds(uuid, typeKey, name, appearance); }
    @Override public CompletionStage<Void> passengers(P viewer, BodyTransport.Body vehicle, List<? extends BodyTransport.Body> passengers, P realRider, boolean authoritative) {
        return inner.passengers(viewer, vehicle, passengers, realRider, authoritative);
    }
    @Override public CompletionStage<Void> dragonPhase(P viewer, BodyTransport.Body dragon, int phase) { return inner.dragonPhase(viewer, dragon, phase); }
    @Override public CompletionStage<Void> relabel(P viewer, BodyTransport.Body body, List<String> rows) { return inner.relabel(viewer, body, rows); }
    @Override public CompletionStage<Void> attributes(P viewer, BodyTransport.Body body, NpcAttributes attributes) { return inner.attributes(viewer, body, attributes); }
    @Override public ControlSubscription subscribeControl(P viewer) { return inner.subscribeControl(viewer); }
    @Override public void placeRider(P rider, double x, double y, double z) { inner.placeRider(rider, x, y, z); }
    @Override public boolean bonesAvailable() { return inner.bonesAvailable(); }
    @Override public Bones createBones(BodyTransport.Body body, List<BoneDisplay<I>> displays) { return inner.createBones(body, displays); }
    @Override public CompletionStage<Void> showBones(P viewer, BodyTransport.Body body, Bones bones, BodyPose pose, List<DisplayTransform> transforms, boolean hideBody) {
        return inner.showBones(viewer, body, bones, pose, transforms, hideBody);
    }
    @Override public CompletionStage<Void> moveBones(P viewer, Bones bones, int[] indices, DisplayTransform[] transforms, int[] durations) {
        return inner.moveBones(viewer, bones, indices, transforms, durations);
    }
    @Override public CompletionStage<Void> restyleBones(P viewer, Bones bones, int[] indices, List<BoneDisplay<I>> displays) { return inner.restyleBones(viewer, bones, indices, displays); }
    @Override public CompletionStage<Void> hideBones(P viewer, Bones bones) { return inner.hideBones(viewer, bones); }
    @Override public CompletionStage<Void> conceal(P viewer, BodyTransport.Body body, boolean hidden) { return inner.conceal(viewer, body, hidden); }
}
