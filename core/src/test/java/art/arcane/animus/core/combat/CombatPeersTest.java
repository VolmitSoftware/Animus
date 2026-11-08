package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One engine per NPC, as a regionized server runs combat: an engine only holds its own NPC and reaches every other
 * combatant through published views and messages its world delivers later, never through another engine's state.
 */
class CombatPeersTest {
    /** Shared terrain and entities; an engine sees NPCs it does not hold only as published views and reaches them by mail. */
    private static final class PeerWorld extends MockCombatWorld {
        final Map<UUID, CombatEngine> holders = new HashMap<>();
        final Map<UUID, CombatSettings> published = new HashMap<>();
        final Deque<Runnable> mail = new ArrayDeque<>();
        final List<String> delivered = new ArrayList<>();
        @Override public Optional<CombatTarget> peer(UUID npc) {
            CombatSettings settings = published.get(npc);
            Optional<VirtualNpcState.Snapshot> snapshot = npc(npc);
            if (settings == null || snapshot.isEmpty() || !snapshot.get().active()) return Optional.empty();
            return Optional.of(CombatEngine.npcTarget(npc, "npc-" + npc, snapshot.get(), "npc-" + npc, settings, CombatLoadout.NONE));
        }
        @Override public DamageOutcome strikePeer(TargetRef attacker, WorldPosition from, CombatTarget target, double amount, double knockback, DamageKind kind) {
            CombatEngine holder = holders.get(target.ref().id());
            mail.add(() -> { delivered.add("strike"); holder.struck(target.ref().id(), attacker, from, amount, knockback, kind); });
            return new DamageOutcome(DamageStatus.SCHEDULED, 0, false);
        }
        @Override public int alertPeers(String squad, UUID source, WorldPosition from, double range, TargetRef target, int limit) {
            int asked = 0;
            for (Map.Entry<UUID, CombatSettings> entry : published.entrySet()) {
                if (asked >= limit) break;
                if (entry.getKey().equals(source) || !entry.getValue().squad().name().equals(Optional.of(squad))) continue;
                if (npc(entry.getKey()).orElseThrow().position().point().distance(from.point()) > range) continue;
                CombatEngine holder = holders.get(entry.getKey());
                UUID member = entry.getKey();
                mail.add(() -> { delivered.add("alert"); holder.alerted(member, target); });
                asked++;
            }
            return asked;
        }
        /** Every other engine hears of the fight; the attacker's own engine already handled it. */
        @Override public void foughtElsewhere(TargetRef attacker, TargetRef victim) {
            for (Map.Entry<UUID, CombatEngine> entry : holders.entrySet())
                if (!entry.getKey().equals(attacker.id())) mail.add(() -> { delivered.add("fight"); entry.getValue().heardFight(victim, attacker); });
        }
        void deliver() { while (!mail.isEmpty()) mail.poll().run(); }
    }

    private final PeerWorld world = new PeerWorld();
    private final List<CombatEngine> engines = new ArrayList<>();

    private static CombatSettings settings(String json, String health) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module, CombatTesting.health(health));
    }
    private UUID combatant(double x, double z, double health, String json, String rules, CombatEngine.Allowance allowance) {
        UUID npc = world.npc(x, z, health);
        CombatSettings settings = settings(json, rules);
        CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(7), (String message) -> {}, allowance);
        assertTrue(engine.register(npc, "npc-" + npc, settings, CombatLoadout.NONE));
        world.holders.put(npc, engine); world.published.put(npc, settings);
        engines.add(engine);
        return npc;
    }
    private UUID combatant(double x, double z, double health, String json) { return combatant(x, z, health, json, "{\"killable\":true}"); }
    private UUID combatant(double x, double z, double health, String json, String rules) { return combatant(x, z, health, json, rules, CombatEngine.Allowance.UNLIMITED); }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) { for (CombatEngine engine : engines) engine.tick(); world.deliver(); } }
    private CombatEngine.Status status(UUID npc) { return world.holders.get(npc).status(npc).orElseThrow(); }
    private static TargetRef npcRef(UUID id) { return new TargetRef(TargetKind.NPC, id); }

    @Test void shouldStrikeAnNpcHeldByAnotherEngineThroughItsOwnerAndApplyItsDefense() {
        UUID hunter = combatant(0, 0, 20, "{\"targets\":{\"categories\":[\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":10,\"cooldownTicks\":1}}");
        UUID prey = combatant(2, 0, 20, "{}", "{\"killable\":true,\"armor\":0.5,\"invulnerableTicks\":0}");
        run(1);
        assertEquals(npcRef(prey), status(hunter).target().orElseThrow(), "the other engine's NPC is seen through its published view");
        assertTrue(world.delivered.contains("strike"), "the hit went to the prey's owner as a message");
        assertEquals(15, world.npc(prey).orElseThrow().health(), "the prey's own engine applied its armor");
        run(1);
        assertEquals(npcRef(hunter), status(prey).target().orElseThrow(), "the prey fights back against the NPC another engine holds");
    }

    @Test void shouldLandAnArrowOnAnNpcAnotherEngineHoldsAndCreditTheArcher() {
        UUID archer = combatant(0, 0, 20, "{\"targets\":{\"categories\":[\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"damage\":5,\"range\":30,\"cooldownTicks\":20}}");
        UUID dummy = combatant(10, 0, 20, "{\"melee\":{\"enabled\":false},\"pursuit\":{\"closeChase\":false}}", "{\"killable\":true,\"invulnerableTicks\":0}");
        run(1);
        UUID helper = world.helpers.keySet().iterator().next();
        world.helpers.put(helper, new art.arcane.animus.core.virtual.Vec3(11, 65, 0));
        run(1);
        assertTrue(world.removedHelpers.contains(helper), "the arrow is spent on the other engine's NPC");
        assertEquals(15, world.npc(dummy).orElseThrow().health(), "its owner applied the hit once");
        assertEquals(npcRef(archer), status(dummy).target().orElseThrow(), "the hit is credited to the archer");
    }

    @Test void shouldNeverTargetAnInvincibleNpcAnotherEngineHolds() {
        UUID hunter = combatant(0, 0, 20, "{\"targets\":{\"categories\":[\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1}}");
        combatant(2, 0, 20, "{\"targeting\":{\"fightBack\":false}}", "{}");
        run(3);
        assertTrue(status(hunter).target().isEmpty());
        assertFalse(world.delivered.contains("strike"));
    }

    @Test void shouldAlertSquadMatesHeldByOtherEnginesWithinTheSharedBudget() {
        String squad = "{\"targets\":{\"categories\":[\"monsters\"]},\"squad\":{\"name\":\"town\",\"alertRange\":20},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":8}}";
        UUID first = combatant(0, 0, 20, squad);
        UUID second = combatant(15, 0, 20, squad);
        UUID zombie = world.monster("minecraft:zombie", 5, 0);
        run(2);
        assertEquals(new TargetRef(TargetKind.MOB, zombie), status(first).target().orElseThrow());
        assertEquals(new TargetRef(TargetKind.MOB, zombie), status(second).target().orElseThrow(), "an alert reaches a member another engine holds");
        assertTrue(world.delivered.contains("alert"));
    }

    @Test void shouldDefendAChargeHurtInAFightAnotherEngineSaw() {
        UUID charge = world.player("Alice", 4, 0);
        UUID guard = combatant(0, 0, 20, "{\"guard\":{\"player\":\"" + charge + "\"},\"targeting\":{\"scanIntervalTicks\":200}}");
        UUID bandit = combatant(6, 0, 20, "{\"targets\":{\"players\":[\"alice\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"reach\":3}}");
        run(3);
        assertTrue(world.realDamage.containsKey(charge));
        assertTrue(world.delivered.contains("fight"));
        assertEquals(npcRef(bandit), status(guard).target().orElseThrow());
    }

    @Test void shouldShareTickAllowancesAcrossTheEnginesOfOneRegion() {
        Map<CombatEngine.Allowance.Kind, Integer> left = new EnumMap<>(CombatEngine.Allowance.Kind.class);
        CombatEngine.Allowance region = new CombatEngine.Allowance() {
            @Override public int take(Kind kind, int wanted) { int granted = Math.min(wanted, left.getOrDefault(kind, 0)); left.merge(kind, -granted, Integer::sum); return granted; }
            @Override public void refund(Kind kind, int unused) { if (unused > 0) left.merge(kind, unused, Integer::sum); }
        };
        String hunter = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16}}";
        UUID first = combatant(0, 0, 20, hunter, "{\"killable\":true}", region), second = combatant(0, 40, 20, hunter, "{\"killable\":true}", region);
        world.monster("minecraft:zombie", 5, 0); world.monster("minecraft:zombie", 5, 40);
        for (CombatEngine.Allowance.Kind kind : CombatEngine.Allowance.Kind.values()) left.put(kind, 64);
        left.put(CombatEngine.Allowance.Kind.SCANS, 1);
        int before = world.realScans;
        for (CombatEngine engine : engines) engine.tick();
        assertEquals(before + 1, world.realScans, "one scan for the whole region tick");
        assertEquals(0, left.get(CombatEngine.Allowance.Kind.SCANS));
        assertTrue(status(first).target().isPresent() ^ status(second).target().isPresent(), "exactly one engine acquired its target");
    }
}
