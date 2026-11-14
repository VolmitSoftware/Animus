package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** All fields, the field editors, attributes and modules: names, the one click rule, the one confirm, through the mock host. */
class PropertyScreensTest extends ToolkitHarness {
    static final Set<String> DANGER = Set.of("Reset", "Remove settings", "Remove", "Delete", "Close", "Click again to confirm");

    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(npcFile("guard", GUARD, ",\"pose\":\"sneaking\"")));
        for (int i = 0; i < 21; i++) files.add(npcFile("extra" + i, UUID.nameUUIDFromBytes(("extra" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)), ""));
        start(files, true);
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        drain();
    }

    void edit(String... ids) { toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new PropertyScreen<>(kit, viewer, lent, List.of(ids))); }

    /** The slot of the item named exactly {@code name}. */
    int exact(String name) {
        for (Map.Entry<Integer, ScreenFrame.Item> item : alex.frame.items().entrySet())
            if (item.getValue().name().map(RichText::plain).orElse("").equals(name)) return item.getKey();
        throw new AssertionError("no item named exactly " + name + " in " + buttons());
    }

    /** Clicks the item named exactly {@code name}; frames keep no slot order. */
    @Override void click(String name, Click click) { click(exact(name), click); }

    @Override String lore(String name) { return lore(exact(name)); }

    JsonObject stored(String path) throws IOException {
        String text = store.read(path);
        assertNotNull(text, path + " is missing");
        return JsonDocuments.object(text);
    }

    JsonObject guard() throws IOException { return stored("npcs/town/guard.json"); }

    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    void type(String text) { MockScreenHost.answer(alex, Optional.of(text)); drain(); }

    void centreIsSafe() {
        for (int slot : List.of(13, 22, 31)) assertFalse(DANGER.contains(name(slot)), title() + " has " + name(slot) + " on " + slot);
        assertEquals("Help", name(ToolScreen.HELP), title() + " has no Help");
    }

    @Test void allFieldsSaysWhoChangesAndOffersIdAndBody() {
        edit("guard");
        assertEquals("All fields of guard", title());
        assertEquals(Map.ofEntries(Map.entry(4, "guard"), Map.entry(10, "Identity"), Map.entry(11, "Body settings"), Map.entry(12, "Position"), Map.entry(13, "Appearance"),
                Map.entry(14, "Movement"), Map.entry(15, "Attributes"), Map.entry(16, "Modules"), Map.entry(19, "Id"), Map.entry(20, "Body"),
                Map.entry(45, "Back"), Map.entry(53, "Help")), buttons());
        assertTrue(lore(PropertyScreen.HEADER).contains("Changes guard"), lore(PropertyScreen.HEADER));
        assertTrue(lore(PropertyScreen.ID).contains("Now: guard") && lore(PropertyScreen.ID).endsWith("id"), lore(PropertyScreen.ID));
        assertTrue(lore(PropertyScreen.BODY).contains("Now: Player") && lore(PropertyScreen.BODY).endsWith("type"), lore(PropertyScreen.BODY));
        centreIsSafe();
        click("Help", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("21-in-game-toolkit#all-fields"), alex.messages.getLast());

        List<String> ids = new ArrayList<>(List.of("guard"));
        for (int i = 0; i < 21; i++) ids.add("extra" + i);
        edit(ids.toArray(String[]::new));
        assertEquals("All fields of 22 NPCs", title());
        assertEquals("22 NPCs", name(PropertyScreen.HEADER));
        assertTrue(lore(PropertyScreen.HEADER).contains("Id and position: guard only"), lore(PropertyScreen.HEADER));
        assertTrue(lore("Position").contains("First only") && lore("Id").contains("First only"));

        edit();
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
        click("Nothing selected", Click.LEFT);
        assertEquals("NPCs", title());
    }

    @Test void fieldRowsReadAsWordsWithTheKeyInGreyOnlyInAdvanced() {
        edit("guard");
        click("Body settings", Click.LEFT);
        assertEquals("Body settings of guard", title());
        String glowing = lore("Glowing");
        assertTrue(glowing.contains("An outline glow in the team colour.") && glowing.contains("Now: off (default)") && glowing.contains("Click: turn on"), glowing);
        assertTrue(glowing.endsWith("\nglowing"), "the raw key is the last line: " + glowing);
        assertTrue(shows("Team color") && !shows("teamColor") && !shows("Armor stand"), buttons().toString());
        assertTrue(lore("Gravity").split("\n")[1].endsWith("..."), "descriptions stop after two lines: " + lore("Gravity"));
        assertEquals("Reset a field", name(FieldScreen.RESET_FIELD), "the pose is set, so it can be reset");
        assertFalse(buttons().containsKey(FieldScreen.RESET) || buttons().containsKey(ToolScreen.ACTIONS), "a root section has no Reset and no note: " + buttons());
        centreIsSafe();
        toolkit.mode(alex.id, ToolkitMode.SIMPLE);
        manager.viewer(alex.id).ifPresent((ScreenManager<Player, Stack>.Viewer viewer) -> viewer.refresh());
        assertFalse(lore("Glowing").endsWith("glowing"), "Simple shows no raw keys: " + lore("Glowing"));
    }

    @Test void aToggleFlipsOnAClickAndResetAFieldPutsItBack() throws IOException {
        edit("guard");
        click("Body settings", Click.LEFT);
        click("Glowing", Click.RIGHT);
        drain();
        assertTrue(guard().get("glowing").getAsBoolean(), "any click flips it");
        assertTrue(last().startsWith("Set glowing to true on guard; tx "), last());
        assertTrue(lore("Glowing").contains("Now: on"));
        click("Reset a field", Click.LEFT);
        assertEquals("Reset a field", title());
        assertEquals("Search", name(ToolScreen.SEARCH));
        assertTrue(lore("Glowing").contains("Now: true") && lore("Glowing").endsWith("glowing"), lore("Glowing"));
        click("Glowing", Click.LEFT);
        assertEquals("Body settings of guard", title());
        drain();
        assertFalse(guard().has("glowing"));
        assertTrue(last().startsWith("Reset glowing on guard; tx "), last());
    }

    @Test void shortOptionListsStepBothWaysAndLongOnesOpenASearchablePicker() throws IOException {
        edit("guard");
        click("Body settings", Click.LEFT);
        assertTrue(lore("Pose").contains("Now: Sneaking") && lore("Pose").contains("Click: next") && lore("Pose").contains("Right-click: previous"), lore("Pose"));
        click("Pose", Click.LEFT);
        drain();
        assertEquals("sleeping", guard().get("pose").getAsString());
        click("Pose", Click.RIGHT);
        drain();
        assertEquals("sneaking", guard().get("pose").getAsString());
        assertTrue(lore("Team color").contains("Click: pick from 16"), lore("Team color"));
        click("Team color", Click.LEFT);
        assertTrue(title().startsWith("Body settings of guard / Team"), title());
        assertEquals("Search", name(ToolScreen.SEARCH));
        assertTrue(lore("Light purple").endsWith("light_purple"), lore("Light purple"));
        centreIsSafe();
        click("Light purple", Click.LEFT);
        drain();
        assertEquals("light_purple", guard().get("teamColor").getAsString());
        assertEquals("Body settings of guard", title(), "the picker goes back to the fields");
    }

    @Test void numbersStepTypeAndABlankValueResets() throws IOException {
        edit("guard");
        click("Movement", Click.LEFT);
        String speed = lore("Speed");
        assertTrue(speed.contains("from 1 to 5") && speed.contains("Click: +0.1") && speed.contains("Right-click: -0.1") && speed.contains("Sneak-click: type a value"), speed);
        click("Speed", Click.LEFT);
        drain();
        assertEquals(1.1, guard().getAsJsonObject("movement").get("speed").getAsDouble(), 1e-9);
        click("Speed", Click.RIGHT);
        drain();
        assertEquals(1, guard().getAsJsonObject("movement").get("speed").getAsDouble(), 1e-9);
        click("Speed", Click.SHIFT_LEFT);
        assertEquals("Speed", alex.prompt.label());
        type("4.5");
        assertEquals(4.5, guard().getAsJsonObject("movement").get("speed").getAsDouble(), 1e-9);
        click("Speed", Click.SHIFT_RIGHT);
        type("9");
        assertEquals("9 is not a number from 1 to 5", last());
        click("Speed", Click.SHIFT_LEFT);
        type("");
        assertFalse(guard().has("movement"), "a blank value resets the field and drops the emptied object");
    }

    @Test void tickFieldsReadAndTakeSeconds() throws IOException {
        edit("guard");
        click("Movement", Click.LEFT);
        click("Stuck", Click.LEFT);
        assertEquals("Movement of guard / Stuck", title());
        String after = lore("After");
        assertTrue(after.contains("5 seconds by default") && after.contains("Now: 5 seconds (default)") && after.contains("Click: +5 seconds"), after);
        assertFalse(after.replace("afterTicks", "").toLowerCase(java.util.Locale.ROOT).contains("tick"), after);
        click("After", Click.LEFT);
        drain();
        assertEquals(200, guard().getAsJsonObject("movement").getAsJsonObject("stuck").get("afterTicks").getAsInt());
        assertTrue(last().startsWith("Set after to 10 seconds on guard"), last());
        click("After", Click.SHIFT_LEFT);
        assertEquals("After (seconds)", alex.prompt.label());
        assertEquals("10", alex.prompt.initial());
        type("2");
        assertEquals(40, guard().getAsJsonObject("movement").getAsJsonObject("stuck").get("afterTicks").getAsInt());
        assertTrue(lore("After").contains("Now: 2 seconds"), lore("After"));
    }

    @Test void textAsksForAValueAndABlankOneResets() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        assertFalse(buttons().containsValue("Id") || buttons().containsValue("Body"), "id and body are not typed into a row: " + buttons());
        click("Name", Click.LEFT);
        assertEquals("Name", alex.prompt.label());
        type("&6Captain");
        assertEquals("&6Captain", guard().get("name").getAsString());
        click("Back", Click.LEFT);
        click("Appearance", Click.LEFT);
        click("Disguise", Click.RIGHT);
        type("zombie");
        assertEquals("zombie", guard().get("disguise").getAsString());
        click("Disguise", Click.LEFT);
        assertEquals("zombie", alex.prompt.initial());
        type("");
        assertFalse(guard().has("disguise"), "a blank value resets it");
    }

    @Test void idAsksForANewIdAndChecksItBeforeWriting() throws IOException {
        edit("guard");
        click("Id", Click.LEFT);
        assertEquals("New id", alex.prompt.label());
        assertEquals("guard", alex.prompt.initial());
        MockScreenHost.answer(alex, Optional.of("Bad Id"));
        assertTrue(queue.isEmpty(), "refused before any file work");
        assertEquals("Bad Id is not 1-64 lowercase letters, digits, _ or -", last());
        click("Id", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("extra0"));
        assertEquals("An NPC named extra0 exists", last());
        click("Id", Click.LEFT);
        type("watcher");
        assertNull(store.read("npcs/town/guard.json"));
        assertEquals(GUARD.toString(), stored("npcs/town/watcher.json").get("uuid").getAsString());
        assertTrue(last().startsWith("Renamed guard to watcher; tx "), last());
        assertEquals("All fields of guard", title(), "the title names the NPC, whose name did not change");
        assertTrue(lore(PropertyScreen.HEADER).contains("watcher"), "the screen follows the NPC: " + lore(PropertyScreen.HEADER));
    }

    @Test void bodyOpensTheBodyScreenTheNpcScreenUses() throws IOException {
        toolkit.selectOnly(alex.id, "guard");
        edit("guard");
        click("Body", Click.LEFT);
        assertEquals("Body", title());
        assertEquals("Player", name(BodyScreen.SLOTS[0]));
        assertTrue(lore("Player").contains("Now: this body"), lore("Player"));
        assertEquals("Type an id", name(BodyScreen.TYPED), "Advanced types any body id");
        centreIsSafe();
        click("Search", Click.LEFT);
        type("polar");
        click("Polar bear", Click.LEFT);
        click(MockScreenHost.slot(alex, "Click again"), Click.LEFT);
        drain();
        JsonObject guard = guard();
        assertEquals("minecraft:polar_bear", guard.get("type").getAsString());
        assertFalse(guard.has("pose"), "only player bodies take a pose other than standing");
        String before = store.read("npcs/town/guard.json");
        assertEquals("Body", title(), "the pick goes back to the common bodies");
        click("Type an id", Click.LEFT);
        assertEquals("Body id", alex.prompt.label());
        type("Not A Type");
        assertTrue(last().contains("is not a body id"), last());
        assertEquals(before, store.read("npcs/town/guard.json"), "nothing is written");
        click("Back", Click.LEFT);
        assertEquals("All fields of guard", title());
    }

    @Test void listEntriesEditOnAClickAndMoveOrRemoveInTheirDetails() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        click("Tags", Click.LEFT);
        assertEquals(Map.of(22, "No entries yet; Add an entry adds one", 36, "Add an entry", 45, "Back", 53, "Help"), buttons());
        click("Add an entry", Click.LEFT);
        type("vip");
        click("Add an entry", Click.LEFT);
        type("night");
        assertEquals("[\"vip\",\"night\"]", guard().get("tags").toString());
        assertTrue(last().startsWith("Added night to tags on guard; tx "), last());
        click("#1 vip", Click.LEFT);
        type("gate");
        assertEquals("[\"gate\",\"night\"]", guard().get("tags").toString());
        click("#2 night", Click.RIGHT);
        assertEquals("Identity of guard / Tags #2", title());
        assertEquals(Map.of(4, "#2 night", 11, "Move up", 17, "Remove", 18, "Back", 26, "Help"), buttons());
        click("Move up", Click.LEFT);
        drain();
        assertEquals("[\"night\",\"gate\"]", guard().get("tags").toString());
        assertEquals("Identity of guard / Tags #1", title(), "the details follow the entry");
        assertEquals("Move down", name(ListScreen.DOWN));
        click("Remove", Click.LEFT);
        assertEquals("Click again to confirm", name(ListScreen.REMOVE));
        click(ListScreen.REMOVE, Click.LEFT);
        drain();
        assertEquals("[\"gate\"]", guard().get("tags").toString());
        assertEquals("Identity of guard / Tags", title());
        click("#1 gate", Click.RIGHT);
        click("Back", Click.LEFT);
        assertEquals("[\"gate\"]", guard().get("tags").toString(), "a right-click never removes");
        click("Reset", Click.LEFT);
        assertEquals("Click again to confirm", name(ListScreen.RESET));
        assertTrue(lore(ListScreen.RESET).contains("Removes the whole list"), lore(ListScreen.RESET));
        click(ListScreen.RESET, Click.LEFT);
        drain();
        assertFalse(guard().has("tags"));
    }

    @Test void mapsAddRemoveAndResetEntriesByName() throws IOException {
        edit("guard");
        click("Body settings", Click.LEFT);
        click("Poses", Click.LEFT);
        assertFalse(shows("Remove an entry"), "nothing to remove yet");
        click("Add an entry", Click.LEFT);
        assertEquals("New name", alex.prompt.label());
        type("wave");
        assertTrue(guard().getAsJsonObject("poses").has("wave"), guard().toString());
        click("Remove an entry", Click.LEFT);
        assertEquals("Remove an entry", title());
        click("wave", Click.LEFT);
        assertEquals("Click again to confirm", name(MapScreen.REMOVE));
        assertTrue(guard().getAsJsonObject("poses").has("wave"), "the first click only asks");
        click(MapScreen.REMOVE, Click.LEFT);
        drain();
        assertFalse(guard().has("poses"));
    }

    @Test void modulesAreOneRowEachAndOpenWithASwitch() throws IOException {
        edit("guard");
        click("Modules", Click.LEFT);
        assertEquals("Modules of guard", title());
        List<String> names = new ArrayList<>();
        Set<String> icons = new HashSet<>();
        for (int slot = 0; slot < 14; slot++) {
            names.add(name(slot));
            assertTrue(icons.add(alex.frame.items().get(slot).item().id()), name(slot) + " shares an icon");
        }
        assertEquals(List.of("Movement", "Combat", "Health", "Shop", "GUI", "Hire", "Mount", "Riding", "Look at players", "Schedule", "ModelEngine", "MythicMobs", "Home", "Mirror viewer"), names);
        assertEquals("minecraft:leather_boots", alex.frame.items().get(0).item().id());
        String look = lore("Look at players");
        assertTrue(look.contains("Now: off") && look.contains("Click: open its fields") && look.endsWith("animus:look-close"), look);
        centreIsSafe();
        click("Look at players", Click.LEFT);
        assertEquals("Look at players: off", title());
        assertEquals("Off", name(FieldScreen.SWITCH));
        assertFalse(shows("Enabled"), "the switch is the only on/off");
        assertFalse(shows("Remove settings"), "nothing to remove yet");
        click(FieldScreen.SWITCH, Click.LEFT);
        drain();
        assertTrue(guard().getAsJsonObject("modules").getAsJsonObject("animus:look-close").get("enabled").getAsBoolean());
        assertEquals("Look at players: on", title());
        click("Range", Click.LEFT);
        drain();
        click(FieldScreen.SWITCH, Click.LEFT);
        drain();
        JsonObject settings = guard().getAsJsonObject("modules").getAsJsonObject("animus:look-close");
        assertFalse(settings.get("enabled").getAsBoolean());
        assertTrue(settings.has("range"), "Off keeps the settings");
        centreIsSafe();
        click("Remove settings", Click.LEFT);
        assertEquals("Click again to confirm", name(FieldScreen.RESET));
        click(FieldScreen.RESET, Click.LEFT);
        drain();
        assertFalse(guard().has("modules"));
        click("Back", Click.LEFT);
        click("Shop", Click.LEFT);
        assertEquals("Shop: off", title());
        assertFalse(lore(FieldScreen.SWITCH).contains("Click"), "a module without a switch is on while it has settings: " + lore(FieldScreen.SWITCH));
    }

    @Test void aChangeToMoreThanTwentyNpcsAsksOnceAndIsOneTransaction() throws IOException {
        List<String> ids = new ArrayList<>(List.of("guard"));
        for (int i = 0; i < 21; i++) ids.add("extra" + i);
        edit(ids.toArray(String[]::new));
        click("Body settings", Click.LEFT);
        int glowing = exact("Glowing");
        click(glowing, Click.LEFT);
        drain();
        assertFalse(guard().has("glowing"), "the first click only asks");
        assertEquals("Click again to confirm", name(glowing));
        assertTrue(lore(glowing).contains("22 NPCs affected") && lore(glowing).contains("Any other click cancels"), lore(glowing));
        click(glowing, Click.LEFT);
        drain();
        for (String id : ids) assertTrue(stored("npcs/town/" + id + ".json").get("glowing").getAsBoolean(), id);
        assertTrue(last().startsWith("Set glowing to true on 22 NPCs; tx "), last());
        assertEquals(1, changes().size(), "one transaction for all 22");
        click("Back", Click.LEFT);
        click("Position", Click.LEFT);
        click("X", Click.LEFT);
        drain();
        assertEquals(1, guard().getAsJsonObject("position").get("x").getAsDouble(), 1e-9, "position goes to the first NPC at once");
        assertEquals(0, stored("npcs/town/extra0.json").getAsJsonObject("position").get("x").getAsDouble(), 1e-9);
    }

    @Test void attributesPutTheCommonOnesFirstAndABlankValueGoesBackToVanilla() throws IOException {
        edit("guard");
        click("Attributes", Click.LEFT);
        assertEquals("Attributes of guard", title());
        assertEquals(List.of("Max health", "Movement speed", "Scale", "Attack damage"), List.of(name(0), name(1), name(2), name(3)));
        assertEquals("Search", name(ToolScreen.SEARCH));
        assertFalse(shows("Follow range"), "player bodies have no follow range");
        String scale = lore("Scale");
        assertTrue(scale.contains("Now: 1 (vanilla default)") && scale.endsWith("scale") && scale.contains("Sneak-click: type a value"), scale);
        centreIsSafe();
        click("Scale", Click.LEFT);
        drain();
        assertEquals(1.1, guard().getAsJsonObject("attributes").get("scale").getAsDouble(), 1e-9);
        assertTrue(last().startsWith("Set scale to 1.1 on guard"), last());
        click("Scale", Click.SHIFT_LEFT);
        assertEquals("Scale", alex.prompt.label());
        type("");
        assertFalse(guard().has("attributes"));
        assertTrue(last().startsWith("Reset scale on guard"), last());
    }

    @Test void attributeIconsAreDistinct() {
        Set<String> icons = new HashSet<>();
        for (VanillaAttributes.Attribute attribute : VanillaAttributes.ALL) {
            String icon = AttributeScreen.icon(attribute.id());
            assertNotEquals(FieldScreen.NUMBER, icon, attribute.id());
            assertTrue(icons.add(icon), attribute.id() + " shares " + icon);
        }
    }

    @Test void theCoveredPathsReachNestedListsMapsAndModules() {
        Set<String> covered = PropertyScreen.covered(PropertyScreen.SCHEMA);
        for (String path : List.of("id", "type", "name", "tags[]", "position.x", "armorStand.head[]", "poses.*.yaw", "hologram.lines[]", "movement.stuck.afterTicks",
                "attributes.minecraft:scale", "modules.animus:combat.enabled", "modules.animus:health.death.drops[].item.id", "modules.animus:combat.melee.weaponDamage.*"))
            assertTrue(covered.contains(path), path);
        assertFalse(covered.contains("equipment.main_hand.id"), "items belong to the Equipment screen");
    }
}
