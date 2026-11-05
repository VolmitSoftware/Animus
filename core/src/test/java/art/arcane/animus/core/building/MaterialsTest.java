package art.arcane.animus.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.building.MaterialRules.Kind;
import art.arcane.animus.core.building.MaterialRules.Need;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaterialsTest {
    private static Need need(String state, String item) { return MaterialRules.need(BlockState.parse(state), Optional.ofNullable(item)); }

    @Test void secondHalvesAndAirAreFree() {
        assertEquals(Kind.FREE, need("air", null).kind());
        assertEquals(Kind.FREE, need("oak_door[half=upper]", "minecraft:oak_door").kind());
        assertEquals(Kind.FREE, need("red_bed[part=head]", "minecraft:red_bed").kind());
        assertEquals(Kind.FREE, need("piston_head[facing=up]", null).kind());
        assertEquals(new Need(Kind.ITEM, "minecraft:oak_door", 1), need("oak_door[half=lower]", "minecraft:oak_door"));
        assertEquals(new Need(Kind.ITEM, "minecraft:red_bed", 1), need("red_bed[part=foot]", "minecraft:red_bed"));
    }

    @Test void stackedPiecesCostOneItemEach() {
        assertEquals(2, need("oak_slab[type=double]", "minecraft:oak_slab").count());
        assertEquals(1, need("oak_slab[type=top]", "minecraft:oak_slab").count());
        assertEquals(3, need("candle[candles=3]", "minecraft:candle").count());
        assertEquals(4, need("sea_pickle[pickles=4]", "minecraft:sea_pickle").count());
        assertEquals(5, need("snow[layers=5]", "minecraft:snow").count());
        assertEquals(1, need("chest[type=left]", "minecraft:chest").count());
    }

    @Test void fluidsNeedBucketsAndItemlessBlocksCannotBeSourced() {
        assertEquals(new Need(Kind.ITEM, "minecraft:water_bucket", 1), need("water[level=0]", null));
        assertEquals(new Need(Kind.ITEM, "minecraft:lava_bucket", 1), need("lava", null));
        assertEquals(Kind.UNOBTAINABLE, need("end_portal", null).kind());
        assertEquals(Kind.UNOBTAINABLE, need("fire", "minecraft:air").kind());
    }

    @Test void ledgerTakesOnlyWhatItHolds() {
        MaterialLedger ledger = new MaterialLedger();
        ledger.add("minecraft:stone", 5);
        ledger.add("minecraft:stone", 2);
        assertEquals(7, ledger.available("minecraft:stone"));
        assertFalse(ledger.take("minecraft:stone", 8));
        assertEquals(7, ledger.available("minecraft:stone"));
        assertTrue(ledger.take("minecraft:stone", 7));
        assertTrue(ledger.isEmpty());
        assertTrue(ledger.take("minecraft:dirt", 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.add("Stone", 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.add("minecraft:stone", 0));
    }

    @Test void ledgerRoundTripsAndRejectsBadCounts() {
        MaterialLedger ledger = new MaterialLedger();
        ledger.add("minecraft:oak_planks", 12);
        ledger.add("minecraft:glass", 3);
        MaterialLedger restored = MaterialLedger.fromJson(ledger.toJson(), "$.materials");
        assertEquals(Map.of("minecraft:glass", 3L, "minecraft:oak_planks", 12L), restored.counts());
        JsonObject bad = new JsonObject();
        bad.addProperty("minecraft:stone", -1);
        assertThrows(IllegalArgumentException.class, () -> MaterialLedger.fromJson(bad, "$.materials"));
        JsonObject fraction = new JsonObject();
        fraction.addProperty("minecraft:stone", 1.5);
        assertThrows(IllegalArgumentException.class, () -> MaterialLedger.fromJson(fraction, "$.materials"));
    }

    @Test void ledgerLimitsKindsOfItem() {
        MaterialLedger ledger = new MaterialLedger();
        for (int index = 0; index < MaterialLedger.MAXIMUM_ITEMS; index++) ledger.add("test:item" + index, 1);
        assertThrows(IllegalStateException.class, () -> ledger.add("test:one_more", 1));
        ledger.add("test:item0", 1);
        assertEquals(2, ledger.available("test:item0"));
    }
}
