package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.shop.ShopSpot;
import art.arcane.animus.core.text.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * New shop, opened by the shop item or {@code /animus shop create} on a chest: what the shop does and how its NPC looks,
 * then Make shop. Selling and the villager are picked to start with, so one click makes the usual shop.
 */
final class ShopMakeScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4;
    static final int[] KIND_SLOTS = {10, 12, 14, 16}, BODY_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    /** The bodies a shop's NPC can take, the villager first, as Shopkeepers shows them. */
    static final List<String> BODIES = List.of("minecraft:villager", "minecraft:wandering_trader", ToolkitFiles.PLAYER, "minecraft:piglin", "minecraft:witch",
            "minecraft:zombie_villager", "minecraft:allay");
    /** What each kind does, in one line, and the item it shows as. */
    static final Map<ShopDocument.Kind, String> MEANINGS = Map.of(
            ShopDocument.Kind.SELLING, "Players buy items from your chest.",
            ShopDocument.Kind.BUYING, "Players sell you items; you pay from your chest.",
            ShopDocument.Kind.TRADING, "Players swap items with your chest.",
            ShopDocument.Kind.BOOK, "Players buy copies of books in your chest.");
    static final Map<ShopDocument.Kind, String> ICONS = Map.of(ShopDocument.Kind.SELLING, "minecraft:chest", ShopDocument.Kind.BUYING, "minecraft:hopper",
            ShopDocument.Kind.TRADING, "minecraft:emerald", ShopDocument.Kind.BOOK, "minecraft:writable_book");
    private final ShopSpot spot;
    private final String world;
    private ShopDocument.Kind kind = ShopDocument.Kind.SELLING;
    private String body = BODIES.getFirst();

    ShopMakeScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopSpot spot, String world) {
        super(toolkit, viewer, commands);
        this.spot = spot; this.world = world;
    }

    @Override String title() { return "New shop"; }
    @Override int rows() { return 5; }
    @Override String node() { return PermissionNodes.SHOPS_CREATE; }

    /** The kind's name players read: Selling, Buying, Trading or Book. */
    static String label(ShopDocument.Kind kind) { return kind.key().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + kind.key().substring(1); }

    @Override void draw() {
        back();
        help("Pick what your shop does and how its NPC looks. Make shop puts the NPC in front of your chest; then stock the chest and set the offers.",
                "10-shops", "player-shops");
        icon(HEADER, "minecraft:chest", title("Your chest"), List.of("&7" + spot.x() + " " + spot.y() + " " + spot.z()), Optional.empty());
        ShopDocument.Kind[] kinds = ShopDocument.Kind.values();
        for (int index = 0; index < kinds.length; index++) {
            ShopDocument.Kind shown = kinds[index];
            boolean chosen = shown == kind;
            List<String> lines = new ArrayList<>(List.of("&7" + MEANINGS.get(shown)));
            if (chosen) lines.add("&fChosen");
            button(KIND_SLOTS[index], glint(ICONS.get(shown), chosen), title(label(shown)), lore(lines, any("pick it")), (GuiModel.Click click) -> kind = shown);
        }
        for (int index = 0; index < BODIES.size(); index++) {
            String type = BODIES.get(index);
            boolean chosen = type.equals(body);
            button(BODY_SLOTS[index], glint(BodyScreen.icon(type), chosen), title(Words.readable(type)), lore(chosen ? List.of("&fChosen") : List.of(), any("pick it")),
                    (GuiModel.Click click) -> body = type);
        }
        button(bottom(SAVE), "minecraft:emerald", title("Make shop"), lore(List.of("&f" + label(kind) + " shop, " + Words.readable(body)), any("make it")),
                (GuiModel.Click click) -> toolkit.shopFlow().make(viewer, spot, world, kind, body));
    }

    static NpcItem glint(String icon, boolean chosen) {
        return new NpcItem(icon, 1, chosen ? Optional.of("[enchantment_glint_override=true]") : Optional.empty(), Optional.empty());
    }
}
