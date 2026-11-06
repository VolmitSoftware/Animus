package art.arcane.animus.core.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class AnimationEventTest {
    private static final String SOUND = "{\"tick\":5,\"capability\":\"animus:play-sound\",\"arguments\":{\"sound\":\"minecraft:entity.iron_golem.step\"}}";
    private static final String FLAME = "{\"tick\":0,\"capability\":\"animus:spawn-particle\",\"arguments\":{\"particle\":\"minecraft:flame\",\"count\":4}}";

    private static String clip(String id, int duration, String loop, String... events) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"" + id + "\",\"rig\":\"town:golem\",\"durationTicks\":" + duration + ",\"loop\":\"" + loop
                + "\",\"tracks\":[],\"events\":[" + String.join(",", events) + "]}";
    }

    private static RigPlayback playback(String... clips) {
        RigDocument rig = RigDocument.parse("town:golem", RigPlaybackTest.RIG).document().orElseThrow();
        Map<String, AnimationDocument> loaded = new java.util.HashMap<>();
        for (String text : clips) {
            AnimationDocument document = AnimationDocument.parse(null, text).document().orElseThrow(() -> new AssertionError(AnimationDocument.parse(null, text).problems()));
            loaded.put(document.id(), document);
        }
        return new RigPlayback(rig, (String id) -> Optional.ofNullable(loaded.get(id)), RigPlaybackTest.SEAT);
    }

    private static List<String> fired(RigPlayback playback, long now) {
        List<String> names = new ArrayList<>();
        for (RigPlayback.Fired fired : playback.events(now)) names.add(fired.event().capability() + "@" + fired.event().tick());
        return names;
    }

    @Test
    void loopingClipFiresEachEventOncePerCycle() {
        RigPlayback playback = playback(clip("town:golem/step", 10, "loop", FLAME, SOUND));
        playback.play("town:golem/step", 1, Optional.empty(), 1, 0);
        assertEquals(List.of("animus:spawn-particle@0"), fired(playback, 0));
        for (int tick = 1; tick < 5; tick++) assertEquals(List.of(), fired(playback, tick));
        assertEquals(List.of("animus:play-sound@5"), fired(playback, 5));
        for (int tick = 6; tick < 10; tick++) assertEquals(List.of(), fired(playback, tick));
        assertEquals(List.of("animus:spawn-particle@0"), fired(playback, 10));
    }

    @Test
    void speedScalesEventTimesAndALongGapFiresEachEventAtMostOnce() {
        RigPlayback playback = playback(clip("town:golem/step", 10, "loop", FLAME, SOUND));
        playback.play("town:golem/step", 2, Optional.empty(), 1, 0);
        assertEquals(List.of("animus:spawn-particle@0"), fired(playback, 0));
        assertEquals(List.of(), fired(playback, 2));
        assertEquals(List.of("animus:play-sound@5"), fired(playback, 3));
        List<String> late = fired(playback, 500);
        assertEquals(2, late.size(), late.toString());
    }

    @Test
    void pingpongFiresOnTheWayOutAndBack() {
        RigPlayback playback = playback(clip("town:golem/swing", 10, "pingpong", SOUND.replace("\"tick\":5", "\"tick\":2")));
        playback.play("town:golem/swing", 1, Optional.empty(), 1, 0);
        List<Long> at = new ArrayList<>();
        for (long tick = 0; tick <= 40; tick++) if (!fired(playback, tick).isEmpty()) at.add(tick);
        assertEquals(List.of(2L, 18L, 22L, 38L), at);
    }

    @Test
    void stoppedAndFadingClipsFireNothing() {
        RigPlayback playback = playback(clip("town:golem/step", 10, "loop", FLAME, SOUND));
        playback.play("town:golem/step", 1, Optional.empty(), 1, 0);
        assertEquals(1, fired(playback, 0).size());
        playback.stop(OptionalInt.of(1), 20, 1);
        for (int tick = 1; tick <= 30; tick++) assertEquals(List.of(), fired(playback, tick));
        playback.play("town:golem/step", 1, Optional.empty(), 2, 31);
        assertEquals(1, fired(playback, 31).size());
        playback.stop(OptionalInt.empty(), 0, 32);
        assertEquals(List.of(), fired(playback, 36));
    }

    @Test
    void onceClipFiresItsLastTickEventBeforeItEnds() {
        RigPlayback playback = playback(clip("town:golem/bow", 10, "once", SOUND.replace("\"tick\":5", "\"tick\":10")));
        playback.play("town:golem/bow", 1, Optional.empty(), 0, 0);
        assertEquals(List.of(), fired(playback, 9));
        assertEquals(List.of("animus:play-sound@10"), fired(playback, 10));
        assertEquals(List.of(new RigPlayback.Finished("town:golem/bow", 0)), playback.drainFinished(10));
        assertEquals(List.of(), fired(playback, 30));
    }

    @Test
    void ambientWalkClipFiresItsEvents() {
        RigPlayback playback = playback(clip("town:golem/walk", 10, "loop", SOUND));
        playback.moving(true, 0);
        assertEquals(List.of(), fired(playback, 4));
        assertEquals(List.of("animus:play-sound@5"), fired(playback, 5));
    }

    @Test
    void eventsPlayToNearbyPlayersAndRefuseAPlayerTarget() {
        AnimationDocument document = AnimationDocument.parse(null, clip("town:golem/step", 10, "loop", SOUND)).document().orElseThrow();
        assertTrue(document.events().getFirst().arguments().contains("\"audience\":\"nearby\""), document.events().getFirst().arguments());
        String radius = SOUND.replace("\"sound\":", "\"audience\":\"nearby\",\"radius\":8,\"sound\":");
        assertTrue(AnimationDocument.parse(null, clip("town:golem/step", 10, "loop", radius)).problems().isEmpty());
        for (String refused : List.of("\"audience\":\"trigger\",", "\"at\":\"player\",", "\"player\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",")) {
            List<String> problems = AnimationDocument.parse(null, clip("town:golem/step", 10, "loop", SOUND.replace("\"sound\":", refused + "\"sound\":"))).problems();
            assertEquals(1, problems.size(), refused + " " + problems);
            assertTrue(problems.getFirst().startsWith("$.events[0].arguments"), problems.toString());
        }
    }

    @Test
    void runnerCallsTheCapabilityAndReportsEachFailureOnce() {
        List<String> calls = new ArrayList<>();
        List<String> log = new ArrayList<>();
        AnimationEventRunner runner = new AnimationEventRunner((Capabilities.Key key, NpcHandle npc, String arguments) -> {
            calls.add(key + " " + arguments);
            return CompletableFuture.completedFuture(key.name().equals("play-sound") ? Outcome.<String>rejected(Outcome.Status.INVALID, "unknown-sound", "Unknown sound")
                    : Outcome.ok("{}"));
        }, log::add);
        AnimationDocument document = AnimationDocument.parse(null, clip("town:golem/step", 10, "loop", FLAME, SOUND)).document().orElseThrow();
        UUID npc = UUID.randomUUID();
        for (int i = 0; i < 3; i++)
            for (AnimationDocument.Event event : document.events()) runner.fired(npc, new RigPlayback.Fired(document.id(), 1, event));
        assertEquals(6, calls.size());
        assertTrue(calls.getFirst().startsWith("animus:spawn-particle {"), calls.getFirst());
        assertEquals(1, log.size(), log.toString());
        assertTrue(log.getFirst().contains("town:golem/step") && log.getFirst().contains("Unknown sound"), log.getFirst());
    }
}
