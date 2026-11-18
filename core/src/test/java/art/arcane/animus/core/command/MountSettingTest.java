package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code /animus mount set} on a platform where NPCs cannot ride mobs. */
class MountSettingTest {
    private static final Optional<String> NO_MOBS = Optional.of("NPCs ride mobs only on Paper, Spigot or Folia");

    @Test void aMobOrEntityIsRefusedWhereNpcsCannotRideThemAndAnotherNpcStillWorks() {
        assertEquals(NO_MOBS, MountSetting.refused("minecraft:horse", NO_MOBS));
        assertEquals(NO_MOBS, MountSetting.refused("0f1e2d3c-4b5a-4968-8776-655443322110", NO_MOBS));
        assertEquals(Optional.empty(), MountSetting.refused("baker", NO_MOBS));
        assertEquals(Optional.empty(), MountSetting.refused("minecraft:horse", Optional.empty()));
    }
}
