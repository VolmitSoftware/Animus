package art.arcane.animus.bukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.AnimusCommandTree;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.command.CommandActor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandTreeHintsTest {
    private record Actor(Set<String> nodes) implements CommandActor {
        /** As plugin.yml children make it on a server: animus.admin also holds the nodes under it. */
        @Override public boolean permitted(String node) { return art.arcane.animus.core.command.PermissionNodes.granted(nodes::contains, node); }
        @Override public void tell(String message) {}
        @Override public Optional<UUID> player() { return Optional.of(UUID.nameUUIDFromBytes(new byte[]{1})); }
        @Override public boolean reply(Runnable action) { action.run(); return true; }
    }

    private static CommandDispatcher<Object> client(CommandActor actor) throws ReflectiveOperationException { return client(actor, (String plugin) -> true, false); }

    private static CommandDispatcher<Object> client(CommandActor actor, java.util.function.Predicate<String> plugins) throws ReflectiveOperationException {
        return client(actor, plugins, false);
    }

    private static CommandDispatcher<Object> client(CommandActor actor, java.util.function.Predicate<String> plugins, boolean buttons) throws ReflectiveOperationException {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("animus").then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())));
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor who) -> List.of(), plugins);
        CommandTreeHints.rewrite(dispatcher.getRoot(), List.of("animus", "animus:animus"), director, actor, buttons);
        return dispatcher;
    }

    private static boolean parses(CommandDispatcher<Object> dispatcher, String command) {
        ParseResults<Object> parsed = dispatcher.parse(command, new Object());
        return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null;
    }

    @Test void theClientTreeRefusesUnknownSubcommandsAndKeepsEveryRealOne() throws ReflectiveOperationException {
        CommandDispatcher<Object> admin = client(new Actor(Set.of(AnimusDirector.ADMIN, "animus.status", "animus.debug", "animus.debug.mode", "animus.build.donate")));
        assertFalse(parses(admin, "animus nope"), "an unknown word is red in the chat box");
        assertFalse(parses(admin, "animus npc nope"));
        assertTrue(parses(admin, "animus"));
        assertTrue(parses(admin, "animus status"));
        assertTrue(parses(admin, "animus behaviors"));
        assertTrue(parses(admin, "animus behaviors help 2"), "help page links");
        assertTrue(parses(admin, "animus help 2"));
        assertTrue(parses(admin, "animus help npc skin"), "help for one command");
        assertTrue(parses(admin, "animus help all"));
        assertTrue(parses(admin, "animus edit guard {\"name\": \"A B\"}"));
        assertTrue(parses(admin, "animus list page=2"));
        assertTrue(parses(admin, "animus navigation go guard ^ ^ ^2"));
        assertFalse(parses(admin, "animus edit"), "a command missing its required value is incomplete");
    }

    @Test void aPlayerSeesOnlyWhatTheyMayRunAndTheButtonCommandsOnlyWhileAButtonWaits() throws ReflectiveOperationException {
        Actor player = new Actor(Set.of("animus.status", art.arcane.animus.core.command.PermissionNodes.QUESTS_USE));
        CommandDispatcher<Object> idle = client(player);
        assertNotNull(idle.getRoot().getChild("animus").getChild("status"));
        assertNull(idle.getRoot().getChild("animus").getChild("edit"));
        assertNull(idle.getRoot().getChild("animus").getChild("choice"), "no dialogue button waits, so completion does not offer its command");
        assertNull(idle.getRoot().getChild("animus").getChild("quest").getChild("choice"), "nor the quest offer's");
        CommandDispatcher<Object> prompted = client(player, (String plugin) -> true, true);
        assertTrue(parses(prompted, "animus choice sometoken 1"), "a waiting dialogue button runs a command the client knows");
        assertTrue(parses(prompted, "animus quest choice sometoken accept"));
    }

    @Test void eachGroupReachesTheClientUnderItsFirstNameOnly() throws ReflectiveOperationException {
        CommandDispatcher<Object> admin = client(new Actor(Set.of(AnimusDirector.ADMIN)));
        for (String plural : List.of("shops", "builds", "quests", "guis", "records", "imports", "rig", "module", "group", "behavior"))
            assertNull(admin.getRoot().getChild("animus").getChild(plural), plural + " would be offered beside its first form");
        for (String name : List.of("shop", "build", "quest", "gui", "record", "import", "rigs", "modules", "groups", "behaviors"))
            assertNotNull(admin.getRoot().getChild("animus").getChild(name), name);
    }

    @Test void aPlayersTreeIsSentAgainWhenAButtonStartsOrStopsWaiting() {
        Set<UUID> waiting = new java.util.HashSet<>();
        CommandTreeHints.Buttons buttons = new CommandTreeHints.Buttons(waiting::contains);
        UUID alex = UUID.randomUUID();
        assertFalse(buttons.stale(alex), "nothing waits and nothing was sent");
        assertFalse(buttons.include(alex), "a tree sent while nothing waits leaves the button commands out");
        waiting.add(alex);
        assertTrue(buttons.stale(alex), "a button now waits and alex's tree lacks its command");
        assertTrue(buttons.include(alex));
        assertFalse(buttons.stale(alex));
        waiting.remove(alex);
        assertTrue(buttons.stale(alex), "answered or gone: the command leaves the tree again");
        assertFalse(buttons.include(alex));
        assertFalse(buttons.stale(alex));
    }

    @Test void aTreeStillCarryingTheButtonCommandsAfterItsQuestionExpiredIsFound() {
        Set<UUID> waiting = new java.util.HashSet<>();
        CommandTreeHints.Buttons buttons = new CommandTreeHints.Buttons(waiting::contains);
        UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
        waiting.add(alex);
        waiting.add(sam);
        buttons.include(alex);
        buttons.include(sam);
        assertTrue(buttons.expired().isEmpty(), "both questions still wait");
        waiting.remove(alex);
        assertTrue(buttons.expired().contains(alex) && !buttons.expired().contains(sam), "alex's question expired unanswered, so the sweep sends alex a new tree");
        buttons.include(alex);
        assertTrue(buttons.expired().isEmpty(), "the new tree leaves the commands out");
    }

    @Test void guiAloneParsesForEveryPlayerWhoMayOpenTheMenu() throws ReflectiveOperationException {
        CommandDispatcher<Object> builder = client(new Actor(Set.of(art.arcane.animus.core.command.PermissionNodes.TOOLS)));
        assertTrue(parses(builder, "animus gui"), "/animus gui opens the menu");
        assertFalse(parses(builder, "animus gui reload"), "the gui commands stay behind animus.gui");
        assertTrue(parses(builder, "animus"), "a bare /animus is help");
        CommandDispatcher<Object> visitor = client(new Actor(Set.of("animus.status")));
        assertFalse(parses(visitor, "animus gui"), "no menu, no gui");
    }

    @Test void integrationCommandsReachTheClientOnlyWithTheirPlugin() throws ReflectiveOperationException {
        Actor admin = new Actor(Set.of(AnimusDirector.ADMIN));
        CommandDispatcher<Object> without = client(admin, (String plugin) -> false);
        assertFalse(parses(without, "animus npc model guard"));
        assertFalse(parses(without, "animus import betonquest preview"));
        assertFalse(parses(without, "animus mythic boss SkeletonKing"));
        CommandDispatcher<Object> with = client(admin, (String plugin) -> true);
        assertTrue(parses(with, "animus npc model guard"));
        assertTrue(parses(with, "animus import betonquest preview"));
        assertTrue(parses(with, "animus mythic boss SkeletonKing"));
    }
}
