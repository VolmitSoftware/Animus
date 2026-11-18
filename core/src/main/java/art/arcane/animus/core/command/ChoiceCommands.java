package art.arcane.animus.core.command;

import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.text.Palette;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /animus choice <token> <option>}: what a conversation's answer buttons run, and what a player types to answer
 * one. Ordinary players send it, so it needs no permission: only a live token of a question asked to that player grants
 * anything, and any other token reads the same whatever the reason. Completion offers the player's own tokens, then the
 * answers of the one typed.
 */
final class ChoiceCommands {
    static final String NOT_AVAILABLE = "That choice is no longer available";

    /** The conversation questions waiting for players, and the answer to one. */
    interface Questions {
        /** Each question waiting for {@code player}'s answer, oldest first: its token and its option ids. */
        Map<String, List<String>> waiting(UUID player);

        PendingChoices.Selection select(UUID player, String token, String option);
    }

    /** Where conversations do not run: nothing waits. */
    static final Questions NONE = new Questions() {
        @Override public Map<String, List<String>> waiting(UUID player) { return Map.of(); }
        @Override public PendingChoices.Selection select(UUID player, String token, String option) { return PendingChoices.Selection.UNKNOWN; }
    };

    private ChoiceCommands() {}

    static void choose(CommandActor sender, String token, String option, Questions questions) {
        Optional<UUID> player = sender.player();
        Map<String, List<String>> waiting = player.map(questions::waiting).orElse(Map.of());
        if (waiting.isEmpty()) { sender.tell(Palette.error(Answers.Question.CONVERSATION.none)); return; }
        List<String> answers = waiting.get(token);
        if (answers == null) { sender.tell(Palette.error(NOT_AVAILABLE)); return; }
        String answer = option.toLowerCase(Locale.ROOT);
        if (!answers.contains(answer)) { sender.tell(Palette.error(DialogueFormat.notAnAnswer(option, answers))); return; }
        if (questions.select(player.get(), token, answer) != PendingChoices.Selection.ACCEPTED) sender.tell(Palette.error(NOT_AVAILABLE));
    }

    /** The tokens of the questions waiting for the actor, oldest first. */
    static List<String> tokens(CommandActor actor, Questions questions) {
        return actor.player().map((UUID player) -> List.copyOf(questions.waiting(player).keySet())).orElse(List.of());
    }

    /** The option ids of the actor's question {@code token}; none for a token that is not theirs. */
    static List<String> answers(CommandActor actor, Questions questions, String token) {
        return actor.player().map((UUID player) -> questions.waiting(player).getOrDefault(token, List.of())).orElse(List.of());
    }
}
