package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugWatch;
import art.arcane.animus.core.metrics.MetricsSnapshot;
import art.arcane.animus.core.metrics.PacketKind;
import art.arcane.animus.core.metrics.RuntimeGauges;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DebugCommandsTest {
    private final AtomicLong nanos = new AtomicLong();
    private final AnimusMetrics metrics = new AnimusMetrics(nanos::get, () -> 1L);
    private final DebugWatch watch = new DebugWatch();
    private final UUID guard = UUID.randomUUID(), player = UUID.randomUUID();
    private final DebugCommands commands = new DebugCommands(() -> metrics, watch,
            (String id) -> id.equals("guard") ? Optional.of(guard) : Optional.empty(), () -> List.of("Editor: running"));

    private static final class Actor implements CommandActor {
        final List<String> told = new ArrayList<>();
        final Optional<UUID> player;
        Actor(UUID player) { this.player = Optional.ofNullable(player); }
        @Override public boolean permitted(String node) { return true; }
        @Override public void tell(String message) { told.add(RichText.plain(message)); }
        @Override public Optional<UUID> player() { return player; }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
        String text() { return String.join("\n", told); }
    }

    private void second() { for (int i = 0; i < 20; i++) { nanos.addAndGet(50_000_000L); metrics.serverTick(); } }

    /** The typed debug command {@code /animus debug ...} routes these words to. */
    private List<String> run(Actor actor, String... arguments) {
        actor.told.clear();
        String operation = arguments.length < 2 ? "summary" : arguments[1];
        switch (operation) {
            case "summary" -> commands.summary(actor);
            case "network" -> commands.network(actor, arguments.length > 2 ? Optional.of(arguments[2]) : Optional.empty());
            case "performance" -> commands.performance(actor);
            case "npc" -> commands.npc(actor, arguments[2]);
            case "watch" -> commands.watch(actor);
            default -> throw new AssertionError(operation);
        }
        return actor.told;
    }

    @Test void summaryChangesWithLoadAndStaysHonest() {
        Actor console = new Actor(null);
        second();
        String idle = String.join("\n", run(console, "debug"));
        assertTrue(idle.contains("0.0 pkt/s"), idle);
        assertTrue(idle.contains("Measured on this server"), idle);
        assertTrue(idle.contains("Editor: running"), idle);

        metrics.gauges(() -> new RuntimeGauges("test", 30, 24, 6, 2, 1, 24, 3, 1, 0, false, false, 0, 0, 0,
                List.of(new RuntimeGauges.NpcPlace(guard, "world", 0, 0))));
        metrics.sent(metrics.viewer(player, "Alex"), metrics.npc(guard), PacketKind.MOVE, 200);
        metrics.cost(Subsystem.PRESENTATION, 20_000_000L);
        second();
        String busy = String.join("\n", run(console, "debug"));
        assertTrue(busy.contains("24 active"), busy);
        assertTrue(busy.contains("100.0 pkt/s"), busy);
        assertTrue(busy.contains("presentation"), busy);
    }

    @Test void aPlayerGetsTheReportsWithinSixLines() {
        second();
        List<String> summary = run(new Actor(player), "debug");
        assertTrue(summary.size() <= CommandGroup.PLAYER_LINES, summary.toString());
        assertTrue(summary.getLast().startsWith("[Animus]: Showing 5 of "), summary.toString());
        assertTrue(run(new Actor(null), "debug").size() > CommandGroup.PLAYER_LINES, "the console reads the whole report");
    }

    @Test void networkListsKindsAndOneViewer() {
        Actor console = new Actor(null);
        metrics.sent(metrics.viewer(player, "Alex"), null, PacketKind.HOLOGRAM, 5);
        metrics.sent(metrics.viewer(UUID.randomUUID(), "Sam"), null, PacketKind.SPAWN, 1);
        second();
        String all = String.join("\n", run(console, "debug", "network"));
        assertTrue(all.contains("hologram"), all);
        assertTrue(all.contains("Alex"), all);
        String alex = String.join("\n", run(console, "debug", "network", "alex"));
        assertTrue(alex.contains("Viewer Alex"), alex);
        assertTrue(alex.contains("hologram: 5"), alex);
        assertFalse(alex.contains("spawn"), alex);
        assertTrue(String.join("\n", run(console, "debug", "network", "nobody")).contains("No traffic recorded for "));
    }

    @Test void performanceShowsEverySubsystemAndTickShare() {
        metrics.cost(Subsystem.NAVIGATION, 50_000_000L);
        second();
        String text = String.join("\n", run(new Actor(null), "debug", "performance"));
        for (Subsystem subsystem : Subsystem.values()) assertTrue(text.contains(subsystem.label()), subsystem.label());
        assertTrue(text.contains("2.500"), text);
        assertTrue(text.contains("5.00%"), text);
    }

    @Test void npcReportsItsShare() {
        metrics.gauges(() -> new RuntimeGauges("test", 1, 1, 0, 0, 1, 1, 0, 0, 0, false, false, 0, 0, 0, List.of(new RuntimeGauges.NpcPlace(guard, "world", 0, 0))));
        metrics.sent(null, metrics.npc(guard), PacketKind.RIG_BONES, 30);
        metrics.sent(null, null, PacketKind.MOVE, 30);
        second();
        String text = String.join("\n", run(new Actor(null), "debug", "npc", "guard"));
        assertTrue(text.contains("active"), text);
        assertTrue(text.contains("50.0%"), text);
        assertTrue(text.contains("rig_bones: 30"), text);
        assertTrue(String.join("\n", run(new Actor(null), "debug", "npc", "ghost")).contains("No NPC named ghost"));
    }

    @Test void watchTogglesForPlayersOnlyAndPublishesLines() {
        Actor alex = new Actor(player);
        assertTrue(run(alex, "debug", "watch").get(0).contains("on"));
        assertTrue(watch.watching(player));
        second();
        Map<UUID, String> bars = new HashMap<>();
        watch.publish(metrics.snapshot(), (UUID id, String line) -> { bars.put(id, line); return true; });
        assertTrue(bars.get(player).contains("Animus"));
        watch.publish(metrics.snapshot(), (UUID id, String line) -> false);
        assertFalse(watch.watching(player), "an unreachable watcher is dropped");
        assertTrue(run(new Actor(null), "debug", "watch").get(0).contains("The live watch is for players; use "));
    }

    @Test void viewerNamesCompleteTheNetworkPlayer() {
        metrics.viewer(player, "Alex");
        assertEquals(List.of("Alex"), commands.viewers());
        MetricsSnapshot empty = metrics.snapshot();
        assertEquals(0, empty.sequence());
    }
}
