package art.arcane.animus.bukkit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandGrantsTest {
    private static final Plugin PLUGIN = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Animus";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });

    /** A player that keeps its attachments, so a test can read what it holds right now. */
    private static final class Held {
        final List<PermissionAttachment> attachments = new ArrayList<>();
        final Player player;

        Held() {
            UUID id = UUID.randomUUID();
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
                case "addAttachment" -> { PermissionAttachment attachment = new PermissionAttachment((Plugin) args[0], (Player) proxy); attachments.add(attachment); yield attachment; }
                case "removeAttachment" -> { if (!attachments.remove((PermissionAttachment) args[0])) throw new IllegalArgumentException("not attached"); yield null; }
                case "recalculatePermissions" -> null;
                case "hasPermission" -> attachments.stream().anyMatch((PermissionAttachment attachment) -> Boolean.TRUE.equals(attachment.getPermissions().get(((String) args[0]).toLowerCase())));
                case "getUniqueId" -> id;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "player " + id;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    private final List<Runnable> scheduled = new ArrayList<>();
    private final Map<Runnable, Long> delays = new HashMap<>();
    private boolean accepting = true;
    private final CommandGrants grants = new CommandGrants(PLUGIN, (Player player, Runnable work, long ticks) -> {
        if (!accepting) return false;
        scheduled.add(work);
        delays.put(work, ticks);
        return true;
    });

    @Test void theCommandRunsHoldingTheNodesAndLosesThemRightAfter() {
        Held alex = new Held();
        List<Boolean> during = new ArrayList<>();
        assertTrue(grants.perform(alex.player, List.of("essentials.warp", "essentials.warps.docks"), 0, (Player player) -> {
            during.add(player.hasPermission("essentials.warp") && player.hasPermission("essentials.warps.docks"));
            return true;
        }));
        assertEquals(List.of(true), during);
        assertTrue(alex.attachments.isEmpty(), "taken back at once with no ticks");
        assertTrue(scheduled.isEmpty());
    }

    @Test void nodesStayForTheTicksAndGoThroughThePlayersScheduler() {
        Held alex = new Held();
        assertFalse(grants.perform(alex.player, List.of("kits.starter"), 100, (Player player) -> false), "what the command returned comes back");
        assertTrue(alex.player.hasPermission("kits.starter"));
        assertEquals(1, scheduled.size());
        assertEquals(100L, delays.get(scheduled.getFirst()));
        scheduled.getFirst().run();
        assertFalse(alex.player.hasPermission("kits.starter"));
        scheduled.getFirst().run();
        assertTrue(alex.attachments.isEmpty(), "a second removal changes nothing");
    }

    @Test void aSchedulerThatRefusesTakesTheNodesBackAtOnce() {
        accepting = false;
        Held alex = new Held();
        grants.perform(alex.player, List.of("kits.starter"), 100, (Player player) -> true);
        assertTrue(alex.attachments.isEmpty());
    }

    @Test void aFailingCommandStillLosesTheNodes() {
        Held alex = new Held();
        assertThrows(IllegalStateException.class, () -> grants.perform(alex.player, List.of("kits.starter"), 0, (Player player) -> { throw new IllegalStateException("boom"); }));
        assertTrue(alex.attachments.isEmpty());
    }

    @Test void quittingAndStoppingTakeEveryLentNodeBack() {
        Held alex = new Held(), blake = new Held();
        grants.perform(alex.player, List.of("kits.starter"), 600, (Player player) -> true);
        grants.perform(blake.player, List.of("kits.starter"), 600, (Player player) -> true);
        grants.quit(alex.player);
        assertTrue(alex.attachments.isEmpty());
        assertEquals(1, blake.attachments.size(), "another player keeps theirs");
        grants.close();
        assertTrue(blake.attachments.isEmpty());
        scheduled.forEach(Runnable::run);
        assertTrue(alex.attachments.isEmpty() && blake.attachments.isEmpty(), "late removals find nothing left");
    }

    @Test void noNodesMeansNoAttachment() {
        Held alex = new Held();
        assertTrue(grants.perform(alex.player, List.of(), 100, (Player player) -> true));
        assertTrue(alex.attachments.isEmpty());
        assertTrue(scheduled.isEmpty());
    }
}
