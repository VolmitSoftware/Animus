package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the selected NPCs hold and wear, each slot showing their shared item, or Mixed when they differ, and an empty slot
 * the shape of what goes there. A click puts the item in your main hand into the slot on all of them, with its
 * components; with an empty hand it clears the slot, asking once more. The wand is refused, since it is what you hold
 * when you open this screen with it. A body that cannot show a slot skips it. The NPCs show the change as soon as the
 * file is saved.
 */
final class EquipmentScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, COPY = ACTIONS, CLEAR = ACTIONS + 8;
    /** Where each slot shows: armor down the left, hands and the animal slots on the right, the window's centre column left free. */
    static final Map<EquipmentSlot, Integer> SLOTS = Map.of(EquipmentSlot.HEAD, 2, EquipmentSlot.CHEST, 11, EquipmentSlot.LEGS, 20, EquipmentSlot.FEET, 29,
            EquipmentSlot.MAIN_HAND, 15, EquipmentSlot.OFF_HAND, 16, EquipmentSlot.BODY, 24, EquipmentSlot.SADDLE, 25);
    /** The slots a player's own hands and armor fill. */
    static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    /** What an empty slot shows, so each slot reads at a glance: the shape of what goes there. */
    static final Map<EquipmentSlot, String> SHAPES = Map.of(EquipmentSlot.HEAD, "minecraft:leather_helmet", EquipmentSlot.CHEST, "minecraft:leather_chestplate",
            EquipmentSlot.LEGS, "minecraft:leather_leggings", EquipmentSlot.FEET, "minecraft:leather_boots", EquipmentSlot.MAIN_HAND, "minecraft:item_frame",
            EquipmentSlot.OFF_HAND, "minecraft:item_frame", EquipmentSlot.BODY, "minecraft:leather_horse_armor", EquipmentSlot.SADDLE, "minecraft:saddle");
    static final String NOT_THE_WAND = "Hold the item it should have";

    EquipmentScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Equipment"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean forSelection() { return true; }

    @Override void draw() {
        back();
        help("Hold an item and click a slot to put it there; an empty hand clears the slot. Every selected NPC changes.", "21-in-game-toolkit", "equipment");
        List<String> ids = toolkit.selected(viewer.id());
        if (ids.isEmpty()) {
            button(HEADER, "minecraft:armor_stand", title("Nothing selected"), lore(List.of("&7Pick an NPC first"), any("pick one")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        List<String> types = ids.stream().map((String id) -> PropertyScreen.type(NpcScreen.stored(toolkit, id))).distinct().toList();
        String name = ids.size() > 1 ? ToolkitFiles.count(ids.size()) : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst());
        icon(HEADER, "minecraft:armor_stand", title(name), List.of(ids.size() > 1 ? "&7Changes all of them" : "&7Body &f" + Words.readable(types.getFirst())), Optional.empty());
        for (EquipmentSlot slot : EquipmentSlot.values()) slot(slot, ids.stream().map((String id) -> item(NpcScreen.stored(toolkit, id), slot)).distinct().toList(), types);
        head(COPY, title("Copy my outfit"), lore(List.of("&7Your empty slots clear " + (ids.size() == 1 ? "its slots" : "their slots")), any("put it on " + (ids.size() == 1 ? "it" : "them"))), viewer.host().name(viewer.player()),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> chosen) -> ask(COPY, "Puts your hands and armor on " + named(chosen) + "; your empty slots clear theirs", chosen.size(), false, this::copy)));
        confirm(CLEAR, "minecraft:lava_bucket", "Clear all", "Empties every slot of " + named(ids) + "; Undo in chat brings them back", ids.size(), true,
                () -> equip(empty(List.of(EquipmentSlot.values()))));
    }

    /** One slot: the item the selected NPCs share there, or Mixed when they differ; a click puts the held item on all of them. */
    private void slot(EquipmentSlot equipment, List<Optional<NpcItem>> worn, List<String> types) {
        Optional<NpcItem> item = worn.size() == 1 ? worn.getFirst() : Optional.empty();
        int at = SLOTS.get(equipment);
        String label = label(equipment), lower = label.toLowerCase(Locale.ROOT);
        if (types.stream().noneMatch((String type) -> BodyRules.equipmentSlots(type).contains(equipment))) {
            icon(at, "minecraft:red_stained_glass_pane", "&7" + label, List.of("&7This body cannot show it"), Optional.empty());
            return;
        }
        boolean empty = worn.stream().allMatch(Optional::isEmpty);
        List<String> lines = new ArrayList<>();
        if (!empty) {
            lines.add("&7An empty hand clears it");
            lines.add("&fNow: " + (worn.size() > 1 ? "Mixed" : item.map(ToolScreen::itemName).orElse("empty")));
        }
        NpcItem icon = item.orElse(new NpcItem(SHAPES.get(equipment), 1, Optional.empty(), Optional.empty()));
        button(at, icon, empty ? "&7" + label + ": empty" : title(label), lore(lines, any("put your held item here")), (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> {
            if (toolkit.host().holdsWand(viewer.player())) { refuse(NOT_THE_WAND); return; }
            Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
            if (held.isPresent()) {
                Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
                items.put(equipment, held);
                ask(at, "Puts " + itemName(held.get()) + " in the " + lower + " of " + named(ids), ids.size(), false, () -> equip(items));
            } else if (ids.stream().noneMatch((String id) -> holds(id, equipment))) refuse("Hold the item in your main hand first");
            else ask(at, "Clears the " + lower + " of " + named(ids), ids.size(), true, () -> equip(empty(List.of(equipment))));
        }));
    }

    /** {@code Main hand}, {@code Head}; the body slot reads {@code Body armor} so it is not mistaken for the NPC's body. */
    static String label(EquipmentSlot slot) {
        return slot == EquipmentSlot.BODY ? "Body armor" : CombatFields.label(slot.key().replace('_', ' '));
    }

    private boolean holds(String id, EquipmentSlot slot) {
        JsonElement equipment = NpcScreen.stored(toolkit, id).get("equipment");
        return equipment != null && equipment.isJsonObject() && equipment.getAsJsonObject().has(slot.key());
    }

    /** Your hands and armor on theirs; the wand in your hand is not copied, so their main hand stays as it is. */
    private void copy() {
        boolean wand = toolkit.host().holdsWand(viewer.player());
        Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : WORN) if (!wand || slot != EquipmentSlot.MAIN_HAND) items.put(slot, toolkit.host().worn(viewer.player(), slot));
        equip(items);
    }

    private void equip(Map<EquipmentSlot, Optional<NpcItem>> items) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).equip(ids, items)));
    }

    private static Map<EquipmentSlot, Optional<NpcItem>> empty(List<EquipmentSlot> slots) {
        Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : slots) items.put(slot, Optional.empty());
        return items;
    }

    /** What the stored NPC has in the slot. */
    private static Optional<NpcItem> item(JsonObject document, EquipmentSlot slot) {
        JsonElement equipment = document.get("equipment");
        JsonElement value = equipment != null && equipment.isJsonObject() ? equipment.getAsJsonObject().get(slot.key()) : null;
        if (value == null) return Optional.empty();
        return NpcItem.parse(value, "$.equipment", new ArrayList<>());
    }
}
