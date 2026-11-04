package art.arcane.animus.core.mount;

import art.arcane.animus.core.virtual.Vec3;

/**
 * Where a body carries a passenger and where it attaches when it is one, in body-local coordinates.
 * Values come from the exact native entity type, so authoritative seats follow what clients render.
 */
public record SeatGeometry(double seatX, double seatY, double seatZ, double vehicleAttachmentY) {
    private static final double MOVEMENT_HEIGHT_LIMIT = 8;
    public SeatGeometry {
        if (!Double.isFinite(seatX) || !Double.isFinite(seatY) || !Double.isFinite(seatZ) || !Double.isFinite(vehicleAttachmentY))
            throw new IllegalArgumentException("Seat offsets must be finite");
    }
    /** Same rotation as the native attachment transform, so the seat turns with the vehicle. */
    public Vec3 passengerFeet(Vec3 vehicleFeet, float vehicleYaw, double passengerAttachmentY) {
        double radians = Math.toRadians(vehicleYaw), sin = Math.sin(radians), cos = Math.cos(radians);
        return new Vec3(vehicleFeet.x() + seatX * cos - seatZ * sin,
                vehicleFeet.y() + seatY - passengerAttachmentY,
                vehicleFeet.z() + seatZ * cos + seatX * sin);
    }
    /** Height a moving body plus a seated rider occupies, bounded by what the movement check accepts. */
    public double riderClearance(double bodyHeight, double riderAttachmentY, double riderHeight) {
        return Math.min(MOVEMENT_HEIGHT_LIMIT, Math.max(bodyHeight, seatY - riderAttachmentY + riderHeight));
    }
}
