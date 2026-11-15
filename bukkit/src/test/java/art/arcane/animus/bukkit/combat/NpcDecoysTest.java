package art.arcane.animus.bukkit.combat;

import art.arcane.animus.core.virtual.VirtualNpcState;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Wolf;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which real mobs fight an NPC that lets mobs target it, as their own AI would choose a player. */
class NpcDecoysTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000d0c1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000d0c2");
    private Set<UUID> fought = Set.of();
    private final NpcDecoys decoys = new NpcDecoys(proxy(Plugin.class, (String name, Object[] args) -> "getName".equals(name) ? "Animus" : null), new NpcDecoys.Host() {
        @Override public Optional<VirtualNpcState.Snapshot> targetable(UUID npc) { return Optional.empty(); }
        @Override public String body(UUID npc) { return "minecraft:player"; }
        @Override public boolean allows(Mob mob, VirtualNpcState.Snapshot npc) { return true; }
        @Override public boolean fought(UUID npc, UUID player) { return fought.contains(player); }
    });

    @Test void monstersThatHuntPlayersFightAnyBody() {
        for (EntityType type : new EntityType[] {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.PILLAGER, EntityType.WITCH})
            assertTrue(decoys.fights(mob(Mob.class, type, 0, null), NPC, "minecraft:player"), type.toString());
        assertFalse(decoys.fights(mob(Mob.class, EntityType.COW, 0, null), NPC, "minecraft:player"));
    }
    @Test void spidersFightOnlyInTheDark() {
        assertTrue(decoys.fights(mob(Mob.class, EntityType.SPIDER, 4, null), NPC, "minecraft:player"));
        assertFalse(decoys.fights(mob(Mob.class, EntityType.SPIDER, 15, null), NPC, "minecraft:player"));
    }
    @Test void golemsFightMonsterBodiesButNotCreepers() {
        Mob golem = mob(Mob.class, EntityType.IRON_GOLEM, 0, null);
        assertTrue(decoys.fights(golem, NPC, "minecraft:zombie"));
        assertFalse(decoys.fights(golem, NPC, "minecraft:creeper"));
        assertFalse(decoys.fights(golem, NPC, "minecraft:player"));
        assertTrue(decoys.fights(mob(Mob.class, EntityType.SNOW_GOLEM, 0, null), NPC, "minecraft:skeleton"));
    }
    @Test void wolvesFightSkeletonsAndWhatTheirOwnerFought() {
        Wolf wild = mob(Wolf.class, EntityType.WOLF, 0, null);
        assertTrue(decoys.fights(wild, NPC, "minecraft:skeleton"));
        assertFalse(decoys.fights(wild, NPC, "minecraft:player"));
        Wolf tame = mob(Wolf.class, EntityType.WOLF, 0, proxy(AnimalTamer.class, (String name, Object[] args) -> "getUniqueId".equals(name) ? OWNER : null));
        assertFalse(decoys.fights(tame, NPC, "minecraft:player"));
        fought = Set.of(OWNER);
        assertTrue(decoys.fights(tame, NPC, "minecraft:player"), "a tame wolf joins its owner's fight");
    }

    @Test void aProjectileStrikesOnceThoughItBouncesBackIntoTheDecoy() {
        java.util.Map<Object, Object> stored = new java.util.HashMap<>();
        org.bukkit.persistence.PersistentDataContainer data = proxy(org.bukkit.persistence.PersistentDataContainer.class, (String name, Object[] args) -> switch (name) {
            case "has" -> stored.containsKey(args[0]);
            case "set" -> stored.put(args[0], args[2]);
            default -> null;
        });
        org.bukkit.entity.Trident trident = proxy(org.bukkit.entity.Trident.class, (String name, Object[] args) -> "getPersistentDataContainer".equals(name) ? data : null);
        org.bukkit.NamespacedKey struck = java.util.Objects.requireNonNull(org.bukkit.NamespacedKey.fromString("animus:struck_npc"));
        assertTrue(NpcDecoys.firstStrike(trident, struck), "the throw that reaches the decoy");
        assertFalse(NpcDecoys.firstStrike(trident, struck), "the bounce back into the same decoy");
    }

    private static <T extends Mob> T mob(Class<T> type, EntityType kind, int light, AnimalTamer owner) {
        Block block = proxy(Block.class, (String name, Object[] args) -> "getLightLevel".equals(name) ? (byte) light : null);
        return proxy(type, (String name, Object[] args) -> switch (name) {
            case "getType" -> kind;
            case "getLocation" -> new Location(null, 0, 64, 0) { @Override public Block getBlock() { return block; } };
            case "isTamed" -> owner != null;
            case "getOwner" -> owner;
            default -> null;
        });
    }
    private interface Answer { Object of(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (Object self, Method method, Object[] args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            return answer.of(method.getName(), args);
        }));
    }
}
