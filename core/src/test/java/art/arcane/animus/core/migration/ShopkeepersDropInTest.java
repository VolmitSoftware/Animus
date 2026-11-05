package art.arcane.animus.core.migration;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShopkeepersDropInTest {
    @TempDir Path root;
    private final AtomicBoolean shopkeepersRunning = new AtomicBoolean();

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private CitizensDropIn dropIn(DocumentStore store, NpcRepository npcs) {
        return new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, shopkeepersRunning::get, () -> false));
    }

    private void leave(String fixture, String target) throws IOException {
        Path file = root.resolve(target);
        Files.createDirectories(file.getParent());
        Files.copy(SharedFixtures.fixture("shopkeepers/" + fixture), file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    private static Optional<JsonObject> shop(NpcRepository npcs, String id) {
        return npcs.all().stream().filter((NpcDefinition definition) -> definition.id().equals(id)).findFirst()
                .map((NpcDefinition definition) -> definition.json().getAsJsonObject("modules"))
                .filter((JsonObject modules) -> modules.has("animus:shop")).map((JsonObject modules) -> modules.getAsJsonObject("animus:shop"));
    }

    @Test void bothSavesStageApplyReviewAcceptAndRollBackTogether() throws Exception {
        leave("admin-citizens.yml", "plugins/Citizens/saves.yml");
        leave("admin.yml", "plugins/Shopkeepers/data/save.yml");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            String staged = joined(dropIn(store, npcs).execute(new String[0]));
            assertTrue(staged.contains("Shopkeepers plugins/Shopkeepers/data/save.yml: 6 shops"), staged);
            assertTrue(staged.contains("2 found, 1 not found"), staged);
            shopkeepersRunning.set(true);
            IOException refused = assertThrows(IOException.class, () -> dropIn(store, npcs).execute(new String[]{"apply"}));
            assertTrue(refused.getMessage().contains("Shopkeepers is still enabled"));
            shopkeepersRunning.set(false);
            String applied = joined(dropIn(store, npcs).execute(new String[]{"apply"}));
            assertTrue(applied.contains("2 bound to Citizens NPCs, 1 wait for their NPC"), applied);
            assertEquals(Optional.of(false), shop(npcs, "citizens-0").map((JsonObject module) -> module.get("reviewAccepted").getAsBoolean()));
            assertTrue(npcs.all().stream().anyMatch((NpcDefinition definition) -> definition.id().equals("shopkeeper-3")));
            String review = joined(dropIn(store, npcs).execute(new String[]{"review"}));
            assertTrue(review.contains("shops: 3 waiting"), review);
            dropIn(store, npcs).execute(new String[]{"accept", "shops"});
            assertEquals(Optional.of(true), shop(npcs, "citizens-0").map((JsonObject module) -> module.get("reviewAccepted").getAsBoolean()));
            dropIn(store, npcs).execute(new String[]{"rollback"});
            assertNull(store.read("shops/shopkeepers/shop-1.json"));
            assertTrue(npcs.all().isEmpty());
        }
    }

    @Test void shopkeepersFirstThenCitizensBindsTheWaitingShops() throws Exception {
        leave("admin.yml", "plugins/Animus/imports/shopkeepers/save.yml");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            assertTrue(dropIn(store, npcs).startupNotice().orElseThrow().contains("Shopkeepers migration: found imports/shopkeepers/save.yml"));
            String staged = joined(dropIn(store, npcs).execute(new String[0]));
            assertTrue(staged.contains("3 unchecked"), staged);
            String applied = joined(dropIn(store, npcs).execute(new String[]{"apply"}));
            assertTrue(applied.contains("0 bound to Citizens NPCs, 3 wait"), applied);
            assertNotNull(store.read("shops/shopkeepers/shop-1.json"));
            leave("admin-citizens.yml", "plugins/Animus/imports/saves.yml");
            dropIn(store, npcs).execute(new String[0]);
            String later = joined(dropIn(store, npcs).execute(new String[]{"apply"}));
            assertTrue(later.contains("Bound 2 Shopkeepers shops to their Citizens NPCs"), later);
            assertEquals(Optional.of("shopkeepers:shop-5"), shop(npcs, "citizens-7").map((JsonObject module) -> module.get("shop").getAsString()));
            dropIn(store, npcs).execute(new String[]{"rollback"});
            assertNull(store.read("shops/shopkeepers/shop-5.json"));
            assertTrue(npcs.all().isEmpty());
        }
    }

    @Test void citizensFirstThenShopkeepersBindsToTheInstalledNpcs() throws Exception {
        leave("admin-citizens.yml", "plugins/Animus/imports/saves.yml");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).execute(new String[0]);
            dropIn(store, npcs).execute(new String[]{"apply"});
            assertTrue(shop(npcs, "citizens-0").isEmpty());
            leave("player.yml", "plugins/Animus/imports/shopkeepers/save.yml");
            leave("player-config.yml", "plugins/Animus/imports/shopkeepers/config.yml");
            leave("admin.yml", "plugins/Animus/imports/shopkeepers/save.yml");
            String staged = joined(dropIn(store, npcs).execute(new String[0]));
            assertTrue(staged.contains("with its config.yml"), staged);
            String applied = joined(dropIn(store, npcs).execute(new String[]{"apply"}));
            assertTrue(applied.contains("2 bound to Citizens NPCs"), applied);
            assertTrue(shop(npcs, "citizens-0").isPresent());
            IOException again = assertThrows(IOException.class, () -> dropIn(store, npcs).execute(new String[0]));
            assertTrue(again.getMessage().contains("already applied"));
            dropIn(store, npcs).execute(new String[]{"rollback"});
            assertTrue(npcs.all().isEmpty());
        }
    }
}
