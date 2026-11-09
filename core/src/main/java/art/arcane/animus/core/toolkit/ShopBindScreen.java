package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shop files on this server. A click binds a shop to the selection in one transaction, a right click opens it for
 * you, and sneak opens its settings. Offers are edited in the web editor.
 */
final class ShopBindScreen<P, S> extends ToolScreen<P, S> {
    static final int RELOAD = 46, CREATE = 47, SELECTION = 53;
    static final String RELOAD_COMMAND = "animus shop reload";
    private List<ShopFiles.Shop> shops;

    ShopBindScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        reload();
    }

    /** Reads {@code shops/} again; queued after any file change so the list shows it. */
    void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files().shops(), (List<ShopFiles.Shop> found) -> { shops = found; viewer.refresh(); }); }

    ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Shops"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Map<String, Integer> picked = bound(selected);
        if (shops == null) icon(22, "minecraft:clock", "&7Reading shops/", List.of(), Optional.empty());
        else list(shops, "No shops yet; make one below", (Integer slot, ShopFiles.Shop shop) -> {
            List<String> lines = new ArrayList<>();
            lines.add("&7" + shop.path());
            if (shop.problem().isPresent()) lines.add("&c" + shop.problem().get());
            else lines.add("&7Offers &f" + shop.offers() + "&7, window &f" + shop.style().map(ShopDocument.Style::key).orElse("default"));
            lines.add("&7In the selection &f" + picked.getOrDefault(shop.id(), 0) + "&7 of &f" + selected.size());
            button(slot, shop.problem().isPresent() ? "minecraft:barrel" : "minecraft:emerald", title(shop.id()), lore(lines, hints("bind to the selection", "open it for you now", "settings")),
                    (GuiModel.Click click) -> {
                        if (sneak(click)) viewer.push(new ShopDetailScreen<>(toolkit, viewer, commands, shop, this::reload));
                        else if (right(click)) { viewer.close(); viewer.host().command(Optional.of(viewer.player()), "animus shop open " + shop.id()); }
                        else chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().bindShop(shop.id(), ids)));
                    });
        });
        back();
        button(RELOAD, "minecraft:repeater", title("Reload shops"), lore(List.of("&7After editing files by hand"), any("reload shops and chest GUIs")), (GuiModel.Click click) -> {
            viewer.host().command(Optional.of(viewer.player()), RELOAD_COMMAND);
            reload();
        });
        button(CREATE, "minecraft:writable_book", title("New shop"), lore(List.of("&7Asks for an id such as town:baker", "&7Writes an empty shop; add offers", "&7in the web editor"), any("type the id")),
                (GuiModel.Click click) -> prompt("Shop id", "", (String id) -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().newShop(id.trim()), RELOAD_COMMAND, this::reload)));
        icon(SELECTION, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"), List.of("&7" + names(selected, 6)), Optional.empty());
    }

    /** How many of the selected NPCs bind each shop through {@code animus:shop}. */
    private Map<String, Integer> bound(List<String> selected) {
        Set<String> chosen = new HashSet<>(selected);
        Map<String, Integer> picked = new HashMap<>();
        for (NpcDefinition definition : toolkit.npcs().stored()) {
            if (!chosen.contains(definition.id())) continue;
            ShopFiles.module(definition.json(), ShopBinding.MODULE).filter((JsonObject module) -> module.has("shop") && module.get("shop").isJsonPrimitive())
                    .ifPresent((JsonObject module) -> picked.merge(module.get("shop").getAsString(), 1, Integer::sum));
        }
        return picked;
    }
}
