package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** {@code /animus status}: one line for a player with the rest behind [Details]; three lines for the console. */
class StatusReplyTest {
    private static final List<String> LINES = List.of("v0.1.0 on Paper 26.3 | 4 NPCs", "Settings in effect: npcs on", "Animus 0.1.0 | Paper | Minecraft 26.3 | Live QA");
    private static final String RUNTIME = "NPC runtime ready | active=2 | viewers=1";

    @Test void aPlayerGetsOneLineWithDetailsAndDocs() {
        List<Object> sent = new ArrayList<>();
        AnimusCommandTree.status(actor(UUID.randomUUID(), sent), LINES, RUNTIME);
        assertEquals(1, sent.size());
        ChatLine line = (ChatLine) sent.getFirst();
        assertEquals("[Animus]: v0.1.0 on Paper 26.3 | 4 NPCs | NPC runtime ready [Details] [Docs]", PlainTextComponentSerializer.plainText().serialize(line.component()));
    }

    @Test void aPlayerWithoutAdminGetsTheHeadlineButNotTheCountersAndSettings() {
        List<Object> sent = new ArrayList<>();
        AnimusCommandTree.status(actor(UUID.randomUUID(), sent, false), LINES, RUNTIME);
        assertEquals(1, sent.size());
        String shown = PlainTextComponentSerializer.plainText().serialize(((ChatLine) sent.getFirst()).component());
        assertEquals("[Animus]: v0.1.0 on Paper 26.3 | 4 NPCs | NPC runtime ready [Docs]", shown);
        assertFalse(((ChatLine) sent.getFirst()).console().contains("Settings in effect"));
    }

    @Test void theConsoleGetsTheHeadlineCountersAndBudgets() {
        List<Object> sent = new ArrayList<>();
        AnimusCommandTree.status(actor(null, sent), LINES, RUNTIME);
        assertEquals(List.of("v0.1.0 on Paper 26.3 | 4 NPCs", RUNTIME, "Settings in effect: npcs on"), sent);
    }

    private static CommandActor actor(UUID player, List<Object> sent) { return actor(player, sent, true); }

    private static CommandActor actor(UUID player, List<Object> sent, boolean admin) {
        return new CommandActor() {
            @Override public boolean permitted(String node) { return admin || !node.equals(AnimusDirector.ADMIN); }
            @Override public void tell(String message) { sent.add(RichText.plain(message)); }
            @Override public void rich(ChatLine line) { sent.add(line); }
            @Override public Optional<UUID> player() { return Optional.ofNullable(player); }
            @Override public boolean reply(Runnable action) { action.run(); return true; }
        };
    }
}
