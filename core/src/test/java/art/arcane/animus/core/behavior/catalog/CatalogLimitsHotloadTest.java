package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLimitsHotloadTest {
    @TempDir Path root;
    private static final String EMPTY = "{\"type\":\"sequence\",\"steps\":[]}";
    private static final BehaviorCatalog.Limits ROOMY = BehaviorCatalog.Limits.defaults();
    private static final BehaviorCatalog.Limits TIGHT = new BehaviorCatalog.Limits(1, 4096, 8, BehaviorCompiler.MAX_BYTES, 16L * 1024 * 1024, 8192);

    private void put(String id) throws Exception {
        Path file = root.resolve(BehaviorPath.forId(id));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":1,\"revision\":1,\"id\":\"" + id + "\",\"entry\":\"main\",\"routines\":{\"main\":" + EMPTY + "}}");
    }
    private static BehaviorCatalog.Reload reload(BehaviorCatalog catalog) { return catalog.reload(catalog.snapshot().generation(), Set.of()); }

    @Test void loweredDocumentBudgetRejectsTheNextReloadAndKeepsEveryAcceptedProgram() throws Exception {
        put("example:one");
        put("example:two");
        BehaviorCatalog catalog = new BehaviorCatalog(root, ROOMY, resolver -> BehaviorCatalog.Capability.present(), BehaviorCheckpoints.disabled());
        assertTrue(reload(catalog).published());
        BehaviorCatalog.Snapshot accepted = catalog.snapshot();
        catalog.limits(TIGHT);
        assertSame(accepted, catalog.snapshot(), "limits wait for the next reload");
        BehaviorCatalog.Reload refused = reload(catalog);
        assertFalse(refused.published());
        assertSame(accepted, catalog.snapshot());
        assertTrue(catalog.snapshot().find("example:one").orElseThrow().available());
        assertTrue(catalog.snapshot().find("example:two").orElseThrow().available());
        assertFalse(catalog.validate(catalog.snapshot().generation()).scanned(), "validation uses the adopted budget too");
        catalog.limits(ROOMY);
        assertTrue(reload(catalog).published());
        assertEquals(accepted.generation() + 1, catalog.snapshot().generation());
    }
}
