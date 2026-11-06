package art.arcane.animus.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModServerBehaviorsTest {
    @Test void removingOurNodeTakesTheLabelOutOfTheTree() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("greet").executes((CommandContext<Object> context) -> 1));
        CommandNode<Object> ours = dispatcher.getRoot().getChild("greet");
        assertTrue(ModServerBehaviors.remove(dispatcher.getRoot(), "greet", ours));
        assertNull(dispatcher.getRoot().getChild("greet"));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("greet", new Object()));
    }

    @Test void removalLeavesSomeoneElsesNodeAlone() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("greet").executes((CommandContext<Object> context) -> 1));
        CommandNode<Object> stale = LiteralArgumentBuilder.literal("greet").build();
        assertTrue(ModServerBehaviors.remove(dispatcher.getRoot(), "greet", stale));
        assertNotNull(dispatcher.getRoot().getChild("greet"));
    }

    @Test void reRegisteringALabelMergesIntoTheSameNode() throws CommandSyntaxException {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.literal("greet").executes((CommandContext<Object> context) -> 1));
        CommandNode<Object> first = dispatcher.getRoot().getChild("greet");
        dispatcher.register(LiteralArgumentBuilder.literal("greet").executes((CommandContext<Object> context) -> 2));
        assertSame(first, dispatcher.getRoot().getChild("greet"));
        assertEquals(2, dispatcher.execute("greet", new Object()));
    }
}
