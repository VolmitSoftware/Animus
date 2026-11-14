package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The text prompts waiting on a platform, one per player, shared by the Bukkit and mod hosts. Each prompt is answered
 * exactly once: with the typed line, or empty when the player typed {@value #CANCEL}, closed the input, left, died or
 * opened another prompt. The answer runs through the owner given at the start, so it reaches the player's own thread.
 */
public final class TextPrompts {
    /** Typing this word, in any case, cancels the prompt. */
    public static final String CANCEL = "cancel";
    private static final String CONFIRM = Palette.TEXT + "Click to confirm";

    /** The lore of an anvil's result paper: how to answer, then the prompt's hint when it has one. RichText source. */
    public static List<String> lore(ScreenHost.Prompt prompt) {
        return prompt.hint().isBlank() ? List.of(CONFIRM) : List.of(CONFIRM, Palette.GREY + prompt.hint());
    }

    /** What the chat fallback says: what to type, the current text when there is one, and how to cancel, then the hint. RichText source. */
    public static List<String> chat(ScreenHost.Prompt prompt) {
        String now = prompt.initial().isBlank() ? "" : " (now " + prompt.initial() + ")";
        String ask = Palette.GREY + "Type " + prompt.label() + now + " in chat, or type " + CANCEL;
        return prompt.hint().isBlank() ? List.of(ask) : List.of(ask, Palette.GREY + prompt.hint());
    }

    /** How the player answers: an anvil window, or a chat line when the anvil could not be opened. */
    public enum Input { ANVIL, CHAT }

    /** One waiting prompt. {@code window} is the platform's anvil, or null for chat. */
    public record Waiting(ScreenHost.Prompt prompt, Input input, Object window, Consumer<Runnable> owner) {
        public Waiting { Objects.requireNonNull(prompt); Objects.requireNonNull(input); Objects.requireNonNull(owner); }
    }

    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /** Starts a prompt for the player; one already waiting is answered empty first. Returns that previous prompt. */
    public Optional<Waiting> start(UUID player, Waiting next) {
        Waiting previous = waiting.put(player, Objects.requireNonNull(next));
        if (previous != null) previous.owner().accept(() -> previous.prompt().answer().accept(Optional.empty()));
        return Optional.ofNullable(previous);
    }

    public Optional<Waiting> waiting(UUID player) { return Optional.ofNullable(waiting.get(player)); }

    /** Whether {@code window} is the anvil of the player's waiting prompt. */
    public boolean owns(UUID player, Object window) {
        Waiting current = waiting.get(player);
        return current != null && current.window() != null && current.window().equals(window);
    }

    /**
     * The player answered. The line is trimmed; {@value #CANCEL} answers empty. False when no prompt was waiting, so
     * the platform lets the line through.
     */
    public boolean typed(UUID player, String line) {
        Waiting current = waiting.remove(player);
        if (current == null) return false;
        String text = line.strip();
        Optional<String> answer = text.toLowerCase(Locale.ROOT).equals(CANCEL) ? Optional.empty() : Optional.of(text);
        current.owner().accept(() -> current.prompt().answer().accept(answer));
        return true;
    }

    /** The player closed the input, left or died. False when no prompt was waiting. */
    public boolean cancel(UUID player) {
        Waiting current = waiting.remove(player);
        if (current == null) return false;
        current.owner().accept(() -> current.prompt().answer().accept(Optional.empty()));
        return true;
    }

    /**
     * The anvil {@code window} closed. Cancels only the prompt that window belongs to, so the close of an anvil a newer
     * prompt replaced does nothing. False when it was not the waiting prompt's window.
     */
    public boolean closed(UUID player, Object window) {
        Waiting current = waiting.get(player);
        if (current == null || current.window() == null || !current.window().equals(window) || !waiting.remove(player, current)) return false;
        current.owner().accept(() -> current.prompt().answer().accept(Optional.empty()));
        return true;
    }

    /** Shutdown: forgets every prompt without answering, since no owner thread runs any more. Returns who was waiting. */
    public List<UUID> clear() {
        List<UUID> players = new ArrayList<>(waiting.keySet());
        waiting.clear();
        return players;
    }
}
