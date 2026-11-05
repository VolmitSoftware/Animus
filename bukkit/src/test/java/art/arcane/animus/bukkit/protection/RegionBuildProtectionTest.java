package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.building.BuildProtection.Action;
import art.arcane.animus.core.building.BuildProtection.Decision;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegionBuildProtectionTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STARTER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static BuildProtection.Request request(int x, Action action) {
        return new BuildProtection.Request(NPC, Optional.of(STARTER), "world", new BlockPos(x, 61, 3), action);
    }

    @Test void placeRunsTheBuildTestWithBlockPlaceAtTheBlockCenterForANonMember() {
        FakeRegionFlags flags = new FakeRegionFlags();
        assertEquals(Decision.ALLOW, new RegionBuildProtection(flags).decide(request(4, Action.PLACE)));
        FakeRegionFlags.Query query = flags.queries.getFirst();
        assertEquals("build", query.kind());
        assertEquals(List.of(Flag.BLOCK_PLACE), query.flags());
        assertEquals(new Vec3(4.5, 61.5, 3.5), query.at().point());
        assertEquals("world", query.at().world());
        assertNull(query.player(), "the NPC and whoever started the build are both ignored: the NPC is a non-member");
    }

    @Test void breakRunsTheBuildTestWithBlockBreak() {
        FakeRegionFlags flags = new FakeRegionFlags();
        new RegionBuildProtection(flags).decide(request(4, Action.BREAK));
        assertEquals(List.of(Flag.BLOCK_BREAK), flags.queries.getFirst().flags());
    }

    @Test void aFailedBuildTestSkipsTheBlockAndAPassedOneProceeds() {
        FakeRegionFlags flags = new FakeRegionFlags().build(20.5, 3.5, null, false);
        RegionBuildProtection protection = new RegionBuildProtection(flags);
        assertEquals(Decision.DENY, protection.decide(request(20, Action.PLACE)));
        assertEquals(Decision.ALLOW, protection.decide(request(0, Action.PLACE)));
    }

    @Test void blockPlaceAndBlockBreakApplyOnlyToTheirAction() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 3, Flag.BLOCK_PLACE, State.DENY).set(30, 3, Flag.BLOCK_BREAK, State.DENY);
        RegionBuildProtection protection = new RegionBuildProtection(flags);
        assertEquals(Decision.DENY, protection.decide(request(20, Action.PLACE)));
        assertEquals(Decision.ALLOW, protection.decide(request(20, Action.BREAK)));
        assertEquals(Decision.DENY, protection.decide(request(30, Action.BREAK)));
        assertEquals(Decision.ALLOW, protection.decide(request(30, Action.PLACE)));
    }

    @Test void failedFlagQueryRefusesTheChange() {
        FakeRegionFlags flags = new FakeRegionFlags();
        flags.failure = new IllegalStateException("WorldGuard is disabled");
        BuildProtection.Gate gate = new BuildProtection.Gate(new RegionBuildProtection(flags), (String message) -> { });
        assertFalse(gate.allows(request(0, Action.PLACE)));
        assertEquals(1, gate.failures());
        assertEquals(1, gate.denials());
    }
}
