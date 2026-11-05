package art.arcane.animus.core.building;

import java.util.Objects;
import java.util.Optional;

/**
 * What one placed block costs. The platform names the item that places a block; these rules fix the count: the second
 * half of a door, tall plant or bed is free, a double slab is two slabs, and blocks that stack several pieces in one
 * cell (candles, sea pickles, eggs, snow layers, petals) cost one item per piece. Water and lava need a bucket each,
 * and the bucket is used up.
 */
public final class MaterialRules {
    public enum Kind { FREE, ITEM, UNOBTAINABLE }
    public record Need(Kind kind, String item, int count) {
        public static final Need FREE = new Need(Kind.FREE, "", 0);
        public static final Need UNOBTAINABLE = new Need(Kind.UNOBTAINABLE, "", 0);
        public Need { Objects.requireNonNull(kind); Objects.requireNonNull(item); }
    }

    private MaterialRules() {}

    public static Need need(BlockState state, Optional<String> platformItem) {
        if (state.air() || state.structureVoid()) return Need.FREE;
        String path = state.path();
        if (path.equals("water")) return new Need(Kind.ITEM, "minecraft:water_bucket", 1);
        if (path.equals("lava")) return new Need(Kind.ITEM, "minecraft:lava_bucket", 1);
        if (path.equals("piston_head") || path.equals("moving_piston")) return Need.FREE;
        if (state.property("half").filter("upper"::equals).isPresent()) return Need.FREE;
        if (state.property("part").filter("head"::equals).isPresent()) return Need.FREE;
        if (platformItem.isEmpty() || platformItem.get().equals("minecraft:air")) return Need.UNOBTAINABLE;
        int count = 1;
        if (state.property("type").filter("double"::equals).isPresent() && path.endsWith("_slab")) count = 2;
        for (String stacked : new String[]{"candles", "pickles", "eggs", "flower_amount", "segment_amount"}) {
            Optional<String> pieces = state.property(stacked);
            if (pieces.isPresent()) count = number(pieces.get());
        }
        if (path.equals("snow")) count = state.property("layers").map(MaterialRules::number).orElse(1);
        return new Need(Kind.ITEM, platformItem.get(), count);
    }

    private static int number(String text) {
        try { return Math.max(1, Math.min(64, Integer.parseInt(text))); }
        catch (NumberFormatException invalid) { return 1; }
    }
}
