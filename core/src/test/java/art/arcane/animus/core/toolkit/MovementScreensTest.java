package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Movement per mode, its live controls, and the Mounts screen behind it. */
class MovementScreensTest extends ToolkitHarness {
    static final Map<Integer, String> SIMPLE = Map.of(4, "Movement", 20, "Route", 22, "Recording", 24, "Stand still", 45, "Back", 53, "Help");

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"route\":\"market\"}}"), npcFile("baker", BAKER, ""),
                file("routes/market.json", ToolkitRoutesTest.MARKET), file("recordings/wave.json", ToolkitRoutesTest.recording("wave"))), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 3.0);
        toolkit.selectOnly(alex.id, "guard");
    }

    void movement() {
        toolkit.open(alex.id, MovementScreen::new);
        drain();
    }

    @Test void simpleShowsRouteAndRecordingSideBySideAndAdvancedAddsTheLiveRow() {
        movement();
        assertEquals("Movement", title());
        assertEquals(SIMPLE, buttons());
        assertEquals("Walks point to point.\nClick: open", lore("Route"));
        assertEquals("Replays what you did.\nClick: open", lore("Recording"));
        assertTrue(lore(MovementScreen.HEADER).startsWith("Now: walks route Market"), lore(MovementScreen.HEADER));

        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        movement();
        Map<Integer, String> advanced = new HashMap<>(SIMPLE);
        advanced.putAll(Map.of(37, "Walk once", 38, "Start", 39, "Stop now", 40, "Movement settings", 41, "Mounts and riding"));
        assertEquals(advanced, buttons(), "Advanced adds a row; Simple items stay where they were");
        for (int slot : List.of(13, 22, 31)) assertFalse(List.of("Stand still", "Stop now").contains(name(slot)));
    }

    @Test void helpExplainsBothWithTheirWikiLinksAndKeepsTheWindow() {
        movement();
        Object window = alex.window;
        click("Help", Click.LEFT);
        String help = alex.messages.getLast();
        assertTrue(help.contains("A route is points on the ground") && help.contains("A recording is your own movement, replayed exactly"), help);
        assertTrue(help.contains("https://docs.volmitsoftware.com/animus/05-movement-mounts-riding#routes"), help);
        assertTrue(help.contains("https://docs.volmitsoftware.com/animus/19-performance-recordings#recording-a-performance"), help);
        assertEquals(window, alex.window);
    }

    @Test void theHeaderSaysWhatItDoesAndStandStillAsksThenRemovesIt() throws IOException {
        movement();
        click("Stand still", Click.LEFT);
        assertEquals("Click again to confirm", name(MovementScreen.STILL));
        click(MovementScreen.STILL, Click.LEFT);
        drain();
        assertTrue(calls.contains("stop guard"));
        assertTrue(calls.contains("relocate guard world 0.0 64.0 0.0"), "it goes back to its saved spot, where a replay or a walk took it from: " + calls);
        assertTrue(lore(MovementScreen.HEADER).startsWith("Now: stands still"), lore(MovementScreen.HEADER));
        store.commit(List.of(new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"),
                npc("baker", BAKER, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"recording\":\"wave\"}}"))));
        npcs.reload();
        toolkit.selectOnly(alex.id, "baker");
        movement();
        assertTrue(lore(MovementScreen.HEADER).startsWith("Now: replays recording Wave"), lore(MovementScreen.HEADER));
        click("Recording", Click.LEFT);
        drain();
        assertEquals("Recordings", title());
    }

    @Test void anNpcWalkingItsOwnPointsSaysSoAndStandStillKeepsThemAndEverySetting() throws IOException {
        String own = ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"points\":[{\"x\":0.5,\"y\":64,\"z\":0.5},{\"x\":6.5,\"y\":64,\"z\":0.5}],"
                + "\"speed\":2,\"loop\":true,\"waitTicks\":20}}";
        store.commit(List.of(new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"), npc("baker", BAKER, own))));
        npcs.reload();
        toolkit.selectOnly(alex.id, "baker");
        movement();
        assertTrue(lore(MovementScreen.HEADER).startsWith("Now: walks its own route"), "an imported patrol does not read as standing still: " + lore(MovementScreen.HEADER));
        click("Stand still", Click.LEFT);
        assertTrue(lore(MovementScreen.STILL).contains("Takes its route or recording off"), lore(MovementScreen.STILL));
        click(MovementScreen.STILL, Click.LEFT);
        drain();
        assertTrue(calls.contains("stop baker"));
        com.google.gson.JsonObject navigation = npcs.find("baker").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation");
        assertFalse(navigation.get("enabled").getAsBoolean(), "it stops walking");
        assertEquals(2, navigation.getAsJsonArray("points").size(), "its own points stay");
        assertEquals(2, navigation.get("speed").getAsInt());
        assertTrue(navigation.get("loop").getAsBoolean());
        assertEquals(20, navigation.get("waitTicks").getAsInt());
        assertTrue(lore(MovementScreen.HEADER).startsWith("Now: stands still"), lore(MovementScreen.HEADER));
    }

    @Test void standStillTakesOffOnlyTheRecordingAndItsPlayback() throws IOException {
        String recorded = ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"recording\":\"wave\",\"playback\":\"loop\"},\"animus:combat\":{\"enabled\":true}}";
        store.commit(List.of(new DocumentStore.Change("npcs/town/baker.json", store.hash("npcs/town/baker.json"), npc("baker", BAKER, recorded))));
        npcs.reload();
        toolkit.selectOnly(alex.id, "baker");
        movement();
        click("Stand still", Click.LEFT);
        click(MovementScreen.STILL, Click.LEFT);
        drain();
        com.google.gson.JsonObject modules = npcs.find("baker").orElseThrow().json().getAsJsonObject("modules");
        assertFalse(modules.has("animus:navigation"), "a module left with only its switch goes");
        assertTrue(modules.has("animus:combat"), "other modules stay");
        assertEquals(1, changes().size());
    }

    @Test void walkOncePicksARouteAndStartAndStopNowAct() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        movement();
        click("Walk once", Click.LEFT);
        drain();
        assertEquals("Walk once", title());
        click("market", Click.LEFT);
        assertEquals("Movement", title());
        assertTrue(calls.contains("follow guard market"), calls.toString());
        assertTrue(alex.messages.getLast().contains("Walking market: guard"), alex.messages.getLast());
        calls.clear();
        click("Start", Click.LEFT);
        assertTrue(calls.contains("follow guard market"), "Start runs its own route: " + calls);
        click("Stop now", Click.LEFT);
        assertTrue(calls.contains("stop guard"), calls.toString());
        click("Movement settings", Click.LEFT);
        assertEquals("Movement settings", title());
        click("Back", Click.LEFT);
        click("Mounts and riding", Click.LEFT);
        assertEquals("Mounts", title());
    }

    @Test void nothingSelectedSaysWhatToDo() {
        toolkit.selection().clear(alex.id);
        movement();
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
        click("Nothing selected", Click.LEFT);
        assertEquals("NPCs", title());
    }

    @Test void mountsUseMenuWordsAndDismountAsksOnce() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id, MountScreen::new);
        assertEquals(Map.of(4, "guard", 10, "Ride another NPC", 11, "Carry another NPC", 12, "Pick a rider", 14, "Riding", 15, "Ride status", 17, "Dismount",
                45, "Back", 53, "Help"), buttons());
        assertTrue(lore("Ride status").contains("Shows in chat"), lore("Ride status"));
        click("Ride another NPC", Click.LEFT);
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, BAKER, false, false));
        drain();
        assertTrue(alex.messages.contains("bar:NPC for guard to ride: click an NPC with the wand"), alex.messages.toString());
        assertEquals("baker", npcs.find("guard").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:mount").get("vehicle").getAsString());
        toolkit.open(alex.id, MountScreen::new);
        assertTrue(lore("guard").contains("Rides baker"), lore("guard"));
        click("Dismount", Click.LEFT);
        assertEquals("Click again to confirm", name(MountScreen.DISMOUNT));
        assertTrue(npcs.find("guard").orElseThrow().json().getAsJsonObject("modules").has("animus:mount"), "Dismount asks first");
        click(MountScreen.DISMOUNT, Click.LEFT);
        drain();
        assertFalse(npcs.find("guard").orElseThrow().json().getAsJsonObject("modules").has("animus:mount"));
        click("Ride status", Click.LEFT);
        assertEquals(List.of("animus ride status"), alex.commands);
    }
}
