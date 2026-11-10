package art.arcane.animus.core.runtime;

import art.arcane.animus.core.editor.EditorError;
import art.arcane.animus.core.editor.EditorPreviews;
import art.arcane.animus.core.mount.SeatGeometry;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ViewerPreviewsTest {
    private static final String WORLD = "world-key";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BLAKE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final WorldPosition NEAR = new WorldPosition(WORLD, new Vec3(1, 64, 2));
    private static final WorldPosition FAR = new WorldPosition(WORLD, new Vec3(500, 64, 500));
    private static final Function<NpcDefinition, DefinitionSpec<String>> PARSE = (NpcDefinition definition) ->
            DefinitionSpec.parse(definition, Set.of("minecraft:player"), (String reference) -> WORLD, (art.arcane.animus.core.npc.NpcItem item, String path, List<String> errors) -> item.id());

    @TempDir Path root;
    private DocumentStore store;
    private ViewerPreviews previews;
    private final FakeTransport packets = new FakeTransport();
    private final List<String> ended = new ArrayList<>();

    @BeforeEach void open() throws Exception {
        store = new DocumentStore(root);
        previews = new ViewerPreviews(Runnable::run, store);
    }

    @AfterEach void close() throws Exception { previews.close(); store.close(); }

    private Consumer<String> ending(String id) { return (String state) -> ended.add(id + ":" + state); }

    private int update(PreviewBodies<String, String> bodies, String player, WorldPosition at) {
        return bodies.update(player, packets, at, 48, (art.arcane.animus.core.virtual.ChunkPosition chunk) -> true, PARSE, Runnable::run, 0, 64);
    }

    private boolean hides(UUID viewer, NpcRuntime.ActiveNpc npc) { return previews.hides(viewer, npc.id(), npc.state().npcId()); }

    private static void await(CompletionStage<Void> stage) throws Exception { stage.toCompletableFuture().get(5, TimeUnit.SECONDS); }

    @Test void present_showsCandidateToOneViewer_othersKeepLive() throws Exception {
        NpcDefinition guard = DefinitionQueueTest.definition("guard", "Preview Guard", 2);
        CompletionStage<Void> presented = previews.present("prev_a", ALEX, List.of(guard), ending("prev_a"));
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX), blake = new PreviewBodies<>(previews, BLAKE);
        assertEquals(1, update(alex, "alex", NEAR));
        assertEquals(0, update(blake, "blake", NEAR));
        await(presented);

        assertTrue(previews.hides(ALEX, "guard", UUID.randomUUID()), "the live guard is replaced for the viewer");
        assertTrue(previews.hides(ALEX, "other", guard.uuid()), "matching by UUID as well");
        assertFalse(previews.hides(BLAKE, "guard", guard.uuid()), "other players keep the live guard");
        assertEquals(List.of("Preview Guard"), packets.spawnedNames("alex"));
        assertEquals(List.of(), packets.spawnedNames("blake"));
        assertNotEquals(guard.uuid(), packets.visible.get("alex").iterator().next().uuid(), "a fresh identity, so the leaving live body never collides");
    }

    @Test void replace_swapsPresentedSet() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        previews.present("prev_a", ALEX, List.of(DefinitionQueueTest.definition("guard", "First", 2)), ending("prev_a"));
        update(alex, "alex", NEAR);
        CompletionStage<Void> replaced = previews.present("prev_a", ALEX,
                List.of(DefinitionQueueTest.definition("guard", "Second", 3), DefinitionQueueTest.definition("baker", "Baker", 1)), ending("prev_a"));
        update(alex, "alex", NEAR);
        await(replaced);

        assertEquals(Set.of("Second", "Baker"), new HashSet<>(packets.spawnedNames("alex")));
        assertEquals(1, packets.despawns);
        assertEquals(List.of(), ended, "a replace is not an end");
        assertTrue(previews.hides(ALEX, "baker", UUID.randomUUID()));
    }

    @Test void end_restoresLiveView() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        await(present(alex, "prev_a", "guard"));
        previews.end("prev_a");
        update(alex, "alex", NEAR);

        assertFalse(previews.hides(ALEX, "guard", UUID.randomUUID()));
        assertEquals(List.of(), packets.spawnedNames("alex"));
        assertEquals(0, alex.shown());
        assertEquals(0, alex.reservedIds());
    }

    @Test void left_endsWithPlayerLeft() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        await(present(alex, "prev_a", "guard"));
        previews.left(ALEX);
        previews.left(BLAKE);

        assertEquals(List.of("prev_a:player-left"), ended);
        assertNull(previews.staged(ALEX));
    }

    @Test void anotherPreviewForTheSameViewer_endsTheEarlierOne() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        await(present(alex, "prev_a", "guard"));
        await(present(alex, "prev_b", "baker"));

        assertEquals(List.of("prev_a:ended"), ended);
        assertFalse(previews.hides(ALEX, "guard", UUID.randomUUID()));
        assertEquals(List.of("baker"), packets.spawnedNames("alex"));
    }

    @Test void hiddenLiveNpc_isExcludedForThePreviewingViewerOnly_untilThePreviewEnds() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        UUID liveUuid = UUID.randomUUID();
        NpcRuntime.ActiveNpc live = new NpcRuntime.ActiveNpc("guard", "Guard", new art.arcane.animus.core.virtual.VirtualNpcState(liveUuid, NEAR, 0.6, 1.8, 20).snapshot());
        NpcRuntime.ActiveNpc baker = new NpcRuntime.ActiveNpc("baker", "Baker", new art.arcane.animus.core.virtual.VirtualNpcState(UUID.randomUUID(), NEAR, 0.6, 1.8, 20).snapshot());
        assertFalse(hides(ALEX, live), "nothing is hidden before a preview");
        await(present(alex, "prev_a", "guard"));

        assertTrue(hides(ALEX, live), "the previewing viewer neither sees nor hears the live guard");
        assertFalse(hides(BLAKE, live), "other players are unaffected");
        assertFalse(hides(ALEX, baker), "NPCs outside the preview are unaffected");
        assertTrue(previews.hides(ALEX, "guard", liveUuid));

        previews.end("prev_a");
        assertFalse(hides(ALEX, live), "ending the preview restores the live guard for the viewer");

        await(present(alex, "prev_b", "guard"));
        assertTrue(hides(ALEX, live));
        previews.left(ALEX);
        assertFalse(hides(ALEX, live), "leaving the server ends the hiding too");
    }

    @Test void pluginHiding_hidesForItsViewerAlongsidePreviews() throws Exception {
        UUID guard = UUID.randomUUID();
        NpcRuntime.ActiveNpc live = new NpcRuntime.ActiveNpc("guard", "Guard", new art.arcane.animus.core.virtual.VirtualNpcState(guard, NEAR, 0.6, 1.8, 20).snapshot());
        Set<UUID> hiddenFromAlex = new HashSet<>();
        previews.alsoHide((UUID viewer, String id, UUID npc) -> viewer.equals(ALEX) && hiddenFromAlex.contains(npc));
        assertFalse(hides(ALEX, live));

        hiddenFromAlex.add(guard);
        assertTrue(hides(ALEX, live), "a plugin's hiding applies with no preview staged");
        assertFalse(hides(BLAKE, live), "other viewers still see it");

        hiddenFromAlex.clear();
        assertFalse(hides(ALEX, live), "showing it again restores it");
    }

    @Test void endedBeforeStaging_neverShows() throws Exception {
        ViewerPreviews delayed = new ViewerPreviews((Runnable work) -> CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(work), store);
        CompletionStage<Void> presented = delayed.present("prev_a", ALEX, List.of(DefinitionQueueTest.definition("guard", "Guard", 1)), ending("prev_a"));
        delayed.end("prev_a");
        assertThrows(ExecutionException.class, () -> await(presented));
        assertNull(delayed.staged(ALEX));
    }

    @Test void invalidCandidate_refusesPreview() throws Exception {
        NpcDefinition horse = NpcDefinition.parse("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000009","id":"horse","group":"test","name":"Horse",
                 "type":"minecraft:horse","spawned":true,"position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """);
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        CompletionStage<Void> presented = previews.present("prev_a", ALEX, List.of(horse), ending("prev_a"));
        update(alex, "alex", NEAR);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(presented));
        assertTrue(failure.getCause().getMessage().contains("$.type"), failure.getCause().getMessage());
        assertEquals(List.of(), packets.spawnedNames("alex"));
    }

    @Test void missingSkin_refusesPreview() throws Exception {
        NpcDefinition skinned = NpcDefinition.parse("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000008","id":"skinned","group":"test","name":"Skinned",
                 "type":"minecraft:player","spawned":true,"skin":"%s","position":{"world":"world","x":1,"y":64,"z":2,"yaw":0,"pitch":0}}
                """.formatted("a".repeat(64)));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(previews.present("prev_a", ALEX, List.of(skinned), ending("prev_a"))));
        assertTrue(failure.getCause().getMessage().contains("skinned skin"), failure.getCause().getMessage());
        assertNull(previews.staged(ALEX));
    }

    @Test void previewBodies_areInert_andFollowRange() throws Exception {
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        await(present(alex, "prev_a", "guard"));
        UUID body = packets.visible.get("alex").iterator().next().uuid();

        assertTrue(alex.owns(body), "the runtime drops interactions with this body before any trigger, listener or selection");
        assertFalse(alex.owns(UUID.randomUUID()));
        assertEquals(0, packets.watches, "previews never take over the viewer's interaction watch");
        update(alex, "alex", FAR);
        assertEquals(List.of(), packets.spawnedNames("alex"), "out of range despawns");
        update(alex, "alex", NEAR);
        assertEquals(List.of("guard"), packets.spawnedNames("alex"), "back in range spawns again");
    }

    @Test void expiry_throughEditorPreviews_endsThePresentation() throws Exception {
        MovingClock clock = new MovingClock();
        List<String> states = new ArrayList<>();
        EditorPreviews running = new EditorPreviews(clock, (String id, String state) -> { previews.end(id); states.add(state); });
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        EditorPreviews.Preview started = start(running, "token");
        await(present(alex, started.id(), "guard"));

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        running.expire();
        update(alex, "alex", NEAR);

        assertEquals(List.of("expired"), states);
        assertNull(previews.staged(ALEX));
        assertEquals(List.of(), packets.spawnedNames("alex"));
    }

    @Test void tokenClose_throughEditorPreviews_endsThePresentation() throws Exception {
        EditorPreviews running = new EditorPreviews(new MovingClock(), (String id, String state) -> previews.end(id));
        PreviewBodies<String, String> alex = new PreviewBodies<>(previews, ALEX);
        await(present(alex, start(running, "token").id(), "guard"));
        running.endToken("token");
        assertNull(previews.staged(ALEX));
    }

    private static EditorPreviews.Preview start(EditorPreviews running, String token) throws EditorError {
        return running.start(Optional.empty(), token, ALEX, List.of("test/guard"), 4, Duration.ofMinutes(15));
    }

    private CompletionStage<Void> present(PreviewBodies<String, String> bodies, String previewId, String npc) throws Exception {
        CompletionStage<Void> presented = previews.present(previewId, ALEX, List.of(DefinitionQueueTest.definition(npc, npc, 1)), ending(previewId));
        update(bodies, "alex", NEAR);
        return presented;
    }

    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private record FakeBody(int entityId, UUID uuid, String typeKey, String name) implements BodyTransport.Body {
        @Override public double width() { return .6; }
        @Override public double height() { return 1.8; }
        @Override public SeatGeometry seat() { return null; }
        @Override public int[] entityIds() { return new int[]{entityId}; }
    }

    /** Records what each viewer's client shows; every packet completes at once. */
    private static final class FakeTransport implements BodyTransport<String, String> {
        final Map<String, Set<FakeBody>> visible = new java.util.HashMap<>();
        int watches, despawns, nextId = 1000;

        List<String> spawnedNames(String viewer) { return visible.getOrDefault(viewer, Set.of()).stream().map(FakeBody::name).sorted().toList(); }

        @Override public Set<String> entityTypes() { return Set.of("minecraft:player"); }
        @Override public String riderUnavailable() { return "test"; }
        @Override public boolean presentationAvailable() { return true; }
        @Override public boolean equipmentAvailable() { return true; }
        @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String profileName, BodySkin skin) { return new FakeBody(nextId++, uuid, typeKey, profileName); }
        @Override public BodyTransport.Body createBody(UUID uuid, String typeKey, String name, NpcAppearance appearance, BodySkin skin) { return new FakeBody(nextId++, uuid, typeKey, name); }
        @Override public int presentationIds(UUID uuid, String typeKey, String name, NpcAppearance appearance) { return 0; }
        @Override public CompletionStage<Void> watch(String viewer, Consumer<Interaction> interactions) { watches++; return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> spawn(String viewer, BodyTransport.Body body, BodyPose pose) {
            visible.computeIfAbsent(viewer, (String ignored) -> new HashSet<>()).add((FakeBody) body);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> move(String viewer, BodyTransport.Body body, BodyPose pose) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> passengers(String viewer, BodyTransport.Body vehicle, List<? extends BodyTransport.Body> passengers, String realRider, boolean authoritative) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> dragonPhase(String viewer, BodyTransport.Body dragon, int phase) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> equipment(String viewer, BodyTransport.Body body, Map<EquipmentSlot, String> items) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> despawn(String viewer, BodyTransport.Body body) {
            despawns++;
            visible.getOrDefault(viewer, new HashSet<>()).remove((FakeBody) body);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void forget(String viewer) { visible.remove(viewer); }
        @Override public ControlSubscription subscribeControl(String viewer) { throw new UnsupportedOperationException(); }
        @Override public void placeRider(String rider, double x, double y, double z) {}
        @Override public void close() {}
    }
}
