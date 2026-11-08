package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Greetings, event and combined rules, weapons, equipment, ammunition, regions and limits. */
class CombatParityTest {
    private final MockCombatWorld world = new MockCombatWorld();
    private final List<ProtectionAdapter.Request> protectionCalls = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private BiFunction<String, WorldPosition, Optional<Boolean>> regions = (String region, WorldPosition at) -> Optional.empty();
    private CombatBudgets budgets = CombatBudgets.DEFAULT;
    private CombatEngine engine;

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(budgets, world, new ProtectionAdapter() {
            @Override public Decision decide(Request request) {
                protectionCalls.add(request);
                return Decision.ALLOW;
            }
            @Override public Optional<Boolean> inRegion(String region, WorldPosition position) { return regions.apply(region, position); }
        }, new Random(7), diagnostics::add);
        return engine;
    }
    private static CombatSettings settings(String json) { return settings(json, CombatTesting.KILLABLE); }
    private static CombatSettings settings(String json, HealthSettings health) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module, health);
    }
    private UUID combatant(double x, double z, String json) { return combatant(x, z, json, CombatTesting.KILLABLE, CombatLoadout.NONE); }
    private UUID combatant(double x, double z, String json, CombatLoadout loadout) { return combatant(x, z, json, CombatTesting.KILLABLE, loadout); }
    private UUID combatant(double x, double z, String json, HealthSettings health, CombatLoadout loadout) {
        UUID npc = world.npc(x, z, 20);
        assertTrue(engine().register(npc, "npc-" + npc, settings(json, health), loadout));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }
    private Optional<TargetRef> target(UUID npc) { return engine().status(npc).orElseThrow().target(); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private static TargetRef mob(UUID id) { return new TargetRef(TargetKind.MOB, id); }
    private static CombatLoadout holding(String mainHand, Map<String, Integer> inventory) {
        return new CombatLoadout(Optional.empty(), Map.of("main_hand", mainHand), inventory);
    }

    @Test void warningsGoToTargetsAndGreetingsToEveryoneElseOncePerVisit() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"players\":[\"Outlaw\"]},\"melee\":{\"enabled\":false},\"pursuit\":{\"closeChase\":false},"
                + "\"targeting\":{\"scanIntervalTicks\":1},\"greetings\":{\"friendly\":\"Welcome\",\"hostile\":\"Halt\",\"range\":5,\"cooldownTicks\":0}}");
        UUID outlaw = world.player("Outlaw", 3, 0);
        UUID friend = world.player("Friend", 0, 3);
        UUID far = world.player("Far", 0, 12);
        run(5);
        assertEquals(List.of("[npc-" + npc + "]: Halt"), world.told.get(outlaw));
        assertEquals(List.of("[npc-" + npc + "]: Welcome"), world.told.get(friend));
        assertNull(world.told.get(far));
        world.moveReal(friend, 0, 20);
        run(3);
        world.moveReal(friend, 0, 3);
        run(3);
        assertEquals(2, world.told.get(friend).size(), "Leaving and coming back greets again");
    }
    @Test void aGreetingCooldownLimitsRepeats() {
        combatant(0, 0, "{\"targeting\":{\"scanIntervalTicks\":1},\"greetings\":{\"friendly\":\"Hello\",\"range\":5,\"cooldownTicks\":1000}}");
        UUID friend = world.player("Friend", 0, 3);
        run(3);
        world.moveReal(friend, 0, 20);
        run(3);
        world.moveReal(friend, 0, 3);
        run(3);
        assertEquals(1, world.told.get(friend).size());
    }
    @Test void aFightNearbyMakesTheAttackerATarget() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"events\":{\"kinds\":[\"player_hurts_player\"]}},\"targeting\":{\"scanIntervalTicks\":1}}");
        UUID attacker = world.player("Attacker", 3, 0);
        UUID victim = world.player("Victim", 4, 0);
        run(3);
        assertTrue(target(npc).isEmpty(), "Nobody is a target until a fight starts");
        engine().realDamaged(player(victim), player(attacker));
        run(2);
        assertEquals(player(attacker), target(npc).orElseThrow());
    }
    @Test void fightRulesCanNameWhatWasHurtAndRespectRangeAndIgnores() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"events\":{\"playerAttacks\":{\"categories\":[\"animals\"]}}},\"ignores\":{\"players\":[\"Farmer\"]},\"targeting\":{\"scanRange\":10}}");
        UUID farmer = world.player("Farmer", 3, 0);
        UUID poacher = world.player("Poacher", 2, 2);
        UUID distant = world.player("Distant", 30, 0);
        UUID cow = world.animal("minecraft:cow", 2, 0);
        UUID zombie = world.monster("minecraft:zombie", 2, 1);
        engine().realDamaged(mob(cow), player(farmer));
        engine().realDamaged(mob(cow), player(distant));
        engine().realDamaged(mob(zombie), player(poacher));
        run(2);
        assertTrue(target(npc).isEmpty(), "Ignored, too far, or not an animal");
        engine().realDamaged(mob(cow), player(poacher));
        run(2);
        assertEquals(player(poacher), target(npc).orElseThrow());
    }
    @Test void aGuardThatAssistsAttacksWhatItsChargeAttacks() {
        UUID charge = world.player("Charge", 2, 0);
        UUID npc = combatant(0, 0, "{\"guard\":{\"player\":\"" + charge + "\",\"assist\":true}}");
        UUID pig = world.animal("minecraft:pig", 3, 0);
        engine().realDamaged(mob(pig), player(charge));
        run(1);
        assertEquals(mob(pig), target(npc).orElseThrow());
        UUID other = combatant(0, 5, "{\"guard\":{\"player\":\"" + charge + "\"}}");
        engine().realDamaged(mob(world.animal("minecraft:cow", 3, 5)), player(charge));
        run(1);
        assertTrue(target(other).isEmpty(), "Without assist a guard only defends");
    }
    @Test void chatContainingTheTextMakesTheSpeakerATarget() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"chat\":[\"Surrender\"]}}");
        UUID speaker = world.player("Speaker", 3, 0);
        engine().chat(player(speaker), "hello there");
        run(2);
        assertTrue(target(npc).isEmpty());
        engine().chat(player(speaker), "I will never SURRENDER");
        run(2);
        assertEquals(player(speaker), target(npc).orElseThrow());
    }
    @Test void eventsAreQueuedWithABoundAndCountedWhenDropped() {
        combatant(0, 0, "{\"targets\":{\"chat\":[\"x\"]}}");
        UUID speaker = world.player("Speaker", 3, 0);
        for (int i = 0; i < 100; i++) engine().chat(player(speaker), "nothing");
        assertEquals(36, engine().droppedEvents());
        run(20);
        engine().chat(player(speaker), "nothing");
        assertEquals(36, engine().droppedEvents(), "The queue drains within its per-tick budget");
    }
    @Test void aTogetherGroupNeedsADifferentTargetForEachPart() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"together\":[[{\"entityTypes\":[\"minecraft:zombie\"]},{\"entityTypes\":[\"minecraft:skeleton\"]}]]},\"targeting\":{\"scanIntervalTicks\":1},"
                + "\"pursuit\":{\"closeChase\":false},\"melee\":{\"enabled\":false}}");
        UUID zombie = world.monster("minecraft:zombie", 4, 0);
        world.monster("minecraft:zombie", 5, 0);
        run(3);
        assertTrue(target(npc).isEmpty(), "Two zombies do not complete a zombie and a skeleton");
        world.monster("minecraft:skeleton", 6, 0);
        run(3);
        assertEquals(mob(zombie), target(npc).orElseThrow(), "The nearest member of the completed group");
    }
    @Test void permissionAndGroupRulesAskThePlatform() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"groups\":[\"bandits\"]},\"targeting\":{\"scanIntervalTicks\":1}}");
        UUID bandit = world.player("Bandit", 3, 0);
        world.player("Citizen", 2, 0);
        world.permissions.add(bandit + " group.bandits");
        run(2);
        assertEquals(player(bandit), target(npc).orElseThrow());
    }
    @Test void eachWeaponLaunchesItsOwnHelper() {
        for (String weapon : List.of("snowball", "trident", "fireball", "egg", "wither_skull", "llama_spit", "spectral_arrow")) {
            UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"enabled\":false},"
                    + "\"ranged\":{\"enabled\":true,\"weapon\":\"" + weapon + "\",\"cooldownTicks\":5}}");
            UUID zombie = world.monster("minecraft:zombie", 8, 0);
            run(2);
            assertEquals(Set.of(RangedWeapon.parse(weapon).orElseThrow()), Set.copyOf(world.launched.values()), weapon);
            UUID helper = world.launched.keySet().iterator().next();
            assertTrue(engine().helperHitReal(helper, mob(zombie)), weapon);
            assertFalse(engine().helperHitReal(helper, mob(zombie)), "A shot lands once: " + weapon);
            engine().unregister(npc);
            world.launched.clear();
            world.real.clear();
            world.realDamage.clear();
        }
    }
    @Test void ammunitionIsCountedFromTheInventoryAndRunsOut() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"cooldownTicks\":1,\"useAmmo\":true}}", holding("minecraft:bow", Map.of("minecraft:arrow", 2)));
        world.monster("minecraft:zombie", 8, 0);
        assertEquals(2, engine().status(npc).orElseThrow().ammunition());
        run(20);
        assertEquals(2, world.helperLaunches);
        assertEquals(0, engine().status(npc).orElseThrow().ammunition());
        UUID unlimited = combatant(0, 40, "{\"ranged\":{\"enabled\":true}}");
        assertEquals(-1, engine().status(unlimited).orElseThrow().ammunition(), "Not counted without useAmmo");
    }
    @Test void aHealingPotionHelpsAFriendWithoutProtectionOrDamage() {
        UUID charge = world.player("Charge", 6, 0);
        world.details(charge, 5, new CombatTarget.Details(20, CombatLoadout.EMPTY, CombatLoadout.EMPTY, List.of(), Set.of()));
        UUID npc = combatant(0, 0, "{\"targets\":{\"all\":[{\"healthBelow\":50,\"categories\":[\"players\"]}]},\"guard\":{\"player\":\"" + charge + "\"},\"targeting\":{\"scanIntervalTicks\":1},"
                + "\"ranged\":{\"enabled\":true,\"weapon\":\"potion\",\"effect\":{\"id\":\"minecraft:instant_health\",\"amplifier\":1},\"cooldownTicks\":5},\"melee\":{\"enabled\":true}}");
        run(2);
        assertEquals(player(charge), target(npc).orElseThrow(), "A helpful potion may choose its own charge");
        UUID helper = world.launched.keySet().iterator().next();
        assertEquals(RangedWeapon.POTION, world.launched.get(helper));
        protectionCalls.clear();
        assertTrue(engine().helperHitReal(helper, player(charge)));
        assertEquals(List.of(new CombatSettings.Effect("minecraft:instant_health", 1, 200)), world.effects.get(charge));
        assertTrue(world.realDamage.isEmpty());
        assertTrue(protectionCalls.isEmpty(), "Healing is not an attack");
    }
    @Test void aHarmfulPotionIsAnAttackThroughProtection() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"weapon\":\"potion\",\"effect\":{\"id\":\"minecraft:poison\",\"durationTicks\":100}}}");
        UUID zombie = world.monster("minecraft:zombie", 8, 0);
        run(2);
        UUID helper = world.launched.keySet().iterator().next();
        int asked = protectionCalls.size();
        engine().helperHitReal(helper, mob(zombie));
        assertEquals(asked + 1, protectionCalls.size(), "The landing asks protection once more");
        assertEquals(List.of(new CombatSettings.Effect("minecraft:poison", 0, 100)), world.effects.get(zombie));
        assertTrue(engine().registered(npc));
    }
    @Test void aPotionNeedsAnEffectAndOnlyAPotionHasOne() {
        assertThrows(IllegalArgumentException.class, () -> settings("{\"ranged\":{\"weapon\":\"potion\"}}"));
        assertThrows(IllegalArgumentException.class, () -> settings("{\"ranged\":{\"weapon\":\"arrow\",\"effect\":{\"id\":\"minecraft:speed\"}}}"));
    }
    @Test void damageAndArmorComeFromEquipmentWhenAsked() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"fromEquipment\":true,\"cooldownTicks\":20}}",
                holding("minecraft:diamond_sword", Map.of()));
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(1);
        assertEquals(7, world.realDamage.get(zombie));
        assertTrue(engine().registered(npc));
        UUID mapped = combatant(0, 30, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":2,\"weaponDamage\":{\"minecraft:stick\":11}}}",
                holding("minecraft:stick", Map.of()));
        UUID other = world.monster("minecraft:zombie", 2, 30);
        run(1);
        assertEquals(11, world.realDamage.get(other), "The weapon map replaces the fixed damage");
        CombatLoadout armored = new CombatLoadout(Optional.empty(), Map.of("chest", "minecraft:diamond_chestplate", "head", "minecraft:diamond_helmet"), Map.of());
        UUID tank = combatant(0, 60, "{}", CombatTesting.health("{\"killable\":true,\"armorFromEquipment\":true,\"invulnerableTicks\":0}"), armored);
        engine().playerAttack(tank, player(world.player("Hitter", 1, 60)), MockCombatWorld.at(1, 64, 60), 10);
        assertEquals(20 - 10 * (1 - .44), world.npc(tank).orElseThrow().health(), 1e-9);
        assertTrue(engine().registered(mapped));
    }
    @Test void aPursuitRegionKeepsTheFightInsideIt() {
        regions = (String region, WorldPosition at) -> Optional.of(region.equals("castle") && at.point().x() < 5);
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"pursuit\":{\"region\":\"castle\"},\"melee\":{\"reach\":3}}");
        UUID outside = world.monster("minecraft:zombie", 6, 0);
        run(3);
        assertTrue(target(npc).isEmpty(), "A target outside the region is not chosen");
        world.moveReal(outside, 3, 0);
        run(2);
        assertEquals(mob(outside), target(npc).orElseThrow());
        world.moveReal(outside, 8, 0);
        run(12);
        assertTrue(target(npc).isEmpty(), "Leaving the region ends the fight");
    }
    @Test void anUnavailableRegionIsReportedOnceAndNotApplied() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"pursuit\":{\"region\":\"castle\"}}");
        world.monster("minecraft:zombie", 6, 0);
        run(30);
        assertTrue(target(npc).isPresent());
        assertEquals(1, engine().regionUnavailable());
        assertEquals(1, diagnostics.stream().filter((String line) -> line.contains("pursuit region castle")).count());
    }
    @Test void scanRangeAndSpeedAreLimitedByTheServerSettings() {
        budgets = new CombatBudgets(true, 64, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 16, 3);
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":200},\"pursuit\":{\"speed\":9}}");
        world.monster("minecraft:zombie", 40, 0);
        run(3);
        assertTrue(target(npc).isEmpty(), "Beyond the server's maximum scan range");
        world.moveReal(world.real.keySet().iterator().next(), 12, 0);
        run(12);
        assertTrue(target(npc).isPresent());
        assertFalse(world.speeds.isEmpty());
        assertTrue(world.speeds.stream().allMatch((Double speed) -> speed == 3), "Pursuit speed is limited too: " + world.speeds);
        assertEquals(200, settings("{\"targeting\":{\"scanRange\":200}}").targeting().scanRange(), "Definitions may ask for more than 64");
    }
}
