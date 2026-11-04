package art.arcane.animus.core.mount;

import art.arcane.animus.core.virtual.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeatGeometryTest {
    @Test void passengerFeetUseVehicleSeatMinusPassengerAttachment() {
        SeatGeometry horse = new SeatGeometry(0, 1.44375, 0, 0);
        Vec3 feet = horse.passengerFeet(new Vec3(10, 64, -3), 0, 0.6);
        assertEquals(10, feet.x(), 1e-9);
        assertEquals(64.84375, feet.y(), 1e-9);
        assertEquals(-3, feet.z(), 1e-9);
    }
    @Test void seatOffsetRotatesExactlyLikeTheNativeAttachmentTransform() {
        SeatGeometry llama = new SeatGeometry(0, 1.37, -0.3, 0);
        Vec3 front = llama.passengerFeet(Vec3.ZERO, 0, 0);
        assertEquals(-0.3, front.z(), 1e-9);
        assertEquals(0, front.x(), 1e-9);
        // Yaw 90 faces -X, so a seat behind the body centre moves toward +X.
        Vec3 turned = llama.passengerFeet(Vec3.ZERO, 90, 0);
        assertEquals(0.3, turned.x(), 1e-9);
        assertEquals(0, turned.z(), 1e-9);
    }
    @Test void riderClearanceCoversTheBodyAndARiderOnItsSeat() {
        SeatGeometry horse = new SeatGeometry(0, 1.44375, 0, 0);
        assertEquals(2.64375, horse.riderClearance(1.6, 0.6, 1.8), 1e-9);
        assertEquals(8.0, new SeatGeometry(0, 8, 0, 0).riderClearance(8, 0.6, 1.8), 1e-9, "the movement check's height limit bounds a very tall seat");
        assertEquals(1.975, new SeatGeometry(0, 0.1, 0, 0).riderClearance(1.975, 0.6, 1.8), 1e-9, "a tall body with a low seat keeps its own height");
    }
    @Test void geometryRejectsNonFiniteOffsets() {
        assertThrows(IllegalArgumentException.class, () -> new SeatGeometry(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SeatGeometry(0, Double.POSITIVE_INFINITY, 0, 0));
    }
}
