package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The decoy real mobs fight in an NPC's place on Folia lives and moves only where the NPC's owner holds it. */
class ActorDecoyTest {
    private static final String WORLD = "8a0f6f1c-0000-4000-8000-000000000009";
    private static final class Decoy { Vec3 at; boolean valid = true, held = true; Decoy(Vec3 at) { this.at = at; } }
    private static final class Mob { final String name; Decoy target; boolean held = true; Mob(String name) { this.name = name; } }

    private static final class Host implements ActorDecoy.Host<Mob, Decoy> {
        Vec3 feet = new Vec3(0.5, 64, 0.5);
        boolean targetable = true, areaHeld = true;
        final List<Mob> mobs = new ArrayList<>();
        final List<Decoy> spawned = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        /** The decoy standing for the NPC now, as the world sees it. */
        Decoy live;
        VirtualNpcState.Snapshot self() {
            return new VirtualNpcState.Snapshot(UUID.fromString("00000000-0000-0000-0000-0000000000d1"), new WorldPosition(WORLD, feet),
                    new Hitbox(feet.subtract(new Vec3(.3, 0, .3)), feet.add(new Vec3(.3, 1.8, .3))), 20, true, 0);
        }
        @Override public Optional<VirtualNpcState.Snapshot> targetable() { return targetable ? Optional.of(self()) : Optional.empty(); }
        @Override public ActorDecoy.Scan<Mob> scan(VirtualNpcState.Snapshot self, Decoy decoy) {
            if (!areaHeld) return null;
            int drawn = 0; List<Mob> free = new ArrayList<>();
            for (Mob mob : mobs) {
                if (!mob.held) continue;
                if (decoy != null && mob.target == decoy) drawn++;
                else if (mob.target == null) free.add(mob);
            }
            return new ActorDecoy.Scan<>(drawn, free);
        }
        @Override public Decoy spawn(VirtualNpcState.Snapshot self) { Decoy made = new Decoy(self.position().point()); spawned.add(made); live = made; log.add("spawn"); return made; }
        @Override public boolean valid(Decoy decoy) { if (!decoy.held) throw new IllegalStateException("read off its region"); return decoy.valid; }
        @Override public boolean owns(Decoy decoy) { return decoy.held; }
        @Override public Vec3 at(Decoy decoy) { return decoy.at; }
        @Override public void move(Decoy decoy, Vec3 feet) { if (!decoy.held) throw new IllegalStateException("moved off its region"); decoy.at = feet; log.add("move"); }
        @Override public void remove(Decoy decoy) {
            decoy.valid = false; live = null; log.add("remove");
            for (Mob mob : mobs) if (mob.target == decoy) mob.target = null;
        }
        @Override public void removeThere(Decoy decoy) { live = null; log.add("remove there"); }
        @Override public void forget(Decoy decoy) { live = null; log.add("forget"); }
        @Override public boolean target(Mob mob, Decoy decoy) { if (!mob.held) throw new IllegalStateException("mob off its region"); mob.target = decoy; return true; }
    }
    private final Host host = new Host();
    private final ActorDecoy<Mob, Decoy> decoy = new ActorDecoy<>(host, 0);
    private void turns(int count) { for (int i = 0; i < count; i++) decoy.tick(); }

    @Test void aMobThatWouldFightTheNpcTargetsADecoyOnItsFeet() {
        Mob zombie = new Mob("zombie");
        host.mobs.add(zombie);
        turns(ActorDecoy.DRAW_TURNS);
        assertNotNull(host.live);
        assertSame(host.live, zombie.target);
        assertEquals(host.feet, host.live.at);
    }

    @Test void theDecoyFollowsTheNpcWithoutATeleport() {
        host.mobs.add(new Mob("zombie"));
        turns(ActorDecoy.DRAW_TURNS);
        host.feet = new Vec3(3.5, 64, 0.5);
        turns(1);
        assertEquals(host.feet, host.live.at);
        assertTrue(host.log.contains("move"));
    }

    @Test void atMostSixMobsAreDrawnToOneNpc() {
        for (int i = 0; i < 9; i++) host.mobs.add(new Mob("zombie" + i));
        turns(ActorDecoy.DRAW_TURNS * 3);
        assertEquals(ActorDecoy.PER_NPC, host.mobs.stream().filter((Mob mob) -> mob.target != null).count());
    }

    @Test void nothingIsDrawnWhenThisRegionDoesNotHoldTheArea() {
        host.mobs.add(new Mob("zombie"));
        host.areaHeld = false;
        turns(ActorDecoy.DRAW_TURNS);
        assertNull(host.live);
        assertTrue(host.spawned.isEmpty());
    }

    @Test void aDecoyAnotherRegionHoldsIsRemovedThereAndDrawnAgainHere() {
        host.mobs.add(new Mob("zombie"));
        turns(ActorDecoy.DRAW_TURNS);
        Decoy first = host.live;
        first.held = false;
        host.mobs.getFirst().target = null;
        turns(ActorDecoy.DRAW_TURNS);
        assertTrue(host.log.contains("remove there"), "removed on its own scheduler, never touched here");
        assertNotSame(first, host.live, "the new owner draws a decoy of its own");
        assertEquals(2, host.spawned.size());
    }

    @Test void aDecoyNoMobTargetsAndAnNpcNoLongerTargetableLoseIt() {
        Mob zombie = new Mob("zombie");
        host.mobs.add(zombie);
        turns(ActorDecoy.DRAW_TURNS);
        host.mobs.clear();
        turns(ActorDecoy.DRAW_TURNS);
        assertNull(host.live, "taken away until a mob would fight the NPC again");
        host.mobs.add(zombie); zombie.target = null;
        turns(ActorDecoy.DRAW_TURNS);
        assertNotNull(host.live);
        host.targetable = false;
        turns(1);
        assertNull(host.live);
        assertNull(zombie.target, "mobs on the decoy let go");
    }
}
