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

    private static CommandDispatcher<Object> client(CommandActor actor) throws ReflectiveOperationException {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("animus").then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())));
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor who) -> List.of());
        CommandTreeHints.rewrite(dispatcher.getRoot(), List.of("animus", "animus:animus"), director.engine().getRoot(), actor);
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
        assertTrue(parses(admin, "animus edit guard {\"name\": \"A B\"}"));
        assertTrue(parses(admin, "animus list page=2"));
        assertTrue(parses(admin, "animus navigation go guard ^ ^ ^2"));
        assertFalse(parses(admin, "animus edit"), "a command missing its required value is incomplete");
    }

    @Test void aPlayerSeesOnlyWhatTheyMayRunPlusHiddenChipCommands() throws ReflectiveOperationException {
        CommandDispatcher<Object> visitor = client(new Actor(Set.of("animus.status")));
        assertNotNull(visitor.getRoot().getChild("animus").getChild("status"));
        assertNull(visitor.getRoot().getChild("animus").getChild("edit"));
        assertTrue(parses(visitor, "animus choice sometoken 1"), "dialogue choice chips run a hidden command");
    }
}
