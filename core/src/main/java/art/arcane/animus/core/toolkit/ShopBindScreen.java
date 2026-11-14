package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.conversation.ConversationDocument;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shop files on this server. A click puts a shop on the selection in one transaction, asking once more above
 * {@link #BULK} NPCs; a right-click, or any click with nothing selected, opens its details. New shop asks for a name.
 */
final class ShopBindScreen<P, S> extends ToolScreen<P, S> {
    static final int CREATE = ACTIONS, RELOAD = ACTIONS + 1, NOTE = ACTIONS + 8;
    static final String RELOAD_COMMAND = "animus shop reload";
    private List<ShopFiles.Shop> shops;
    /** Whether a selected NPC both talks and opens its shop on a right-click, read with the shops. */
    private boolean talksToo;

    ShopBindScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() { reload(); }

    /** Reads {@code shops/} again, and whether a selected NPC also talks; queued after any file change so the list shows it. */
    void reload() {
        List<String> selected = toolkit.selected(viewer.id());
        toolkit.later(viewer, (ToolkitFiles ignored) -> {
            talksToo = talksToo(selected);
            return files().shops();
        }, (List<ShopFiles.Shop> found) -> { shops = found; viewer.refresh(); });
    }

    /**
     * Whether a selected NPC opens its shop on a right-click and has a conversation that starts on one: the runtime runs
     * both, so one click talks and opens the shop. Runs on the file lane.
     */
    private boolean talksToo(List<String> selected) throws IOException {
        List<String> shopping = selected.stream().filter((String id) -> ShopFiles.opensOnRightClick(NpcScreen.stored(toolkit, id))).toList();
        if (shopping.isEmpty()) return false;
        BehaviorFiles behaviors = BehaviorScreen.files(toolkit);
        for (BehaviorFiles.Behavior talk : behaviors.talks(shopping))
            if (behaviors.conversation(talk.id()).conversation().start.kind == ConversationDocument.StartKind.CLICK) return true;
        return false;
    }

    ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Shops"; }
    @Override String node() { return PermissionNodes.SHOPS; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Map<String, Integer> picked = bound(selected);
        Set<String> used = used();
        if (shops == null) icon(22, "minecraft:clock", "&7Reading shops", List.of(), Optional.empty());
        else list(shops, (ShopFiles.Shop shop) -> shop.name() + " " + shop.id(), "None yet. Make one with New shop", (Integer slot, ShopFiles.Shop shop) -> {
            List<String> lines = new ArrayList<>();
            lines.add(shop.problem().map((String problem) -> "&c" + problem).orElse("&7" + offers(shop.offers()) + (used.contains(shop.id()) ? "" : ", on no NPC")));
            int on = picked.getOrDefault(shop.id(), 0);
            if (on > 0) lines.add("&fNow: on " + (selected.size() == 1 ? "it" : on + " of " + selected.size()));
            if (advanced()) lines.add("&8" + shop.id());
            List<String> clicks = selected.isEmpty() ? any("details") : hints("use on " + named(selected), "details", "");
            button(slot, shop.problem().isPresent() ? "minecraft:barrel" : "minecraft:emerald", title(shop.name()), lore(lines, clicks), (GuiModel.Click click) -> {
                if (selected.isEmpty() || right(click)) { push(new ShopDetailScreen<>(toolkit, viewer, commands, shop, this::reload)); return; }
                chosen().ifPresent((List<String> ids) -> {
                    List<String> replaced = bound(ids).keySet().stream().filter((String other) -> !other.equals(shop.id())).map(this::called).sorted().toList();
                    String what = replaced.isEmpty() ? "Puts " + shop.name() + " on " + named(ids) : shop.name() + " replaces " + names(replaced, 2);
                    ask(slot, what, ids.size(), !replaced.isEmpty(), () -> {
                        toolkit.write(viewer, (ToolkitFiles ignored) -> files().bindShop(shop.id(), ids));
                        reload();
                    });
                });
            });
        });
        button(CREATE, "minecraft:writable_book", title("New shop"), lore(List.of("&7A trade window for your NPCs"), any("name it")),
                (GuiModel.Click click) -> prompt("Shop name", "", (String name) -> create(name, selected)));
        if (advanced())
            button(RELOAD, "minecraft:repeater", title("Reload"), lore(List.of("&7Reads changed shop files"), any("reload")), (GuiModel.Click click) -> {
                viewer.host().command(Optional.of(viewer.player()), RELOAD_COMMAND);
                reload();
            });
        if (talksToo) icon(NOTE, "minecraft:bell", "&eAlso has a conversation", List.of("&7Players get both"), Optional.empty());
        back();
        help("A shop is a trade window an NPC opens. Click one to put it on the selected NPC, right-click for its offers and settings.", "21-in-game-toolkit", "shops");
    }

    /** Makes the shop in the first selected NPC's folder, puts it on the selected NPCs in the same change, and opens its details. */
    private void create(String name, List<String> selected) {
        toolkit.later(viewer, (ToolkitFiles ignored) -> {
            ShopFiles.Created made = files().create(name, selected);
            toolkit.living().refresh();
            return made;
        }, (ShopFiles.Created made) -> {
            toolkit.say(viewer.player(), ChatLine.parse(Palette.success(made.line())));
            viewer.host().command(Optional.empty(), RELOAD_COMMAND);
            reload();
            push(new ShopDetailScreen<>(toolkit, viewer, commands, made.shop(), this::reload));
        });
    }

    static String offers(int count) { return count == 0 ? "No offers yet" : count + (count == 1 ? " offer" : " offers"); }

    /** A shop by its title while its file is listed, else by its id. */
    private String called(String id) {
        return shops == null ? id : shops.stream().filter((ShopFiles.Shop shop) -> shop.id().equals(id)).map(ShopFiles.Shop::name).findFirst().orElse(id);
    }

    /** The shops any NPC or trait group opens through {@code animus:shop}. */
    private Set<String> used() {
        Set<String> used = new HashSet<>();
        for (NpcDefinition definition : toolkit.npcs().stored()) shop(definition.json()).ifPresent(used::add);
        for (GroupDocument group : toolkit.npcs().groups().groups().values()) shop(group.values()).ifPresent(used::add);
        return used;
    }

    private static Optional<String> shop(JsonObject document) {
        return ShopFiles.module(document, ShopBinding.MODULE).filter((JsonObject module) -> module.has("shop") && module.get("shop").isJsonPrimitive())
                .map((JsonObject module) -> module.get("shop").getAsString());
    }

    /** How many of the selected NPCs use each shop through {@code animus:shop}. */
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
