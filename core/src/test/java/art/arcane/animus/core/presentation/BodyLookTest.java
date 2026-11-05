package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyLookTest {
    static final UUID ID = UUID.fromString("0a1b2c3d-0000-4000-8000-00000000abcd");
    static NpcAppearance appearance(List<String> lines, boolean nameplate, NpcPose pose) {
        return new NpcAppearance(Map.of(), lines, nameplate, pose, Optional.empty());
    }

    @Test void shortPlainPlayerNamesKeepTheNativeNameplate() {
        BodyLook look = BodyLook.of(ID, "minecraft:player", "Town Guard", NpcAppearance.PLAIN);
        assertEquals("Town Guard", look.profileName());
        assertEquals(Optional.empty(), look.text());
        assertFalse(look.hideProfileName());
        assertEquals(0, look.extraIds());
    }

    @Test void longOrFormattedPlayerNamesMoveToATextDisplayAndHideTheProfileName() {
        BodyLook look = BodyLook.of(ID, "minecraft:player", "Captain of the Northern Watch", NpcAppearance.PLAIN);
        assertEquals("0a1b2c3d00004000", look.profileName());
        assertDoesNotThrow(() -> BodyRules.validateProfileName(look.profileName()));
        assertEquals(Optional.of("Captain of the Northern Watch"), look.text());
        assertTrue(look.hideProfileName());
        assertEquals(1, look.extraIds());
        BodyLook colored = BodyLook.of(ID, "minecraft:player", "&6Guard", NpcAppearance.PLAIN);
        assertEquals(Optional.of("§6Guard"), colored.text());
        assertTrue(colored.hideProfileName());
        assertEquals("Tom & Jerry", BodyLook.of(ID, "minecraft:player", "Tom & Jerry", NpcAppearance.PLAIN).profileName());
    }

    @Test void hologramLinesStackAboveTheNameWithFormattingCodes() {
        BodyLook native_ = BodyLook.of(ID, "minecraft:player", "Guard", appearance(List.of("&aQuest", "Talk to me"), true, NpcPose.STANDING));
        assertEquals("Guard", native_.profileName());
        assertEquals(Optional.of("§aQuest\n§rTalk to me"), native_.text());
        assertFalse(native_.hideProfileName());
        assertTrue(native_.aboveNativeNameplate());
        BodyLook shown = BodyLook.of(ID, "minecraft:horse", "Old Bess", appearance(List.of("For hire"), true, NpcPose.STANDING));
        assertEquals(Optional.of("For hire\nOld Bess"), shown.text());
        assertFalse(shown.aboveNativeNameplate());
    }

    @Test void hiddenNameplatesShowOnlyHologramLines() {
        BodyLook player = BodyLook.of(ID, "minecraft:player", "Guard", appearance(List.of(), false, NpcPose.STANDING));
        assertTrue(player.hideProfileName());
        assertEquals(Optional.empty(), player.text());
        assertEquals(0, player.extraIds());
        BodyLook horse = BodyLook.of(ID, "minecraft:horse", "Old Bess", appearance(List.of("Sale"), false, NpcPose.STANDING));
        assertEquals(Optional.of("Sale"), horse.text());
        assertEquals(Optional.empty(), BodyLook.of(ID, "minecraft:horse", "Old Bess", appearance(List.of(), false, NpcPose.STANDING)).text());
    }

    @Test void nonPlayerNamesRenderAsTextDisplays() {
        BodyLook look = BodyLook.of(ID, "minecraft:armor_stand", "Statue of the Founder", NpcAppearance.PLAIN);
        assertEquals(Optional.of("Statue of the Founder"), look.text());
        assertFalse(look.hideProfileName());
        assertEquals(1, look.extraIds());
    }

    @Test void sittingReservesASeat() {
        BodyLook look = BodyLook.of(ID, "minecraft:player", "Guard", appearance(List.of(), true, NpcPose.SITTING));
        assertTrue(look.seated());
        assertEquals(1, look.extraIds());
        assertEquals(2, BodyLook.of(ID, "minecraft:player", "Captain of the Northern Watch", appearance(List.of(), true, NpcPose.SITTING)).extraIds());
    }

    @Test void poseFlagsMatchNativeBits() {
        assertEquals(0, BodyLook.sharedFlags(NpcPose.STANDING, false));
        assertEquals(0x02, BodyLook.sharedFlags(NpcPose.SNEAKING, false));
        assertEquals(0x10, BodyLook.sharedFlags(NpcPose.SWIMMING, false));
        assertEquals(0x20, BodyLook.sharedFlags(NpcPose.STANDING, true));
        assertEquals(0, BodyLook.sharedFlags(NpcPose.SLEEPING, false));
        assertEquals("STANDING", BodyLook.nativePose(NpcPose.STANDING));
        assertEquals("CROUCHING", BodyLook.nativePose(NpcPose.SNEAKING));
        assertEquals("SLEEPING", BodyLook.nativePose(NpcPose.SLEEPING));
        assertEquals("SWIMMING", BodyLook.nativePose(NpcPose.SWIMMING));
        assertEquals("STANDING", BodyLook.nativePose(NpcPose.SITTING));
    }

    @Test void armorStandFlagsMatchClientBits() {
        assertEquals(0x01 | 0x04 | 0x08, BodyLook.armorStandFlags(new ArmorStandLook(true, true, false, false, Map.of())));
        assertEquals(0, BodyLook.armorStandFlags(new ArmorStandLook(false, false, true, true, Map.of())));
        assertEquals(0x10 | 0x08, BodyLook.SEAT_FLAGS);
    }

    @Test void hologramLinesAcceptHexAndTagsAndNeverTintTheNextLine() {
        BodyLook look = BodyLook.of(ID, "minecraft:horse", "&#ffaa00Old Bess", appearance(List.of("<green>Ride me", "plain"), true, NpcPose.STANDING));
        assertEquals(Optional.of("§aRide me\n§rplain\n§r§x§f§f§a§a§0§0Old Bess"), look.text());
        assertTrue(BodyLook.of(ID, "minecraft:player", "<red>Guard", NpcAppearance.PLAIN).hideProfileName());
    }

    @Test void textSitsAboveTheHeadOrTheNativeNameplate() {
        assertEquals(0.3, BodyLook.lift(1.8, 1.8, false), 1e-9);
        assertEquals(0.55, BodyLook.lift(1.8, 1.8, true), 1e-9);
        assertEquals(0.9, BodyLook.lift(1.6, 1.0, false), 1e-9);
    }
}
