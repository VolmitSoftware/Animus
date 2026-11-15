package art.arcane.animus.core.control;

import art.arcane.animus.core.navigation.BodyMobility;
import art.arcane.animus.core.navigation.MotionValidator;
import art.arcane.animus.core.navigation.VoxelSnapshot;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Map;
import java.util.Set;

/**
 * What a seat on an NPC answers on every runtime: which bodies their rider's client steers, where seated players stand
 * and get off, which ends leave a player where they are and which ones they are told about.
 */
public final class SeatRules {
    /** Bodies a rider's own client steers when they carry a saddle or harness; they take riders through riding instead. */
    private static final Set<String> SADDLED = Set.of("minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:skeleton_horse", "minecraft:zombie_horse",
            "minecraft:camel", "minecraft:camel_husk", "minecraft:pig", "minecraft:strider", "minecraft:nautilus", "minecraft:zombie_nautilus");
    private static final Set<String> IN_PLACE = Set.of("offline", "death", "real-vehicle", "world-changed", ControlInbox.CONNECTION_CLOSED, "attach-failed", "seat-unreachable");
    private static final Set<String> QUIET = Set.of("shift", "shutdown", "death", "offline", "stack-ended");
    private static final int GROUND_SEARCH = 16, STAND_CELLS = 256;
    private static final double PLAYER_WIDTH = 0.6, PLAYER_HEIGHT = 1.8;
    public static final String STEERED = "its rider's own client steers a saddled body; give it the riding module instead";
    private SeatRules() {}

    /** A body its rider's client steers once it wears a saddle, or a harness on a happy ghast. */
    public static boolean steered(String type, Map<EquipmentSlot, NpcItem> worn) {
        if (SADDLED.contains(type)) return worn.containsKey(EquipmentSlot.SADDLE);
        NpcItem body = worn.get(EquipmentSlot.BODY);
        return type.equals("minecraft:happy_ghast") && body != null && body.id().endsWith("_harness");
    }
    /** The player's destination already belongs to someone else, or the client never sat down: no teleport beside the NPC. */
    public static boolean leavesInPlace(String reason) { return IN_PLACE.contains(reason); }
    /** The line a player is told when getting off, or null for an end that needs no word. */
    public static String told(String npcName, String reason) {
        return QUIET.contains(reason) ? null : "Got off " + npcName + ": " + reason.replace('-', ' ');
    }

    /** The ground under {@code feet}, where seated players stand for the server so vanilla never treats them as flying. */
    public static WorldPosition ground(WorldPosition feet, MotionValidator.Sampler terrain) {
        Vec3 point = feet.point();
        int x = (int) Math.floor(point.x()), y = (int) Math.floor(point.y()), z = (int) Math.floor(point.z());
        for (int down = 0; down <= GROUND_SEARCH; down++) {
            VoxelSnapshot.Cell cell = terrain.sample(x, y - down, z);
            if (cell.unavailable() || cell.water()) return feet;
            double top = y - down + cell.top();
            if (cell.collides() && top <= point.y() + 0.000001) return new WorldPosition(feet.world(), new Vec3(point.x(), top, point.z()));
        }
        return feet;
    }
    /** Where a player getting off stands: clear ground beside the NPC, or the seat's own ground when none is clear. */
    public static WorldPosition standingPlace(WorldPosition ground, float yaw, double npcWidth, MotionValidator.Sampler terrain) {
        RideSteering.Probe stand = RideSteering.probe(BodyMobility.forBody("minecraft:player", PLAYER_WIDTH, PLAYER_HEIGHT), STAND_CELLS, terrain);
        double half = Math.max(npcWidth, PLAYER_WIDTH) / 2;
        for (Vec3 candidate : RideSteering.dismountCandidates(ground.point(), yaw, half)) {
            RideSteering.Result placed = stand.check(candidate, candidate);
            if (placed.outcome() == RideSteering.Outcome.MOVED && placed.grounded()) return new WorldPosition(ground.world(), placed.position());
        }
        return ground;
    }
}
