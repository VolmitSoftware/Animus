package art.arcane.animus.bukkit.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.ScreenHost;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** The chat side of Bukkit prompts, which needs no server: who is asked, what is consumed, and every cancel path. */
class BukkitPromptsTest {
    final UUID id = UUID.randomUUID();
    final List<String> told = new ArrayList<>();
    final List<Runnable> owner = new ArrayList<>();
    final List<Optional<String>> answers = new ArrayList<>();
    final Player player = (Player) Proxy.newProxyInstance(BukkitPromptsTest.class.getClassLoader(), new Class<?>[]{Player.class}, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
        case "getUniqueId" -> id;
        case "getName" -> "Alex";
        case "sendMessage" -> { told.add(String.valueOf(arguments[0])); yield null; }
        case "hashCode" -> 1;
        case "equals" -> proxy == arguments[0];
        default -> throw new UnsupportedOperationException(method.getName());
    });
    final BukkitPrompts prompts = new BukkitPrompts((Player who, Runnable work) -> owner.add(work));

    void run() { while (!owner.isEmpty()) owner.removeFirst().run(); }

    void ask() { prompts.ask(player, new ScreenHost.Prompt("Player name", "Notch", answers::add)); }

    @Test void theChatFallbackTellsThePlayerWhatToTypeAndConsumesOnlyTheAnswer() {
        ask();
        assertTrue(told.getLast().contains("Type Player name (now Notch) in chat, or cancel"), told.toString());
        assertTrue(prompts.chatted(player, " jeb_ "), "the answer is not broadcast");
        assertTrue(answers.isEmpty(), "the answer waits for the owner");
        run();
        assertEquals(List.of(Optional.of("jeb_")), answers);
        assertFalse(prompts.chatted(player, "hello"), "later lines are ordinary chat");
    }

    @Test void typingCancelLeavingOrDyingCancels() {
        ask();
        assertTrue(prompts.chatted(player, "Cancel"));
        ask();
        prompts.forget(player);
        run();
        assertEquals(List.of(Optional.<String>empty(), Optional.<String>empty()), answers);
        assertFalse(prompts.chatted(player, "late"));
    }
}
