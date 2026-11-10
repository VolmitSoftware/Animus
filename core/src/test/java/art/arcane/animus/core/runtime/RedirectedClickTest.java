package art.arcane.animus.core.runtime;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.presentation.BodyTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page beside the player redirects its clicks to a herald far away. Interactions reach the herald; an attack lands on
 * the page that was hit; and a herald the player's viewers filter hides is never the target, nor heard from.
 */
class RedirectedClickTest {
    private static final UUID PAGE = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0c31"), HERALD = UUID.fromString("5d1c8a52-1f0c-4f43-a3a3-9d1f8f8a0c32");
    @TempDir Path directory;

    private record Asked(UUID npc, RuntimeHost.Interaction action) {}

    private ViewerWatchRaceTest.Harness harness(String heraldExtra) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        Files.writeString(npcs.resolve("page.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "page", "group": "test", "name": "Page", "type": "minecraft:villager",
                 "spawned": true, "clickRedirect": "%s", "position": {"world": "world", "x": 0.5, "y": 64, "z": 2.5, "yaw": 180, "pitch": 0}}
                """.formatted(PAGE, HERALD));
        Files.writeString(npcs.resolve("herald.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "%s", "id": "herald", "group": "test", "name": "Herald", "type": "minecraft:villager",
                 "spawned": true, %s "position": {"world": "world", "x": 300.5, "y": 64, "z": 300.5, "yaw": 0, "pitch": 0}}
                """.formatted(HERALD, heraldExtra.formatted(harness.player.id())));
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        runtime.npcs().reload();
        harness.service.refresh();
        assertTrue(harness.service.available(), harness.service.status());
        harness.tick(6);
        assertTrue(harness.service.active(HERALD).isPresent(), "the far herald is active");
        return harness;
    }

    private static List<Asked> record(ViewerWatchRaceTest.Harness harness) {
        List<Asked> asked = new ArrayList<>();
        harness.allowed = (UUID npc, RuntimeHost.Interaction action) -> { asked.add(new Asked(npc, action)); return true; };
        return asked;
    }

    private static void click(ViewerWatchRaceTest.Harness harness, BodyTransport.Action action) {
        BodyTransport.Body page = harness.transport.lastSpawned;
        assertNotNull(page);
        assertEquals(PAGE, page.uuid(), "only the page is in view");
        harness.transport.interactions.accept(new BodyTransport.Interaction(page, action, "MAIN_HAND", false, System.nanoTime()));
    }

    @Test
    void anAttackOnARedirectingNpcLandsOnThatNpcNotOnTheFarTarget() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("");
        List<Asked> asked = record(harness);
        List<UUID> heard = new ArrayList<>();
        harness.service.observeInteractions((UUID npc, UUID viewer, boolean attack, double distance, boolean preview) -> heard.add(npc));

        click(harness, BodyTransport.Action.ATTACK);
        assertEquals(List.of(new Asked(PAGE, RuntimeHost.Interaction.ATTACK)), asked, "the event that deals damage names the NPC that was hit");
        assertEquals(List.of(HERALD), heard, "triggers and listeners still hear the herald");
    }

    @Test
    void anInteractionOnARedirectingNpcReachesTheTarget() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("");
        List<Asked> asked = record(harness);
        click(harness, BodyTransport.Action.INTERACT);
        assertEquals(List.of(new Asked(HERALD, RuntimeHost.Interaction.INTERACT)), asked, "an interaction is redirected");
    }

    @Test
    void aTargetHiddenFromThePlayerKeepsTheClickOnTheClickedNpc() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("\"viewers\": {\"players\": [\"%s\"]},");
        List<Asked> asked = record(harness);

        click(harness, BodyTransport.Action.INTERACT);
        assertEquals(List.of(new Asked(PAGE, RuntimeHost.Interaction.INTERACT)), asked, "the hidden herald never receives the click");
    }

    @Test
    void anNpcTheViewersFilterHidesIsHiddenForSpeechAndTriggers() throws IOException {
        ViewerWatchRaceTest.Harness harness = harness("\"viewers\": {\"players\": [\"%s\"]},");
        UUID player = harness.player.id();
        assertTrue(harness.service.hidden().hides(player, "herald", HERALD), "the filtered player neither hears nor triggers the herald");
        assertFalse(harness.service.hidden().hides(player, "page", PAGE));
        assertFalse(harness.service.hidden().hides(UUID.randomUUID(), "herald", HERALD), "players offline or unnamed are not filtered");
    }
}
