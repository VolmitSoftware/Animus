package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real WorldGuard flag resolution for an NPC, which WorldGuard sees as a non-member. */
class WorldGuardRegionFlagsTest {
    private static final ProtectedRegion GLOBAL = new GlobalProtectedRegion(ProtectedRegion.GLOBAL_REGION);

    private static ProtectedRegion claim() { return new ProtectedCuboidRegion("claim", BlockVector3.at(0, 0, 0), BlockVector3.at(10, 10, 10)); }
    private static ApplicableRegionSet outside() { return new RegionResultSet(new ArrayList<ProtectedRegion>(), GLOBAL); }
    private static ApplicableRegionSet inside(ProtectedRegion region) { return new RegionResultSet(new ArrayList<ProtectedRegion>(List.of(region)), GLOBAL); }

    @Test void npcMayBuildOutsideEveryRegion() {
        // WorldGuard refuses a null subject for the build flag; Animus once passed null and every block failed closed.
        assertTrue(WorldGuardRegionFlags.nonMemberMayBuild(outside(), Flag.BLOCK_PLACE));
        assertTrue(WorldGuardRegionFlags.nonMemberMayBuild(outside(), Flag.BLOCK_BREAK));
    }

    @Test void npcCannotBuildInARegionItIsNotAMemberOf() {
        assertFalse(WorldGuardRegionFlags.nonMemberMayBuild(inside(claim()), Flag.BLOCK_PLACE));
    }

    @Test void buildFlagsDecideInsideARegion() {
        ProtectedRegion open = claim();
        open.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
        assertTrue(WorldGuardRegionFlags.nonMemberMayBuild(inside(open), Flag.BLOCK_PLACE));
        open.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        assertFalse(WorldGuardRegionFlags.nonMemberMayBuild(inside(open), Flag.BLOCK_PLACE));
        assertTrue(WorldGuardRegionFlags.nonMemberMayBuild(inside(open), Flag.BLOCK_BREAK));
        ProtectedRegion closed = claim();
        closed.setFlag(Flags.BUILD, StateFlag.State.DENY);
        assertFalse(WorldGuardRegionFlags.nonMemberMayBuild(inside(closed), Flag.BLOCK_BREAK));
    }

    @Test void combatFlagsResolveForANonMember() {
        ProtectedRegion arena = claim();
        assertEquals(State.UNSET, WorldGuardRegionFlags.state(inside(arena), WorldGuardRegionFlags.NON_MEMBER, Flag.PVP));
        arena.setFlag(Flags.PVP, StateFlag.State.DENY);
        arena.setFlag(Flags.INVINCIBILITY, StateFlag.State.ALLOW);
        assertEquals(State.DENY, WorldGuardRegionFlags.state(inside(arena), WorldGuardRegionFlags.NON_MEMBER, Flag.PVP));
        assertEquals(State.ALLOW, WorldGuardRegionFlags.state(inside(arena), WorldGuardRegionFlags.NON_MEMBER, Flag.INVINCIBLE));
        assertEquals(State.ALLOW, WorldGuardRegionFlags.state(outside(), WorldGuardRegionFlags.NON_MEMBER, Flag.MOB_DAMAGE));
    }
}
