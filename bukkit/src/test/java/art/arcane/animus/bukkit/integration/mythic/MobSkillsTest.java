package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.api.NpcPosition;
import art.arcane.animus.bukkit.event.VirtualNpcDespawnEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.event.VirtualNpcSpawnEvent;
import art.arcane.animus.core.integration.MythicBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobSkillsTest {
    private final FakeMythic mythic = new FakeMythic(Set.of(), Set.of("Clock"));
    private final List<String> log = new ArrayList<>();
    private final MobSkills skills = new MobSkills(mythic, mythic, log::add);
    private final UUID viewer = UUID.fromString("abcd0000-0000-0000-0000-000000000000");

    private VirtualNpcInteractionEvent click(MythicHost.Npc npc, VirtualNpcInteractionEvent.Action action, boolean preview) {
        return new VirtualNpcInteractionEvent(FakeMythic.player(viewer), npc.uuid(), action, 2, preview);
    }

    @Test void clicksRunTheMobsInteractAndDamagedSkillsWithTheClickerAsTrigger() {
        MythicHost.Npc king = mythic.add("king", Optional.of(new MythicBinding("SkeletonKing", true)), true);
        skills.interacted(click(king, VirtualNpcInteractionEvent.Action.INTERACT, false));
        skills.interacted(click(king, VirtualNpcInteractionEvent.Action.ATTACK, false));
        assertEquals(List.of("INTERACT SkeletonKing by king for abcd", "DAMAGED SkeletonKing by king for abcd"), mythic.calls);
    }

    @Test void previewsUnboundDisabledAndDespawnedNpcsRunNothing() {
        MythicHost.Npc king = mythic.add("king", Optional.of(new MythicBinding("SkeletonKing", true)), true);
        MythicHost.Npc plain = mythic.add("plain", Optional.empty(), true);
        MythicHost.Npc off = mythic.add("off", Optional.of(new MythicBinding("SkeletonKing", false)), true);
        MythicHost.Npc away = mythic.add("away", Optional.of(new MythicBinding("SkeletonKing", true)), false);
        skills.interacted(click(king, VirtualNpcInteractionEvent.Action.INTERACT, true));
        for (MythicHost.Npc npc : List.of(plain, off, away)) skills.interacted(click(npc, VirtualNpcInteractionEvent.Action.INTERACT, false));
        assertEquals(List.of(), mythic.calls);
    }

    @Test void spawningRunsSpawnSkillsAndTimersRunUntilDespawn() {
        MythicHost.Npc clock = mythic.add("clock", Optional.of(new MythicBinding("Clock", true)), true);
        MythicHost.Npc king = mythic.add("king", Optional.of(new MythicBinding("SkeletonKing", true)), true);
        skills.spawned(new VirtualNpcSpawnEvent(clock.uuid(), "clock", new NpcPosition("missing-world", 1, 64, 2), false));
        skills.spawned(new VirtualNpcSpawnEvent(king.uuid(), "king", new NpcPosition("missing-world", 1, 64, 2), false));
        skills.tick();
        skills.tick();
        skills.despawned(new VirtualNpcDespawnEvent(clock.uuid(), "clock", false));
        skills.tick();
        assertEquals(List.of("SPAWN Clock by clock for none", "SPAWN SkeletonKing by king for none", "timer Clock by clock at tick 1", "timer Clock by clock at tick 2"), mythic.calls);
    }

    @Test void aFailingSkillIsLoggedNotThrown() {
        MythicHost.Npc king = mythic.add("king", Optional.of(new MythicBinding("SkeletonKing", true)), true);
        MobSkills failing = new MobSkills(new FakeMythic(Set.of(), Set.of()) {
            @Override public boolean runMobSkills(String mob, Trigger trigger, Caster caster, org.bukkit.Location origin, Optional<org.bukkit.entity.Entity> cause) {
                throw new IllegalStateException("broken mechanic");
            }
        }, mythic, log::add);
        failing.interacted(click(king, VirtualNpcInteractionEvent.Action.INTERACT, false));
        assertTrue(log.getFirst().contains("broken mechanic"), log.toString());
    }
}
