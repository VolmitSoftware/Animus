package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcDisguise;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisguiseSettingTest {
    @TempDir Path root;
    private static final String GUARD = "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"12345678-1234-1234-1234-123456789abc\",\"id\":\"guard\",\"group\":\"\",\"name\":\"N\","
            + "\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}";
    private final NpcDisguise.Resolver pigs = (String text) -> {
        if (!text.equals("pig setBurning")) throw new IllegalArgumentException("LibsDisguises cannot read '" + text + "'");
        return new NpcDisguise.Body(JsonParser.parseString("{\"type\":\"minecraft:pig\"}").getAsJsonObject(), Optional.empty(), List.of("setBurning"));
    };

    @AfterEach void uninstall() { NpcDisguise.uninstall(pigs); }

    private NpcRepository loaded(DocumentStore store) throws Exception {
        store.commit(List.of(new DocumentStore.Change("npcs/guard.json", "missing", GUARD)));
        NpcRepository repository = new NpcRepository(store);
        assertTrue(repository.reload().isEmpty());
        return repository;
    }

    @Test void setShowsTheBodyAndWhatItLeavesOutThenResetRemovesIt() throws Exception {
        NpcDisguise.install(pigs);
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store);
            List<String> set = DisguiseSetting.set(repository, "guard", "pig setBurning");
            assertTrue(set.getFirst().contains("guard: disguised as minecraft:pig; tx "), set.toString());
            assertEquals("disguise options not shown: setBurning", set.get(1));
            assertEquals("pig setBurning", repository.draft("guard").document().get("disguise").getAsString());
            assertEquals(List.of("guard wears 'pig setBurning' as minecraft:pig", "disguise options not shown: setBurning"), DisguiseSetting.show(repository, "guard"));
            assertTrue(DisguiseSetting.set(repository, "guard", "reset").getFirst().contains("guard: disguise removed"));
            assertFalse(repository.draft("guard").document().has("disguise"));
            assertEquals("minecraft:player", repository.find("guard").orElseThrow().json().get("type").getAsString());
        }
    }

    @Test void textLibsDisguisesCannotReadIsRefusedBeforeSaving() throws Exception {
        NpcDisguise.install(pigs);
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> DisguiseSetting.set(repository, "guard", "pig setWings"));
            assertEquals("LibsDisguises cannot read 'pig setWings'", refused.getMessage());
            assertFalse(repository.draft("guard").document().has("disguise"));
        }
    }

    @Test void withoutLibsDisguisesTheTextIsKeptAndTheReplySaysSo() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            NpcRepository repository = loaded(store);
            List<String> set = DisguiseSetting.set(repository, "guard", "zombie");
            assertTrue(set.getFirst().contains("disguised as minecraft:player"), set.toString());
            assertEquals(DisguiseSetting.NOT_INSTALLED, set.get(1));
        }
    }
}
