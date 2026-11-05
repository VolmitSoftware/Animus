package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Gloss actions Animus used to refuse and now runs itself: teleport, bossbar, broadcast, effect, particle, parallel and repeat. */
class NativeActionsTest {
    private final MockScreenHost host = new MockScreenHost();
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (java.util.UUID npc) -> false, (String warning) -> { throw new AssertionError(warning); });
    private final Player alex = new Player("Alex");

    private static String gui(String actions) {
        return """
                {"schemaVersion": 1, "revision": 1, "title": "t", "resolution": "9x1", "mask": ["B        "],
                 "keys": {"B": {"type": "button", "icon": {"type": "item", "item": "minecraft:stone", "name": "Go"}, "actions": %s}}}
                """.formatted(actions);
    }

    private void open(String actions) {
        GuiDocument.Result result = GuiDocument.parse("town:menu", gui(actions));
        assertEquals(List.of(), result.problems());
        GuiDocument document = result.document().orElseThrow();
        Screens<Player, Stack> screens = new Screens<>() {
            @Override public Optional<Screen> gui(ScreenManager<Player, Stack>.Viewer viewer, String id, Map<String, String> args) { return Optional.of(new GuiScreen<>(document, viewer, this, args)); }
            @Override public Optional<Screen> shop(ScreenManager<Player, Stack>.Viewer viewer, String id) { return Optional.empty(); }
            @Override public Object npc(ScreenManager<Player, Stack>.Viewer viewer, String property) { return null; }
        };
        alex.pose = new ScreenHost.Pose(Set.of("minecraft:overworld"), 10, 64, 10, 65.62, 0, 0, false);
        assertTrue(manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> screens.gui(viewer, "town:menu", Map.of())));
    }

    private void tick(int ticks) { for (int index = 0; index < ticks; index++) manager.tick(alex); }

    @Test void teleportBossbarEffectAndParticleReachTheHost() {
        open("""
                [{"type": "teleport", "world": "minecraft:the_nether", "x": 1, "y": 70, "z": -3, "yaw": 90, "pitch": 0},
                 {"type": "bossbar", "id": "quest", "title": "&6Quest {{ viewer.name }}", "progress": "0.5", "color": "yellow", "style": "segmented_10", "ticks": 40},
                 {"type": "effect", "effect": "minecraft:speed", "ticks": 200, "amplifier": 1},
                 {"type": "particle", "particle": "minecraft:flame", "count": 5}]
                """);
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertEquals(List.of("teleport:minecraft:the_nether:1,70,-3", "bossbar:quest:Quest Alex:0.5:yellow:segmented_10:40",
                "effect:minecraft:speed:200:1", "particle:minecraft:flame:5@1,71,-3"), alex.effects);
    }

    @Test void anUnknownEffectStopsTheList() {
        open("[{\"type\": \"effect\", \"effect\": \"minecraft:unknown\", \"ticks\": 20}, {\"type\": \"message\", \"message\": \"after\"}]");
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertTrue(alex.messages.isEmpty());
    }

    @Test void broadcastNamesItsScope() {
        open("[{\"type\": \"broadcast\", \"message\": \"&aHi from {{ viewer.name }}\", \"scope\": \"radius\", \"radius\": 30, \"permission\": \"town.news\"}]");
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertEquals(List.of("radius[town.news]:Hi from Alex"), host.broadcasts);
    }

    @Test void parallelBranchesKeepTheirOwnDelays() {
        open("""
                [{"type": "parallel", "branches": [
                   [{"type": "delay", "ticks": 5}, {"type": "message", "message": "slow"}],
                   [{"type": "message", "message": "fast"}]]},
                 {"type": "message", "message": "after"}]
                """);
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertEquals(List.of("fast", "after"), alex.messages);
        tick(5);
        assertEquals(List.of("fast", "after", "slow"), alex.messages);
    }

    @Test void repeatRunsItsStepsWithAWhileCondition() {
        open("[{\"type\": \"repeat\", \"times\": 5, \"steps\": [{\"type\": \"message\", \"message\": \"x\"}]}]");
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertEquals(5, alex.messages.size());
        alex.messages.clear();
        open("""
                [{"type": "setSession", "var": "n", "value": "0"},
                 {"type": "repeat", "times": 10, "everyTicks": 2, "while": "number(session.n) < 3",
                  "steps": [{"type": "message", "message": "tick"}, {"type": "setSession", "var": "n", "value": "{{ number(session.n) + 1 }}"}]}]
                """);
        manager.click(alex, alex.window, 0, Click.LEFT);
        assertEquals(1, alex.messages.size());
        tick(10);
        assertEquals(3, alex.messages.size());
    }

    @Test void behaviorOnlyTargetsAndLocationParticlesAreRefusedInChestWindows() {
        List<String> problems = GuiDocument.parse("town:menu", gui("""
                [{"type": "effect", "effect": "minecraft:speed", "ticks": 20, "target": "subject"},
                 {"type": "particle", "particle": "minecraft:flame", "at": "location"},
                 {"type": "teleport", "world": "Overworld", "x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0}]
                """)).problems();
        assertEquals(List.of("$.keys.B.actions[0].target: target 'subject' belongs to Gloss behaviors; Animus applies effects to the viewer",
                "$.keys.B.actions[1].at: a chest window has no location; use viewer",
                "$.keys.B.actions[2].world: expected a world key such as minecraft:overworld"), problems);
    }

    @Test void theStillRefusedGlossActionsNameThemselves() {
        for (String type : GuiParser.GLOSS_ONLY)
            assertTrue(GuiDocument.parse("town:menu", gui("[{\"type\": \"" + type + "\"}]")).problems()
                    .contains("$.keys.B.actions[0].type: action '" + type + "' is not available in Animus"), type);
    }
}
