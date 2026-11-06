package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.ViewerPreviews;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BukkitBehaviorPlatformTest {
    private static final String WORLD = "00000000-0000-0000-0000-0000000000ff";
    private static final WorldPosition AT = new WorldPosition(WORLD, new Vec3(0, 64, 0));
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BLAKE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID GUARD = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @TempDir Path root;
    private DocumentStore store;
    private ViewerPreviews previews;
    private final Map<UUID, Player> online = new LinkedHashMap<>();
    private final List<UUID> reached = new ArrayList<>();
    private final NpcRuntime.ActiveNpc guard = new NpcRuntime.ActiveNpc("guard", "Guard", new VirtualNpcState(GUARD, AT, 0.6, 1.8, 20).snapshot());

    @BeforeEach void open() throws Exception {
        store = new DocumentStore(root);
        previews = new ViewerPreviews(Runnable::run, store);
        online.put(ALEX, player(ALEX));
        online.put(BLAKE, player(BLAKE));
    }

    @AfterEach void close() throws Exception { previews.close(); store.close(); }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "player " + id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private final NpcRuntime.PlayerAccess<Player> players = new NpcRuntime.PlayerAccess<>() {
        @Override public boolean online(UUID player) { return online.containsKey(player); }
        @Override public Optional<Player> owned(UUID player) { return Optional.ofNullable(online.get(player)); }
        @Override public List<Player> ownedNear(WorldPosition at, double radius, int limit) { return List.copyOf(online.values()); }
        @Override public boolean deliver(UUID player, Consumer<Player> action) {
            Player target = online.get(player);
            if (target == null) return false;
            action.accept(target);
            return true;
        }
        @Override public boolean console(Runnable action) { action.run(); return true; }
    };

    private int say(Optional<UUID> player) {
        return BukkitBehaviorPlatform.deliver(players, previews, guard, new BehaviorPlatform.Audience(player, 16), AT, false, (Player viewer) -> reached.add(viewer.getUniqueId()));
    }

    private void preview() throws Exception {
        NpcDefinition candidate = NpcDefinition.parse("{\"schemaVersion\":1,\"revision\":2,\"uuid\":\"" + GUARD + "\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"Preview\","
                + "\"type\":\"minecraft:player\",\"spawned\":true,\"position\":{\"world\":\"" + WORLD + "\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        previews.present("prev_a", ALEX, List.of(candidate), (String state) -> {});
        assertNotNull(previews.staged(ALEX));
    }

    @Test void speechSoundsAndParticlesSkipTheViewerWhosePreviewHidesTheNpc() throws Exception {
        assertEquals(2, say(Optional.empty()));
        assertEquals(List.of(ALEX, BLAKE), reached);
        reached.clear();

        preview();
        assertEquals(1, say(Optional.empty()), "nearby delivery leaves out the previewing viewer");
        assertEquals(List.of(BLAKE), reached);
        reached.clear();
        assertEquals(0, say(Optional.of(ALEX)), "a line addressed to the previewing viewer is not delivered");
        assertEquals(1, say(Optional.of(BLAKE)), "other players keep hearing the live NPC");
        assertEquals(List.of(BLAKE), reached);
        reached.clear();

        previews.end("prev_a");
        assertEquals(2, say(Optional.empty()), "ending the preview restores delivery");
        assertEquals(1, say(Optional.of(ALEX)));
        assertEquals(List.of(ALEX, BLAKE, ALEX), reached);
    }

    @Test void giftsSplitIntoFullStacksAndTakingNeverExceedsWhatIsHeld() {
        assertArrayEquals(new int[] {64, 64, 2}, BukkitBehaviorPlatform.stacks(130, 64));
        assertArrayEquals(new int[] {1, 1}, BukkitBehaviorPlatform.stacks(2, 1));
        assertArrayEquals(new int[] {16}, BukkitBehaviorPlatform.stacks(16, 16));
        assertArrayEquals(new int[] {}, BukkitBehaviorPlatform.stacks(0, 64));

        int[] held = {5, 0, 10};
        assertEquals(12, BukkitBehaviorPlatform.take(held, 12));
        assertArrayEquals(new int[] {0, 0, 3}, held);
        assertEquals(3, BukkitBehaviorPlatform.take(held, 50), "only what is held is taken");
        assertArrayEquals(new int[] {0, 0, 0}, held);
        assertEquals(0, BukkitBehaviorPlatform.take(held, 1));
        assertEquals(0, BukkitBehaviorPlatform.take(new int[] {4}, -2));
    }

    @Test void titleTicksStayWithinTheirRange() {
        assertEquals(0, BukkitBehaviorPlatform.ticks(-5));
        assertEquals(20, BukkitBehaviorPlatform.ticks(20));
        assertEquals(72000, BukkitBehaviorPlatform.ticks(100000));
    }
}
