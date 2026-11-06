package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerCommandSyncTest {
    private final Set<String> foreign = new HashSet<>(Set.of("tp", "home"));
    private final Set<String> ours = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private final ServerCommandSync sync = new ServerCommandSync(new ServerCommandSync.Host() {
        @Override public boolean taken(String label) { return foreign.contains(label); }
        @Override public void register(ServerTriggerDispatcher.Command command, List<String> labels) { ours.addAll(labels); calls.add("register:" + labels); }
        @Override public void unregister(ServerTriggerDispatcher.Command command, List<String> labels) { labels.forEach(ours::remove); calls.add("unregister:" + labels); }
        @Override public void changed() { calls.add("changed"); }
    }, line -> {});

    private static ServerTriggerDispatcher.Command command(String name, String... aliases) {
        return new ServerTriggerDispatcher.Command("denizen:command/" + name, "run", name, List.of(aliases), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test void registersFreeNamesRefusesTakenOnesAndUnregistersRemovedCommands() {
        sync.sync(new ServerTriggerDispatcher.Commands(1, List.of(command("spawn", "hub", "home"), command("tp"))));
        assertEquals(Set.of("spawn", "hub"), ours);
        assertEquals(List.of("register:[spawn, hub]", "changed"), calls);
        List<String> codes = sync.refusals().stream().map(Diagnostic::code).toList();
        assertEquals(List.of("server-command-alias-collision", "server-command-collision"), codes);
        calls.clear();
        sync.sync(new ServerTriggerDispatcher.Commands(1, List.of()));
        assertEquals(List.of(), calls, "the same generation changes nothing");
        sync.sync(new ServerTriggerDispatcher.Commands(2, List.of(command("warp"))));
        assertEquals(Set.of("warp"), ours);
        assertEquals(List.of("unregister:[spawn, hub]", "register:[warp]", "changed"), calls);
        sync.clear();
        assertEquals(Set.of(), ours);
    }

    @Test void firingChecksThePermissionAndPublishesTheCommandEvent() {
        ServerTriggerDispatcher dispatcher = new ServerTriggerDispatcher(ServerTriggerSettings.DEFAULT, () -> new art.arcane.animus.core.behavior.catalog.BehaviorCatalog.Snapshot(0, java.util.Map.of(), java.util.Map.of(), List.of()),
                () -> art.arcane.animus.core.behavior.catalog.ServerBehaviorList.UNLOADED);
        ServerTriggerDispatcher.Command guarded = new ServerTriggerDispatcher.Command("denizen:command/spawn", "run", "spawn", List.of(), Optional.of("town.spawn"), Optional.empty(), Optional.empty());
        assertFalse(ServerCommandSync.fire(dispatcher, guarded, Optional.empty(), false, "spawn", ""));
        assertTrue(ServerCommandSync.fire(dispatcher, guarded, Optional.empty(), true, "spawn", "a b"));
        assertTrue(ServerCommandSync.fire(dispatcher, command("open"), Optional.empty(), false, "open", ""));
    }
}
