package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.text.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A shop a player runs, as its owner sees it after clicking its NPC: Edit offers, Stock, Chests, Rename, Skin for a
 * player body, Open or Closed, the NPC's body, and Remove shop, which takes the NPC away and leaves the chests and their
 * items. Staff with {@code animus.npc.others} see every player's shop the same way.
 */
final class PlayerShopScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, OFFERS = 10, STOCK = 11, CHESTS = 12, KIND = 13, RENAME = 14, SKIN = 15, STATE = 16, REMOVE = 35;
    /** Why a shop its owner closed is closed. */
    static final String OWNER_CLOSED = "Closed by its owner";
    private final String id;
    private Optional<PlayerShopFiles.Shop> shop = Optional.empty();
    private Optional<String> missing = Optional.empty();

    PlayerShopScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String id) {
        super(toolkit, viewer, commands);
        this.id = id;
    }

    @Override String title() { return shop.map((PlayerShopFiles.Shop found) -> RichText.plain(RichText.colorize(found.shop().title()))).orElse("Shop"); }
    @Override int rows() { return 6; }
    @Override String node() { return PlayerShopFlow.node(toolkit.shopFlow().manager(viewer.player())); }
    @Override void begin() { reload(); }

    private PlayerShopFiles files() { return new PlayerShopFiles(toolkit.npcs(), toolkit.documents()); }

    void reload() {
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().read(id), (PlayerShopFiles.Shop found) -> { shop = Optional.of(found); missing = Optional.empty(); viewer.refresh(); },
                (String reason) -> { shop = Optional.empty(); missing = Optional.of(reason); viewer.refresh(); });
    }

    @Override void draw() {
        back();
        help("Your shop: set its offers, keep its chest stocked and collect what it earns from the chest. Players trade by clicking its NPC.", "10-shops", "player-shops");
        if (shop.isEmpty()) { icon(22, "minecraft:paper", "&7" + missing.orElse("Loading"), List.of(), Optional.empty()); return; }
        ShopDocument found = shop.get().shop();
        ShopDocument.Kind kind = found.kind().orElse(ShopDocument.Kind.TRADING);
        int offers = found.entries().size(), chests = found.containers().size();
        List<String> lines = new ArrayList<>(List.of("&f" + ShopMakeScreen.label(kind) + " shop"));
        if (!found.owns(viewer.id())) lines.add("&fOwner: " + PlayerShopFlow.owner(found));
        lines.add(found.closed().map((String reason) -> "&cClosed: " + reason).orElse("&aOpen"));
        lines.add("&7" + offers + (offers == 1 ? " offer, " : " offers, ") + chests + (chests == 1 ? " chest" : " chests"));
        icon(HEADER, ShopMakeScreen.ICONS.get(kind), title(RichText.plain(RichText.colorize(found.title()))), lines, Optional.empty());
        button(OFFERS, "minecraft:emerald", title("Edit offers"), lore(List.of("&7What players get and pay"), any("edit them")),
                (GuiModel.Click click) -> push(new OfferEditorScreen<>(toolkit, viewer, commands, id, this::reload)));
        button(STOCK, "minecraft:chest", title("Stock"), lore(List.of("&7Stock it and collect earnings"), any("open your chest")), (GuiModel.Click click) -> stock(found));
        button(CHESTS, "minecraft:barrel", title("Chests"), lore(List.of("&f" + chests + " of " + ShopDocument.MAXIMUM_CONTAINERS), any("add or remove chests")),
                (GuiModel.Click click) -> push(new ShopChestsScreen<>(toolkit, viewer, commands, id, this::reload)));
        List<ShopDocument.Kind> kinds = List.of(ShopDocument.Kind.values());
        button(KIND, ShopMakeScreen.ICONS.get(kind), title("Kind: " + ShopMakeScreen.label(kind)), lore(List.of("&7" + ShopMakeScreen.MEANINGS.get(kind)), hints("next kind", "previous kind", "")),
                (GuiModel.Click click) -> {
                    ShopDocument.Kind next = cycle(click, kinds, kind);
                    toolkit.write(viewer, (ToolkitFiles ignored) -> files().kind(id, next), this::changed);
                });
        button(RENAME, "minecraft:name_tag", title("Rename"), lore(List.of("&fNow: " + RichText.plain(RichText.colorize(found.title()))), any("type a new name")),
                (GuiModel.Click click) -> prompt("Shop name", RichText.plain(RichText.colorize(found.title())),
                        (String typed) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().rename(id, typed.strip()), this::changed)));
        Optional<NpcDefinition> npc = shop.get().npcs().stream().findFirst().flatMap(toolkit.npcs()::find);
        String body = npc.map((NpcDefinition definition) -> PropertyScreen.type(definition.json())).orElse("");
        if (body.equals(ToolkitFiles.PLAYER) && npc.isPresent()) {
            String skinned = npc.get().id();
            if (holds(PermissionNodes.NPC_SKIN)) button(SKIN, HEAD, title("Skin"), lore(List.of("&7The look the NPC wears"), any("change it")), (GuiModel.Click click) -> {
                toolkit.selectOnly(viewer.id(), skinned);
                push(new SkinScreen<>(toolkit, viewer, commands));
            });
            else denied(SKIN, "Skin", PermissionNodes.NPC_SKIN);
        }
        if (found.closed().isPresent())
            button(STATE, "minecraft:gray_dye", title("Closed"), lore(List.of("&c" + found.closed().get()), any("open the shop")),
                    (GuiModel.Click click) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().closed(id, Optional.empty()), this::changed));
        else button(STATE, "minecraft:lime_dye", title("Open"), lore(List.of("&7Players can trade"), any("close the shop")),
                (GuiModel.Click click) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().closed(id, Optional.of(OWNER_CLOSED)), this::changed));
        for (int index = 0; index < ShopMakeScreen.BODIES.size(); index++) {
            String type = ShopMakeScreen.BODIES.get(index);
            boolean now = type.equals(body);
            List<String> sources = shop.get().npcs();
            button(ShopMakeScreen.BODY_SLOTS[index], ShopMakeScreen.glint(BodyScreen.icon(type), now), title(Words.readable(type)),
                    lore(now ? List.of("&fNow: this body") : List.of("&7Body"), any(now ? "keep it" : "change to it")), (GuiModel.Click click) -> {
                        if (!now && !sources.isEmpty()) toolkit.write(viewer, (ToolkitFiles ignored) -> BodyScreen.change(toolkit.npcs(), sources, type), this::reload);
                    });
        }
        confirm(REMOVE, "minecraft:lava_bucket", "Remove shop", "Removes the shop and its NPC; your chests and their items stay", 1, true, () ->
                toolkit.write(viewer, (ToolkitFiles ignored) -> files().remove(id), () -> { reloadShops(); viewer.back(); }));
    }

    private void changed() { reloadShops(); reload(); }

    private void reloadShops() { toolkit.hooks().ifPresent((Toolkit.Commands hooks) -> hooks.shops().get().reload()); }

    /** Opens the shop's first chest, as if the player opened it by hand. */
    private void stock(ShopDocument found) {
        if (found.containers().isEmpty()) { refuse("The shop has no chest; add one in Chests"); return; }
        Optional<String> refused = toolkit.host().openContainer(viewer.player(), found.containers().getFirst());
        if (refused.isPresent()) refuse("Cannot open the chest: " + refused.get());
    }
}
