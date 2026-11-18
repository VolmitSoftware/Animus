package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code /animus speak} runs animus:say the way Citizens' /npc speak reads its target, range and bubble. */
class SpeakCommandTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static JsonObject json(String text) {
        try { return JsonDocuments.object(text); } catch (IOException invalid) { throw new AssertionError(invalid); }
    }

    @Test void withoutTargetOrRangeEveryoneNearTheNpcHearsIt() {
        assertEquals(json("{\"text\":\"Market opens at dawn\",\"audience\":\"nearby\"}"), SpeakCommand.arguments("Market opens at dawn", Optional.empty(), "", ""));
    }

    @Test void aTargetHearsItAloneAndARangeReachesEveryoneInIt() {
        assertEquals(json("{\"text\":\"Psst\",\"player\":\"" + ALEX + "\"}"), SpeakCommand.arguments("Psst", Optional.of(ALEX), "", ""));
        assertEquals(json("{\"text\":\"Hear ye\",\"audience\":\"nearby\",\"radius\":32.0}"), SpeakCommand.arguments("Hear ye", Optional.empty(), "32", ""));
        assertEquals(json("{\"text\":\"Hear ye\",\"audience\":\"nearby\",\"bubbleTicks\":60}"), SpeakCommand.arguments("Hear ye", Optional.empty(), "", "60"),
                "a bubble shows over the NPC as well as in chat");
    }

    @Test void valuesOutsideTheirRangesAreRefusedWithTheRange() {
        assertEquals("range takes 1 to 64 blocks, not 65", assertThrows(IllegalArgumentException.class, () -> SpeakCommand.arguments("Hi", Optional.empty(), "65", "")).getMessage());
        assertEquals("range takes 1 to 64 blocks, not far", assertThrows(IllegalArgumentException.class, () -> SpeakCommand.arguments("Hi", Optional.empty(), "far", "")).getMessage());
        assertEquals("bubble takes 1 to 1200 ticks, not 0", assertThrows(IllegalArgumentException.class, () -> SpeakCommand.arguments("Hi", Optional.empty(), "", "0")).getMessage());
        assertEquals("Give target or range, not both", assertThrows(IllegalArgumentException.class, () -> SpeakCommand.arguments("Hi", Optional.of(ALEX), "8", "")).getMessage());
        assertEquals("Give the NPC something to say", assertThrows(IllegalArgumentException.class, () -> SpeakCommand.arguments(" ", Optional.empty(), "", "")).getMessage());
    }

    @Test void theReplySaysHowManyPlayersHeardItOrWhyNot() {
        assertEquals("guard said it to 3 players", SpeakCommand.reply("guard", new Outcome<>(Outcome.Status.OK, Optional.of("{\"delivered\":3}"), List.of())));
        assertEquals("guard said it to 1 player", SpeakCommand.reply("guard", new Outcome<>(Outcome.Status.OK, Optional.of("{\"delivered\":1}"), List.of())));
        assertEquals("guard said it, but no player heard it", SpeakCommand.reply("guard", new Outcome<>(Outcome.Status.OK, Optional.of("{\"delivered\":0}"), List.of())));
        String refused = SpeakCommand.reply("guard", new Outcome<>(Outcome.Status.UNAVAILABLE, Optional.empty(),
                List.of(new Diagnostic(Diagnostic.Severity.ERROR, "npc-inactive", "$", "The NPC is not active"))));
        assertTrue(refused.contains("The NPC is not active"), refused);
    }

    @Test void speakNeedsItsOwnNode() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode speak = director.engine().getRoot().getChildren().stream()
                .filter((DirectorRuntimeNode child) -> child.getDescriptor().getName().equals("speak")).findFirst().orElseThrow();
        assertEquals(PermissionNodes.NPC_SPEAK, AnimusDirector.permission(speak));
        AnimusDirectorTest.Actor builder = new AnimusDirectorTest.Actor(true, PermissionNodes.NPC_EDIT);
        director.execute(builder, new String[]{"speak", "guard", "Hello"});
        assertTrue(builder.text().contains("No permission: " + PermissionNodes.NPC_SPEAK), builder.text());
    }
}
