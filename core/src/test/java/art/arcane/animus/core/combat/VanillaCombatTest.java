package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Enchantments, critical hits, weapon switching, potions and explosions through the engine. */
class VanillaCombatTest {
    private final MockCombatWorld world = new MockCombatWorld();
    private CombatEngine engine;
    private static final VanillaMelee.Weapon SHARP_NETHERITE = new VanillaMelee.Weapon("minecraft:netherite_sword", Map.of(VanillaMelee.SHARPNESS, 5));

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(7), (String line) -> {});
        return engine;
    }
    private static CombatSettings settings(String json, HealthSettings health) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module, health);
    }
    private UUID combatant(double x, double z, String json, HealthSettings health, CombatLoadout loadout) {
        UUID npc = world.npc(x, z, health.defense().maximumHealth());
        assertTrue(engine().register(npc, "npc-" + npc, settings(json, health), loadout));
        return npc;
    }
    private UUID target(double x, double z) { return combatant(x, z, "{\"melee\":{\"enabled\":false},\"pursuit\":{\"closeChase\":false}}", CombatTesting.health("{\"killable\":true,\"invulnerableTicks\":0}"), CombatLoadout.NONE); }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private static NpcItem item(String id, String components) { return new NpcItem(id, 1, components.isEmpty() ? Optional.empty() : Optional.of(components), Optional.empty()); }
    private static CombatLoadout carrying(String body, NpcItem hand, NpcItem... inventory) {
        List<NpcItem> carried = new ArrayList<>();
        Map<String, String> equipment = hand == null ? Map.of() : Map.of("main_hand", hand.id());
        Map<String, Map<String, Integer>> levels = hand == null ? Map.of() : Map.of("main_hand", EnchantmentLevels.of(hand.components()));
        if (hand != null) carried.add(hand);
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (NpcItem stored : inventory) { carried.add(stored); counts.merge(stored.id(), stored.count(), Integer::sum); }
        return new CombatLoadout(Optional.empty(), equipment, counts, List.of(), body, levels, carried);
    }
    private CombatEngine.PlayerHit swing(UUID npc, UUID player, VanillaMelee.Swing swing, VanillaMelee.Weapon weapon) {
        return engine().playerAttack(npc, player(player), MockCombatWorld.at(1, 64, 0), swing, weapon);
    }

    @Test void aSharpnessFiveNetheriteSwordKillsATwentyHealthGuardInTwoHits() {
        UUID guard = target(0, 0);
        UUID player = UUID.randomUUID();
        assertEquals(11, swing(guard, player, VanillaMelee.Swing.standing(8), SHARP_NETHERITE).outcome().applied(), 1e-9);
        assertEquals(List.of("magic"), world.criticals.get(guard), "Enchantment damage shows the enchanted sparkle");
        assertTrue(swing(guard, player, VanillaMelee.Swing.standing(8), SHARP_NETHERITE).outcome().killed());
    }
    @Test void aCriticalHitShowsBothParticlesAndDealsHalfTheBaseAgain() {
        UUID guard = combatant(0, 0, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"maximumHealth\":40}"), CombatLoadout.NONE);
        VanillaMelee.Swing falling = new VanillaMelee.Swing(8, 1, 0, 0, .5, false, false, false, false, false, false, false);
        assertEquals(15, swing(guard, UUID.randomUUID(), falling, SHARP_NETHERITE).outcome().applied(), 1e-9);
        assertEquals(List.of("critical+magic"), world.criticals.get(guard));
    }
    @Test void smiteCountsAgainstAnUndeadBody() {
        UUID zombie = combatant(0, 0, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"maximumHealth\":40}"), carrying("minecraft:zombie", null));
        VanillaMelee.Weapon smite = new VanillaMelee.Weapon("minecraft:iron_sword", Map.of(VanillaMelee.SMITE, 2));
        assertEquals(6 + 5, swing(zombie, UUID.randomUUID(), VanillaMelee.Swing.standing(6), smite).outcome().applied(), 1e-9);
    }
    @Test void aSweepStrikesTheNpcBeside() {
        UUID first = target(0, 0), beside = target(.8, 0), far = target(4, 0);
        VanillaMelee.Swing swing = new VanillaMelee.Swing(7, 1, 0, .5, 0, true, false, false, false, false, false, false);
        swing(first, UUID.randomUUID(), swing, new VanillaMelee.Weapon("minecraft:diamond_sword", Map.of()));
        assertEquals(20 - 7, world.npc(first).orElseThrow().health(), 1e-9);
        assertEquals(20 - (1 + .5 * 7), world.npc(beside).orElseThrow().health(), 1e-9);
        assertEquals(20, world.npc(far).orElseThrow().health(), 1e-9);
    }
    @Test void fireAspectSetsANpcThatTakesFireBurning() {
        UUID guard = combatant(0, 0, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"maximumHealth\":40,\"sources\":[\"players\",\"fire\"]}"), CombatLoadout.NONE);
        swing(guard, UUID.randomUUID(), VanillaMelee.Swing.standing(1), new VanillaMelee.Weapon("minecraft:wooden_sword", Map.of(VanillaMelee.FIRE_ASPECT, 2)));
        run(30);
        assertEquals(Boolean.TRUE, world.burning.get(guard));
        assertTrue(world.npc(guard).orElseThrow().health() < 39, "Burning hurts after the hit");
    }
    @Test void lootingAddsToTheDropsOfThePlayersKill() {
        int plain = 0, looted = 0;
        for (int round = 0; round < 40; round++) {
            UUID npc = combatant(round * 10, 50, "{\"melee\":{\"enabled\":false}}",
                    CombatTesting.health("{\"killable\":true,\"maximumHealth\":1,\"death\":{\"drops\":[{\"item\":{\"id\":\"minecraft:bone\"},\"chance\":1}]}}"), CombatLoadout.NONE);
            boolean withLooting = round % 2 == 0;
            int before = world.drops.size();
            engine().playerAttack(npc, player(UUID.randomUUID()), MockCombatWorld.at(round * 10 + 1, 64, 50), VanillaMelee.Swing.standing(5),
                    new VanillaMelee.Weapon("minecraft:iron_sword", withLooting ? Map.of(VanillaMelee.LOOTING, 3) : Map.of()));
            int amount = world.drops.subList(before, world.drops.size()).stream().mapToInt(CombatWorld.Drop::amount).sum();
            if (withLooting) looted += amount; else plain += amount;
        }
        assertEquals(20, plain, "One bone each without Looting");
        assertTrue(looted > 30, "Looting III adds up to three bones a kill: " + looted);
    }
    @Test void anNpcsSharpWeaponAddsItsEnchantmentToItsOwnHits() {
        combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"fromEquipment\":true}}",
                CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:netherite_sword", "[enchantments={sharpness:5,fire_aspect:1}]")));
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertEquals(11, world.realDamage.get(zombie), 1e-9);
        assertEquals(80, world.ignited.get(zombie), "Fire Aspect I sets the zombie burning for four seconds");
    }
    @Test void aWeaponRedirectFightsAsTheOtherItem() {
        combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"fromEquipment\":true},"
                + "\"weapons\":{\"redirects\":{\"stick\":\"diamond_sword\"}}}", CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:stick", "")));
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertEquals(7, world.realDamage.get(zombie), 1e-9);
    }
    @Test void autoswitchTakesTheBowFarAwayAndTheSwordUpClose() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"fromEquipment\":true,\"reach\":3},"
                + "\"ranged\":{\"fromEquipment\":true,\"range\":30},\"pursuit\":{\"closeChase\":false},\"weapons\":{\"autoswitch\":true}}";
        NpcItem sword = item("minecraft:iron_sword", ""), bow = item("minecraft:bow", "[enchantments={power:5}]");
        UUID npc = combatant(0, 0, json, CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, sword, bow, item("minecraft:stone_sword", "")));
        UUID zombie = world.monster("minecraft:zombie", 12, 0);
        run(2);
        assertEquals(Optional.of(bow), world.held.get(npc), "The bow comes out for a target beyond reach");
        assertEquals(1, world.helperLaunches);
        world.moveReal(zombie, 2, 0);
        run(3);
        assertEquals(Optional.empty(), world.held.get(npc), "Back to its own sword, the best melee weapon it carries");
        assertEquals(6, world.realDamage.get(zombie), 1e-9);
        engine().release(npc);
        assertEquals(Optional.empty(), world.held.get(npc));
    }
    @Test void aPowerBowShootsFullDrawCriticalArrowsWithPunchAndFlame() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"fromEquipment\":true,\"range\":30}}";
        combatant(0, 0, json, CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:bow", "[enchantments={power:5,flame:1,punch:2}]")));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = world.helpers.keySet().iterator().next();
        assertTrue(engine().helperHitReal(helper, new TargetRef(TargetKind.MOB, zombie)));
        double damage = world.realDamage.get(zombie);
        assertTrue(damage >= 15 && damage <= 24, "Power V full draw is 15, plus up to 9 for a critical arrow: " + damage);
        assertEquals(100, world.ignited.get(zombie), "Flame sets the target burning for five seconds");
        assertEquals(.4 + 2 * .6, world.realKnockback.get(zombie), 1e-9, "Punch II adds 1.2 to an arrow's 0.4");
    }
    @Test void multishotFiresThreeArrowsAndPiercingGoesThrough() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"fromEquipment\":true,\"range\":30}}";
        combatant(0, 0, json, CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:crossbow", "[enchantments={multishot:1}]")));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        assertEquals(3, world.helperLaunches);
        UUID pierced = combatant(0, 30, json.replace("\"range\":30}", "\"range\":30,\"safeShot\":false}"), CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:crossbow", "[enchantments={piercing:1}]")));
        UUID first = world.monster("minecraft:zombie", 5, 30), second = world.monster("minecraft:husk", 10, 30);
        run(2);
        UUID arrow = world.launchOrder.getLast();
        assertTrue(engine().helperHitReal(arrow, new TargetRef(TargetKind.MOB, first)));
        assertTrue(engine().ownsHelper(arrow), "Piercing I goes on through the first body");
        assertTrue(engine().helperHitReal(arrow, new TargetRef(TargetKind.MOB, first)), "Touching the same body again is no new hit");
        assertTrue(engine().helperHitReal(arrow, new TargetRef(TargetKind.MOB, second)));
        assertFalse(engine().ownsHelper(arrow));
        assertNotNull(world.realDamage.get(second));
        assertTrue(engine().registered(pierced));
    }
    @Test void aHeldSplashPotionSplashesFoesAroundWhereItBreaks() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"range\":30},\"weapons\":{\"autoswitch\":true}}";
        combatant(0, 0, json, CombatTesting.KILLABLE, carrying(CombatLoadout.PLAYER, item("minecraft:splash_potion", "[potion_contents={potion:\"minecraft:strong_poison\"}]")));
        UUID zombie = world.monster("minecraft:zombie", 10, 0), beside = world.monster("minecraft:husk", 11, 0), away = world.monster("minecraft:husk", 20, 0);
        run(2);
        assertEquals(RangedWeapon.POTION, world.launched.values().iterator().next());
        assertTrue(engine().helperHitReal(world.helpers.keySet().iterator().next(), new TargetRef(TargetKind.MOB, zombie)));
        assertEquals(List.of("minecraft:poison splash"), world.potions);
        assertEquals(432, world.effects.get(zombie).getFirst().durationTicks(), "The struck one takes the whole effect");
        assertTrue(world.effects.get(beside).getFirst().durationTicks() < 432, "A splash is weaker with distance");
        assertNull(world.effects.get(away));
    }
    @Test void aLingeringPotionLeavesACloudThatKeepsApplying() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"weapon\":\"potion\",\"effect\":{\"id\":\"minecraft:slowness\",\"durationTicks\":400},\"lingering\":true,\"range\":30,\"cooldownTicks\":1000}}";
        combatant(0, 0, json, CombatTesting.KILLABLE, CombatLoadout.NONE);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = world.helpers.keySet().iterator().next();
        world.helpers.put(helper, new Vec3(10, 64, 0));
        assertTrue(engine().helperHitBlock(helper));
        assertEquals(List.of("minecraft:slowness cloud"), world.potions);
        run(60);
        assertTrue(world.effects.get(zombie).size() >= 2, "A cloud applies again once a second");
        assertEquals(100, world.effects.get(zombie).getFirst().durationTicks(), "A lingering cloud applies a quarter of the effect");
    }
    @Test void anExplosionHurtsAnNpcAndBlastProtectionReducesIt() {
        UUID bare = combatant(0, 0, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"armorFromEquipment\":true}"), CombatLoadout.NONE);
        UUID creeper = UUID.randomUUID();
        WorldPosition at = MockCombatWorld.at(1, 64, 0);
        assertEquals(10, engine().exploded(bare, Optional.of(new TargetRef(TargetKind.MOB, creeper)), at, 10).applied(), 1e-9);
        CombatLoadout blast = new CombatLoadout(Optional.empty(), Map.of("chest", "minecraft:leather_chestplate"), Map.of(), List.of(), CombatLoadout.PLAYER,
                Map.of("chest", Map.of(VanillaMelee.BLAST_PROTECTION, 4)), List.of());
        UUID armored = combatant(0, 30, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"armorFromEquipment\":true}"), blast);
        double taken = engine().exploded(armored, Optional.empty(), MockCombatWorld.at(1, 64, 30), 10).applied();
        double armor = 10 * (1 - Math.max(3 / 5.0, 3 - 10 / 2.0) / 25);
        assertEquals(armor * (1 - 8 / 25.0), taken, 1e-9);
        UUID unharmed = combatant(0, 60, "{\"melee\":{\"enabled\":false}}", CombatTesting.health("{\"killable\":true,\"sources\":[\"players\"]}"), CombatLoadout.NONE);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine().exploded(unharmed, Optional.empty(), MockCombatWorld.at(1, 64, 60), 10).status());
    }
    @Test void baneOfArthropodsSlowsASpiderBodysChase() {
        UUID spider = combatant(0, 0, "{\"targets\":{\"players\":[\"Runner\"]},\"targeting\":{\"scanIntervalTicks\":1},\"pursuit\":{\"speed\":5}}",
                CombatTesting.health("{\"killable\":true,\"maximumHealth\":100}"), carrying("minecraft:spider", null));
        UUID runner = world.player("Runner", 8, 0);
        run(3);
        assertEquals(5, world.speeds.getLast(), 1e-9);
        engine().playerAttack(spider, player(runner), MockCombatWorld.at(1, 64, 0), VanillaMelee.Swing.standing(1),
                new VanillaMelee.Weapon("minecraft:iron_sword", Map.of(VanillaMelee.BANE, 5)));
        world.moveReal(runner, 9, 2);
        run(25);
        assertEquals(2, world.speeds.getLast(), 1e-9, "Slowness IV cuts the chase to 40 percent");
    }
}
