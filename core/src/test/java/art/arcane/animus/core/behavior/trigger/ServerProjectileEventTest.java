package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorFacts;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.builtin.MemoryBehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerProjectileEventTest {
    private static final String ROUTINES = "\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}";
    private static final String BEHAVIOR = "denizen:world/archery";
    private final UUID alex = UUID.randomUUID(), arrow = UUID.randomUUID(), zombie = UUID.randomUUID();
    private final AtomicReference<BehaviorCatalog.Snapshot> catalog = new AtomicReference<>(new BehaviorCatalog.Snapshot(0, Map.of(), Map.of(), List.of()));
    private final AtomicReference<ServerBehaviorList> enabled = new AtomicReference<>(ServerBehaviorList.UNLOADED);
    private final List<ServerTriggerDispatcher.Request> started = new ArrayList<>();
    private ServerTriggerDispatcher dispatcher;

    private void setup(String triggers) {
        dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, catalog::get, enabled::get);
        dispatcher.connect();
        BehaviorCompiler.Result compiled = BehaviorCompiler.compile(document(triggers));
        BehaviorProgram program = compiled.program().orElseThrow(() -> new AssertionError(compiled.diagnostics().toString()));
        Map<String, BehaviorCatalog.Resolution> resolutions = new LinkedHashMap<>();
        resolutions.put(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, program, Set.of())), List.of()));
        catalog.set(new BehaviorCatalog.Snapshot(1, Map.of(), Map.copyOf(resolutions), List.of()));
        enabled.set(new ServerBehaviorList(1, List.of(BEHAVIOR), List.of()));
        pump();
    }
    private static String document(String triggers) {
        return "{\"schemaVersion\":1,\"id\":\"" + BEHAVIOR + "\",\"server\":true,\"entry\":\"main\"," + ROUTINES + ",\"triggers\":" + triggers + "}";
    }
    private void pump() { dispatcher.pump(request -> { started.add(request); return Outcome.ok(UUID.randomUUID()); }); }
    private List<String> fired() { return started.stream().map(request -> request.binding().trigger().id()).toList(); }

    private ServerEvent hitBlock(Optional<UUID> player, String shooter) {
        return ServerEvent.projectileHit(player, "minecraft:arrow", arrow, shooter, player.map(UUID::toString).orElse(""), "", "", "minecraft:target", "world", 4, 70, -2);
    }
    private ServerEvent hitZombie(Optional<UUID> player) {
        return ServerEvent.projectileHit(player, "minecraft:arrow", arrow, "minecraft:player", alex.toString(), "minecraft:zombie", zombie.toString(), "", "world", 1, 64, 1);
    }

    @Test void projectileTriggersCompileTheirFiltersAndCancel() {
        assertTrue(BehaviorCompiler.compile(document("[{\"id\":\"a\",\"event\":\"projectile_hit\",\"projectile\":\"arrow\",\"shooter\":\"player\",\"entity\":\"*:*\",\"block\":\"*\",\"cancel\":\"true\"},"
                + "{\"id\":\"b\",\"event\":\"projectile_launch\",\"projectile\":\"snowball\",\"shooter\":\"*\",\"cancel\":\"true\"},"
                + "{\"id\":\"c\",\"event\":\"entity_shoot_bow\",\"shooter\":\"skeleton\",\"bow\":\"crossbow\",\"projectile\":\"arrow\",\"cancel\":\"num(context.force) < 1\"}]")).program().isPresent());
        assertTrue(BehaviorCompiler.compile(document("[{\"id\":\"a\",\"event\":\"projectile_launch\",\"entity\":\"zombie\"}]")).program().isEmpty(), "a launch hits nothing");
        assertTrue(BehaviorCompiler.compile(document("[{\"id\":\"a\",\"event\":\"entity_shoot_bow\",\"bow\":\"Bow\"}]")).program().isEmpty(), "filters are lowercase");
    }

    @Test void hitsFilterOnWhatWasHitAndWhoShot() {
        setup("[{\"id\":\"entities\",\"event\":\"projectile_hit\",\"entity\":\"*:*\"},{\"id\":\"targets\",\"event\":\"projectile_hit\",\"shooter\":\"player\",\"block\":\"target\"},"
                + "{\"id\":\"any\",\"event\":\"projectile_hit\",\"projectile\":\"arrow\"}]");
        dispatcher.publish(hitBlock(Optional.empty(), "minecraft:skeleton"));
        dispatcher.publish(hitBlock(Optional.of(alex), "minecraft:player"));
        dispatcher.publish(hitZombie(Optional.of(alex)));
        pump();
        assertEquals(List.of("any", "targets", "any", "entities", "any"), fired());
        assertTrue(started.getFirst().player().isEmpty(), "a skeleton's arrow carries no player");
        Map<String, Object> hit = started.get(3).event();
        assertEquals("minecraft:zombie", hit.get("entity"));
        assertEquals(zombie.toString(), hit.get("entity_uuid"));
        assertEquals("", hit.get("block"));
        assertEquals("1.00,64.00,1.00,world", hit.get("location"));
    }

    @Test void launchesAndBowShotsCarryTheirContext() {
        setup("[{\"id\":\"thrown\",\"event\":\"projectile_launch\",\"projectile\":\"snowball\"},{\"id\":\"drawn\",\"event\":\"entity_shoot_bow\",\"shooter\":\"player\",\"bow\":\"bow\"}]");
        dispatcher.publish(ServerEvent.projectileLaunch(Optional.empty(), "minecraft:snowball", arrow, "", "", "world", 0, 64, 0));
        dispatcher.publish(ServerEvent.shootBow(Optional.of(alex), "minecraft:player", alex, "minecraft:crossbow", "minecraft:arrow", arrow, 1, "main_hand", "world", 0, 64, 0));
        dispatcher.publish(ServerEvent.shootBow(Optional.of(alex), "minecraft:player", alex, "minecraft:bow", "minecraft:arrow", arrow, 0.5, "off_hand", "world", 0, 64, 0));
        pump();
        assertEquals(List.of("thrown", "drawn"), fired());
        assertEquals("", started.getFirst().event().get("shooter"));
        Map<String, Object> shot = started.get(1).event();
        assertEquals(0.5, shot.get("force"));
        assertEquals("off_hand", shot.get("hand"));
        assertEquals(arrow.toString(), shot.get("projectile_uuid"));
        assertEquals(Optional.of(alex), started.get(1).player());
    }

    @Test void cancelConditionsReadTheShot() {
        setup("[{\"id\":\"weak\",\"event\":\"entity_shoot_bow\",\"cancel\":\"num(context.force) < 0.5\"},{\"id\":\"pass\",\"event\":\"projectile_hit\",\"cancel\":\"context.entity == 'minecraft:zombie'\"}]");
        dispatcher.consumption(new MemoryBehaviorState(), BehaviorFacts.NONE);
        assertTrue(dispatcher.decide(ServerEvent.shootBow(Optional.of(alex), "minecraft:player", alex, "minecraft:bow", "minecraft:arrow", arrow, 0.2, "main_hand", "world", 0, 64, 0)));
        assertFalse(dispatcher.decide(ServerEvent.shootBow(Optional.of(alex), "minecraft:player", alex, "minecraft:bow", "minecraft:arrow", arrow, 1, "main_hand", "world", 0, 64, 0)));
        assertTrue(dispatcher.decide(hitZombie(Optional.of(alex))));
        assertFalse(dispatcher.decide(hitBlock(Optional.empty(), "minecraft:skeleton")));
    }

    @Test void schemaContextListsWhatEachFiringCarries() {
        assertTrue(ServerEvent.contextKeys(TriggerKind.PROJECTILE_HIT).containsAll(List.of("projectile", "shooter", "entity", "block", "location")));
        assertTrue(ServerEvent.contextKeys(TriggerKind.ENTITY_SHOOT_BOW).containsAll(List.of("bow", "force", "hand", "projectile")));
        assertFalse(ServerEvent.contextKeys(TriggerKind.PROJECTILE_LAUNCH).contains("entity"));
    }
}
