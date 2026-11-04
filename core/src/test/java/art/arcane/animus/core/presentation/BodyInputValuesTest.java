package art.arcane.animus.core.presentation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyInputValuesTest {
    @Test void npcProfileNamesPreserveSpacesAndUnicodeWithinTheWireLimit() {
        assertDoesNotThrow(() -> BodyRules.validateProfileName("Town Guard"));
        assertDoesNotThrow(() -> BodyRules.validateProfileName("Étoile 雪"));
        assertDoesNotThrow(() -> BodyRules.validateProfileName("a".repeat(16)));
        for (String invalid : new String[]{"", " ", "a".repeat(17), "Guard\nName", "Guard\u0000"})
            assertThrows(IllegalArgumentException.class, () -> BodyRules.validateProfileName(invalid));
    }
    @Test void signedTexturesRemainOpaqueAndBounded() {
        BodySkin skin = new BodySkin("e30=", "c2lnbmF0dXJl");
        assertEquals("e30=", skin.textureValue());
        assertEquals("c2lnbmF0dXJl", skin.signature());
        assertThrows(IllegalArgumentException.class, () -> new BodySkin("not base64!", null));
        assertThrows(IllegalArgumentException.class, () -> new BodySkin("e30=", "bad signature!"));
        assertThrows(IllegalArgumentException.class, () -> new BodySkin("A".repeat(32769), null));
        assertThrows(IllegalArgumentException.class, () -> new BodySkin("e30=", "A".repeat(8193)));
    }
    @Test void rejectNonFiniteCoordinatesAndInvalidPitchBeforePacketConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new BodyPose("w", Double.NaN, 64, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new BodyPose("w", 0, 64, Double.POSITIVE_INFINITY, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new BodyPose("w", 0, 64, 0, Float.NaN, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new BodyPose("w", 0, 64, 0, 0, 91, true));
        assertEquals(-90, new BodyPose("w", -1, 64, 0, 180, -90, true).pitch());
    }
}
