package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.shop.Prices;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One shop: its offers, opening it, and the {@code animus:shop} settings of the selected NPCs that use it. A setting
 * shows their shared value, or Mixed when they differ; a change sets that one key on all of them in one transaction,
 * and from Mixed says which value they all get and asks first. Profession and level come from the villager bodies only.
 * Add held item sells the item in hand as {@code /animus shop stock} does, refusing the wand, which is what a player
 * holds who opened the menu with it.
 */
final class ShopDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, ADD = 10, OFFERS = 11, OPEN = 12, STYLE = 19, OPEN_ON = 20, PROFESSION = 21, LEVEL = 22, RESET = 28, REMOVE = 33, DELETE = 35;
    static final List<String> STYLES = List.of("chest", "native", "default"), CLICKS = List.of("right", "left", "both");
    static final Map<String, String> STYLE_NAMES = Map.of("chest", "Chest window", "native", "Villager trades", "default", "Shop default"),
            CLICK_NAMES = Map.of("right", "Right-click", "left", "Left-click", "both", "Both clicks");
    static final List<String> LEVELS = List.of("1", "2", "3", "4", "5");
    static final List<String> PROFESSIONS = List.of("minecraft:none", "minecraft:armorer", "minecraft:butcher", "minecraft:cartographer", "minecraft:cleric",
            "minecraft:farmer", "minecraft:fisherman", "minecraft:fletcher", "minecraft:leatherworker", "minecraft:librarian", "minecraft:mason",
            "minecraft:nitwit", "minecraft:shepherd", "minecraft:toolsmith", "minecraft:weaponsmith");
    static final String FIRST = "&7Put it on an NPC first";
    /** Where a price is asked on a server without an economy. */
    static final String NO_ECONOMY = "No economy plugin, so players cannot pay";
    private ShopFiles.Shop shop;
    private final Runnable changed;

    ShopDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Shop shop, Runnable changed) {
        super(toolkit, viewer, commands);
        this.shop = shop; this.changed = changed;
    }

    private ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    /** Reads the shop again, so the offer count shows what changed. */
    void reload() {
        String id = shop.id();
        toolkit.later(viewer, (ToolkitFiles ignored) -> files().shop(id), (Optional<ShopFiles.Shop> found) -> {
            found.ifPresent((ShopFiles.Shop now) -> shop = now);
            changed.run();
            viewer.refresh();
        });
    }

    @Override String title() { return shop.name(); }
    @Override String node() { return PermissionNodes.SHOPS; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<JsonObject> bindings = new ArrayList<>(), villagers = new ArrayList<>();
        for (String id : toolkit.selected(viewer.id())) {
            JsonObject json = NpcScreen.stored(toolkit, id);
            Optional<JsonObject> module = ShopFiles.module(json, ShopBinding.MODULE).filter((JsonObject found) -> new JsonPrimitive(shop.id()).equals(found.get("shop")));
            if (module.isEmpty()) continue;
            bindings.add(module.get());
            if (ShopFiles.VILLAGER.equals(ShopFiles.type(json))) villagers.add(module.get());
        }
        int bound = bindings.size();
        List<String> users = files().shopUsers(shop.id());
        List<String> header = new ArrayList<>(List.of(shop.problem().map((String problem) -> "&c" + problem).orElse("&7" + ShopBindScreen.offers(shop.offers())),
                "&fNow: " + (users.isEmpty() ? "on no NPC" : "on " + npcNames(users, 3))));
        if (advanced()) header.add("&8" + shop.path());
        icon(HEADER, "minecraft:emerald", title(shop.name()), header, Optional.empty());
        stock();
        button(OFFERS, "minecraft:chest", title("Offers"), lore(List.of("&fNow: " + ShopBindScreen.offers(shop.offers())), any("see and remove them")),
                (GuiModel.Click click) -> push(new ShopOffersScreen<>(toolkit, viewer, commands, shop, this::reload)));
        Optional<String> seen = seenOn(toolkit.selected(viewer.id()));
        List<String> open = new ArrayList<>(List.of("&7Opens it as players see it"));
        seen.ifPresent((String npc) -> open.add("&fFrom: " + named(List.of(npc))));
        button(OPEN, "minecraft:ender_eye", title("Open"), lore(open, any("open it")), (GuiModel.Click click) -> {
            viewer.close();
            viewer.host().command(Optional.of(viewer.player()), "animus shop open " + shop.id() + seen.map((String npc) -> " npc=" + npc).orElse(""));
        });
        setting(STYLE, "minecraft:painting", "Window style", "Which window it opens", STYLES, STYLE_NAMES::get, values(bindings, List.of("style"), "default"), List.of("style"),
                (GuiModel.Click click, String now) -> cycle(click, STYLES, now), (String next) -> next.equals("default") ? Optional.empty() : Optional.of(new JsonPrimitive(next)));
        setting(OPEN_ON, "minecraft:lever", "Opens on", "Which click on the NPC opens it", CLICKS, CLICK_NAMES::get, values(bindings, List.of("openOn"), "right"), List.of("openOn"),
                (GuiModel.Click click, String now) -> cycle(click, CLICKS, now), (String next) -> Optional.of(new JsonPrimitive(next)));
        if (!villagers.isEmpty()) {
            setting(PROFESSION, "minecraft:lectern", "Profession", "Villager bodies only", PROFESSIONS, Words::readable,
                    values(villagers, List.of("villager", "profession"), "minecraft:none"), List.of("villager", "profession"),
                    (GuiModel.Click click, String now) -> cycle(click, PROFESSIONS, now), (String next) -> Optional.of(new JsonPrimitive(next)));
            setting(LEVEL, "minecraft:experience_bottle", "Level", "1 novice to 5 master", LEVELS, (String level) -> level,
                    values(villagers, List.of("villager", "level"), "1"), List.of("villager", "level"),
                    (GuiModel.Click click, String now) -> String.valueOf((int) step(click, Integer.parseInt(now), 1, 1, 5)), (String next) -> Optional.of(new JsonPrimitive(Integer.parseInt(next))));
        }
        confirm(RESET, "minecraft:clock", "Reset stock", "Clears limits and stock for everyone", 0, true,
                () -> viewer.host().command(Optional.of(viewer.player()), "animus shop reset " + shop.id()));
        if (bound == 0) icon(REMOVE, "minecraft:gray_dye", "&7Remove from NPC", List.of("&7Not on the selected NPCs"), Optional.empty());
        else button(REMOVE, "minecraft:shears", title("Remove from NPC"), lore(List.of("&7Takes it off the selected NPCs"), any("remove it")), (GuiModel.Click click) ->
                chosen().ifPresent((List<String> ids) -> ask(REMOVE, "Takes " + shop.name() + " off " + named(ids), ids.size(), false,
                        () -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().unbindShop(shop.id(), ids)))));
        if (!users.isEmpty()) icon(DELETE, "minecraft:gray_dye", "&7Delete", List.of("&7On " + npcNames(users, 3) + "; remove it first"), Optional.empty());
        else confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes the shop; Undo in chat brings it back", 0, true,
                () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().deleteShop(shop.id()), ShopBindScreen.RELOAD_COMMAND, () -> { changed.run(); viewer.back(); }));
        back();
        help("Hold an item and click Add held item to sell it here. Window style and Opens on change the selected NPCs that use this shop.", "10-shops", "stocking-a-shop-in-game");
    }

    /**
     * Add held item: takes the item in hand, asks for the price in the server's money, then adds it as a buy offer.
     * Without an economy it still asks, and says that players cannot pay.
     */
    private void stock() {
        Prices prices = commands.prices().get();
        List<String> lines = new ArrayList<>(List.of("&7Hold the item, then open /animus gui"));
        if (prices.unavailable().isPresent()) lines.add("&c" + NO_ECONOMY);
        button(ADD, "minecraft:hopper", title("Add held item"), lore(lines, any("type the price")), (GuiModel.Click click) -> {
            if (toolkit.host().holdsWand(viewer.player())) { refuse("Hold the item it should sell"); return; }
            Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
            if (held.isEmpty()) { refuse("Hold the item it should sell in your main hand"); return; }
            boolean advanced = advanced();
            Prices now = commands.prices().get();
            prompt(priceLabel(now), "", now.unavailable().isPresent() ? NO_ECONOMY : "One number; 2 reads as " + now.format(BigDecimal.TWO), (String typed) -> {
                BigDecimal price;
                try { price = new BigDecimal(typed.strip()); }
                catch (NumberFormatException wrong) { refuse("The price is one number, such as 12"); return; }
                if (price.signum() < 0) { refuse("The price is 0 or more"); return; }
                String shown = now.format(price);
                ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().stock(shop.id(), held.get(), price, shown, advanced), ShopBindScreen.RELOAD_COMMAND, this::reload);
            });
        });
    }

    /**
     * {@code Price in Coins} while the economy names its money, {@code Price ($)} when that name is only a symbol, and
     * {@code Price} without a name or when the label would not fit an anvil's title.
     */
    static String priceLabel(Prices prices) {
        return prices.currency().map((String money) -> money.chars().anyMatch(Character::isLetter) ? "Price in " + money : "Price (" + money + ")")
                .filter((String label) -> label.length() <= ScreenHost.Prompt.TITLE).orElse("Price");
    }

    /**
     * The NPC whose click Open copies: the first selected NPC that opens this shop, else the first NPC that does. Read
     * from the definitions NPC clicks use, so a trait group's shop counts. Empty when no NPC opens it.
     */
    private Optional<String> seenOn(List<String> selected) {
        Map<String, NpcDefinition> effective = toolkit.npcs().snapshot();
        Predicate<String> opens = (String id) -> Optional.ofNullable(effective.get(id)).flatMap(ShopDetailScreen::binding)
                .filter((ShopBinding binding) -> binding.shop().equals(shop.id())).isPresent();
        return selected.stream().filter(opens).findFirst().or(() -> effective.keySet().stream().sorted().filter(opens).findFirst());
    }

    /** An NPC's shop binding; a malformed module binds nothing, as on a click. */
    private static Optional<ShopBinding> binding(NpcDefinition definition) {
        try { return ShopBinding.of(definition); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /** Each binding's value at {@code path} as text, {@code fallback} where it is not set. */
    private static List<String> values(List<JsonObject> bindings, List<String> path, String fallback) {
        return bindings.stream().map((JsonObject module) -> CombatFiles.at(module, path).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).orElse(fallback)).toList();
    }

    /**
     * A setting of the selected NPCs that use the shop, over {@code options}. When they share a value a click moves it
     * on with {@code next} and a right-click back; when they differ it reads Mixed, and a click sets all of them to the
     * first option, a right-click to the last, asking first. Above {@link #BULK} NPCs it asks too; with none it is grey.
     */
    private void setting(int slot, String icon, String label, String what, List<String> options, Function<String, String> names, List<String> values, List<String> path,
                         BiFunction<GuiModel.Click, String, String> next, Function<String, Optional<JsonElement>> value) {
        if (values.isEmpty()) { icon(slot, "minecraft:gray_dye", "&7" + label, List.of(FIRST), Optional.empty()); return; }
        Function<String, String> shown = (String option) -> Optional.ofNullable(names.apply(option)).orElse(option);
        boolean mixed = values.stream().distinct().count() > 1;
        String lower = label.toLowerCase(Locale.ROOT);
        if (!mixed) {
            String now = values.getFirst();
            button(slot, icon, title(label), lore(List.of("&7" + what, "&fNow: " + shown.apply(now)), hints("next", "previous", "")), (GuiModel.Click click) -> {
                String chosen = next.apply(click, now);
                ask(slot, "Sets " + lower + " to " + shown.apply(chosen) + " on " + ToolkitFiles.count(values.size()), values.size(), false, () -> write(path, value.apply(chosen), shown.apply(chosen)));
            });
            return;
        }
        String first = options.getFirst(), last = options.getLast();
        button(slot, icon, title(label), lore(List.of("&7" + what, "&fNow: Mixed"), hints("set all to " + shown.apply(first), "set all to " + shown.apply(last), "")),
                (GuiModel.Click click) -> {
                    String chosen = click.right() ? last : first;
                    ask(slot, "Sets " + lower + " to " + shown.apply(chosen) + " on all " + ToolkitFiles.count(values.size()) + "; they differ now", values.size(), true,
                            () -> write(path, value.apply(chosen), shown.apply(chosen)));
                });
    }

    /** Saves one setting; the list behind re-reads, since Opens on decides whether it notes a conversation. */
    private void write(List<String> path, Optional<JsonElement> value, String shown) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().shopField(shop.id(), ids, path, value, shown), changed));
    }
}
