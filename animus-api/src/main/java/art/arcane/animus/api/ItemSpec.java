package art.arcane.animus.api;

import java.util.Objects;
import java.util.Optional;

/**
 * An item as a definition stores it: an item id such as {@code minecraft:iron_sword}, a count of 1 to 99 and optional
 * component text as a give command writes it after the id, such as {@code [enchantment_glint_override=true]}. A service
 * call answers {@link Outcome.Status#INVALID} for an unknown id, a count out of range or malformed components.
 *
 * @param id item id
 * @param count stack size
 * @param components component text in brackets, if any
 * @since 1.3
 */
public record ItemSpec(String id, int count, Optional<String> components) {
    public ItemSpec { Objects.requireNonNull(id, "id"); Objects.requireNonNull(components, "components"); }

    /**
     * One item without components.
     *
     * @param id item id
     * @return the item
     */
    public static ItemSpec of(String id) { return new ItemSpec(id, 1, Optional.empty()); }
}
