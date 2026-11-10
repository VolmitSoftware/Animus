package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TriggerSettingsHotloadTest {
    private static final String BEHAVIOR = "example:greeter";
    private final UUID npc = UUID.randomUUID(), viewer = UUID.randomUUID();
    private final AtomicReference<Map<String, NpcDefinition>> definitions = new AtomicReference<>(Map.of());
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>();
    private final List<TriggerDispatcher.Request> started = new ArrayList<>();
    private TriggerDispatcher dispatcher;

    /** eventQueue, maximumQueued, queueLifetimeTicks, concurrentTotal and the proximity interval vary; the rest stay generous. */
    private static TriggerSettings settings(boolean enabled, int eventQueue, int maximumQueued, int lifetime, int concurrentTotal, int proximityInterval) {
        return new TriggerSettings(enabled, eventQueue, 32, 8, maximumQueued, 64, 64, lifetime, concurrentTotal, 16, 16, 0, 0, 4096, 1024, 1, 32, proximityInterval, 16, 32, 64, 0);
    }
    @BeforeEach void setUp() throws Exception {
        String source = "{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[{\"id\":\"click\",\"event\":\"interaction\",\"whileRunning\":\"queue\"}]}";
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, BehaviorCompiler.compile(source).program().orElseThrow(), Set.of());
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.of(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(accepted), List.of())), List.of()));
        definitions.set(Map.of("test", NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"" + npc + "\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":true,\"behaviors\":[\"" + BEHAVIOR + "\"],\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}")));
        dispatcher = new TriggerDispatcher(settings(true, 16, 16, 100, 4, 5), definitions::get, catalog::get);
        dispatcher.connect();
        dispatcher.activated(npc);
        pump();
    }
    private Outcome<UUID> start(TriggerDispatcher.Request request) { started.add(request); return Outcome.ok(UUID.randomUUID()); }
    private void pump() { dispatcher.pump(this::start); }
    private Outcome<Boolean> click() { return dispatcher.publish(new TriggerEvent.Interaction(npc, viewer, TriggerEvent.Action.INTERACT)); }
    private long dropped(TriggerDispatcher.DropReason reason) { return dispatcher.snapshot().dropped().getOrDefault(reason, 0L); }

    @Test void loweredQueueCapsKeepQueuedTriggersAndDropOnlyNewOnes() {
        dispatcher.settings(settings(true, 16, 16, 100, 1, 5));
        pump();
        for (int i = 0; i < 4; i++) click();
        pump();
        assertEquals(1, started.size());
        assertEquals(3, dispatcher.snapshot().queued());
        dispatcher.settings(settings(true, 16, 1, 100, 1, 5));
        click();
        pump();
        assertEquals(3, dispatcher.snapshot().queued(), "queued triggers survive a lowered maximumQueued");
        assertEquals(1, dropped(TriggerDispatcher.DropReason.TOTAL_QUEUE));
        for (int i = 0; i < 3; i++) { dispatcher.finished(started.getLast()); pump(); }
        assertEquals(4, started.size());
        assertEquals(0, dispatcher.snapshot().queued());
        dispatcher.finished(started.getLast());
        click();
        pump();
        assertEquals(5, started.size(), "new work is admitted once usage is under the cap");
    }

    @Test void loweredLifetimeDoesNotExpireTriggersAlreadyWaiting() {
        dispatcher.settings(settings(true, 16, 16, 100, 1, 5));
        pump();
        click();
        click();
        pump();
        dispatcher.settings(settings(true, 16, 16, 1, 1, 5));
        for (int i = 0; i < 10; i++) pump();
        assertEquals(1, dispatcher.snapshot().queued());
        assertEquals(0, dropped(TriggerDispatcher.DropReason.EXPIRED));
        dispatcher.finished(started.getLast());
        pump();
        assertEquals(2, started.size());
    }

    @Test void loweredEventQueueRefusesNewEventsUntilTheInboxDrains() {
        for (int i = 0; i < 4; i++) assertEquals(Outcome.Status.OK, click().status());
        dispatcher.settings(settings(true, 2, 16, 100, 16, 5));
        assertEquals(Outcome.Status.OK, click().status(), "pending settings wait for the owner's pump");
        pump();
        assertEquals(5, started.size(), "events already in the inbox were not dropped");
        assertEquals(Outcome.Status.OK, click().status());
        assertEquals(Outcome.Status.OK, click().status());
        assertEquals(Outcome.Status.BUSY, click().status());
    }

    @Test void triggersEnabledTogglesLiveAndRunningTriggersStillFinish() {
        click();
        pump();
        assertEquals(1, started.size());
        dispatcher.settings(settings(false, 16, 16, 100, 4, 5));
        pump();
        assertFalse(dispatcher.available());
        assertEquals(Outcome.Status.UNAVAILABLE, click().status());
        dispatcher.finished(started.getFirst());
        assertEquals(0, dispatcher.snapshot().active());
        dispatcher.settings(settings(true, 16, 16, 100, 4, 5));
        pump();
        assertTrue(dispatcher.available());
        click();
        pump();
        assertEquals(2, started.size());
    }

    @Test void behaviorsDisabledRefusesEventsUntilReEnabled() {
        dispatcher.behaviorsEnabled(false);
        assertEquals(Outcome.Status.UNAVAILABLE, click().status());
        assertEquals("behaviors are disabled in config.toml", dispatcher.availability());
        pump();
        dispatcher.behaviorsEnabled(true);
        pump();
        click();
        pump();
        assertEquals(1, started.size());
    }

    @Test void proximityBudgetsFollowTheAdoptedSettings() {
        assertEquals(5, dispatcher.proximityLimits().intervalTicks());
        dispatcher.settings(settings(true, 16, 16, 100, 4, 9));
        assertEquals(5, dispatcher.proximityLimits().intervalTicks());
        pump();
        assertEquals(9, dispatcher.proximityLimits().intervalTicks());
        assertEquals(9, dispatcher.settings().proximityIntervalTicks());
    }
}
