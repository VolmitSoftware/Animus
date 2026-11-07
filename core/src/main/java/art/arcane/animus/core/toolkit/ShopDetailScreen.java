package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopBinding;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * One shop: open it, reset its stock, and the {@code animus:shop} settings of the selected NPCs that bind it. The
 * values shown are the first such NPC's; a change goes on all of them in one transaction.
 */
final class ShopDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, OPEN = 19, STYLE = 20, OPEN_ON = 21, PROFESSION = 23, LEVEL = 24, RESET = 29, UNBIND = 31, DELETE = 33;
    static final List<String> STYLES = List.of("default", "native", "chest"), CLICKS = List.of("right", "left", "both");
    static final List<String> PROFESSIONS = List.of("minecraft:none", "minecraft:armorer", "minecraft:butcher", "minecraft:cartographer", "minecraft:cleric",
            "minecraft:farmer", "minecraft:fisherman", "minecraft:fletcher", "minecraft:leatherworker", "minecraft:librarian", "minecraft:mason",
            "minecraft:nitwit", "minecraft:shepherd", "minecraft:toolsmith", "minecraft:weaponsmith");
    private final ShopFiles.Shop shop;
    private final Runnable changed;

    ShopDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Shop shop, Runnable changed) {
        super(toolkit, viewer, commands);
        this.shop = shop; this.changed = changed;
    }

    private ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return "Shop " + shop.id(); }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Set<String> chosen = new HashSet<>(selected);
        Optional<JsonObject> binding = Optional.empty();
        boolean villagers = false;
        int bound = 0;
        for (NpcDefinition definition : toolkit.npcs().stored()) {
            if (!chosen.contains(definition.id())) continue;
            JsonObject json = definition.json();
            Optional<JsonObject> module = ShopFiles.module(json, ShopBinding.MODULE).filter((JsonObject found) -> new JsonPrimitive(shop.id()).equals(found.get("shop")));
            if (module.isEmpty()) continue;
            bound++;
            if (binding.isEmpty()) binding = module;
            villagers |= ShopFiles.VILLAGER.equals(ShopFiles.type(json));
        }
        List<String> users = files().shopUsers(shop.id());
        List<String> summary = new ArrayList<>(List.of("&7" + shop.path(), "&7Offers &f" + shop.offers(), "&7Bound to &f" + names(users, 4),
                "&7Bound in the selection &f" + bound + "&7 of &f" + selected.size(), "&7Offers are edited in the web editor"));
        shop.problem().ifPresent((String problem) -> summary.add("&c" + problem));
        icon(SUMMARY, "minecraft:emerald", title(shop.id()), summary, Optional.empty());
        button(OPEN, "minecraft:chest", title("Open now"), lore(List.of("&7Opens it for you, as a customer sees it"), any("open")), (GuiModel.Click click) -> {
            viewer.close();
            viewer.host().command(Optional.of(viewer.player()), "animus shop open " + shop.id());
        });
        String none = "&7No selected NPC binds this shop";
        String style = binding.map((JsonObject module) -> module.has("style") ? module.get("style").getAsString() : "default").orElse("default");
        setting(STYLE, "minecraft:painting", "Window", style, List.of(binding.isPresent() ? "&7native is the villager trading screen" : none, "&7default uses the shop's own style"),
                bound, "style", (GuiModel.Click click) -> cycle(click, STYLES, style), (String next) -> next.equals("default") ? Optional.empty() : Optional.of(new JsonPrimitive(next)));
        String openOn = binding.map((JsonObject module) -> module.has("openOn") ? module.get("openOn").getAsString() : "right").orElse("right");
        setting(OPEN_ON, "minecraft:lever", "Opens on", openOn, List.of(binding.isPresent() ? "&7Which click on the NPC opens the shop" : none),
                bound, "openOn", (GuiModel.Click click) -> cycle(click, CLICKS, openOn), (String next) -> Optional.of(new JsonPrimitive(next)));
        if (villagers) villager(binding.get(), bound);
        guarded(RESET, "minecraft:hopper", "Reset stock", "Clears limits and stock for everyone", 0, true, () -> viewer.host().command(Optional.of(viewer.player()), "animus shop reset " + shop.id()));
        guarded(UNBIND, "minecraft:shears", "Unbind from selection", "Removes animus:shop from " + ToolkitFiles.count(bound), bound, false,
                () -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().unbindShop(shop.id(), ids))));
        guarded(DELETE, "minecraft:lava_bucket", "Delete shop file", users.isEmpty() ? "Rollback restores it" : "Refused while bound; unbind first", 0, true,
                () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().deleteShop(shop.id()), ShopBindScreen.RELOAD_COMMAND, () -> { changed.run(); viewer.back(); }));
        back();
    }

    /** Profession and level for the villager bodies that bind this shop; other bodies ignore them. */
    private void villager(JsonObject binding, int affected) {
        JsonObject current = binding.has("villager") && binding.get("villager").isJsonObject() ? binding.getAsJsonObject("villager") : new JsonObject();
        String profession = current.has("profession") ? current.get("profession").getAsString() : "minecraft:none";
        int level = current.has("level") ? current.get("level").getAsInt() : 1;
        setting(PROFESSION, "minecraft:lectern", "Profession", profession.replace("minecraft:", ""), List.of("&7Villager bodies only"), affected, "villager",
                (GuiModel.Click click) -> cycle(click, PROFESSIONS, profession), (String next) -> Optional.of(villager(next, level)));
        setting(LEVEL, "minecraft:experience_bottle", "Level", String.valueOf(level), List.of("&71 novice to 5 master", "&7Villager bodies only"), affected, "villager",
                (GuiModel.Click click) -> String.valueOf((int) step(click, level, 1, 1, 5)), (String next) -> Optional.of(villager(profession, Integer.parseInt(next))));
    }

    private static JsonElement villager(String profession, int level) {
        JsonObject villager = new JsonObject();
        villager.addProperty("profession", profession);
        villager.addProperty("level", level);
        return villager;
    }

    /**
     * A binding setting: left moves to the next value, right to the previous, sneak as left (or ten steps for a
     * number). On more than {@link #BULK} NPCs it asks twice and only moves forward.
     */
    private void setting(int slot, String icon, String label, String current, List<String> lines, int affected, String field,
                         Function<GuiModel.Click, String> next, Function<String, Optional<JsonElement>> value) {
        if (affected > BULK) {
            String forward = next.apply(GuiModel.Click.LEFT);
            guarded(slot, icon, "Set " + label.toLowerCase(Locale.ROOT) + " to " + forward, label + " is " + current, affected, false, () -> write(field, value.apply(forward)));
            return;
        }
        button(slot, icon, title(label + ": " + current), lore(lines, hints("next", "previous", "next")), (GuiModel.Click click) -> write(field, value.apply(next.apply(click))));
    }

    private void write(String field, Optional<JsonElement> value) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().shopField(shop.id(), ids, field, value)));
    }
}
