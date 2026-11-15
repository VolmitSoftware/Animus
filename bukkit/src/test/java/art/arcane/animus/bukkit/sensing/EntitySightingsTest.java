package art.arcane.animus.bukkit.sensing;

import art.arcane.animus.core.behavior.trigger.EntitySensor;
import art.arcane.animus.core.virtual.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntitySightingsTest {
    private static final UUID ALEX = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static <T> T entity(Class<T> type, EntityType kind, Map<String, Object> answers) {
        UUID id = UUID.randomUUID();
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getType" -> kind;
            case "getUniqueId" -> answers.getOrDefault("id", id);
            case "getBoundingBox" -> new BoundingBox(0, 64, 0, 0.9, 65.3, 0.9);
            case "getVelocity" -> answers.getOrDefault("velocity", new Vector());
            case "getLocation" -> new Location(null, 0.45, 64, 0.45);
            case "getPassengers" -> answers.getOrDefault("passengers", List.of());
            case "getScoreboardTags" -> answers.getOrDefault("tags", Set.of());
            case "getCustomName" -> answers.get("name");
            case "getName" -> answers.getOrDefault("name", "Alex");
            case "getTicksLived" -> answers.getOrDefault("age", 100);
            case "getGameMode" -> answers.getOrDefault("mode", GameMode.SURVIVAL);
            case "getItemStack" -> answers.get("stack");
            case "getThrower" -> answers.get("thrower");
            case "isLeashed" -> answers.containsKey("holder");
            case "getLeashHolder" -> answers.get("holder");
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private static Player alex(GameMode mode) { return entity(Player.class, EntityType.PLAYER, Map.of("id", ALEX, "name", "Alex", "mode", mode)); }
    private static Server online(Player player) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (Object proxy, Method method, Object[] arguments) -> method.getName().equals("getPlayer") && player.getUniqueId().equals(arguments[0]) ? player : null);
    }

    @Test void onlyEntitiesThatCanMeetAnNpcAreSensed() {
        assertTrue(EntitySightings.sensed(alex(GameMode.SURVIVAL)));
        assertFalse(EntitySightings.sensed(alex(GameMode.SPECTATOR)));
        assertTrue(EntitySightings.sensed(entity(Sheep.class, EntityType.SHEEP, Map.of())));
        assertFalse(EntitySightings.sensed(entity(Arrow.class, EntityType.ARROW, Map.of())), "projectiles are followed, not looked for");
    }

    @Test void aStandInForAnNpcBodyIsNeverSensedNorTakesACandidateSlot() {
        Player alex = alex(GameMode.SURVIVAL);
        Mannequin decoy = entity(Mannequin.class, EntityType.MANNEQUIN, Map.of()), other = entity(Mannequin.class, EntityType.MANNEQUIN, Map.of());
        Mannequin plain = entity(Mannequin.class, EntityType.MANNEQUIN, Map.of());
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "isChunkLoaded" -> true;
            case "getNearbyEntities" -> {
                @SuppressWarnings("unchecked") Predicate<Entity> keep = (Predicate<Entity>) arguments[1];
                yield new ArrayList<>(List.of(decoy, other, plain, alex).stream().filter(keep).toList());
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        EntitySightings.Look look = EntitySightings.nearby(world, new BoundingBox(-8, 56, -8, 8, 72, 8), new Vec3(0.45, 64, 0.45), 2,
                (World in, int minX, int minZ, int maxX, int maxZ) -> true, Set.<Entity>of(decoy, other)::contains, (Entity entity) -> true);
        assertEquals(List.of(plain, alex), look.entities(), "a mannequin combat did not place is an entity like any other");
        assertTrue(look.complete(), "the decoys take none of the two candidate slots");
    }

    @Test void anItemCarriesItsStackAndAnOnlineThrower() {
        Player alex = alex(GameMode.SURVIVAL);
        Item item = entity(Item.class, EntityType.ITEM, Map.of("stack", new ItemStack(Material.EMERALD, 3), "thrower", ALEX, "age", 4));
        EntitySensor.Seen seen = EntitySightings.seen(item, online(alex));
        assertEquals(EntitySensor.Role.ITEM, seen.role());
        assertEquals(Optional.of("minecraft:emerald"), seen.item());
        assertEquals(3, seen.amount());
        assertEquals(4, seen.age());
        assertEquals(ALEX, seen.thrower().orElseThrow().subject().id());
        assertTrue(seen.thrower().orElseThrow().player());
        assertEquals("minecraft:item", seen.self().type());
    }

    @Test void aMinecartCarriesItsRiderAndALeashedMobItsHolderUnlessTiedToAFence() {
        Player alex = alex(GameMode.SURVIVAL);
        Minecart cart = entity(Minecart.class, EntityType.MINECART, Map.of("passengers", List.of(alex), "velocity", new Vector(0.4, 0, 0)));
        EntitySensor.Seen riding = EntitySightings.seen(cart, online(alex));
        assertEquals(EntitySensor.Role.VEHICLE, riding.role());
        assertEquals(ALEX, riding.rider().orElseThrow().subject().id());
        assertEquals(0.4, riding.velocity().x(), 1e-9);
        Sheep led = entity(Sheep.class, EntityType.SHEEP, Map.of("holder", alex, "tags", Set.of("pet"), "name", "Dolly"));
        EntitySensor.Seen sheep = EntitySightings.seen(led, online(alex));
        assertEquals(ALEX, sheep.holder().orElseThrow().subject().id());
        assertEquals(Set.of("pet"), sheep.self().tags());
        assertEquals("Dolly", sheep.self().name());
        Sheep tied = entity(Sheep.class, EntityType.SHEEP, Map.of("holder", entity(LeashHitch.class, EntityType.LEASH_KNOT, Map.of())));
        assertEquals(Optional.empty(), EntitySightings.seen(tied, online(alex)).holder());
    }
}
