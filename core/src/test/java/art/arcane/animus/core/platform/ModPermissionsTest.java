package art.arcane.animus.core.platform;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModPermissionsTest {
    @Test void nodesBecomeNamespacedPermissionAtoms() {
        assertEquals(Optional.of("animus:admin"), ModPermissions.atom("animus.admin"));
        assertEquals(Optional.of("animus:ride.town"), ModPermissions.atom("animus.ride.town"));
        assertEquals(Optional.of("myserver:ride"), ModPermissions.atom("myserver.ride"));
    }

    @Test void nodesWithoutANamespaceOrWithInvalidCharactersHaveNoAtom() {
        assertEquals(Optional.empty(), ModPermissions.atom("admin"));
        assertEquals(Optional.empty(), ModPermissions.atom("Animus.Admin"));
        assertEquals(Optional.empty(), ModPermissions.atom("animus."));
        assertEquals(Optional.empty(), ModPermissions.atom(".admin"));
        assertEquals(Optional.empty(), ModPermissions.atom("animus.ride town"));
    }

    @Test void onlyTheStatusAndBuildDonationNodesAreOpenToEveryone() {
        assertTrue(ModPermissions.everyone("animus.status"));
        assertTrue(ModPermissions.everyone("animus.build.donate"));
        assertFalse(ModPermissions.everyone("animus.admin"));
        assertFalse(ModPermissions.everyone("animus.ride"));
    }
}
