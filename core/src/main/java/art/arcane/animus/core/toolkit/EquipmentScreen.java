package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The eight equipment slots of the first selected NPC, each shown as the item it holds. Left puts the item in your
 * main hand into the slot with its components, right clears it, sneak-left copies your hands and armor. Bulk mode
 * makes every click change the whole selection, asking twice above {@link #BULK} NPCs; a body that cannot show a slot
 * is skipped. The NPC shows the change as soon as the file is saved.
 */
final class EquipmentScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, FIRST_SLOT = 9, COPY = 17, MODE = 30, CLEAR = 32;
    /** The slots a player's own hands and armor fill. */
    static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private boolean bulk;
    private int armed = -1;
    private GuiModel.Click armedClick;

    EquipmentScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Equipment"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        back();
        if (selected.isEmpty()) {
            icon(SUMMARY, "minecraft:name_tag", title("Nothing selected"), List.of("&7Click NPCs with the wand, or use Find"), Optional.empty());
            return;
        }
        String first = selected.getFirst();
        JsonObject document = toolkit.npcs().find(first).map(NpcDefinition::json).orElse(new JsonObject());
        String type = document.has("type") ? document.get("type").getAsString() : ToolkitFiles.PLAYER;
        int affected = bulk ? selected.size() : 1;
        icon(SUMMARY, "minecraft:armor_stand", title(first), List.of("&7Body &f" + type, "&7Clicks change &f" + (bulk ? "all " + ToolkitFiles.count(selected.size()) : first)), Optional.empty());
        JsonObject equipment = document.has("equipment") && document.get("equipment").isJsonObject() ? document.getAsJsonObject("equipment") : new JsonObject();
        EquipmentSlot[] slots = EquipmentSlot.values();
        for (int index = 0; index < slots.length; index++) slot(FIRST_SLOT + index, slots[index], equipment, type, affected);
        button(COPY, HEAD, title("Copy your outfit"), lore(List.of("&7Your hands and armor onto " + (bulk ? "the selection" : first), "&7Empty slots of yours clear theirs"),
                any(confirming(COPY) ? "do it" : "copy")), (GuiModel.Click click) -> confirm(COPY, click, affected, this::copy));
        button(MODE, bulk ? "minecraft:chest" : "minecraft:ender_chest", title("Bulk: " + (bulk ? "on" : "off")),
                lore(List.of(bulk ? "&7Clicks change all " + ToolkitFiles.count(selected.size()) : "&7Clicks change only " + first), any("toggle")),
                (GuiModel.Click click) -> { bulk = !bulk; armed = -1; });
        guarded(CLEAR, "minecraft:lava_bucket", "Clear all", "Empties every slot on " + (bulk ? "the selection" : first), affected, true,
                () -> equip(empty(List.of(slots))));
    }

    private void slot(int slot, EquipmentSlot equipment, JsonObject worn, String type, int affected) {
        String label = CombatFields.label(equipment.key().replace('_', ' '));
        boolean shown = BodyRules.equipmentSlots(type).contains(equipment);
        Optional<NpcItem> item = item(worn.get(equipment.key()));
        List<String> lines = new ArrayList<>();
        lines.add(item.map((NpcItem held) -> "&f" + itemName(held)).orElse("&7Empty"));
        if (!shown) lines.add("&c" + BodyRules.slotRefusal(type, equipment));
        boolean ready = confirming(slot);
        String name = ready ? "&cClick again: " + label.toLowerCase(Locale.ROOT) + " on " + ToolkitFiles.count(affected) : label;
        NpcItem icon = item.orElse(new NpcItem(shown ? "minecraft:white_stained_glass_pane" : "minecraft:red_stained_glass_pane", 1, Optional.empty(), Optional.empty()));
        button(slot, icon, title(name), lore(lines, hints("put the item in your hand here", "clear the slot", "copy your whole outfit")), (GuiModel.Click click) -> {
            if (click == GuiModel.Click.SHIFT_LEFT) { confirm(slot, click, affected, this::copy); return; }
            if (!shown && !bulk) { toolkit.say(viewer.player(), ChatLine.error(BodyRules.slotRefusal(type, equipment))); return; }
            if (right(click) || click == GuiModel.Click.SHIFT_RIGHT) { confirm(slot, click, affected, () -> equip(empty(List.of(equipment)))); return; }
            Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
            if (held.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("Hold the item in your main hand first; right click clears the slot")); return; }
            Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
            items.put(equipment, held);
            confirm(slot, click, affected, () -> equip(items));
        });
    }

    /** Runs at once for a small change; above {@link #BULK} NPCs the same click on the same button must come twice. */
    private void confirm(int slot, GuiModel.Click click, int affected, Runnable run) {
        if (affected > BULK && !(armed == slot && armedClick == click)) { armed = slot; armedClick = click; return; }
        armed = -1;
        run.run();
    }

    private boolean confirming(int slot) { return armed == slot; }

    private void copy() {
        Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : WORN) items.put(slot, toolkit.host().worn(viewer.player(), slot));
        equip(items);
    }

    private void equip(Map<EquipmentSlot, Optional<NpcItem>> items) {
        List<String> selected = toolkit.selected(viewer.id());
        if (selected.isEmpty()) return;
        List<String> ids = bulk ? selected : List.of(selected.getFirst());
        toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).equip(ids, items));
    }

    private static Map<EquipmentSlot, Optional<NpcItem>> empty(List<EquipmentSlot> slots) {
        Map<EquipmentSlot, Optional<NpcItem>> items = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : slots) items.put(slot, Optional.empty());
        return items;
    }

    private static Optional<NpcItem> item(JsonElement value) {
        if (value == null) return Optional.empty();
        return NpcItem.parse(value, "$.equipment", new ArrayList<>());
    }
}
