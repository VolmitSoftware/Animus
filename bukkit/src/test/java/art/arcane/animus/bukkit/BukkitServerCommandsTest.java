package art.arcane.animus.bukkit;

import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.behavior.trigger.ServerCommandSync;
import art.arcane.animus.core.behavior.trigger.ServerTriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.ServerTriggerSettings;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitServerCommandsTest {
    private final AtomicInteger resent = new AtomicInteger();
    private final List<String> logged = new ArrayList<>();
    private final ServerTriggerDispatcher dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT,
            () -> new BehaviorCatalog.Snapshot(0, Map.of(), Map.of(), List.of()), () -> ServerBehaviorList.UNLOADED);
    private SimpleCommandMap map;
    private Map<String, Command> known;
    private ServerCommandSync sync;

    private static final class Taken extends Command {
        Taken(String name) { super(name); }
        @Override public boolean execute(CommandSender sender, String label, String[] arguments) { return true; }
    }

    @BeforeEach void open() throws Exception {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class}, (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
            case "hashCode" -> 0;
            case "equals" -> proxy == args[0];
            default -> null;
        });
        map = new SimpleCommandMap(server);
        known = BukkitServerCommands.knownCommands(map);
        map.register("other", new Taken("spawn"));
        map.register("other", new Taken("hub"));
        sync = new ServerCommandSync(new BukkitServerCommands(map, known, dispatcher, resent::incrementAndGet), logged::add);
    }

    private static ServerTriggerDispatcher.Command command(String name, Optional<String> permission, String... aliases) {
        return new ServerTriggerDispatcher.Command("town", "run-" + name, name, List.of(aliases), permission, Optional.of("Town command"), Optional.empty());
    }

    @Test void takenNamesStayWithTheirOwnerAndFreeAliasesRegisterUnderThePrefix() {
        sync.sync(new ServerTriggerDispatcher.Commands(1, List.of(command("spawn", Optional.empty()), command("warp", Optional.empty(), "hub", "w"))));
        assertInstanceOf(Taken.class, known.get("spawn"), "a name another plugin owns is left alone");
        assertInstanceOf(BukkitServerCommands.Managed.class, known.get("warp"));
        assertSame(known.get("warp"), known.get("animus:warp"));
        assertSame(known.get("warp"), known.get("w"));
        assertInstanceOf(Taken.class, known.get("hub"), "a taken alias is left out");
        assertEquals(1, resent.get());
        assertEquals(List.of("server-command-collision", "server-command-alias-collision"), sync.refusals().stream().map((art.arcane.animus.api.Diagnostic d) -> d.code()).toList());
        assertEquals("Town command", known.get("warp").getDescription());
    }

    @Test void ourOwnCommandsAreNotTakenAndUnregisterCompletely() {
        sync.sync(new ServerTriggerDispatcher.Commands(1, List.of(command("warp", Optional.empty(), "w"))));
        sync.sync(new ServerTriggerDispatcher.Commands(2, List.of(command("warp", Optional.of("town.warp"), "w"))));
        assertInstanceOf(BukkitServerCommands.Managed.class, known.get("warp"), "re-registering a changed command is not a collision");
        assertEquals("town.warp", known.get("warp").getPermission());
        assertTrue(sync.refusals().isEmpty());
        sync.clear();
        for (String label : List.of("warp", "animus:warp", "w", "animus:w")) assertNull(known.get(label), label);
        assertInstanceOf(Taken.class, known.get("spawn"));
        assertEquals(3, resent.get());
    }

    @Test void theAliasDropsTheFallbackPrefix() {
        assertEquals("warp", BukkitServerCommands.alias("animus:warp"));
        assertEquals("w", BukkitServerCommands.alias("W"));
    }
}
