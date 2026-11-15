package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntitySensorTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SHEEP = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID ITEM = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final UUID CART = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
    private static final BiPredicate<UUID, String> NOBODY = (UUID player, String node) -> false;
    private static final EntitySensor.Limits LIMITS = new EntitySensor.Limits(64, 5);
    private static final VirtualNpcState.Snapshot BODY = new VirtualNpcState.Snapshot(NPC, new WorldPosition("w", new Vec3(0, 64, 0)),
            Hitbox.atFeet(new Vec3(0, 64, 0), 0.6, 1.8), 20, true, 1);

    private static List<EntitySensor.Watch> watches(String triggers) {
        String document = "{\"schemaVersion\":1,\"id\":\"town:sense\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":" + triggers + "}";
        BehaviorCompiler.Result result = BehaviorCompiler.compile(document);
        assertEquals(List.of(), result.diagnostics());
        List<EntitySensor.Watch> watches = new ArrayList<>();
        for (TriggerBinding binding : result.program().orElseThrow().triggers())
            watches.add(new EntitySensor.Watch("town:sense#" + binding.id(), (TriggerBinding.EntityEvent) binding.spec()));
        return watches;
    }
    private static TriggerEvent.Subject subject(UUID id, String type, String name, String... tags) { return new TriggerEvent.Subject(id, type, name, Set.of(tags)); }
    private static EntitySensor.Seen living(UUID id, String type, Vec3 at, Vec3 velocity) {
        return new EntitySensor.Seen(subject(id, type, ""), EntitySensor.Role.LIVING, type.equals("minecraft:player"), at, Hitbox.atFeet(at, 0.9, 1.3), velocity,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 1000);
    }
    private static EntitySensor.Seen sheep(Vec3 at) { return living(SHEEP, "minecraft:sheep", at, Vec3.ZERO); }
    private static EntitySensor.Party player() { return new EntitySensor.Party(subject(PLAYER, "minecraft:player", "Alex"), true); }
    private static List<TriggerEvent.Entity> sense(EntitySensor sensor, List<EntitySensor.Watch> watches, List<EntitySensor.Seen> seen) {
        return sensor.sense(BODY, watches, seen, Set.of(), true, List.of(), NOBODY, LIMITS).events();
    }

    @Test void proximityFiresOnceOnEnterAndOnLeaveWithTheEntityInContext() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"in\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":4},{\"id\":\"out\",\"event\":\"entity_proximity\",\"phase\":\"leave\",\"radius\":4}]");
        List<TriggerEvent.Entity> entered = sense(sensor, watches, List.of(sheep(new Vec3(3, 64, 0))));
        assertEquals(1, entered.size());
        assertEquals("town:sense#in", entered.getFirst().binding());
        assertEquals(SHEEP.toString(), entered.getFirst().context().get("entity"));
        assertEquals("minecraft:sheep", entered.getFirst().context().get("entity_type"));
        assertEquals("enter", entered.getFirst().context().get("phase"));
        assertEquals(Optional.empty(), entered.getFirst().player());
        assertEquals(List.of(), sense(sensor, watches, List.of(sheep(new Vec3(2, 64, 0)))));
        assertEquals(List.of(), sense(sensor, watches, List.of(sheep(new Vec3(4.3, 64, 0)))), "leaving needs half a block more");
        List<TriggerEvent.Entity> left = sense(sensor, watches, List.of(sheep(new Vec3(6, 64, 0))));
        assertEquals(List.of("town:sense#out"), left.stream().map(TriggerEvent.Entity::binding).toList());
    }

    @Test void anEntityThatIsNoLongerSeenLeavesUnlessTheLookWasCutShort() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"out\",\"event\":\"entity_proximity\",\"phase\":\"leave\",\"radius\":4}]");
        sense(sensor, watches, List.of(sheep(new Vec3(1, 64, 0))));
        assertEquals(List.of(), sensor.sense(BODY, watches, List.of(), Set.of(), false, List.of(), NOBODY, LIMITS).events());
        assertEquals(1, sense(sensor, watches, List.of()).size());
    }

    @Test void filtersTestTypeNameTagPlayersAndPermission() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("""
                [{"id":"mobs","event":"entity_proximity","phase":"enter","radius":8,"entity":"zombie,skeleton","players":false},
                 {"id":"named","event":"entity_proximity","phase":"enter","radius":8,"named":"bo*"},
                 {"id":"tagged","event":"entity_proximity","phase":"enter","radius":8,"tag":"pet"},
                 {"id":"vip","event":"entity_proximity","phase":"enter","radius":8,"permission":"town.vip"}]""");
        EntitySensor.Seen zombie = living(UUID.randomUUID(), "minecraft:zombie", new Vec3(2, 64, 0), Vec3.ZERO);
        EntitySensor.Seen bob = new EntitySensor.Seen(subject(UUID.randomUUID(), "minecraft:cow", "Bob", "pet"), EntitySensor.Role.LIVING, false, new Vec3(1, 64, 1),
                Hitbox.atFeet(new Vec3(1, 64, 1), 0.9, 1.4), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 100);
        EntitySensor.Seen alex = new EntitySensor.Seen(subject(PLAYER, "minecraft:player", "Alex"), EntitySensor.Role.LIVING, true, new Vec3(-1, 64, 0),
                Hitbox.atFeet(new Vec3(-1, 64, 0), 0.6, 1.8), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 100);
        List<TriggerEvent.Entity> events = sensor.sense(BODY, watches, List.of(zombie, bob, alex), Set.of(), true, List.of(),
                (UUID player, String node) -> player.equals(PLAYER) && node.equals("town.vip"), LIMITS).events();
        List<String> fired = events.stream().map((TriggerEvent.Entity event) -> event.binding() + "=" + event.context().get("entity_type")).sorted().toList();
        assertEquals(List.of("town:sense#mobs=minecraft:zombie", "town:sense#named=minecraft:cow", "town:sense#tagged=minecraft:cow", "town:sense#vip=minecraft:player"), fired);
        TriggerEvent.Entity vip = events.stream().filter((TriggerEvent.Entity event) -> event.binding().endsWith("vip")).findFirst().orElseThrow();
        assertEquals(Optional.of(PLAYER), vip.player());
        assertEquals("Alex", vip.context().get("entity_name"));
    }

    @Test void collisionFiresWhenATouchBeginsAndAgainOnlyAfterTheEntityStepsAway() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"bump\",\"event\":\"entity_collide\"},{\"id\":\"stand\",\"event\":\"entity_collide\",\"contact\":\"top\"}]");
        List<TriggerEvent.Entity> side = sense(sensor, watches, List.of(sheep(new Vec3(0.5, 64, 0))));
        assertEquals(List.of("town:sense#bump"), side.stream().map(TriggerEvent.Entity::binding).toList());
        assertEquals("side", side.getFirst().context().get("contact"));
        assertEquals(List.of(), sense(sensor, watches, List.of(sheep(new Vec3(0.6, 64, 0)))));
        assertEquals(List.of(), sense(sensor, watches, List.of(sheep(new Vec3(3, 64, 0)))));
        assertEquals(1, sense(sensor, watches, List.of(sheep(new Vec3(0.5, 64, 0)))).size());
        EntitySensor fresh = new EntitySensor(NPC);
        List<TriggerEvent.Entity> top = sense(fresh, watches, List.of(sheep(new Vec3(0, 65.8, 0))));
        assertEquals(List.of("town:sense#bump", "town:sense#stand"), top.stream().map(TriggerEvent.Entity::binding).sorted().toList());
        assertEquals("top", top.getFirst().context().get("contact"));
    }

    @Test void aFastEntityThatPassedThroughBetweenTwoLooksStillCollides() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"bump\",\"event\":\"entity_collide\"}]");
        EntitySensor.Seen passed = living(SHEEP, "minecraft:wolf", new Vec3(3, 64, 0), new Vec3(1.2, 0, 0));
        assertEquals(1, sense(sensor, watches, List.of(passed)).size());
    }

    @Test void aFreshItemNearTheNpcFiresWithItsThrowerAndAnOldOneDoesNot() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"gift\",\"event\":\"item_dropped\",\"radius\":3,\"item\":\"emerald\"},{\"id\":\"throw\",\"event\":\"item_dropped\",\"radius\":3,\"thrown\":true}]");
        EntitySensor.Seen old = item(UUID.randomUUID(), new Vec3(1, 64, 0), Vec3.ZERO, "minecraft:emerald", 400);
        assertEquals(List.of(), sense(sensor, watches, List.of(old)));
        List<TriggerEvent.Entity> gift = sense(sensor, watches, List.of(item(ITEM, new Vec3(2, 64, 0), Vec3.ZERO, "minecraft:emerald", 4)));
        assertEquals(List.of("town:sense#gift"), gift.stream().map(TriggerEvent.Entity::binding).toList());
        assertEquals("minecraft:emerald", gift.getFirst().context().get("item"));
        assertEquals(3L, gift.getFirst().context().get("amount"));
        assertEquals(ITEM.toString(), gift.getFirst().context().get("item_entity"));
        assertEquals(PLAYER.toString(), gift.getFirst().context().get("entity"));
        assertEquals(Optional.of(PLAYER), gift.getFirst().player());
        assertEquals(false, gift.getFirst().context().get("thrown"));
        assertEquals(List.of(), sense(sensor, watches, List.of(item(ITEM, new Vec3(1, 64, 0), Vec3.ZERO, "minecraft:emerald", 9))), "one item fires once");
        List<TriggerEvent.Entity> thrown = sense(sensor, watches, List.of(item(UUID.randomUUID(), new Vec3(0, 64.5, 0), new Vec3(0.4, 0, 0), "minecraft:bread", 2)));
        assertEquals(List.of("town:sense#throw"), thrown.stream().map(TriggerEvent.Entity::binding).toList());
        assertEquals(true, thrown.getFirst().context().get("thrown"));
    }
    private static EntitySensor.Seen item(UUID id, Vec3 at, Vec3 velocity, String type, int age) {
        return new EntitySensor.Seen(subject(id, "minecraft:item", ""), EntitySensor.Role.ITEM, false, at, Hitbox.atFeet(at, 0.25, 0.25), velocity,
                Optional.empty(), Optional.of(player()), Optional.empty(), Optional.of(type), 3, age);
    }

    @Test void anItemNobodyThrewIsNotADrop() {
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"gift\",\"event\":\"item_dropped\",\"radius\":6}]");
        // What /give leaves for its pickup animation, mob loot and block drops: fresh items with no thrower.
        EntitySensor.Seen loose = new EntitySensor.Seen(subject(UUID.randomUUID(), "minecraft:item", ""), EntitySensor.Role.ITEM, false, new Vec3(2, 64, 0),
                Hitbox.atFeet(new Vec3(2, 64, 0), 0.25, 0.25), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("minecraft:arrow"), 1, 1);
        EntitySensor sensor = new EntitySensor(NPC);
        assertEquals(List.of(), sense(sensor, watches, List.of(loose)));
        assertEquals(List.of(), sensor.senseForPlugins(BODY, watches, List.of(loose), Set.of(), true, List.of(), NOBODY, LIMITS).facts());
        EntitySensor.Party mob = new EntitySensor.Party(subject(UUID.randomUUID(), "minecraft:fox", ""), false);
        EntitySensor.Seen spat = new EntitySensor.Seen(subject(UUID.randomUUID(), "minecraft:item", ""), EntitySensor.Role.ITEM, false, new Vec3(2, 64, 0),
                Hitbox.atFeet(new Vec3(2, 64, 0), 0.25, 0.25), Vec3.ZERO, Optional.empty(), Optional.of(mob), Optional.empty(), Optional.of("minecraft:sweet_berries"), 1, 1);
        assertEquals("minecraft:fox", sense(sensor, watches, List.of(spat)).getFirst().context().get("entity_type"), "a mob that throws an item counts");
    }

    @Test void aMovingVehicleBumpsWithItsRiderAndAStillOneDoesNot() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"cart\",\"event\":\"vehicle_bump\",\"entity\":\"*minecart\",\"players\":true}]");
        EntitySensor.Seen still = cart(Vec3.ZERO, Optional.of(player()));
        assertEquals(List.of(), sense(sensor, watches, List.of(still)));
        List<TriggerEvent.Entity> bumped = sense(sensor, watches, List.of(cart(new Vec3(0.5, 0, 0), Optional.of(player()))));
        assertEquals(1, bumped.size());
        assertEquals(Optional.of(PLAYER), bumped.getFirst().player());
        assertEquals(CART.toString(), bumped.getFirst().context().get("entity"));
        assertEquals(PLAYER.toString(), bumped.getFirst().context().get("rider"));
        assertEquals(0.5, (double) bumped.getFirst().context().get("speed"), 1e-9);
        assertEquals(List.of(), sense(new EntitySensor(NPC), watches, List.of(cart(new Vec3(0.5, 0, 0), Optional.empty()))), "players: true needs a rider");
    }
    private static EntitySensor.Seen cart(Vec3 velocity, Optional<EntitySensor.Party> rider) {
        Vec3 at = new Vec3(0.6, 64, 0);
        return new EntitySensor.Seen(subject(CART, "minecraft:minecart", ""), EntitySensor.Role.VEHICLE, false, at, Hitbox.atFeet(at, 0.98, 0.7), velocity,
                rider, Optional.empty(), Optional.empty(), Optional.empty(), 0, 100);
    }

    @Test void theNpcsOwnMountAndThePlayersSeatedOnItNeverTouchBumpOrLeadPastIt() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"touch\",\"event\":\"entity_collide\"},{\"id\":\"cart\",\"event\":\"vehicle_bump\"},"
                + "{\"id\":\"led\",\"event\":\"leash_pass\",\"radius\":3},{\"id\":\"near\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":4}]");
        UUID horse = UUID.randomUUID();
        EntitySensor.Seen ridden = cart(new Vec3(0.5, 0, 0), Optional.empty());
        EntitySensor.Seen seated = living(PLAYER, "minecraft:player", new Vec3(0, 64, 0), Vec3.ZERO);
        EntitySensor.Seen led = new EntitySensor.Seen(subject(horse, "minecraft:horse", ""), EntitySensor.Role.LIVING, false, new Vec3(0, 64, 0),
                Hitbox.atFeet(new Vec3(0, 64, 0), 1.4, 1.6), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.of(player()), Optional.empty(), 0, 100);
        List<TriggerEvent.Entity> events = sensor.sense(BODY, watches, List.of(ridden, seated, led), Set.of(CART, PLAYER, horse), true, List.of(), NOBODY, LIMITS).events();
        assertEquals(List.of("town:sense#near", "town:sense#near", "town:sense#near"), events.stream().map(TriggerEvent.Entity::binding).toList(),
                "they still come near, and nothing else");
        List<TriggerEvent.Entity> sheep = sensor.sense(BODY, watches, List.of(ridden, seated, led, sheep(new Vec3(0.2, 64, 0))), Set.of(CART, PLAYER, horse), true,
                List.of(), NOBODY, LIMITS).events();
        assertEquals(List.of("town:sense#near", "town:sense#touch"), sheep.stream().map(TriggerEvent.Entity::binding).sorted().toList(), "anything else still touches");
    }

    @Test void aLeashedMobLedPastTheNpcFiresForTheHolder() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"led\",\"event\":\"leash_pass\",\"radius\":3,\"entity\":\"sheep\"}]");
        EntitySensor.Seen led = new EntitySensor.Seen(subject(UUID.randomUUID(), "minecraft:sheep", ""), EntitySensor.Role.LIVING, false, new Vec3(2, 64, 0),
                Hitbox.atFeet(new Vec3(2, 64, 0), 0.9, 1.3), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.of(player()), Optional.empty(), 0, 100);
        List<TriggerEvent.Entity> events = sense(sensor, watches, List.of(sheep(new Vec3(2, 64, 0)), led));
        assertEquals(1, events.size(), "only the leashed mob fires");
        assertEquals(Optional.of(PLAYER), events.getFirst().player());
        assertEquals(PLAYER.toString(), events.getFirst().context().get("holder"));
    }

    @Test void anotherNpcCrossingTheRadiusFiresNpcProximity() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"guard\",\"event\":\"npc_proximity\",\"phase\":\"enter\",\"radius\":5,\"npc\":\"town:guard*\"}]");
        UUID guard = UUID.randomUUID(), baker = UUID.randomUUID();
        List<EntitySensor.Neighbour> near = List.of(new EntitySensor.Neighbour(NPC, "town:self", new Vec3(0, 64, 0)),
                new EntitySensor.Neighbour(guard, "town:guard_2", new Vec3(3, 64, 0)), new EntitySensor.Neighbour(baker, "town:baker", new Vec3(1, 64, 0)));
        List<TriggerEvent.Entity> events = sensor.sense(BODY, watches, List.of(), Set.of(), true, near, NOBODY, LIMITS).events();
        assertEquals(1, events.size());
        assertEquals("town:guard_2", events.getFirst().context().get("npc"));
        assertEquals(guard.toString(), events.getFirst().context().get("npc_uuid"));
        assertEquals(List.of(), sensor.sense(BODY, watches, List.of(), Set.of(), true, near, NOBODY, LIMITS).events());
    }

    @Test void factsForOtherPluginsComeOnlyWhenAsked() {
        EntitySensor sensor = new EntitySensor(NPC);
        EntitySensor.Result quiet = sensor.sense(BODY, List.of(), List.of(sheep(new Vec3(0.4, 64, 0))), Set.of(), true, List.of(), NOBODY, LIMITS);
        assertEquals(List.of(), quiet.facts());
        EntitySensor.Result told = new EntitySensor(NPC).senseForPlugins(BODY, List.of(), List.of(sheep(new Vec3(0.4, 64, 0))), Set.of(), true, List.of(), NOBODY, LIMITS);
        assertEquals(List.of(EntitySensor.Fact.Kind.CONTACT), told.facts().stream().map(EntitySensor.Fact::kind).toList());
        assertEquals(SHEEP, told.facts().getFirst().entity());
    }

    @Test void trackingStopsAtTheLimit() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"in\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":8}]");
        List<EntitySensor.Seen> crowd = new ArrayList<>();
        for (int index = 0; index < 5; index++) crowd.add(living(UUID.randomUUID(), "minecraft:chicken", new Vec3(index, 64, 2), Vec3.ZERO));
        assertEquals(3, sensor.sense(BODY, watches, crowd, Set.of(), true, List.of(), NOBODY, new EntitySensor.Limits(3, 5)).events().size());
        assertEquals(2, sensor.overflow());
    }

    @Test void everySensedContextIsWhatARunCanRead() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("""
                [{"id":"gift","event":"item_dropped","radius":3},{"id":"cart","event":"vehicle_bump"},{"id":"near","event":"entity_proximity","phase":"enter","radius":6},
                 {"id":"touch","event":"entity_collide"},{"id":"led","event":"leash_pass","radius":3}]""");
        EntitySensor.Seen led = new EntitySensor.Seen(subject(UUID.randomUUID(), "minecraft:sheep", ""), EntitySensor.Role.LIVING, false, new Vec3(2, 64, 0),
                Hitbox.atFeet(new Vec3(2, 64, 0), 0.9, 1.3), Vec3.ZERO, Optional.empty(), Optional.empty(), Optional.of(player()), Optional.empty(), 0, 100);
        List<TriggerEvent.Entity> events = new ArrayList<>(sense(sensor, watches, List.of(item(ITEM, new Vec3(2, 64, 0), Vec3.ZERO, "minecraft:emerald", 4),
                cart(new Vec3(0.5, 0, 0), Optional.of(player())), sheep(new Vec3(0.4, 64, 0)), led)));
        events.addAll(EntitySensor.speech(NPC, watches("[{\"id\":\"ear\",\"event\":\"npc_speech\",\"radius\":8}]"), UUID.randomUUID(), "crier", "Hear ye", 2));
        assertEquals(Set.of("town:sense#gift", "town:sense#cart", "town:sense#near", "town:sense#touch", "town:sense#led", "town:sense#ear"),
                events.stream().map(TriggerEvent.Entity::binding).collect(java.util.stream.Collectors.toSet()));
        for (TriggerEvent.Entity event : events)
            assertDoesNotThrow(() -> new art.arcane.animus.core.behavior.BehaviorEngine.Context(new art.arcane.animus.api.NpcHandle(NPC), event.player(), false, () -> true, event.context()), event.binding());
        assertEquals(3L, events.stream().filter((TriggerEvent.Entity event) -> event.binding().endsWith("gift")).findFirst().orElseThrow().context().get("amount"));
    }

    @Test void aSensedEventKeepsOnlyValuesARunCanRead() {
        TriggerEvent.Entity event = new TriggerEvent.Entity(NPC, TriggerKind.ITEM_DROPPED, "town:sense#gift", Optional.empty(), Optional.empty(), java.util.Map.of("amount", 3, "scale", 1.5f));
        assertEquals(3L, event.context().get("amount"));
        assertEquals(1.5, event.context().get("scale"));
        assertThrows(IllegalArgumentException.class, () -> new TriggerEvent.Entity(NPC, TriggerKind.ITEM_DROPPED, "town:sense#gift", Optional.empty(), Optional.empty(), java.util.Map.of("when", new Object())));
    }

    @Test void touchesAreNotRememberedWhenNothingAsksForThem() {
        EntitySensor sensor = new EntitySensor(NPC);
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"in\",\"event\":\"entity_proximity\",\"phase\":\"enter\",\"radius\":8}]");
        assertEquals(1, sensor.sense(BODY, watches, List.of(sheep(new Vec3(0.5, 64, 0))), Set.of(), true, List.of(), NOBODY, new EntitySensor.Limits(1, 5)).events().size());
        assertEquals(0, sensor.overflow());
    }

    @Test void projectileAndLeashTieMatchTheirFilters() {
        List<EntitySensor.Watch> watches = watches("""
                [{"id":"arrow","event":"hit_by_projectile","projectile":"arrow,spectral_arrow","players":true},
                 {"id":"snow","event":"hit_by_projectile","projectile":"snowball"},
                 {"id":"tie","event":"leash_tie","entity":"horse"}]""");
        List<TriggerEvent.Entity> shot = EntitySensor.projectile(NPC, watches, Optional.of(player()), "minecraft:arrow", UUID.randomUUID(), new Vec3(0, 65, 0), true, NOBODY);
        assertEquals(List.of("town:sense#arrow"), shot.stream().map(TriggerEvent.Entity::binding).toList());
        assertEquals(true, shot.getFirst().context().get("stopped"));
        assertEquals(Optional.of(PLAYER), shot.getFirst().player());
        assertEquals(List.of("town:sense#snow"), EntitySensor.projectile(NPC, watches, Optional.empty(), "minecraft:snowball", UUID.randomUUID(), new Vec3(0, 65, 0), false, NOBODY)
                .stream().map(TriggerEvent.Entity::binding).toList());
        List<TriggerEvent.Entity> tied = EntitySensor.leashTie(NPC, watches, subject(UUID.randomUUID(), "minecraft:horse", ""), new Vec3(1, 64, 0), player(), NOBODY);
        assertEquals(List.of("town:sense#tie"), tied.stream().map(TriggerEvent.Entity::binding).toList());
        assertEquals(Optional.of(PLAYER), tied.getFirst().player());
    }

    @Test void speechIsHeardWithinTheRadiusAndMatchesItsPattern() {
        List<EntitySensor.Watch> watches = watches("[{\"id\":\"ear\",\"event\":\"npc_speech\",\"radius\":8,\"pattern\":\"*thief*\"}]");
        UUID speaker = UUID.randomUUID();
        assertEquals(1, EntitySensor.speech(NPC, watches, speaker, "town:guard", "Stop, thief!", 5).size());
        assertEquals(List.of(), EntitySensor.speech(NPC, watches, speaker, "town:guard", "Stop, thief!", 9));
        assertEquals(List.of(), EntitySensor.speech(NPC, watches, speaker, "town:guard", "Good morning", 2));
        assertEquals(List.of(), EntitySensor.speech(NPC, watches, NPC, "town:self", "thief", 0), "an NPC does not hear itself");
    }
}
