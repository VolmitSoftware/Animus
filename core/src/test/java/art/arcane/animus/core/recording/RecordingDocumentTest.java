package art.arcane.animus.core.recording;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingDocumentTest {
    private static final int CAP = 1_048_576;

    private static String fixture(String name) throws IOException { return SharedFixtures.text(SharedFixtures.fixture("recordings/" + name)); }

    private static String minimal(String frames, String events) {
        return "{\"schemaVersion\":1,\"revision\":1,\"id\":\"walk\",\"recordedBy\":\"8667ba71-b85a-4004-af54-457a9734eed7\",\"world\":\"world\","
                + "\"origin\":{\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"sampleTicks\":2,\"durationTicks\":4,"
                + "\"frames\":" + frames + ",\"events\":" + events + "}";
    }

    private static String refusal(String text) {
        return assertThrows(IOException.class, () -> RecordingDocument.parse(text, CAP)).getMessage();
    }

    @Test void bareBlockAndItemIdsAreMinecraftIds() throws IOException {
        RecordingDocument document = RecordingDocument.parse(minimal("[[0,0,0,0,0,0,0],[2,0,0,0,0,0,0],[4,0,0,0,0,0,0]]",
                "[{\"tick\":0,\"type\":\"use\",\"x\":1,\"y\":64,\"z\":2,\"block\":\"oak_door\"},"
                        + "{\"tick\":2,\"type\":\"equipment\",\"slot\":\"main_hand\",\"item\":{\"id\":\"netherite_axe\"}}]"), CAP);
        assertEquals(new RecordingEvent.Use(0, 1, 64, 2, "minecraft:oak_door"), document.events().get(0));
        assertTrue(document.events().get(1).toString().contains("minecraft:netherite_axe"), document.events().get(1).toString());
    }

    @Test void goldenFixtureParsesIntoTypedValues() throws IOException {
        RecordingDocument document = RecordingDocument.parse(fixture("market-loop.json"), CAP);
        assertEquals("market-loop", document.id());
        assertEquals(1, document.revision());
        assertEquals(UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), document.recordedBy());
        assertEquals("world", document.world());
        assertEquals(new RecordingDocument.Origin(100.5, 64, -20.5, 90, 0), document.origin());
        assertEquals(2, document.sampleTicks());
        assertEquals(80, document.durationTicks());
        assertEquals(5, document.frames().size());
        RecordingFrame second = document.frames().get(1);
        assertEquals(new RecordingFrame(20, 1.5, 0, 0.25, 92.5, -3, 5), second);
        assertTrue(second.onGround());
        assertTrue(second.sprinting());
        assertFalse(second.sneaking());
        assertTrue(document.frames().get(3).jumping());
        assertEquals(6, document.events().size());
        assertEquals(new RecordingEvent.Equipment(0, EquipmentSlot.MAIN_HAND, Optional.of(new NpcItem("minecraft:iron_sword", 1, Optional.empty(), Optional.empty()))),
                document.events().get(0));
        assertEquals(new RecordingEvent.Swing(20), document.events().get(1));
        assertEquals(new RecordingEvent.Use(40, 104, 64, -18, "minecraft:oak_door"), document.events().get(3));
        assertEquals(new RecordingEvent.Equipment(60, EquipmentSlot.MAIN_HAND, Optional.empty()), document.events().get(4));
        assertThrows(UnsupportedOperationException.class, () -> document.frames().clear());
    }

    @Test void serializerReproducesTheGoldenBytes() throws IOException {
        String golden = fixture("market-loop.json");
        assertEquals(golden, RecordingDocument.parse(golden, CAP).serialize());
    }

    @Test void nonCanonicalInputSerializesToTheGolden() throws IOException {
        RecordingDocument document = RecordingDocument.parse(fixture("roundtrip-input.json"), CAP);
        assertEquals(fixture("market-loop.json"), document.serialize());
        assertEquals(document, RecordingDocument.parse(document.serialize(), CAP));
    }

    @Test void framesAreRoundedToTheStoredGrid() {
        RecordingFrame frame = new RecordingFrame(3, 1.234, -0.006, 7.996, 10.26, -44.74, 0);
        assertEquals(1.23, frame.dx());
        assertEquals(-0.01, frame.dy());
        assertEquals(8.0, frame.dz());
        assertEquals(10.5, frame.yaw());
        assertEquals(-44.5, frame.pitch());
        assertThrows(IllegalArgumentException.class, () -> new RecordingFrame(0, 0, 0, 0, 0, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> new RecordingFrame(0, Double.NaN, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RecordingFrame(0, 0, 0, 0, 0, 91, 0));
    }

    @Test void framesMustRunFromZeroToTheDurationInStrictOrder() {
        assertTrue(refusal(minimal("[[1,0,0,0,0,0,0],[4,0,0,0,0,0,0]]", "[]")).contains("$.frames[0][0]: expected the first frame at tick 0"));
        assertTrue(refusal(minimal("[[0,0,0,0,0,0,0],[3,0,0,0,0,0,0]]", "[]")).contains("$.frames[1][0]: expected the last frame at durationTicks 4"));
        assertTrue(refusal(minimal("[[0,0,0,0,0,0,0],[2,0,0,0,0,0,0],[2,0,0,0,0,0,0]]", "[]"))
                .contains("$.frames[2][0]: expected a tick above the previous frame's 2"));
        assertTrue(refusal(minimal("[[0,0,0,0,0,0,0]]", "[]")).contains("$.frames: expected at least 2 frames"));
    }

    @Test void frameCountIsBoundedBySampleTicks() throws IOException {
        String message = refusal(fixture("too-many-frames.json"));
        assertTrue(message.contains("$.frames: expected at most 3 frames for 4 ticks sampled every 2"), message);
    }

    @Test void frameShapeErrorsNameTheElement() {
        assertTrue(refusal(minimal("[[0,0,0,0,0,0],[4,0,0,0,0,0,0]]", "[]")).contains("$.frames[0]: expected [tick, dx, dy, dz, yaw, pitch, flags]"));
        assertTrue(refusal(minimal("[[0,0,0,0,0,0,128],[4,0,0,0,0,0,0]]", "[]")).contains("$.frames[0][6]: expected flags 0..63"));
        assertTrue(refusal(minimal("[[0,0,0,0,0,0,0],[4,\"x\",0,0,0,0,0]]", "[]")).contains("$.frames[1][1]: expected a number"));
        assertTrue(refusal(minimal("[[0,0,0,0,400,0,0],[4,0,0,0,0,0,0]]", "[]")).contains("$.frames[0][4]: expected yaw -360..360"));
    }

    @Test void eventsAreCheckedByTypeAndShape() throws IOException {
        assertTrue(refusal(fixture("bad-event.json")).contains("$.events[0].type: expected swing, equipment or use"));
        String frames = "[[0,0,0,0,0,0,0],[4,0,0,0,0,0,0]]";
        assertTrue(refusal(minimal(frames, "[{\"tick\":5,\"type\":\"swing\"}]")).contains("$.events[0].tick: expected integer 0..4"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"swing\",\"hand\":1}]")).contains("$.events[0].hand: unknown field"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"equipment\",\"slot\":\"saddle\",\"item\":null}]"))
                .contains("$.events[0].slot: expected main_hand, off_hand, head, chest, legs or feet"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"equipment\",\"slot\":\"head\"}]")).contains("$.events[0].item: expected item object or null"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"equipment\",\"slot\":\"head\",\"item\":{\"id\":\":stone\"}}]"))
                .contains("$.events[0].item.id: expected namespace:item"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"equipment\",\"slot\":\"head\",\"item\":{\"id\":\"minecraft:stone\",\"imported\":{}}}]"))
                .contains("$.events[0].item.imported: not allowed in a recording"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"use\",\"x\":1.5,\"y\":64,\"z\":0,\"block\":\"minecraft:oak_door\"}]"))
                .contains("$.events[0].x: expected integer coordinate within ±30000000"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":1,\"type\":\"use\",\"x\":1,\"y\":64,\"z\":0,\"block\":\":door\"}]"))
                .contains("$.events[0].block: expected namespace:block"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":2,\"type\":\"swing\"},{\"tick\":1,\"type\":\"swing\"}]"))
                .contains("$.events[1]: expected events sorted by tick then type"));
        assertTrue(refusal(minimal(frames, "[{\"tick\":2,\"type\":\"swing\"},{\"tick\":2,\"type\":\"equipment\",\"slot\":\"head\",\"item\":null}]"))
                .contains("$.events[1]: expected events sorted by tick then type"));
    }

    @Test void atMost4096EventsAreAccepted() throws IOException {
        StringBuilder events = new StringBuilder("[");
        for (int i = 0; i < 4097; i++) events.append(i == 0 ? "" : ",").append("{\"tick\":1,\"type\":\"swing\"}");
        events.append("]");
        String frames = "[[0,0,0,0,0,0,0],[4,0,0,0,0,0,0]]";
        assertTrue(refusal(minimal(frames, events.toString())).contains("$.events: expected at most 4096 events"));
        String accepted = events.substring(0, events.length() - ",{\"tick\":1,\"type\":\"swing\"}]".length()) + "]";
        assertEquals(4096, RecordingDocument.parse(minimal(frames, accepted), CAP).events().size());
    }

    @Test void headerFieldsFollowTheDefinitionRules() {
        String frames = "[[0,0,0,0,0,0,0],[4,0,0,0,0,0,0]]";
        String valid = minimal(frames, "[]");
        assertTrue(refusal(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")).contains("$.schemaVersion: expected integer 1..1"));
        assertTrue(refusal(valid.replace("\"revision\":1", "\"revision\":0")).contains("$.revision: expected integer 1.."));
        assertTrue(refusal(valid.replace("\"id\":\"walk\"", "\"id\":\"Walk\"")).contains("$.id: expected 1–64 lowercase letters, digits, _ or -"));
        assertTrue(refusal(valid.replace("8667ba71-b85a-4004-af54-457a9734eed7", "steve")).contains("$.recordedBy: expected canonical player UUID"));
        assertTrue(refusal(valid.replace("\"world\":\"world\"", "\"world\":\" \"")).contains("$.world: expected nonblank string"));
        assertTrue(refusal(valid.replace("\"x\":0", "\"x\":40000000")).contains("$.origin.x: expected finite coordinate within ±30000000"));
        assertTrue(refusal(valid.replace("\"sampleTicks\":2", "\"sampleTicks\":21")).contains("$.sampleTicks: expected integer 1..20"));
        assertTrue(refusal(valid.replace("\"durationTicks\":4", "\"durationTicks\":72001")).contains("$.durationTicks: expected integer 1..72000"));
        assertTrue(refusal(valid.replace("\"world\":\"world\",", "\"world\":\"world\",\"extra\":true,")).contains("$.extra: unknown field"));
    }

    @Test void oversizeTextIsRefusedBeforeParsing() {
        String valid = minimal("[[0,0,0,0,0,0,0],[4,0,0,0,0,0,0]]", "[]");
        IOException error = assertThrows(IOException.class, () -> RecordingDocument.parse(valid + " ".repeat(64), valid.length() + 10));
        assertTrue(error.getMessage().startsWith("$: recording exceeds "), error.getMessage());
        String wide = valid.replace("\"world\":\"world\"", "\"world\":\"" + "é".repeat(40) + "\"");
        assertThrows(IOException.class, () -> RecordingDocument.parse(wide, wide.length() + 10));
    }

    @Test void constructedDocumentsSerializeAndParseBack() throws IOException {
        RecordingDocument document = new RecordingDocument(3, "made", UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), "world_nether",
                new RecordingDocument.Origin(0.123456789, -60, 30_000_000, -179.9, 45.25), 1, 2,
                List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, 0), new RecordingFrame(2, -1.25, 0.5, 2, -0.5, 0, RecordingFrame.GLIDING)),
                List.of(new RecordingEvent.Swing(1)));
        String text = document.serialize();
        assertTrue(text.contains("\"origin\": {\"x\": 0.123456789, \"y\": -60, \"z\": 30000000, \"yaw\": -179.9, \"pitch\": 45.25}"), text);
        assertTrue(text.contains("    [2, -1.25, 0.5, 2, -0.5, 0, 16]\n"), text);
        assertEquals(document, RecordingDocument.parse(text, CAP));
        assertEquals(text, RecordingDocument.parse(text, CAP).serialize());
        assertThrows(IllegalArgumentException.class, () -> new RecordingDocument(1, "made", document.recordedBy(), "world", document.origin(), 1, 2,
                List.of(new RecordingFrame(0, 0, 0, 0, 0, 0, 0)), List.of()));
    }

    @Test void emptyEventListsSerializeOnOneLine() throws IOException {
        String text = RecordingDocument.parse(minimal("[[0,0,0,0,0,0,0],[4,0,0,0,0,0,0]]", "[]"), CAP).serialize();
        assertTrue(text.endsWith("  ],\n  \"events\": []\n}\n"), text);
    }

    @Test void fromJsonChecksTheSerializedSize() throws IOException {
        com.google.gson.JsonObject source = art.arcane.animus.core.storage.JsonDocuments.object(fixture("market-loop.json"));
        assertEquals("market-loop", RecordingDocument.from(source, CAP).id());
        IOException error = assertThrows(IOException.class, () -> RecordingDocument.from(source, 256));
        assertTrue(error.getMessage().startsWith("$: recording is "), error.getMessage());
    }
}
