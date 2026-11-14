package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Equipment: every click acts on the whole selection, an empty hand clears a slot after asking, and the action row copies or clears. */
class EquipmentScreenTest extends ToolkitHarness {
    static final UUID HORSE = UUID.fromString("42345678-1234-1234-1234-123456789abc");
    static final Map<Integer, String> SIMPLE = Map.ofEntries(Map.entry(2, "Head: empty"), Map.entry(11, "Chest: empty"), Map.entry(20, "Legs: empty"), Map.entry(29, "Feet: empty"),
            Map.entry(15, "Main hand: empty"), Map.entry(16, "Off hand: empty"), Map.entry(24, "Body armor"), Map.entry(25, "Saddle"), Map.entry(36, "Copy my outfit"),
            Map.entry(44, "Clear all"), Map.entry(45, "Back"), Map.entry(53, "Help"));

    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, ""),
                new DocumentStore.Change("npcs/town/horse.json", "missing", npc("horse", HORSE, "").replace(ToolkitFiles.PLAYER, "minecraft:horse"))));
        for (int i = 0; i < 25; i++) files.add(npcFile("m" + i, UUID.nameUUIDFromBytes(("m" + i).getBytes()), ""));
        start(files, false);
        for (NpcDefinition definition : npcs.stored()) active.put(definition.uuid(), 3.0);
    }

    void select(String... chosen) {
        toolkit.selection().clear(alex.id);
        for (String id : chosen) toolkit.selection().toggle(alex.id, id);
        toolkit.open(alex.id, EquipmentScreen::new);
    }

    JsonObject equipment(String id) { return npcs.find(id).orElseThrow().json().getAsJsonObject("equipment"); }


    /** Alex holds or wears the item; anything in the main hand is not the wand. */
    void hold(EquipmentSlot slot, String item) {
        alex.worn.put(slot, new NpcItem(item, 1, Optional.empty(), Optional.empty()));
        if (slot == EquipmentSlot.MAIN_HAND) alex.wand = false;
    }

    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    @Test void theWandIsRefusedAndSaysWhatToHold() {
        select("guard");
        assertTrue(alex.wand, "the harness hands Alex the wand");
        alex.worn.put(EquipmentSlot.MAIN_HAND, Toolkit.WAND_ITEM);
        click("Main hand", Click.LEFT);
        assertEquals("Hold the item it should have", last());
        assertFalse(npcs.find("guard").orElseThrow().json().has("equipment"), "the wand never goes on the NPC");
        hold(EquipmentSlot.HEAD, "minecraft:iron_helmet");
        alex.wand = true;
        click("Copy my outfit", Click.LEFT);
        assertEquals(Set.of("head"), equipment("guard").keySet(), "the wand in hand leaves the main hand as it was");
    }

    @Test void emptySlotsShowTheShapeOfWhatGoesThere() {
        select("guard");
        Map<String, String> shapes = Map.of("Head: empty", "minecraft:leather_helmet", "Chest: empty", "minecraft:leather_chestplate", "Legs: empty", "minecraft:leather_leggings",
                "Feet: empty", "minecraft:leather_boots", "Main hand: empty", "minecraft:item_frame", "Off hand: empty", "minecraft:item_frame");
        for (Map.Entry<String, String> shape : shapes.entrySet())
            assertEquals(shape.getValue(), alex.frame.items().get(MockScreenHost.slot(alex, shape.getKey())).item().id(), shape.getKey());
        hold(EquipmentSlot.MAIN_HAND, "minecraft:bread");
        click("Head", Click.LEFT);
        assertEquals("Head", name(EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD)), "a filled slot drops the empty mark");
        assertEquals("minecraft:bread", alex.frame.items().get(EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD)).item().id());
    }

    @Test void theSlotsAndTheActionRowAreNamedAndOffTheCentre() {
        select("guard");
        assertEquals("Equipment", title());
        Map<Integer, String> shown = new java.util.TreeMap<>(SIMPLE);
        shown.put(4, "guard");
        assertEquals(shown, buttons());
        assertTrue(lore("guard").contains("Body Player"), lore("guard"));
        for (int slot : List.of(13, 22, 31)) assertFalse(buttons().containsKey(slot), "slot " + slot + " stays empty");
        assertTrue(lore("Main hand").contains("Click: put your held item here"), lore("Main hand"));
        assertFalse(lore("Main hand").contains("Right-click") || lore("Main hand").contains("Sneak-click"), "one click does it all");
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        select("guard");
        shown.remove(4);
        Map<Integer, String> advanced = buttons();
        advanced.remove(4);
        assertEquals(shown, advanced, "Advanced has nothing more to show");
    }

    @Test void aHeldItemGoesOnEverySelectedNpcWithItsComponents() {
        select("guard", "baker");
        assertEquals("2 NPCs", name(EquipmentScreen.HEADER));
        assertTrue(lore(EquipmentScreen.HEADER).contains("Changes all of them"), lore(EquipmentScreen.HEADER));
        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:diamond_sword", 1, Optional.of("[enchantment_glint_override=true]"), Optional.empty()));
        alex.wand = false;
        click("Main hand", Click.LEFT);
        for (String id : List.of("guard", "baker")) {
            JsonObject item = equipment(id).getAsJsonObject("main_hand");
            assertEquals("minecraft:diamond_sword", item.get("id").getAsString());
            assertEquals("[enchantment_glint_override=true]", item.get("components").getAsString());
        }
        assertEquals(1, changes().size(), "one transaction for both");
        assertEquals("minecraft:diamond_sword", alex.frame.items().get(MockScreenHost.slot(alex, "Main hand")).item().id(), "the slot shows the item itself");
        assertTrue(lore("Main hand").contains("Now: Diamond Sword"), lore("Main hand"));
        assertTrue(lore("Main hand").contains("An empty hand clears it"));
    }

    @Test void anEmptyHandClearsASlotAfterAskingAndSaysSoOnAnEmptySlot() {
        select("guard", "baker");
        hold(EquipmentSlot.MAIN_HAND, "minecraft:iron_helmet");
        click("Head", Click.LEFT);
        alex.worn.clear();
        alex.wand = false;
        click("Feet", Click.LEFT);
        assertEquals("Hold the item in your main hand first", alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""));
        click("Head", Click.LEFT);
        assertEquals("Click again to confirm", name(EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD)));
        assertTrue(equipment("guard").has("head"), "the first click only asks");
        click(EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD), Click.LEFT);
        assertFalse(npcs.find("guard").orElseThrow().json().has("equipment"));
        assertFalse(npcs.find("baker").orElseThrow().json().has("equipment"));
    }

    @Test void copyMyOutfitMirrorsHandsAndArmorAndClearAllAsks() {
        select("guard", "baker");
        hold(EquipmentSlot.MAIN_HAND, "minecraft:bread");
        click("Main hand", Click.LEFT);
        alex.worn.clear();
        hold(EquipmentSlot.HEAD, "minecraft:iron_helmet");
        click("Copy my outfit", Click.LEFT);
        assertEquals(Set.of("head"), equipment("baker").keySet(), "your empty slots clear theirs");
        click("Clear all", Click.LEFT);
        assertEquals("Click again to confirm", name(EquipmentScreen.CLEAR));
        click(EquipmentScreen.CLEAR, Click.LEFT);
        assertFalse(npcs.find("guard").orElseThrow().json().has("equipment"));
        assertTrue(last().startsWith("Cleared equipment on 2 NPCs"), last());
    }

    @Test void clearAllOnABodyThatCannotShowEverySlotNamesNoSkip() {
        select("guard");
        hold(EquipmentSlot.MAIN_HAND, "minecraft:bread");
        click("Main hand", Click.LEFT);
        click("Clear all", Click.LEFT);
        click(EquipmentScreen.CLEAR, Click.LEFT);
        assertTrue(last().startsWith("Cleared equipment on guard"), last());
        assertFalse(last().contains("skipped"), last());
    }

    @Test void copyMyOutfitSaysThatYourEmptySlotsClearTheirsAndSlotsThatDifferReadMixed() {
        select("guard", "baker");
        assertTrue(lore("Copy my outfit").contains("Your empty slots clear their slots"), "said before it runs: " + lore("Copy my outfit"));
        toolkit.selection().clear(alex.id);
        select("guard");
        hold(EquipmentSlot.MAIN_HAND, "minecraft:bread");
        click("Main hand", Click.LEFT);
        select("guard", "baker");
        assertTrue(lore("Main hand").contains("Now: Mixed"), "not guard's bread: " + lore("Main hand"));
        assertEquals("Head: empty", name(EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD)));
    }

    @Test void aSlotNoSelectedBodyShowsIsGreyAndOthersAreSkipped() {
        select("horse");
        assertEquals("Head", name("Head"));
        assertTrue(lore("Head").contains("This body cannot show it"), lore("Head"));
        click("Head", Click.LEFT);
        assertFalse(npcs.find("horse").orElseThrow().json().has("equipment"));
        select("guard", "horse");
        hold(EquipmentSlot.MAIN_HAND, "minecraft:iron_helmet");
        click("Head", Click.LEFT);
        assertTrue(equipment("guard").has("head"));
        assertTrue(alex.messages.getLast().contains("1 skipped (body cannot show it)"), alex.messages.getLast());
    }

    @Test void changesToMoreThanTwentyAskOnce() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add("m" + i);
        select(many.toArray(String[]::new));
        hold(EquipmentSlot.MAIN_HAND, "minecraft:carved_pumpkin");
        int head = EquipmentScreen.SLOTS.get(EquipmentSlot.HEAD);
        click(head, Click.LEFT);
        assertEquals(0, changes().size(), "the first click asks for 25 NPCs");
        assertTrue(lore(head).contains("25 NPCs affected"), lore(head));
        click(head, Click.LEFT);
        assertEquals("minecraft:carved_pumpkin", equipment("m24").getAsJsonObject("head").get("id").getAsString());
        assertEquals(1, changes().size(), "one transaction for all 25");
    }

    @Test void withNothingSelectedItSaysToPickOne() {
        select();
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
        click("Nothing selected", Click.LEFT);
        assertEquals("NPCs", title());
    }
}
