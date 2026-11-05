package art.arcane.animus.core;

import art.arcane.animus.core.editor.DocumentKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCatalogsTest {
    @TempDir Path root;

    @Test void eachKindReloadsOnlyItsOwnCatalog() throws Exception {
        List<String> ran = new ArrayList<>();
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root))) {
            runtime.start();
            RuntimeCatalogs catalogs = new RuntimeCatalogs(new RuntimeCatalogs.Parts(runtime, () -> ran.add("refresh"), () -> null,
                    () -> ran.add("shops"), () -> ran.add("holograms"), (String line) -> {}));
            catalogs.reload(new CatalogChange(Set.of(DocumentKind.SHOP), Set.of(), false));
            assertEquals(List.of("shops"), ran);
            ran.clear();
            catalogs.reload(new CatalogChange(Set.of(DocumentKind.INVENTORY, DocumentKind.HOLOGRAM), Set.of(), true));
            assertEquals(List.of("shops", "holograms"), ran);
            ran.clear();
            catalogs.reload(new CatalogChange(Set.of(DocumentKind.RIG), Set.of(), false));
            assertEquals(List.of("refresh"), ran);
            ran.clear();
            catalogs.reload(new CatalogChange(Set.of(DocumentKind.NPC), Set.of(), false));
            assertEquals(List.of("refresh"), ran);
        }
    }

    @Test void deliberatelyDeletedPathsAreFoundFromTheTransactionReceipt() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root))) {
            runtime.start();
            String behavior = "{\"schemaVersion\":1,\"revision\":1,\"id\":\"town:greeter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"wait\",\"ticks\":1}}}";
            String written = runtime.documents().commit(List.of(new art.arcane.animus.core.storage.DocumentStore.Change("behaviors/town/greeter.json",
                    art.arcane.animus.core.storage.JsonDocuments.hash(null), behavior)));
            CatalogChange added = CatalogChange.of(runtime.documents(), List.of(written), false);
            assertEquals(Set.of(DocumentKind.BEHAVIOR), added.kinds());
            assertTrue(added.removedBehaviors().isEmpty());
            String inverse = runtime.documents().rollback(written);
            CatalogChange removed = CatalogChange.of(runtime.documents(), List.of(inverse), true);
            assertEquals(Set.of("town:greeter"), removed.removedBehaviors());
            assertEquals(Set.of(), removed.without(DocumentKind.BEHAVIOR).kinds());
        }
    }
}
