package art.arcane.animus.core.behavior.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.behavior.conversation.ConversationDocument.Choices;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.Line;
import art.arcane.animus.core.behavior.conversation.ConversationDocument.StartKind;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The conversation reader and writer against documents the web editor wrote. */
class ConversationDocumentTest {
    /** Written by the editor's own writer ({@code newConversationBehavior} and edits of it), and its hand-written ferry fixture. */
    static final List<String> FIXTURES = List.of("talk", "talk-near", "talk-sneak", "ferryman");
    @TempDir Path root;

    static JsonObject fixture(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(ConversationDocumentTest.class.getResourceAsStream("/conversation/" + name + ".json"), name)) {
            return JsonDocuments.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static ConversationDocument read(JsonObject behavior) {
        ConversationDocument.Read read = ConversationDocument.read(behavior);
        if (read instanceof ConversationDocument.Misfit misfit) throw new AssertionError(misfit.path() + ": " + misfit.reason());
        return ((ConversationDocument.Fits) read).conversation();
    }

    /** Problems the server's compiler finds, as the web editor's save would get them. */
    List<String> compileErrors(String id, JsonObject behavior) throws IOException {
        try (DocumentStore store = new DocumentStore(root)) {
            List<String> errors = new ArrayList<>();
            JsonArray diagnostics = new EditorDocuments(store, () -> 0).validate(DocumentKind.BEHAVIOR, id, behavior);
            for (JsonElement diagnostic : diagnostics) if ("error".equals(diagnostic.getAsJsonObject().get("severity").getAsString())) errors.add(diagnostic.toString());
            return errors;
        }
    }

    @Test void everyEditorFixtureWritesBackUnchangedKeyOrderIncluded() throws IOException {
        for (String name : FIXTURES) {
            JsonObject behavior = fixture(name);
            JsonObject written = read(behavior).write(behavior);
            assertEquals(behavior.toString(), written.toString(), name);
        }
    }

    @Test void readsTheStartsTheEditorWrites() throws IOException {
        assertEquals(StartKind.CLICK, read(fixture("talk")).start.kind);
        ConversationDocument near = read(fixture("talk-near"));
        assertEquals(StartKind.PROXIMITY, near.start.kind);
        assertEquals(7, near.start.radius);
        assertEquals(StartKind.SNEAK_CLICK, read(fixture("talk-sneak")).start.kind);
    }

    @Test void readsLinesWithTheirDelaySoundAndPlaceholders() throws IOException {
        ConversationDocument near = read(fixture("talk-near"));
        Line first = (Line) near.blocks.get("main").steps.getFirst(), second = (Line) near.blocks.get("main").steps.get(1);
        assertEquals("I am {npc.name}, {player_name}.", first.text, "template holes read back as the placeholders the editor shows");
        assertEquals(10, second.delayTicks);
        assertEquals("minecraft:entity.villager.yes", second.sound);
        assertEquals("Well, {player_name}?", ((Choices) near.blocks.get("main").steps.get(2)).prompt);
    }

    @Test void joinsEachAnswerToTheBlockItsTriggerRuns() throws IOException {
        ConversationDocument ferry = read(fixture("ferryman"));
        Choices question = ferry.question("main").orElseThrow();
        assertEquals(List.of("cross", "decline"), question.options.stream().map((ConversationDocument.Option option) -> option.next).toList());
        assertEquals(List.of("yes", "no"), question.options.stream().map((ConversationDocument.Option option) -> option.trigger).toList());
        assertEquals(List.of("paid"), List.of(ferry.question("cross").orElseThrow().options.getFirst().next));
        assertInstanceOf(ConversationDocument.Action.class, ferry.blocks.get("main").steps.getFirst(), "a look action stays a kept step");
    }

    @Test void namesTheFirstFieldThatDoesNotFit() throws IOException {
        assertEquals("$.routines.main", misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"repeat\",\"count\":2,\"body\":{\"type\":\"sequence\",\"steps\":[]}}}}").path());
        assertEquals("$.triggers[0].event", misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[{\"id\":\"t\",\"event\":\"timer\",\"intervalTicks\":20}]}").path());
        assertEquals("$.triggers[0].choice", misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[{\"id\":\"t\",\"event\":\"dialogue_choice\",\"dialogue\":\"x\"}]}").path());
        assertEquals("$.server", misfit("{\"server\":true,\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}}}").path());
        assertEquals("$.routines.main.steps[0]", misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[{\"type\":\"call\",\"routine\":\"main\"},{\"type\":\"return\"}]}}}").path());
        assertEquals("$.routines.main.bindings", misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"animus:say\",\"arguments\":{\"text\":null},\"bindings\":{\"/text\":{\"variable\":\"x\"}}}}}").path());
        assertTrue(misfit("{\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[]}},\"triggers\":[{\"id\":\"t\",\"event\":\"dialogue_choice\",\"dialogue\":\"x\",\"choice\":\"y\"}]}").reason().contains("no line asks"));
    }

    static ConversationDocument.Misfit misfit(String source) throws IOException {
        return (ConversationDocument.Misfit) ConversationDocument.read(JsonDocuments.object(source));
    }

    @Test void addingAnAnswerMakesItsBlockAndTriggerAndTheResultCompiles() throws IOException {
        JsonObject behavior = fixture("talk");
        ConversationDocument talk = read(behavior);
        String block = talk.addOption("main", "Tell me more", "unused");
        assertEquals("tell-me-more", block);
        assertTrue(talk.blocks.get(block).steps.isEmpty(), "a new answer leads to an empty block");
        ConversationDocument.Option added = talk.question("main").orElseThrow().options.getLast();
        assertEquals("ask-tell-me-more", added.trigger);
        JsonObject written = talk.write(behavior);
        assertEquals(written.toString(), read(written).write(written).toString(), "what the game writes reads back the same");
        assertEquals(List.of(), compileErrors("town:talk", written));
    }

    @Test void aBlockWithoutAQuestionGetsOneOnTheFirstAnswer() throws IOException {
        JsonObject behavior = fixture("talk");
        ConversationDocument talk = read(behavior);
        talk.addOption("goodbye", "Wait", "Leaving already?");
        Choices asked = talk.question("goodbye").orElseThrow();
        assertEquals("ask-2", asked.dialogue, "prompt ids stay unique");
        assertEquals("Leaving already?", asked.prompt);
        assertEquals(List.of(), compileErrors("town:talk", talk.write(behavior)));
    }

    @Test void removingAnAnswerDropsOnlyTheBlocksNothingElseReaches() throws IOException {
        JsonObject behavior = fixture("ferryman");
        ConversationDocument ferry = read(behavior);
        Choices question = ferry.question("main").orElseThrow();
        assertEquals(List.of("cross", "paid"), ferry.removeOption(question, 0), "the answer's block and the block only it led to go");
        assertEquals(List.of("main", "decline"), List.copyOf(ferry.blocks.keySet()));
        assertEquals(List.of("decline"), ferry.removeOption(question, 0));
        assertTrue(ferry.question("main").isEmpty(), "a question without answers goes too");
        JsonObject written = ferry.write(behavior);
        assertFalse(written.has("triggers") && written.getAsJsonArray("triggers").size() > 1);
        assertEquals("Kept for the harbor team", written.get("notes").getAsString(), "keys the conversation does not edit stay");
    }

    @Test void linesWithTheNpcNameBecomeTemplatesAndEveryStartCompiles() throws IOException {
        JsonObject behavior = fixture("talk");
        for (StartKind kind : StartKind.values()) {
            ConversationDocument talk = read(behavior);
            ((Line) talk.blocks.get("main").steps.getFirst()).text = "I am {npc.name}.";
            talk.start(kind);
            talk.start.radius = 5;
            JsonObject written = talk.write(behavior);
            assertEquals(kind, read(written).start.kind, kind.name());
            assertEquals("I am {npc.name}.", ((Line) read(written).blocks.get("main").steps.getFirst()).text);
            assertEquals(List.of(), compileErrors("town:talk", written), kind.name());
        }
    }

    @Test void startedMakesTheEditorsRightClickStart() throws IOException {
        JsonObject behavior = JsonDocuments.object("{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:hi\",\"entry\":\"main\",\"routines\":{}}");
        JsonObject written = ConversationDocument.started("Hello").write(behavior);
        assertEquals("{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:hi\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"sequence\",\"steps\":[{\"type\":\"action\","
                + "\"capability\":\"animus:say\",\"arguments\":{\"text\":\"Hello\"}}]}},\"triggers\":[{\"id\":\"start\",\"event\":\"interaction\",\"cooldownTicks\":40}]}", written.toString());
        assertEquals(List.of(), compileErrors("town:hi", written));
    }

    @Test void errorsNameTheBlockAndStep() throws IOException {
        ConversationDocument talk = read(fixture("talk"));
        ((Line) talk.blocks.get("goodbye").steps.getFirst()).text = " ";
        talk.question("main").orElseThrow().options.getFirst().text = "";
        assertEquals(List.of("main step 2: Empty answer.", "goodbye step 1: Empty line."), talk.errors());
    }
}
