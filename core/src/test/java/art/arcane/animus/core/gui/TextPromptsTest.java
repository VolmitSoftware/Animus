package art.arcane.animus.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TextPromptsTest {
    final UUID alex = UUID.randomUUID();
    final TextPrompts prompts = new TextPrompts();
    final List<Optional<String>> answers = new ArrayList<>();
    final List<Runnable> owner = new ArrayList<>();
    final Consumer<Runnable> later = owner::add;

    TextPrompts.Waiting waiting(TextPrompts.Input input, Object window) {
        return new TextPrompts.Waiting(new ScreenHost.Prompt("Name", "old", answers::add), input, window, later);
    }

    void run() { while (!owner.isEmpty()) owner.removeFirst().run(); }

    @Test void aTypedLineIsTrimmedAndAnsweredOnceOnTheOwner() {
        prompts.start(alex, waiting(TextPrompts.Input.CHAT, null));
        assertTrue(prompts.typed(alex, "  Baker "));
        assertTrue(answers.isEmpty(), "the answer waits for the owner thread");
        run();
        assertEquals(List.of(Optional.of("Baker")), answers);
        assertFalse(prompts.typed(alex, "again"), "a second line goes to chat as usual");
        assertFalse(prompts.cancel(alex));
        run();
        assertEquals(1, answers.size());
    }

    @Test void cancelInAnyCaseClosingLeavingOrANewPromptAnswerEmpty() {
        prompts.start(alex, waiting(TextPrompts.Input.CHAT, null));
        prompts.typed(alex, "CANCEL");
        prompts.start(alex, waiting(TextPrompts.Input.CHAT, null));
        prompts.cancel(alex);
        prompts.start(alex, waiting(TextPrompts.Input.CHAT, null));
        assertTrue(prompts.start(alex, waiting(TextPrompts.Input.CHAT, null)).isPresent(), "the replaced prompt is handed back");
        run();
        assertEquals(List.of(Optional.<String>empty(), Optional.<String>empty(), Optional.<String>empty()), answers);
    }

    @Test void onlyTheWaitingAnvilsCloseCancels() {
        Object first = new Object(), second = new Object();
        prompts.start(alex, waiting(TextPrompts.Input.ANVIL, first));
        prompts.start(alex, waiting(TextPrompts.Input.ANVIL, second));
        run();
        answers.clear();
        assertFalse(prompts.closed(alex, first), "the replaced anvil closing late does not cancel the new prompt");
        assertTrue(prompts.owns(alex, second));
        assertFalse(prompts.owns(alex, first));
        assertTrue(prompts.closed(alex, second));
        run();
        assertEquals(List.of(Optional.<String>empty()), answers);
        assertFalse(prompts.owns(alex, second));
    }

    static List<String> plain(List<String> lines) { return lines.stream().map((String line) -> RichText.plain(RichText.colorize(line))).toList(); }

    @Test void aPromptHasNoHintUnlessGivenOne() {
        assertEquals("", new ScreenHost.Prompt("Name", "", answers::add).hint());
        assertEquals("Letters, digits, - and _", new ScreenHost.Prompt("Id", "", "Letters, digits, - and _", answers::add).hint());
    }

    @Test void theAnvilResultSaysHowToAnswerThenTheHint() {
        assertEquals(List.of("Click to confirm"), plain(TextPrompts.lore(new ScreenHost.Prompt("Name", "old", answers::add))));
        assertEquals(List.of("Click to confirm", "Letters, digits, - and _"), plain(TextPrompts.lore(new ScreenHost.Prompt("Id", "", "Letters, digits, - and _", answers::add))));
    }

    @Test void theChatFallbackSaysWhatToTypeAndHowToCancel() {
        assertEquals(List.of("Type Player name (now Notch) in chat, or type cancel"), plain(TextPrompts.chat(new ScreenHost.Prompt("Player name", "Notch", answers::add))),
                "chat cannot show the current text in a box, so the line names it");
        assertEquals(List.of("Type Id in chat, or type cancel", "Letters, digits, - and _"), plain(TextPrompts.chat(new ScreenHost.Prompt("Id", "", "Letters, digits, - and _", answers::add))));
    }

    @Test void shutdownForgetsWithoutAnswering() {
        prompts.start(alex, waiting(TextPrompts.Input.CHAT, null));
        assertEquals(List.of(alex), prompts.clear());
        run();
        assertTrue(answers.isEmpty());
        assertTrue(prompts.waiting(alex).isEmpty());
    }
}
