package art.arcane.animus.core.shop;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShopCatalogRetentionTest {
    @TempDir Path data;

    @Test void aBrokenEditKeepsTheLastValidShopAndADeletionDropsIt() throws Exception {
        String bakery = Files.readString(Path.of(System.getProperty("animusSchemaDirectory")).resolve("fixtures/export/shops/shop/bakery.json"));
        Path file = data.resolve("shops/shop/bakery.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, bakery);
        ShopCatalog valid = ShopCatalog.load(data, (String id) -> false);
        assertTrue(valid.shop("shop:bakery").isPresent(), valid.problems().toString());

        Files.writeString(file, "{\"title\":");
        ShopCatalog broken = ShopCatalog.load(data, (String id) -> false);
        assertTrue(broken.shop("shop:bakery").isEmpty());
        ShopCatalog kept = broken.retaining(valid, data, (String id) -> false);
        assertSame(valid.shop("shop:bakery").orElseThrow(), kept.shop("shop:bakery").orElseThrow());
        assertFalse(kept.problems().isEmpty(), "the refusal is still reported");

        Files.delete(file);
        ShopCatalog deleted = ShopCatalog.load(data, (String id) -> false).retaining(kept, data, (String id) -> false);
        assertTrue(deleted.shop("shop:bakery").isEmpty());
    }
}
