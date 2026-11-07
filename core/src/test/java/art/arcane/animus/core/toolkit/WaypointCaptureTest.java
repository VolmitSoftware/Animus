package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WaypointCaptureTest {
    static ScreenHost.Pose at(double x, double z) { return new ScreenHost.Pose(Set.of("world"), x, 64, z, 65.62, 0, 0, false); }

    static WaypointCapture three() {
        WaypointCapture capture = new WaypointCapture(Set.of("world"));
        capture.add(at(0, 0)); capture.add(at(10, 0)); capture.add(at(20, 0));
        return capture;
    }

    @Test void addingMakesTheNewPointCurrentAndInsertGoesAfterTheChosenOne() {
        WaypointCapture capture = three();
        assertEquals(2, capture.current());
        assertEquals(1, capture.insert(0, at(5, 5)), "insert returns the new index");
        assertEquals(1, capture.current(), "the inserted point becomes current");
        assertEquals(List.of(new Vec3(0.5, 64, 0.5), new Vec3(5.5, 64, 5.5), new Vec3(10.5, 64, 0.5), new Vec3(20.5, 64, 0.5)), capture.points());
        capture.insert(-1, at(-5, 0));
        assertEquals(new Vec3(-4.5, 64, 0.5), capture.points().getFirst(), "inserting after none puts the point first");
    }

    @Test void moveAndDeleteChangeOnlyTheChosenPoint() {
        WaypointCapture capture = three();
        capture.move(1, at(11, 3));
        assertEquals(new Vec3(11.5, 64, 3.5), capture.points().get(1));
        capture.select(2);
        capture.delete(0);
        assertEquals(List.of(new Vec3(11.5, 64, 3.5), new Vec3(20.5, 64, 0.5)), capture.points());
        assertEquals(1, capture.current(), "the current point keeps pointing at the same place");
        capture.delete(1);
        assertEquals(0, capture.current());
        assertThrows(IndexOutOfBoundsException.class, () -> capture.move(4, at(0, 0)));
        assertThrows(IllegalStateException.class, () -> capture.move(0, new ScreenHost.Pose(Set.of("nether"), 0, 64, 0, 65, 0, 0, false)));
    }

    @Test void aPointWaitIsSavedAndClearedAndRemoveLastStillWorks() throws IOException {
        WaypointCapture capture = three();
        capture.waitTicks(1, Optional.of(40));
        assertEquals(Optional.of("40"), capture.waits().get(1));
        WaypointSettings route = WaypointSettings.document(capture.document());
        assertEquals(new WaypointSettings.Wait(40, 40), route.points().get(1).waitTicks());
        capture.waitTicks(1, Optional.empty());
        assertEquals(Optional.empty(), capture.waits().get(1));
        assertThrows(IllegalArgumentException.class, () -> capture.waitTicks(0, Optional.of(WaypointSettings.MAXIMUM_WAIT + 1)));
        assertTrue(capture.removeLast());
        assertEquals(2, capture.points().size());
        assertEquals(1, capture.current());
    }

    @Test void aSavedRouteLoadsKeepingItsSettingsAndJunctionsFollowEdits() throws IOException {
        String text = "{\"schemaVersion\":1,\"order\":\"sequence\",\"speed\":3,\"waitTicks\":10,\"points\":[{\"x\":0,\"y\":64,\"z\":0,\"jitter\":2},"
                + "{\"x\":5,\"y\":64,\"z\":0,\"next\":[{\"to\":2}]},{\"x\":9,\"y\":64,\"z\":0,\"waitTicks\":[5,15]}]}";
        WaypointCapture capture = WaypointCapture.load("market", JsonDocuments.hash(text), text, Set.of("world"));
        assertEquals(Optional.of("market"), capture.source());
        assertEquals(Optional.of("5-15"), capture.waits().get(2));
        capture.insert(0, at(2, 2));
        JsonObject saved = JsonDocuments.object(capture.document());
        assertEquals(3, saved.get("speed").getAsInt());
        assertFalse(saved.has("loop"), "a loaded route keeps its own loop setting");
        assertEquals(2, saved.getAsJsonArray("points").get(0).getAsJsonObject().get("jitter").getAsInt(), "other point fields stay");
        assertEquals(3, saved.getAsJsonArray("points").get(2).getAsJsonObject().getAsJsonArray("next").get(0).getAsJsonObject().get("to").getAsInt(),
                "a junction follows its point when one is inserted before it");
        capture.delete(3);
        saved = JsonDocuments.object(capture.document());
        assertFalse(saved.getAsJsonArray("points").get(2).getAsJsonObject().has("next"), "a junction to a deleted point goes");
    }

    @Test void aRouteWithRecordingStepsIsLeftToTheEditor() {
        String text = "{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":64,\"z\":0},{\"recording\":\"wave\"}]}";
        IOException refused = assertThrows(IOException.class, () -> WaypointCapture.load("show", JsonDocuments.hash(text), text, Set.of("world")));
        assertTrue(refused.getMessage().contains("recording step"), refused.getMessage());
    }
}
