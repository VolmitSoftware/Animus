package art.arcane.animus.core.command;

import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.text.Palette;
import java.util.UUID;

/**
 * The hidden {@code /animus choice <token> <option>} a dialogue button runs. Ordinary players send it, so it needs no
 * permission: only its single-use token grants anything, and every refusal reads the same.
 */
final class ChoiceCommands {
    static final String NOT_AVAILABLE = "That choice is no longer available.";

    @FunctionalInterface interface Selector { PendingChoices.Selection select(UUID player, String token, String option); }

    private ChoiceCommands() {}

    static void choose(CommandActor sender, String token, String option, Selector selector) {
        boolean wellFormed = sender.player().isPresent() && token.matches("[a-f0-9]{32}") && option.matches("[a-z0-9][a-z0-9_-]{0,31}");
        if (!wellFormed || selector.select(sender.player().get(), token, option) != PendingChoices.Selection.ACCEPTED)
            sender.tell(Palette.error(NOT_AVAILABLE));
    }
}
