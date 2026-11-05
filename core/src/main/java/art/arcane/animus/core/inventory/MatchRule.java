package art.arcane.animus.core.inventory;

/** How a sold or traded item is compared with what a player holds. */
public enum MatchRule {
    /** Every piece of item data must be equal. The default for new shops. */
    STRICT,
    /**
     * The imported rule: item type, damage, name, lore, enchantments, potion and book content must be equal; hidden data
     * and custom model data are not compared.
     */
    LEGACY,
    /** Only the item type. Used by GUI {@code take} actions whose icon names no text. */
    MATERIAL
}
