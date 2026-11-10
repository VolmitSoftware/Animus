package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
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
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModeledTransportTest {
    record Viewer(UUID id) {}
    record FakeBody(UUID uuid) implements BodyTransport.Body {
        @Override public int entityId() { return uuid.hashCode(); }
        @Override public String typeKey() { return "minecraft:player"; }
        @Override public double width() { return 0.6; }
        @Override public double height() { return 1.8; }
        @Override public SeatGeometry seat() { return null; }
        @Override public int[] entityIds() { return new int[] {entityId()}; }
    }
    record FakeBones() implements BodyTransport.Bones {
        @Override public int count() { return 0; }
        @Override public int[] entityIds() { return new int[0]; }
    }

    /** Records what reaches the packet layer. */
    static final class Packets implements BodyTransport<Viewer, String> {
        final List<String> calls = new ArrayList<>();
        final Map<UUID, Consumer<Interaction>> watchers = new HashMap<>();
        private CompletionStage<Void> call(String line) { calls.add(line); return CompletableFuture.completedFuture(null); }
        @Override public Set<String> entityTypes() { return Set.of("minecraft:player"); }
        @Override public String riderUnavailable() { return null; }
        @Override public boolean presentationAvailable() { return true; }
        @Override public boolean equipmentAvailable() { return true; }
        @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) { return new FakeBody(uuid); }
        @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) { return new FakeBody(uuid); }
        @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) { return 0; }
        @Override public CompletionStage<Void> watch(Viewer viewer, Consumer<Interaction> interactions) { watchers.put(viewer.id(), interactions); return call("watch"); }
        @Override public CompletionStage<Void> spawn(Viewer viewer, BodyTransport.Body body, BodyPose pose) { return call("spawn " + name(viewer)); }
        @Override public CompletionStage<Void> move(Viewer viewer, BodyTransport.Body body, BodyPose pose) { return call("move " + name(viewer)); }
        @Override public CompletionStage<Void> passengers(Viewer viewer, BodyTransport.Body vehicle, List<? extends BodyTransport.Body> passengers, Viewer realRider, boolean authoritative) { return call("passengers"); }
        @Override public CompletionStage<Void> dragonPhase(Viewer viewer, BodyTransport.Body dragon, int phase) { return call("dragon"); }
        @Override public CompletionStage<Void> equipment(Viewer viewer, BodyTransport.Body body, Map<EquipmentSlot, String> items) { return call("equipment " + name(viewer)); }
        @Override public CompletionStage<Void> look(Viewer viewer, BodyTransport.Body body, BodyLook look) { return call("look " + name(viewer)); }
        @Override public CompletionStage<Void> hurt(Viewer viewer, BodyTransport.Body body) { return call("hurt " + name(viewer)); }
        @Override public CompletionStage<Void> despawn(Viewer viewer, BodyTransport.Body body) { return call("despawn " + name(viewer)); }
        @Override public void forget(Viewer viewer) { calls.add("forget " + name(viewer)); }
        @Override public ControlSubscription subscribeControl(Viewer viewer) { throw new UnsupportedOperationException(); }
        @Override public void placeRider(Viewer rider, double x, double y, double z) {}
        @Override public boolean bonesAvailable() { return true; }
        @Override public BodyTransport.Bones createBones(BodyTransport.Body body, List<BoneDisplay<String>> displays) { return new FakeBones(); }
        @Override public CompletionStage<Void> showBones(Viewer viewer, BodyTransport.Body body, BodyTransport.Bones bones, BodyPose pose, List<DisplayTransform> transforms, boolean hideBody) {
            return call("hide body " + name(viewer) + " " + hideBody);
        }
        @Override public CompletionStage<Void> hideBones(Viewer viewer, BodyTransport.Bones bones) { return call("hide bones " + name(viewer)); }
        @Override public CompletionStage<Void> conceal(Viewer viewer, BodyTransport.Body body, boolean hidden) { return call((hidden ? "hide body " : "show body ") + name(viewer)); }
        @Override public void close() { calls.add("close"); }
    }

    /** Records what the model plugin is asked to draw. */
    static final class Models implements ModelDriver<Viewer> {
        final List<String> calls = new ArrayList<>();
        Clicks<Viewer> clicks;
        @Override public boolean show(UUID npc, ModelEngineModule module, String name, BodyPose pose) {
            calls.add("show " + module.model() + " " + name);
            return !module.model().equals("missing");
        }
        @Override public void view(UUID npc, Viewer viewer, boolean visible) { calls.add((visible ? "view " : "unview ") + name(viewer)); }
        @Override public void move(UUID npc, BodyPose pose) { calls.add("move " + pose.x()); }
        @Override public void walking(UUID npc, boolean walking) { calls.add(walking ? "walk" : "idle"); }
        @Override public void play(UUID npc, ModelEngineModule.State state) { calls.add("play " + state.key()); }
        @Override public void glowing(UUID npc, boolean glowing) { calls.add("glow " + glowing); }
        @Override public Optional<Size> hitbox(UUID npc) { return Optional.of(new Size(4, 3)); }
        @Override public void remove(UUID npc) { calls.add("remove"); }
        @Override public void clicks(Clicks<Viewer> listener) { clicks = listener; }
        @Override public void close() { calls.add("close"); }
    }

    private static final Viewer ALEX = new Viewer(new UUID(0, 1)), BLAKE = new Viewer(new UUID(0, 2));
    private static String name(Viewer viewer) { return viewer.equals(ALEX) ? "alex" : "blake"; }
    private static final UUID DRAKE = new UUID(1, 1), GUARD = new UUID(1, 2);
    private static BodyPose at(double x) { return new BodyPose("world", x, 64, 0, 0, 0, true); }

    private final Packets packets = new Packets();
    private final Models models = new Models();
    private final Map<UUID, ModeledTransport.Shape> shapes = new HashMap<>(Map.of(DRAKE, new ModeledTransport.Shape(ModelEngineModule.of("drake"), "Drake")));
    private final ModeledTransport<Viewer, String> transport = new ModeledTransport<>(packets, models,
            (UUID npc) -> Optional.ofNullable(shapes.get(npc)), new ModeledTransport.Owner<>() {
                @Override public UUID id(Viewer viewer) { return viewer.id(); }
                @Override public void run(Viewer viewer, Runnable work) { work.run(); }
            });

    @Test void aModelledNpcIsDrawnOnceAndShownToEachViewerWithItsBodyHidden() {
        FakeBody drake = new FakeBody(DRAKE);
        transport.spawn(ALEX, drake, at(0));
        transport.spawn(BLAKE, drake, at(0));
        assertEquals(List.of("show drake Drake", "view alex", "view blake"), models.calls);
        assertEquals(List.of("spawn alex", "hide body alex", "spawn blake", "hide body blake"), packets.calls);

        packets.calls.clear();
        transport.equipment(ALEX, drake, Map.of());
        transport.look(ALEX, drake, BodyLook.named("Drake").glowing());
        assertEquals(List.of(), packets.calls, "a hidden body gets no equipment or look");
        assertEquals("glow true", models.calls.getLast());

        models.calls.clear();
        transport.despawn(ALEX, drake);
        assertEquals(List.of("unview alex"), models.calls);
        transport.despawn(BLAKE, drake);
        assertEquals(List.of("unview alex", "unview blake", "remove"), models.calls);
        assertFalse(transport.modelled(DRAKE));
    }

    @Test void movingWalksAndStandingStillGoesBackToIdle() {
        FakeBody drake = new FakeBody(DRAKE);
        transport.spawn(ALEX, drake, at(0));
        transport.spawn(BLAKE, drake, at(0));
        models.calls.clear();
        transport.move(ALEX, drake, at(1));
        transport.move(BLAKE, drake, at(1));
        assertEquals(List.of("move 1.0", "walk"), models.calls, "each viewer's move of the same pose moves the model once");
        for (int i = 0; i < ModeledTransport.WALK_GRACE_TICKS; i++) transport.tick();
        assertEquals("walk", models.calls.getLast());
        transport.tick();
        assertEquals("idle", models.calls.getLast());
    }

    @Test void hurtsSwingsAndDeathsPlayOncePerTickWhateverTheViewerCount() {
        FakeBody drake = new FakeBody(DRAKE);
        transport.spawn(ALEX, drake, at(0));
        transport.spawn(BLAKE, drake, at(0));
        models.calls.clear(); packets.calls.clear();
        transport.hurt(ALEX, drake);
        transport.hurt(BLAKE, drake);
        transport.swing(ALEX, drake);
        transport.die(ALEX, drake);
        assertEquals(List.of("play hurt", "play attack", "play death"), models.calls);
        assertEquals(List.of(), packets.calls, "a hidden body shows no hurt flash of its own");
        transport.tick();
        transport.hurt(ALEX, drake);
        assertEquals("play hurt", models.calls.getLast());
    }

    @Test void clickingTheModelIsAClickOnTheBody() {
        FakeBody drake = new FakeBody(DRAKE);
        List<BodyTransport.Interaction> seen = new ArrayList<>();
        transport.watch(ALEX, seen::add);
        transport.spawn(ALEX, drake, at(0));
        models.calls.clear();
        models.clicks.clicked(DRAKE, ALEX, false, "MAIN_HAND", true);
        models.clicks.clicked(DRAKE, ALEX, true, "MAIN_HAND", false);
        models.clicks.clicked(DRAKE, BLAKE, false, "MAIN_HAND", false);
        assertEquals(2, seen.size(), "a player who does not see the body cannot click it");
        assertSame(drake, seen.get(0).body());
        assertEquals(BodyTransport.Action.INTERACT, seen.get(0).action());
        assertTrue(seen.get(0).secondaryAction());
        assertEquals(BodyTransport.Action.ATTACK, seen.get(1).action());
        assertEquals("none", seen.get(1).hand());
        assertEquals(List.of("play interact"), models.calls, "a right click plays the interact state");

        packets.watchers.get(ALEX.id()).accept(new BodyTransport.Interaction(drake, BodyTransport.Action.INTERACT, "MAIN_HAND", false, 0));
        assertEquals(3, seen.size(), "clicks on the hidden body still reach the runtime");
    }

    @Test void clicksCountOnTheModelsHitboxAsWellAsTheBody() {
        transport.spawn(ALEX, new FakeBody(DRAKE), at(0));
        ViewerSession.HitShape shape = transport.hitShape(DRAKE).orElseThrow();
        VirtualNpcState.Snapshot npc = new VirtualNpcState.Snapshot(DRAKE, new WorldPosition("world", new Vec3(0, 64, 0)),
                Hitbox.atFeet(new Vec3(0, 64, 0), 0.6, 1.8), 20, true, 1);
        Vec3 down = new Vec3(0, -1, 0);
        assertTrue(shape.rayDistance(npc, new Vec3(1.5, 70, 0), down, 10).isPresent(), "inside the 4 wide model, outside the body");
        assertTrue(shape.rayDistance(npc, new Vec3(2.5, 70, 0), down, 10).isEmpty(), "outside both");
        assertEquals(3.0, shape.rayDistance(npc, new Vec3(0, 70, 0), down, 10).getAsDouble(), 1e-9, "the nearer of the two boxes");
        assertTrue(transport.hitShape(GUARD).isEmpty(), "an NPC without a model keeps its body's bounds");
    }

    @Test void withoutAHitboxModelClicksAreIgnored() {
        shapes.put(DRAKE, new ModeledTransport.Shape(new ModelEngineModule("drake", 1, false, false, Optional.empty(), Map.of()), "Drake"));
        List<BodyTransport.Interaction> seen = new ArrayList<>();
        transport.watch(ALEX, seen::add);
        transport.spawn(ALEX, new FakeBody(DRAKE), at(0));
        models.clicks.clicked(DRAKE, ALEX, false, "MAIN_HAND", false);
        assertTrue(seen.isEmpty());
        assertTrue(transport.hitShape(DRAKE).isEmpty());
    }

    @Test void aShownBodyKeepsItsLookAndAnUnmodelledNpcPassesThrough() {
        shapes.put(DRAKE, new ModeledTransport.Shape(new ModelEngineModule("drake", 1, true, true, Optional.empty(), Map.of()), "Drake"));
        FakeBody drake = new FakeBody(DRAKE), guard = new FakeBody(GUARD);
        transport.spawn(ALEX, drake, at(0));
        transport.spawn(ALEX, guard, at(0));
        transport.equipment(ALEX, drake, Map.of());
        transport.hurt(ALEX, guard);
        assertEquals(List.of("spawn alex", "spawn alex", "equipment alex", "hurt alex"), packets.calls);
        assertEquals(List.of("show drake Drake", "view alex"), models.calls);
    }

    @Test void aChangedModelIsRedrawnWhenTheBodyRespawns() {
        FakeBody drake = new FakeBody(DRAKE);
        transport.spawn(ALEX, drake, at(0));
        transport.spawn(BLAKE, drake, at(0));
        shapes.put(DRAKE, new ModeledTransport.Shape(ModelEngineModule.of("wyvern"), "Drake"));
        models.calls.clear();
        transport.despawn(ALEX, drake);
        transport.spawn(ALEX, new FakeBody(DRAKE), at(0));
        assertEquals(List.of("unview alex", "remove", "show wyvern Drake", "view blake", "view alex"), models.calls);
    }

    @Test void aModelThePluginCannotDrawLeavesTheBodyShowing() {
        shapes.put(DRAKE, new ModeledTransport.Shape(ModelEngineModule.of("missing"), "Drake"));
        FakeBody drake = new FakeBody(DRAKE);
        transport.spawn(ALEX, drake, at(0));
        transport.equipment(ALEX, drake, Map.of());
        assertEquals(List.of("spawn alex", "equipment alex"), packets.calls);
        assertFalse(transport.modelled(DRAKE));
    }

    @Test void forgettingAViewerAndClosingReleaseTheirModels() {
        transport.spawn(ALEX, new FakeBody(DRAKE), at(0));
        models.calls.clear();
        transport.forget(ALEX);
        assertEquals(List.of("unview alex", "remove"), models.calls);
        transport.close();
        assertEquals("close", models.calls.getLast());
        assertEquals("close", packets.calls.getLast());
    }
}
