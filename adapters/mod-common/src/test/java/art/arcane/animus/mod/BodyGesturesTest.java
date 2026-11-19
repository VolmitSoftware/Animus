package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

/**
 * A body's gestures go out as this Minecraft version's own packets, checked against its own constants so the test holds
 * on every target (26.3 renumbered the animate actions and gave swings their own packet).
 */
class BodyGesturesTest {
    private static final int BODY = 4242;

    @Test void aSwingIsTheClientsMainArmSwing() {
        assertMainArmSwing(BodyGestures.swing(BODY, null));
    }

    @Test void aPlayerBodyAttackSwingsTheMainArm() {
        for (String type : List.of("minecraft:player", "minecraft:zombie", "minecraft:mannequin"))
            assertMainArmSwing(BodyGestures.attack(BODY, type, null));
    }

    @Test void golemsAndBeastsPlayTheirOwnAttack() {
        for (String type : List.of("minecraft:iron_golem", "minecraft:ravager", "minecraft:hoglin", "minecraft:zoglin", "minecraft:warden", "minecraft:creaking")) {
            ClientboundEntityEventPacket event = assertInstanceOf(ClientboundEntityEventPacket.class, BodyGestures.attack(BODY, type, null), type);
            assertEquals(4, event.getEventId(), type);
        }
    }

    @Test void wakingAndCriticalHitsUseThisVersionsAnimateActions() {
        assertAnimate(BodyGestures.gesture(BODY, 2, null), "WAKE_UP");
        assertAnimate(BodyGestures.gesture(BODY, 4, null), "CRITICAL_HIT");
        assertAnimate(BodyGestures.gesture(BODY, 5, null), "MAGIC_CRITICAL_HIT");
    }

    @Test void anOffHandSwingIsTheClientsOffHandSwing() {
        Packet<?> packet = BodyGestures.gesture(BODY, 3, null);
        if (packet instanceof ClientboundAnimatePacket animate) assertAnimate(animate, "SWING_OFF_HAND");
        else assertSwingPacket(packet, InteractionHand.OFF_HAND);
    }

    /** The client swings the main arm: through its own swing packet, or the animate action this version names a main-arm swing. */
    private static void assertMainArmSwing(Packet<?> packet) {
        if (packet instanceof ClientboundAnimatePacket animate) assertAnimate(animate, "SWING_MAIN_HAND");
        else assertSwingPacket(packet, InteractionHand.MAIN_HAND);
    }

    private static void assertAnimate(Packet<?> packet, String action) {
        ClientboundAnimatePacket animate = assertInstanceOf(ClientboundAnimatePacket.class, packet, action);
        assertEquals(BODY, animate.getId());
        Integer expected = constant(action);
        if (expected == null) fail("this version's animate packet has no " + action + "; action " + animate.getAction() + " is " + named(animate.getAction()));
        assertEquals(expected, animate.getAction(), action + " is action " + expected + "; action " + animate.getAction() + " is " + named(animate.getAction()));
    }

    private static void assertSwingPacket(Packet<?> packet, InteractionHand hand) {
        assertEquals("ClientboundSwingAnimationPacket", packet.getClass().getSimpleName());
        assertEquals(BODY, (int) (Integer) read(packet, "entityId"));
        assertEquals(hand, read(packet, "hand"));
    }

    private static Integer constant(String name) {
        try { return ClientboundAnimatePacket.class.getField(name).getInt(null); }
        catch (NoSuchFieldException absent) { return null; }
        catch (IllegalAccessException unreadable) { throw new AssertionError(unreadable); }
    }

    private static String named(int action) {
        for (Field field : ClientboundAnimatePacket.class.getFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class && field.getInt(null) == action) return field.getName();
            } catch (IllegalAccessException unreadable) { throw new AssertionError(unreadable); }
        }
        return "nothing";
    }

    private static Object read(Packet<?> packet, String accessor) {
        try { return packet.getClass().getMethod(accessor).invoke(packet); }
        catch (ReflectiveOperationException missing) { throw new AssertionError(missing); }
    }
}
