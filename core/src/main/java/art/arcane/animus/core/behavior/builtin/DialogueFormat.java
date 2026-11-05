package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.text.TextPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How NPC speech and clickable answers look on every platform. Speech reads {@code Name » text}; answers are
 * bracketed, coloured buttons with hover text. Unstyled packs get a palette and a red closing answer.
 */
public final class DialogueFormat {
    public static final Set<String> STYLES = Set.of("bold", "italic", "underlined", "plain");
    private static final String SPEAKER = "§6";
    private static final String DIVIDER = "§8 » ";
    private static final String BODY = "§f";
    private static final String BRACKET = "§8";
    private static final String NEGATIVE = "§c";
    private static final List<String> PALETTE = List.of("§a", "§b", "§e", "§d", "§6", "§9", "§3", "§f");
    private static final Set<String> DECLINES = Set.of("no", "pass", "cancel", "decline", "leave", "later", "goodbye", "bye",
            "refuse", "not-now", "close", "nevermind", "never-mind", "no-thanks");

    private DialogueFormat() {}

    /** One spoken line: the coloured speaker name, a divider and the colorized text. */
    public static String speech(String speaker, String text) {
        return "§r" + speaker(speaker) + DIVIDER + BODY + RichText.colorize(text);
    }

    /** The answer buttons for one prompt, each running {@code /label choice token id}. */
    public static List<TextPart> buttons(String commandLabel, String token, List<ChoiceOption> options) {
        List<TextPart> parts = new ArrayList<>(options.size() * 2 + 1);
        parts.add(TextPart.of("  "));
        for (int index = 0; index < options.size(); index++) {
            ChoiceOption option = options.get(index);
            if (index > 0) parts.add(TextPart.of("§r  "));
            String colour = colour(option, index, options.size());
            String style = option.style().map(DialogueFormat::styleCode).orElse("");
            String label = BRACKET + "[ " + colour + style + RichText.colorize(option.text()) + " " + BRACKET + "]";
            String hover = option.hover().map(RichText::colorize)
                    .orElse(colour + "§l" + RichText.plain(option.text()) + "\n§7Click to choose this answer");
            parts.add(new TextPart(label, Optional.of("/" + commandLabel + " choice " + token + " " + option.id()), Optional.of(hover)));
        }
        return List.copyOf(parts);
    }

    static String colour(ChoiceOption option, int index, int count) {
        Optional<String> chosen = option.colour().flatMap(RichText::colourCode);
        if (chosen.isPresent()) return chosen.get();
        if (count > 1 && DECLINES.contains(option.id().toLowerCase(Locale.ROOT))) return NEGATIVE;
        return PALETTE.get(index % PALETTE.size());
    }

    private static String speaker(String name) {
        String coloured = RichText.colorize(name);
        return RichText.formatted(name) ? coloured : SPEAKER + coloured;
    }

    private static String styleCode(String style) {
        return switch (style) { case "bold" -> "§l"; case "italic" -> "§o"; case "underlined" -> "§n"; default -> ""; };
    }
}
