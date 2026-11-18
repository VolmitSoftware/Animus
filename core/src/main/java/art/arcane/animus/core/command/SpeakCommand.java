package art.arcane.animus.core.command;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /animus speak}: the {@code animus:say} it runs, as Citizens' {@code /npc speak} words it. A target hears the line
 * alone, a range reaches everyone within it, and with neither the players near the NPC hear it; a bubble also shows it
 * over the NPC.
 */
final class SpeakCommand {
    private SpeakCommand() {}

    /** The say arguments; refuses a value outside its range with what it takes. */
    static JsonObject arguments(String text, Optional<UUID> target, String range, String bubble) {
        if (text.isBlank()) throw new IllegalArgumentException("Give the NPC something to say");
        if (target.isPresent() && !range.isEmpty()) throw new IllegalArgumentException("Give target or range, not both");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", text);
        if (target.isPresent()) arguments.addProperty("player", target.get().toString());
        else arguments.addProperty("audience", "nearby");
        if (!range.isEmpty()) arguments.addProperty("radius", radius(range));
        if (!bubble.isEmpty()) arguments.addProperty("bubbleTicks", ticks(bubble));
        return arguments;
    }

    /** How many players heard it, or why it was not said. */
    static String reply(String npc, Outcome<String> outcome) {
        if (outcome.status() != Outcome.Status.OK)
            return "Speak: " + outcome.diagnostics().stream().map(Diagnostic::message).collect(Collectors.joining("; "));
        int heard = outcome.value().map(SpeakCommand::delivered).orElse(0);
        if (heard == 0) return npc + " said it, but no player heard it";
        return npc + " said it to " + heard + (heard == 1 ? " player" : " players");
    }

    private static int delivered(String result) {
        try {
            JsonObject response = JsonDocuments.object(result);
            return response.has("delivered") ? response.get("delivered").getAsInt() : 0;
        } catch (IOException | RuntimeException unreadable) { return 0; }
    }

    private static double radius(String text) {
        try {
            double blocks = Double.parseDouble(text);
            if (blocks >= 1 && blocks <= 64) return blocks;
        } catch (NumberFormatException unreadable) { /* refused below with what range takes */ }
        throw new IllegalArgumentException("range takes 1 to 64 blocks, not " + text);
    }

    private static int ticks(String text) {
        try {
            int ticks = Integer.parseInt(text);
            if (ticks >= 1 && ticks <= 1200) return ticks;
        } catch (NumberFormatException unreadable) { /* refused below with what bubble takes */ }
        throw new IllegalArgumentException("bubble takes 1 to 1200 ticks, not " + text);
    }
}
