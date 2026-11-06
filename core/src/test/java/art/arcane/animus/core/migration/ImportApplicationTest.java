package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end storage cases exercise conflicts, rollback/restart, copied references and resource bounds. */
class ImportApplicationTest {
    @TempDir Path root;

    @Test void reimportUpdatesAnNpcTheOwnerMovedWhereItNowLives() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("data"))) {
            CitizensImporter.Preview first = ApplicationChecks.preview(store, root.resolve("source.yml"), "Guard");
            ImportApplications.apply(store, first.id(), ImportApplications.stage(store, first.id(), ImportProjection.Mode.REJECT, null).applicationId());
            Path moved = store.resolve("npcs/world/town/gate/citizens-1.json");
            Files.createDirectories(moved.getParent());
            Files.move(store.resolve("npcs/world/citizens-1.json"), moved);
            NpcRepository npcs = new NpcRepository(store);
            assertTrue(npcs.loadForStartup().isEmpty());

            CitizensImporter.Preview updated = ApplicationChecks.preview(store, root.resolve("source.yml"), "Updated");
            ImportApplications.Preview replacement = ImportApplications.stage(store, updated.id(), ImportProjection.Mode.REPLACE, null);
            assertEquals(2, replacement.replacements());
            ImportApplications.apply(store, updated.id(), replacement.applicationId());
            assertNull(store.read("npcs/world/citizens-1.json"), "the moved NPC is not installed again at its import folder");
            NpcDefinition kept = NpcDefinition.parse(store.read("npcs/world/town/gate/citizens-1.json"));
            assertEquals("Updated", kept.name());
            assertEquals("world/town/gate", kept.group());
            assertTrue(npcs.reload().isEmpty());
            assertEquals("npcs/world/town/gate/citizens-1.json", npcs.find("citizens-1").orElseThrow().path());
        }
    }

    @Test void identityProjectionRetainsAndRemapsOnlyReviewedReferences() throws Exception { ProjectionChecks.main(new String[0]); }
    @Test void applicationsRemainAtomicAcrossEditsRestartAndRollback() throws Exception { ApplicationChecks.main(new String[0]); }
    @Test void compactTransactionsRecoverWithBoundedValidatedPayloads() throws Exception { CompactTransactionChecks.main(new String[0]); }
    @Test void applicationPlansFitTheirLoadAndTransactionBudgets() throws Exception { ApplicationLimitsChecks.main(new String[0]); }
}
