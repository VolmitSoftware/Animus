package art.arcane.animus.bukkit;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitBehaviorPlatformTest {
    private final List<String> messages = new ArrayList<>();
    private final UUID playerId = UUID.randomUUID();

    private CommandSender sender(boolean player) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{player ? Player.class : CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "sendMessage" -> { messages.add((String) args[0]); yield null; }
            case "toString" -> "Choice test sender";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Unexpected Bukkit access: " + method.getName());
        });
    }

    @Test void disabledRuntimeReportsItsOwnReasonOnEveryPlatform() {
        assertEquals(Optional.of("NPC runtime disabled in config.toml"), BukkitBehaviorPlatform.unavailability(() -> false, () -> "NPC runtime disabled in config.toml"));
        assertEquals(Optional.empty(), BukkitBehaviorPlatform.unavailability(() -> true, () -> { throw new AssertionError("A ready runtime must not format status off its owner"); }));
    }

    @Test void runtimeRejectionsKeepExpectedStatuses() {
        assertEquals("ok", BukkitBehaviorPlatform.mapped(() -> "ok"));
        CapabilityRefusal invalid = assertThrows(CapabilityRefusal.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw new IllegalArgumentException("Route leg is too long"); }));
        assertEquals(Outcome.Status.INVALID, invalid.status());
        assertTrue(invalid.diagnostics().getFirst().message().contains("Route leg"));
        CapabilityRefusal unavailable = assertThrows(CapabilityRefusal.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw new IllegalStateException("NPC must be active"); }));
        assertEquals(Outcome.Status.UNAVAILABLE, unavailable.status());
        CapabilityRefusal passed = new CapabilityRefusal(Outcome.Status.BUSY, "busy", "Busy");
        assertSame(passed, assertThrows(CapabilityRefusal.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw passed; })));
        assertThrows(NullPointerException.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw new NullPointerException(); }));
    }

    @Test void rejectionMessagesAreBounded() {
        CapabilityRefusal refused = assertThrows(CapabilityRefusal.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw new IllegalStateException("x".repeat(10_000)); }));
        assertTrue(refused.diagnostics().getFirst().message().length() <= 512);
        CapabilityRefusal noMessage = assertThrows(CapabilityRefusal.class, () -> BukkitBehaviorPlatform.mapped(() -> { throw new IllegalStateException(); }));
        assertFalse(noMessage.diagnostics().getFirst().message().isBlank());
    }

    @Test void choiceOptionsAreClickableAndCarryTheTokenWithoutInterpretingText() {
        BaseComponent[] rendered = BukkitChoiceMessages.options("animus", "a".repeat(32), List.of(new BehaviorPlatform.ChoiceOption("yes", "Yes &c/op me"), new BehaviorPlatform.ChoiceOption("no", "No")));
        List<TextComponent> buttons = new ArrayList<>();
        for (BaseComponent component : rendered) if (component.getClickEvent() != null) buttons.add((TextComponent) component);
        assertEquals(2, buttons.size());
        assertEquals(ClickEvent.Action.RUN_COMMAND, buttons.getFirst().getClickEvent().getAction());
        assertEquals("/animus choice " + "a".repeat(32) + " yes", buttons.getFirst().getClickEvent().getValue());
        assertEquals("/animus choice " + "a".repeat(32) + " no", buttons.getLast().getClickEvent().getValue());
        assertTrue(buttons.getFirst().getText().contains("Yes &c/op me"));
        assertEquals(0, buttons.getFirst().getExtra() == null ? 0 : buttons.getFirst().getExtra().size());
    }

    private static final String TOKEN = "b".repeat(32);

    @Test void choiceCommandPassesTheSendersOwnIdentity() {
        List<String> seen = new ArrayList<>();
        BukkitChoiceCommand.execute(sender(true), new String[]{"choice", TOKEN, "yes"}, (player, token, option) -> { seen.add(player + ":" + token + ":" + option); return PendingChoices.Selection.ACCEPTED; });
        assertEquals(List.of(playerId + ":" + TOKEN + ":yes"), seen);
        assertTrue(messages.isEmpty());
    }

    @Test void rejectedChoiceTellsThePlayerNothingAboutWhy() {
        for (PendingChoices.Selection rejected : List.of(PendingChoices.Selection.UNKNOWN, PendingChoices.Selection.EXPIRED, PendingChoices.Selection.WRONG_PLAYER, PendingChoices.Selection.UNKNOWN_OPTION, PendingChoices.Selection.NPC_UNAVAILABLE)) {
            messages.clear();
            BukkitChoiceCommand.execute(sender(true), new String[]{"choice", TOKEN, "yes"}, (player, token, option) -> rejected);
            assertEquals(List.of(BukkitChoiceCommand.NOT_AVAILABLE), messages);
        }
    }

    @Test void malformedChoiceNeverReachesSelection() {
        for (String[] arguments : List.of(new String[]{"choice"}, new String[]{"choice", TOKEN}, new String[]{"choice", TOKEN, "yes", "extra"},
                new String[]{"choice", "short", "yes"}, new String[]{"choice", TOKEN.toUpperCase(), "yes"}, new String[]{"choice", TOKEN, "Bad Option"}, new String[]{"choice", TOKEN, "x".repeat(33)})) {
            messages.clear();
            BukkitChoiceCommand.execute(sender(true), arguments, (player, token, option) -> { throw new AssertionError("Malformed click reached selection"); });
            assertEquals(List.of(BukkitChoiceCommand.NOT_AVAILABLE), messages);
        }
    }

    @Test void consoleCannotAnswerAChoice() {
        BukkitChoiceCommand.execute(sender(false), new String[]{"choice", TOKEN, "yes"}, (player, token, option) -> { throw new AssertionError("Console reached selection"); });
        assertEquals(List.of(BukkitChoiceCommand.NOT_AVAILABLE), messages);
    }
}
