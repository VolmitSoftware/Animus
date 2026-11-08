package art.arcane.animus.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.navigation.FallTracker;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real projectiles, fire, lava and falls hurting killable NPCs whose bodies are virtual. */
class RealDamageTest {
    private final MockCombatWorld world = new MockCombatWorld();
    private ProtectionAdapter protection = ProtectionAdapter.NONE;
    private final CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, (ProtectionAdapter.Request request) -> protection.decide(request),
            new Random(3), (String line) -> {});

    private UUID killable(String health, double maximum) {
        UUID npc = world.npc(0, 0, maximum);
        CombatSettings settings = CombatSettings.parse(HealthSettingsTest.definition("{\"animus:health\":" + health + "}", null)).orElseThrow();
        assertTrue(engine.register(npc, "villager", settings, CombatLoadout.NONE));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine.tick(); }
    private double health(UUID npc) { return world.npc(npc).orElseThrow().health(); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private static RealShot arrow(UUID shooter, double damage) {
        return RealShot.of(RealShot.Kind.ARROW, player(shooter), MockCombatWorld.at(-10, 65, 0), damage, Optional.empty());
    }
    private ProjectileFlight.Scanner scanner() {
        return (WorldPosition center, double radius, art.arcane.animus.core.virtual.SpatialNpcIndex.Budget budget) -> world.nearbyNpcs(center, radius, null, budget);
    }

    @Test void arrowDamageFollowsDrawSpeedPowerAndCriticalHits() {
        Random random = new Random(1);
        assertEquals(6, RealShot.arrowDamage(3.0, 2, 0, false, random), "A fully drawn bow");
        assertEquals(7, RealShot.arrowDamage(3.15, 2, 0, false, random), "A crossbow bolt is faster");
        assertEquals(2, RealShot.arrowDamage(.6, 2, 0, false, random), "A barely drawn bow");
        assertEquals(9, RealShot.arrowDamage(3.0, 2, 1, false, random), "Power I adds a block of damage per speed");
        for (int i = 0; i < 50; i++) {
            int critical = RealShot.arrowDamage(3.0, 2, 0, true, random);
            assertTrue(critical >= 6 && critical <= 10, "Rolled " + critical);
        }
    }

    @Test void otherProjectilesUseTheirVanillaDamage() {
        UUID alice = UUID.randomUUID();
        WorldPosition from = MockCombatWorld.at(0, 64, 0);
        assertEquals(8, RealShot.of(RealShot.Kind.TRIDENT, player(alice), from, 0, Optional.empty()).damage());
        assertEquals(0, RealShot.of(RealShot.Kind.SNOWBALL, player(alice), from, 0, Optional.empty()).damage());
        RealShot fireball = RealShot.of(RealShot.Kind.SMALL_FIREBALL, player(alice), from, 0, Optional.empty());
        assertEquals(5, fireball.damage());
        assertEquals(100, fireball.igniteTicks());
        assertEquals(RealShot.Landing.BOUNCE, RealShot.Kind.TRIDENT.landing());
        assertEquals(RealShot.Landing.REMOVE, RealShot.Kind.ARROW.landing());
        assertEquals(RealShot.Landing.DROP, RealShot.Kind.POTION.landing());
        assertEquals(RealShot.Landing.EXPLODE, RealShot.Kind.LARGE_FIREBALL.landing());
    }

    @Test void aFlightSegmentStrikesTheNearestShootableBodyOnce() {
        UUID near = killable("{\"killable\":true}", 20);
        UUID far = world.npc(4, 0, 20);
        assertTrue(engine.register(far, "villager", HealthSettingsTest.parse("{\"animus:health\":{\"killable\":true}}"), CombatLoadout.NONE));
        world.moveNpc(near, 2, 0);
        ProjectileFlight flight = new ProjectileFlight(0);
        Optional<ProjectileFlight.Hit> hit = flight.advance(MockCombatWorld.at(6, 65, 0), new Vec3(6, 0, 0), scanner(), engine::shootable);
        assertEquals(near, hit.orElseThrow().npc());
        assertEquals(1.7, hit.get().point().x(), 1e-9, "It meets the near face of the body");
        assertTrue(flight.spent());
        assertTrue(flight.advance(MockCombatWorld.at(9, 65, 0), new Vec3(3, 0, 0), scanner(), engine::shootable).isEmpty());
    }

    @Test void aFlightStartsWhereItWasFiredAndNeverBehindIt() {
        UUID behind = killable("{\"killable\":true}", 20);
        world.moveNpc(behind, -1, 0);
        ProjectileFlight flight = new ProjectileFlight(0);
        flight.launched(MockCombatWorld.at(0, 65, 0));
        assertTrue(flight.advance(MockCombatWorld.at(3, 65, 0), new Vec3(3, 0, 0), scanner(), engine::shootable).isEmpty(), "The body behind the shooter is never struck");
        assertEquals(new Vec3(0, 65, 0), flight.origin().orElseThrow());
    }

    @Test void aPiercingArrowPassesThroughAndStrikesEachBodyOnce() {
        UUID first = killable("{\"killable\":true}", 20);
        UUID second = world.npc(3, 0, 20);
        assertTrue(engine.register(second, "villager", HealthSettingsTest.parse("{\"animus:health\":{\"killable\":true}}"), CombatLoadout.NONE));
        ProjectileFlight flight = new ProjectileFlight(1);
        assertEquals(first, flight.advance(MockCombatWorld.at(1, 65, 0), new Vec3(2, 0, 0), scanner(), engine::shootable).orElseThrow().npc());
        assertFalse(flight.spent());
        assertEquals(second, flight.advance(MockCombatWorld.at(4, 65, 0), new Vec3(3, 0, 0), scanner(), engine::shootable).orElseThrow().npc());
        assertTrue(flight.spent());
    }

    @Test void bodiesThatCannotTakeShotsLetTheProjectilePass() {
        UUID npc = killable("{\"killable\":true,\"sources\":[\"players\"]}", 20);
        UUID plain = world.npc(3, 0, 20);
        ProjectileFlight flight = new ProjectileFlight(0);
        assertTrue(flight.advance(MockCombatWorld.at(6, 65, 0), new Vec3(8, 0, 0), scanner(), engine::shootable).isEmpty());
        assertFalse(engine.shootable(npc));
        assertFalse(engine.shootable(plain));
    }

    @Test void aScaledBodyIsStruckWhereItsLargerBoundsReach() {
        UUID npc = killable("{\"killable\":true}", 20);
        world.npcs.get(npc).resize(1.2, 3.6);
        world.index.upsert(world.npcs.get(npc).snapshot());
        Hitbox bounds = world.npc(npc).orElseThrow().bounds();
        assertEquals(67.6, bounds.max().y(), 1e-9);
        ProjectileFlight flight = new ProjectileFlight(0);
        assertEquals(npc, flight.advance(MockCombatWorld.at(3, 67, 0), new Vec3(6, 0, 0), scanner(), engine::shootable).orElseThrow().npc(),
                "A shot above a normal head still meets a doubled body");
    }

    @Test void aPlayersArrowHurtsKnocksBackAndCreditsTheKill() {
        UUID npc = killable("{\"killable\":true,\"invulnerableTicks\":0}", 10);
        UUID alice = world.player("Alice", -10, 0);
        assertEquals(CombatWorld.DamageStatus.APPLIED, engine.shot(npc, arrow(alice, 6)).status());
        assertEquals(4, health(npc));
        assertEquals(RealShot.KNOCKBACK, world.knocked.get(npc), 1e-9);
        assertTrue(engine.shot(npc, arrow(alice, 6)).killed());
        assertEquals(List.of(player(alice)), world.credits, "The shooter gets the kill");
    }

    @Test void shotsCountAsProjectilesAndFollowPermissionAndProtection() {
        UUID alice = world.player("Alice", -10, 0);
        UUID meleeOnly = killable("{\"killable\":true,\"sources\":[\"players\"]}", 20);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine.shot(meleeOnly, arrow(alice, 6)).status());
        UUID gated = killable("{\"killable\":true,\"attackerPermission\":\"town.hit\"}", 20);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine.shot(gated, arrow(alice, 6)).status());
        UUID guarded = killable("{\"killable\":true}", 20);
        protection = (ProtectionAdapter.Request request) -> request.action() == ProtectionAdapter.Action.PLAYER_ATTACK ? ProtectionAdapter.Decision.DENY : ProtectionAdapter.Decision.ALLOW;
        assertEquals(CombatWorld.DamageStatus.PROTECTED, engine.shot(guarded, arrow(alice, 6)).status());
        assertEquals(20, health(guarded));
    }

    @Test void snowballsOnlyPushAndPotionsHarmOrHeal() {
        UUID npc = killable("{\"killable\":true,\"invulnerableTicks\":0}", 20);
        UUID alice = world.player("Alice", -10, 0);
        WorldPosition from = MockCombatWorld.at(-10, 65, 0);
        engine.shot(npc, RealShot.of(RealShot.Kind.SNOWBALL, player(alice), from, 0, Optional.empty()));
        assertEquals(20, health(npc));
        assertEquals(RealShot.KNOCKBACK, world.knocked.get(npc), 1e-9);
        engine.shot(npc, RealShot.of(RealShot.Kind.POTION, player(alice), from, 0, Optional.of(new CombatSettings.Effect("minecraft:instant_damage", 1, 1))));
        assertEquals(8, health(npc), "Harming II deals 12");
        engine.shot(npc, RealShot.of(RealShot.Kind.POTION, player(alice), from, 0, Optional.of(new CombatSettings.Effect("minecraft:instant_health", 0, 1))));
        assertEquals(12, health(npc), "Healing I heals 4");
    }

    @Test void environmentalSourcesAreOffUnlessListed() {
        CombatSettings plain = HealthSettingsTest.parse("{\"animus:health\":{\"killable\":true}}");
        assertEquals(HealthSettings.DamageSource.ATTACKS, plain.health().defense().sources());
        UUID npc = killable("{\"killable\":true}", 20);
        world.contacts.put(npc, CombatWorld.Contact.LAVA);
        run(40);
        assertEquals(20, health(npc));
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine.environment(npc, HealthSettings.DamageSource.FALL, 5).status());
    }

    @Test void lavaBurnsTheBodyAndHurtsEveryHalfSecond() {
        UUID npc = killable("{\"killable\":true,\"sources\":[\"lava\",\"fire\"]}", 40);
        world.contacts.put(npc, CombatWorld.Contact.LAVA);
        run(41);
        assertEquals(40 - 4 * 4, health(npc), 1e-9, "4 damage at most every 10 ticks");
        assertEquals(Boolean.TRUE, world.burning.get(npc));
        world.contacts.remove(npc);
        double out = health(npc);
        run(60);
        assertEquals(out - 3, health(npc), 1e-9, "Still burning: 1 damage a second");
        world.contacts.put(npc, CombatWorld.Contact.WATER);
        run(11);
        assertEquals(Boolean.FALSE, world.burning.get(npc), "Water puts it out");
    }

    @Test void fireContactSetsItAlightAndTheBurnRunsOut() {
        UUID npc = killable("{\"killable\":true,\"sources\":[\"fire\"]}", 40);
        world.contacts.put(npc, CombatWorld.Contact.FIRE);
        run(11);
        assertEquals(39, health(npc), 1e-9);
        assertEquals(Boolean.TRUE, world.burning.get(npc));
        world.contacts.remove(npc);
        run(200);
        assertEquals(Boolean.FALSE, world.burning.get(npc), "The burn runs out after eight seconds");
        double settled = health(npc);
        run(60);
        assertEquals(settled, health(npc));
    }

    @Test void aSmallFireballSetsAFireListingNpcAlight() {
        UUID npc = killable("{\"killable\":true,\"invulnerableTicks\":0,\"sources\":[\"mobs\",\"projectiles\",\"fire\"]}", 40);
        UUID blaze = world.monster("minecraft:blaze", -10, 0);
        engine.shot(npc, RealShot.of(RealShot.Kind.SMALL_FIREBALL, new TargetRef(TargetKind.MOB, blaze), MockCombatWorld.at(-10, 65, 0), 0, Optional.empty()));
        assertEquals(35, health(npc), 1e-9);
        run(11);
        assertEquals(Boolean.TRUE, world.burning.get(npc));
    }

    @Test void aDeathInLavaCreditsNoOne() {
        UUID npc = killable("{\"killable\":true,\"sources\":[\"lava\"]}", 4);
        world.contacts.put(npc, CombatWorld.Contact.LAVA);
        run(11);
        assertEquals(CombatState.DEAD, engine.status(npc).orElseThrow().state());
        assertTrue(world.credits.isEmpty());
    }

    @Test void fallsHurtByTheBlocksPastTheSafeDistance() {
        assertEquals(7, FallTracker.damage(10, NpcAttributes.NONE));
        assertEquals(0, FallTracker.damage(3, NpcAttributes.NONE));
        assertEquals(4, FallTracker.damage(10, NpcAttributes.NONE.with("fall_damage_multiplier", .5)), "Rounded up as vanilla does");
        assertEquals(2, FallTracker.damage(10, NpcAttributes.NONE.with("safe_fall_distance", 8)));
        UUID npc = killable("{\"killable\":true,\"sources\":[\"fall\"]}", 20);
        assertEquals(CombatWorld.DamageStatus.APPLIED, engine.environment(npc, HealthSettings.DamageSource.FALL, 7).status());
        assertEquals(13, health(npc));
    }

    @Test void theTrackerMeasuresAFallFromWhereItStartedToWhereItLands() {
        FallTracker fall = new FallTracker();
        assertEquals(OptionalDouble.empty(), fall.step(70, 69.5, false, false));
        assertEquals(OptionalDouble.empty(), fall.step(69.5, 66, false, false));
        assertEquals(OptionalDouble.of(6), fall.step(66, 64, true, false));
        assertEquals(OptionalDouble.empty(), fall.step(64, 64, true, false), "Standing is no fall");
        fall.step(80, 75, false, false);
        assertEquals(OptionalDouble.of(0), fall.step(75, 64, true, true), "Water breaks the fall");
    }

    @Test void theSourceListParsesTheNewNames() {
        CombatSettings settings = HealthSettingsTest.parse("{\"animus:health\":{\"killable\":true,\"sources\":[\"players\",\"fire\",\"lava\",\"fall\"]}}");
        assertEquals(EnumSet.of(HealthSettings.DamageSource.PLAYERS, HealthSettings.DamageSource.FIRE, HealthSettings.DamageSource.LAVA,
                HealthSettings.DamageSource.FALL), settings.health().defense().sources());
    }
}
