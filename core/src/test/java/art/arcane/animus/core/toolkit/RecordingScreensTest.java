package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.recording.RecordingDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recordings: the list, one recording's details, and New recording from the countdown to the NPC playing it. */
class RecordingScreensTest extends ToolkitHarness {
    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, ",\"modules\":{\"animus:navigation\":{\"enabled\":true,\"recording\":\"wave\",\"playback\":\"loop\"}}"),
                file("recordings/wave.json", ToolkitRoutesTest.recording("wave"))), true);
        active.put(GUARD, 3.0);
        active.put(BAKER, 3.0);
    }

    JsonObject navigation(String id) { return npcs.find(id).orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation"); }

    void recordings() {
        toolkit.open(alex.id, RecordingScreen::new);
        drain();
    }

    void ticks(int count) { for (int i = 0; i < count; i++) toolkit.tick(alex); }

    @Test void theListShowsLengthInSecondsAndClickPutsItOnTheSelection() {
        toolkit.selectOnly(alex.id, "guard");
        recordings();
        assertEquals("Recordings", title());
        assertEquals(Map.of(0, "Wave", 36, "New recording", 45, "Back", 46, "Search", 53, "Help"), buttons());
        assertEquals("2 seconds, played by 1 NPC\nClick: use on guard\nRight-click: details", lore("Wave"));
        click("Wave", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Recording Wave on guard"), alex.messages.getLast());
        assertEquals("wave", navigation("guard").get("recording").getAsString());
        assertEquals("loop", navigation("guard").get("playback").getAsString());
        assertTrue(lore("Wave").contains("Now: guard plays it (Loop)"), lore("Wave"));

        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        recordings();
        assertEquals(Map.of(0, "Wave", 36, "New recording", 37, "Show my trail", 45, "Back", 46, "Search", 53, "Help"), buttons());
        click("Show my trail", Click.LEFT);
        assertEquals(List.of("animus record ghost"), alex.commands);
    }

    @Test void withNothingSelectedAClickOpensTheDetailsAndAnEmptyListSaysWhatToDo() throws IOException {
        recordings();
        assertEquals("2 seconds, played by 1 NPC\nClick: details", lore("Wave"));
        click("Wave", Click.LEFT);
        drain();
        assertEquals("Recording Wave", title());
        store.commit(List.of(new DocumentStore.Change("recordings/wave.json", store.hash("recordings/wave.json"), null)));
        recordings();
        assertEquals("No recordings yet; New recording makes one", name(22));
    }

    @Test void whileRecordingTheListStopsAndSavesOrDiscardsAfterAsking() {
        recording = true;
        recordings();
        assertEquals("Stop and save", name(RecordingScreen.CREATE));
        assertEquals("Discard", name(RecordingScreen.DISCARD));
        click("Discard", Click.LEFT);
        assertTrue(alex.commands.isEmpty(), "discarding asks once");
        click(RecordingScreen.DISCARD, Click.LEFT);
        assertEquals(List.of("animus record cancel"), alex.commands);
        click("Stop and save", Click.LEFT);
        assertEquals(List.of("animus record cancel", "animus record stop"), alex.commands);
    }

    @Test void newRecordingCountsDownRecordsWithChipsAndPutsTheSavedRecordingOnTheNpc() throws IOException {
        toolkit.selectOnly(alex.id, "guard");
        recordings();
        click("New recording", Click.LEFT);
        assertEquals("Recording name", alex.prompt.label());
        assertEquals("guard recording", alex.prompt.initial(), "a free name is suggested");
        MockScreenHost.answer(alex, Optional.of("Greeting"));
        drain();
        assertNull(alex.window, "the window closes before recording starts");
        assertTrue(alex.messages.contains("bar:Recording starts in 3"), alex.messages.toString());
        ticks(CaptureFlow.COUNTDOWN - 1);
        assertTrue(alex.messages.contains("bar:Recording starts in 2") && alex.messages.contains("bar:Recording starts in 1"), alex.messages.toString());
        assertTrue(alex.commands.isEmpty(), "nothing records during the countdown");
        ticks(1);
        assertEquals(List.of("animus record start greeting"), alex.commands);
        assertEquals(1, alex.messages.stream().filter((String line) -> line.contains("Recording Greeting starts in 3 seconds; it copies what you hold and wear")).count(),
                "said once, before the countdown: " + alex.messages);
        assertFalse(alex.messages.stream().anyMatch((String line) -> line.contains("/animus record stop")), "the start command's own line carries the chips: " + alex.messages);

        recording = true;
        ticks(5);
        store.commit(List.of(new DocumentStore.Change("recordings/greeting.json", "missing", ToolkitRoutesTest.recording("greeting"))));
        recording = false;
        ticks(1);
        drain();
        assertEquals("greeting", navigation("guard").get("recording").getAsString());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Recording Greeting on guard")), alex.messages.toString());
        assertEquals("Movement", title(), "Movement opens again on the NPC");
        assertTrue(lore(MovementScreen.HEADER).contains("Now: replays recording Greeting"), lore(MovementScreen.HEADER));
    }

    @Test void aCancelledRecordingPutsNothingOnTheNpcAndARefusedStartIsForgotten() {
        toolkit.selectOnly(alex.id, "guard");
        recordings();
        click("New recording", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("greeting"));
        drain();
        ticks(CaptureFlow.COUNTDOWN);
        recording = true;
        ticks(1);
        recording = false;
        ticks(1);
        drain();
        assertFalse(npcs.find("guard").orElseThrow().json().has("modules"), "nothing was saved, so nothing is put on guard");
        assertNull(alex.window);

        toolkit.selection().clear(alex.id);
        recordings();
        click("New recording", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("greeting"));
        drain();
        assertTrue(toolkit.watching().contains(alex.id), "the countdown ticks");
        ticks(CaptureFlow.COUNTDOWN + CaptureFlow.START_WAIT);
        assertFalse(toolkit.watching().contains(alex.id), "a start the server refused is dropped");
    }

    @Test void recordingOverAnExistingNameAsksFirst() {
        recordings();
        click("New recording", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("wave"));
        assertEquals("Click again to confirm", name(RecordingScreen.CREATE));
        assertTrue(lore(RecordingScreen.CREATE).contains("Records over Wave"), lore(RecordingScreen.CREATE));
        click(RecordingScreen.CREATE, Click.LEFT);
        assertNull(alex.window, "the second click starts the countdown");
    }

    @Test void newRecordingWaitsUntilTheRecordingsAreReadSoATakenNameAlwaysAsks() {
        toolkit.open(alex.id, RecordingScreen::new);
        assertEquals("New recording", name(RecordingScreen.CREATE));
        assertEquals("Reading recordings", lore(RecordingScreen.CREATE));
        click(RecordingScreen.CREATE, Click.LEFT);
        assertNull(alex.prompt, "no name is asked while the taken names are unknown");
        drain();
        click("New recording", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("wave"));
        assertEquals("Click again to confirm", name(RecordingScreen.CREATE), "recording over wave asks");
    }

    @Test void theDetailNamesPlaybackPreviewsAndDeletesAfterAsking() throws IOException {
        toolkit.selectOnly(alex.id, "baker");
        recordings();
        click("Wave", Click.RIGHT);
        drain();
        assertEquals("Recording Wave", title());
        assertEquals(Map.of(4, "Wave", 19, "Rename", 20, "Preview", 28, "Playback", 35, "Delete", 45, "Back", 53, "Help"), buttons());
        for (int slot : List.of(13, 22, 31)) assertFalse(List.of("Delete").contains(name(slot)));
        assertTrue(lore("Playback").contains("Now: Loop"), lore("Playback"));
        click("Playback", Click.LEFT);
        drain();
        assertEquals("pingpong", navigation("baker").get("playback").getAsString());
        assertTrue(alex.messages.getLast().contains("Playback of Wave on baker: Back and forth"), alex.messages.getLast());
        assertTrue(lore("Playback").contains("Now: Back and forth"), lore("Playback"));
        click("Playback", Click.RIGHT);
        drain();
        assertEquals("loop", navigation("baker").get("playback").getAsString(), "right-click steps back");

        click("Rename", Click.LEFT);
        assertEquals("New recording name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("greet"));
        drain();
        assertTrue(alex.messages.getLast().contains("Renamed recording Wave to Greet, 1 NPC repointed"), alex.messages.getLast());
        assertEquals("greet", RecordingDocument.parse(store.read("recordings/greet.json"), JsonDocuments.MAX_BYTES).id());
        assertEquals("greet", navigation("baker").get("recording").getAsString());

        assertTrue(lore("Delete").contains("Deletes recording Greet and stops it on baker"), lore("Delete"));
        click("Delete", Click.LEFT);
        click(RecordingDetailScreen.DELETE, Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Deleted recording Greet, unassigned from 1 NPC"), alex.messages.getLast());
        assertFalse(npcs.find("baker").orElseThrow().json().has("modules"));
        assertTrue(shows("Recording Greet is gone"));
    }

    @Test void playbackWaitsForAnNpcThatPlaysItAndPreviewClosesWithAnEndChip() {
        toolkit.selectOnly(alex.id, "guard");
        recordings();
        click("Wave", Click.RIGHT);
        drain();
        assertTrue(lore("Playback").contains("Use it on guard first"), lore("Playback"));
        click("Playback", Click.LEFT);
        assertTrue(alex.commands.isEmpty());
        click("Preview", Click.LEFT);
        assertEquals(List.of("animus record preview wave"), alex.commands);
        assertNull(alex.window, "the window closes so the ghost shows");
        assertTrue(alex.messages.getLast().contains("/animus record clear"), alex.messages.getLast());
    }
}
