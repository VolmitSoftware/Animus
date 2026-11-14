package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
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

/** Combat, target rules, mounts, riding and equipment from the menu, each change one transaction. */
class CombatScreensTest extends ToolkitHarness {
    static final UUID OLD = UUID.fromString("32345678-1234-1234-1234-123456789abc"), HORSE = UUID.fromString("42345678-1234-1234-1234-123456789abc"),
            RAIDER = UUID.fromString("52345678-1234-1234-1234-123456789abc"), ESCORT = UUID.fromString("62345678-1234-1234-1234-123456789abc");
    static final String IMPORTED = ",\"migration\":{\"importId\":\"a\",\"citizensId\":1}";
    /** Target rules no Attacks choice describes. */
    static final String CUSTOM = ",\"modules\":{\"animus:combat\":{\"targets\":{\"categories\":[\"monsters\"],\"players\":[\"Steve\"],"
            + "\"entityTypes\":[\"minecraft:pillager\"],\"healthBelow\":50,\"squads\":[\"north\"]}}}";
    static final String GUARDS_BAKER = ",\"modules\":{\"animus:combat\":{\"guard\":{\"npc\":\"" + BAKER + "\"}}}";
    @BeforeEach void setUp() throws IOException {
        List<DocumentStore.Change> files = new ArrayList<>(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, ""), npcFile("old", OLD, IMPORTED),
                new DocumentStore.Change("npcs/town/horse.json", "missing", npc("horse", HORSE, "").replace(ToolkitFiles.PLAYER, "minecraft:horse")),
                npcFile("raider", RAIDER, CUSTOM), npcFile("escort", ESCORT, GUARDS_BAKER)));
        for (int i = 0; i < 25; i++) files.add(npcFile("m" + i, UUID.nameUUIDFromBytes(("m" + i).getBytes()), ""));
        start(files, false);
        for (NpcDefinition definition : npcs.stored()) active.put(definition.uuid(), 3.0);
    }

    void select(String... chosen) {
        toolkit.selection().clear(alex.id);
        for (String id : chosen) toolkit.selection().toggle(alex.id, id);
    }

    void open(Toolkit.ScreenFactory<Player, Stack> factory) { toolkit.open(alex.id, factory); }

    void advanced() { toolkit.mode(alex.id, ToolkitMode.ADVANCED); }

    JsonObject json(String id) { return npcs.find(id).orElseThrow().json(); }

    CombatSettings combat(String id) { return CombatSettings.parse(json(id)).orElseThrow(); }

    JsonObject module(String id) { return CombatFiles.module(json(id), CombatSettings.MODULE); }

    boolean tx() { return !changes().isEmpty(); }

    long transactions() { return changes().size(); }

    void pickNpc(UUID npc) {
        now += Toolkit.REPEAT_NANOS;
        assertTrue(toolkit.interacted(alex, npc, false, false));
    }

    @Test void simpleShowsSixControlsEachSayingWhatItIsNow() {
        select("guard");
        open(CombatScreen::new);
        assertEquals("Combat", title());
        assertEquals(Map.of(4, "guard", 10, "Fights", 11, "Attacks", 12, "Can be hurt", 13, "Health", 14, "Guard me", 15, "Mobs can attack it", 45, "Back", 53, "Help"), buttons());
        assertTrue(lore("Fights").contains("Now: off"), lore("Fights"));
        assertTrue(lore("Attacks").contains("Now: Nobody"), lore("Attacks"));
        assertTrue(lore("Can be hurt").contains("Now: off"), lore("Can be hurt"));
        assertTrue(lore("Health").contains("Now: 20") && !lore("Health").contains("cannot be hurt"), "Can be hurt says it: " + lore("Health"));
        assertTrue(lore("Guard me").contains("Now: off"), lore("Guard me"));
        assertTrue(lore("Mobs can attack it").contains("Now: off"), lore("Mobs can attack it"));
        click("Help", Click.LEFT);
        assertTrue(alex.messages.getLast().contains("https://docs.volmitsoftware.com/animus/21-in-game-toolkit#combat"), alex.messages.getLast());
        assertEquals("Combat", title(), "help keeps the window");
    }

    @Test void advancedAddsLabelledRulesLiveAndSettingsRowsBelow() {
        advanced();
        select("guard");
        open(CombatScreen::new);
        assertEquals(Map.ofEntries(Map.entry(4, "guard"), Map.entry(10, "Fights"), Map.entry(11, "Attacks"), Map.entry(12, "Can be hurt"), Map.entry(13, "Health"), Map.entry(14, "Guard me"),
                Map.entry(15, "Mobs can attack it"), Map.entry(18, "Rules"), Map.entry(19, "Targets"), Map.entry(20, "Ignores"), Map.entry(21, "Avoids"),
                Map.entry(27, "Live"), Map.entry(28, "Status"), Map.entry(29, "Engage"), Map.entry(30, "Disengage"), Map.entry(31, "Release"), Map.entry(32, "Guard me now"), Map.entry(33, "Server status"),
                Map.entry(36, "Settings"), Map.entry(37, "Targeting"), Map.entry(38, "Avoidance"), Map.entry(39, "Squad"), Map.entry(40, "Guard"), Map.entry(41, "Pursuit"),
                Map.entry(42, "Melee"), Map.entry(43, "Ranged"), Map.entry(44, "Greetings"), Map.entry(45, "Back"), Map.entry(53, "Help")), buttons());
        assertTrue(lore("Release").contains("Back to its own rules"), lore("Release"));
        assertTrue(lore("Status").contains("Shows in chat"), lore("Status"));
        assertTrue(lore("Server status").contains("Shows in chat"), lore("Server status"));
        assertTrue(lore(CombatScreen.SECTIONS).contains("Now: defaults"), lore(CombatScreen.SECTIONS));
    }

    @Test void fightsAndMobsSaveAtOnceWithUndo() {
        select("guard", "old");
        open(CombatScreen::new);
        click("Fights", Click.LEFT);
        assertTrue(module("guard").get("enabled").getAsJsonPrimitive().isBoolean());
        assertTrue(combat("guard").enabled());
        assertTrue(combat("old").enabled(), "an imported NPC needs nothing more than enabled");
        assertTrue(CombatSettings.active(npcs.find("old").orElseThrow()).isPresent());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("2 NPCs: combat on")) && tx(), alex.messages.toString());
        assertTrue(lore("Fights").contains("Now: on"), lore("Fights"));
        assertTrue(lore("Fights").contains("Click: turn off"), lore("Fights"));

        click("Fights", Click.LEFT);
        assertFalse(module("guard").has("enabled"), "off removes the literal true");
        assertTrue(CombatSettings.active(npcs.find("guard").orElseThrow()).isEmpty());

        click("Mobs can attack it", Click.LEFT);
        assertTrue(combat("guard").targetableByMobs());
        assertTrue(lore("Mobs can attack it").contains("Now: on"), lore("Mobs can attack it"));
        click("Mobs can attack it", Click.LEFT);
        assertFalse(module("guard").has("targetableByMobs"));
        assertEquals(4, transactions(), alex.messages.toString());
    }

    @Test void attacksCyclesFourChoicesWritingTheTargetCategories() {
        select("guard");
        open(CombatScreen::new);
        assertTrue(lore("Attacks").contains("Click: switch to Monsters"), lore("Attacks"));
        assertTrue(lore("Attacks").contains("Right-click: switch to Monsters and players"), lore("Attacks"));
        click("Attacks", Click.LEFT);
        assertEquals(Set.of(TargetCategory.MONSTERS), combat("guard").targets().categories());
        assertTrue(lore("Attacks").contains("Now: Monsters"), lore("Attacks"));
        click("Attacks", Click.LEFT);
        assertEquals(Set.of(TargetCategory.PLAYERS), combat("guard").targets().categories());
        click("Attacks", Click.LEFT);
        assertEquals(Set.of(TargetCategory.PLAYERS, TargetCategory.MONSTERS), combat("guard").targets().categories());
        assertTrue(lore("Attacks").contains("Now: Monsters and players"), lore("Attacks"));
        click("Attacks", Click.LEFT);
        assertFalse(module("guard").has("targets"), "Nobody removes the rule list");
        click("Attacks", Click.RIGHT);
        assertEquals(Set.of(TargetCategory.PLAYERS, TargetCategory.MONSTERS), combat("guard").targets().categories(), "right-click steps back");
        assertEquals(5, transactions(), alex.messages.toString());
    }

    @Test void attacksKeepsCustomRulesUntilAChoiceIsConfirmed() {
        select("raider");
        open(CombatScreen::new);
        assertTrue(lore("Attacks").contains("Now: Custom"), lore("Attacks"));
        click("Attacks", Click.LEFT);
        assertEquals("Click again to confirm", name(CombatScreen.ATTACKS));
        assertTrue(lore(CombatScreen.ATTACKS).contains("Replaces the custom target rules with Monsters"), lore(CombatScreen.ATTACKS));
        click(CombatScreen.HEADER, Click.LEFT);
        assertEquals("Attacks", name(CombatScreen.ATTACKS), "another click cancels");
        assertEquals(Set.of("steve"), combat("raider").targets().players(), "the custom rules stay");
        assertFalse(tx());

        click("Attacks", Click.RIGHT);
        assertTrue(lore(CombatScreen.ATTACKS).contains("with Nobody"), lore(CombatScreen.ATTACKS));
        click(CombatScreen.ATTACKS, Click.LEFT);
        assertFalse(module("raider").has("targets"));
        assertTrue(lore("Attacks").contains("Now: Nobody"), lore("Attacks"));
    }

    @Test void healthStepsAndTakesATypedValue() {
        select("guard", "baker");
        open(CombatScreen::new);
        assertTrue(lore("Health").contains("Now: 20"), lore("Health"));
        assertTrue(lore("Health").contains("Sneak-click: type a value"), lore("Health"));
        click("Health", Click.LEFT);
        assertEquals(22, HealthSettings.maximum(json("baker")));
        click("Health", Click.RIGHT);
        click("Health", Click.RIGHT);
        assertEquals(18, HealthSettings.maximum(json("guard")));
        assertTrue(lore("Health").contains("Now: 18"), lore("Health"));

        click("Health", Click.SHIFT_LEFT);
        assertTrue(alex.messages.contains("prompt:Health"), alex.messages.toString());
        MockScreenHost.answer(alex, Optional.of("40"));
        assertEquals(40, HealthSettings.maximum(json("guard")));
        assertEquals(40, HealthSettings.maximum(json("baker")));
        assertEquals(40, json("guard").getAsJsonObject("modules").getAsJsonObject(HealthSettings.MODULE).get("maximumHealth").getAsInt());

        click("Health", Click.SHIFT_RIGHT);
        MockScreenHost.answer(alex, Optional.of("5000"));
        assertTrue(alex.messages.getLast().contains("Health is a number from 1 to 2048"), alex.messages.getLast());
        assertEquals(40, HealthSettings.maximum(json("guard")));

        click(CombatScreen.HEALTH, Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of(""));
        assertFalse(json("guard").has("modules"), "a blank value goes back to the default");
        assertEquals(20, HealthSettings.maximum(json("guard")));
    }

    @Test void canBeHurtWritesTheKillableSettingAdvancedShows() {
        select("guard", "baker");
        open(CombatScreen::new);
        click("Can be hurt", Click.LEFT);
        assertTrue(json("guard").getAsJsonObject("modules").getAsJsonObject(HealthSettings.MODULE).get("killable").getAsBoolean());
        assertTrue(json("baker").getAsJsonObject("modules").getAsJsonObject(HealthSettings.MODULE).get("killable").getAsBoolean());
        assertTrue(lore("Can be hurt").contains("Now: on"), lore("Can be hurt"));
        assertTrue(lore("Can be hurt").contains("Click: turn off"), lore("Can be hurt"));
        assertEquals(1, changes().size(), "one change for both");
        click("Can be hurt", Click.LEFT);
        assertFalse(json("guard").has("modules"), "off goes back to the default, which cannot be hurt");
        assertTrue(lore("Can be hurt").contains("Now: off"), lore("Can be hurt"));
    }

    @Test void guardMeSavesYouAsTheChargeInPlaceOfAnother() {
        select("escort");
        open(CombatScreen::new);
        assertTrue(lore("Guard me").contains("Now: guards baker"), lore("Guard me"));
        click("Guard me", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(CombatScreen.GUARD), "taking it off the NPC it guards asks first");
        assertTrue(lore(CombatScreen.GUARD).contains("Replaces who 1 NPC guard now with you"), lore(CombatScreen.GUARD));
        click(CombatScreen.GUARD, Click.LEFT);
        assertEquals(Optional.of(alex.id), combat("escort").guard().player());
        assertEquals(Optional.empty(), combat("escort").guard().npc(), "guarding you replaces the NPC it guarded");
        assertTrue(lore("Guard me").contains("Now: on"), lore("Guard me"));
        assertTrue(lore("Guard me").contains("Click: stop guarding you"), lore("Guard me"));
        click("Guard me", Click.LEFT);
        assertFalse(module("escort").has("guard"));
        assertEquals(2, transactions(), alex.messages.toString());
    }

    @Test void healthThatDiffersReadsMixedAndTypesOneValueForAllAfterAsking() throws IOException {
        new CombatFiles(npcs).health(List.of("guard"), Optional.of(200.0));
        select("baker", "guard");
        open(CombatScreen::new);
        assertTrue(lore("Health").contains("Now: Mixed, 20 to 200"), lore("Health"));
        assertTrue(lore("Health").contains("Click: type one value for all"), lore("Health"));
        click("Health", Click.LEFT);
        assertEquals(200.0, HealthSettings.maximum(json("guard")), "a click on Mixed copies nothing");
        assertEquals("Health", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("50"));
        assertEquals(ToolScreen.CONFIRM, name(CombatScreen.HEALTH), "setting one value on NPCs that differ asks first");
        assertTrue(lore(CombatScreen.HEALTH).contains("Sets health to 50 on all 2 NPCs; they differ now"), lore(CombatScreen.HEALTH));
        click(CombatScreen.HEALTH, Click.LEFT);
        assertEquals(50.0, HealthSettings.maximum(json("guard")));
        assertEquals(50.0, HealthSettings.maximum(json("baker")));
        assertTrue(lore("Health").contains("Now: 50"), lore("Health"));
    }

    @Test void guardMeOnNpcsThatGuardSomeoneElseSaysMixedAndAsks() {
        select("guard", "escort");
        open(CombatScreen::new);
        assertTrue(lore("Guard me").contains("Now: Mixed, 0 guard you, 1 guard others"), lore("Guard me"));
        click("Guard me", Click.LEFT);
        assertEquals(ToolScreen.CONFIRM, name(CombatScreen.GUARD));
        assertEquals(Optional.of(BAKER.toString()), CombatFiles.at(module("escort"), List.of("guard", "npc")).map(com.google.gson.JsonElement::getAsString), "nothing yet");
        click(CombatScreen.GUARD, Click.LEFT);
        assertEquals(Optional.of(alex.id), combat("guard").guard().player());
        assertEquals(Optional.of(alex.id), combat("escort").guard().player());
        assertEquals(1, transactions());
    }

    @Test void guardMeNowInAdvancedGuardsWithoutSaving() {
        advanced();
        select("guard", "baker");
        open(CombatScreen::new);
        assertTrue(lore("Guard me now").contains("Until released; not saved"), lore("Guard me now"));
        click("Guard me now", Click.LEFT);
        assertEquals(List.of("animus combat guard guard " + alex.id, "animus combat guard baker " + alex.id), alex.commands);
        click("Guard me now", Click.RIGHT);
        assertEquals("animus combat guard baker stop", alex.commands.getLast());
        assertFalse(tx(), "nothing is saved");
    }

    @Test void liveButtonsRunTheCombatCommandsAndStatusShowsInChat() {
        advanced();
        select("guard", "baker");
        open(CombatScreen::new);
        click("Engage", Click.LEFT);
        click("Disengage", Click.LEFT);
        click("Release", Click.LEFT);
        click("Status", Click.LEFT);
        assertNull(alex.window, "status closes the window so the reply shows");
        open(CombatScreen::new);
        click("Server status", Click.LEFT);
        assertEquals(List.of("animus combat engage guard", "animus combat engage baker", "animus combat disengage guard", "animus combat disengage baker",
                "animus combat release guard", "animus combat release baker", "animus combat status npc=guard", "animus combat status npc=baker",
                "animus combat status"), alex.commands);
        assertFalse(tx(), "live buttons change no file");
    }

    @Test void nothingSelectedSaysSoAtTheTopOfEveryCombatScreen() {
        select();
        open(CombatScreen::new);
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
        click("Nothing selected", Click.LEFT);
        assertEquals("NPCs", title());

        open((Toolkit<Player, Stack> menu, art.arcane.animus.core.gui.ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                new TargetRulesScreen<>(menu, viewer, lent, "targets"));
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
        open((Toolkit<Player, Stack> menu, art.arcane.animus.core.gui.ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                new SettingsScreen<>(menu, viewer, lent, CombatFields.combat("melee")));
        assertEquals(Map.of(4, "Nothing selected", 45, "Back", 53, "Help"), buttons());
    }

    @Test void ruleSummariesOnTheCombatScreenStayWithinThreeLines() {
        advanced();
        select("raider");
        open(CombatScreen::new);
        String targets = lore("Targets");
        assertTrue(targets.contains("Monsters") && targets.contains("+3 more"), targets);
        assertFalse(targets.contains("Squads"), targets);
        assertTrue(lore("Ignores").contains("Now: none"), lore("Ignores"));
    }

    @Test void aRulesEditWritesWhatTargetRulesParses() {
        advanced();
        select("guard", "baker");
        open(CombatScreen::new);
        click("Targets", Click.LEFT);
        assertEquals("Targets", title());
        assertEquals("Saved", name(ToolScreen.SAVE), "Save shows before any change");
        click("Monsters", Click.LEFT);
        click("Players by name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Steve"));
        click("NPCs by id", Click.RIGHT);
        assertEquals("NPCs by id", title());
        click("Pick an NPC", Click.LEFT);
        pickNpc(BAKER);
        assertEquals("NPCs by id", title(), "the pick comes back to the entries");
        assertTrue(shows("baker"));
        assertTrue(lore("Save").contains("3 changes for 2 NPCs"), "the entries screen saves the same draft: " + lore("Save"));
        click("Back", Click.LEFT);
        click("Health below", Click.LEFT);
        click("Player name pattern", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("(unclosed"));
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("invalid pattern")), alex.messages.toString());
        assertFalse(tx(), "nothing is written before Save");
        String rules = lore(TargetRulesScreen.INFO_SLOT);
        assertTrue(rules.contains("Monsters") && rules.contains("Players by name: Steve"), rules);
        assertTrue(lore("Save").contains("4 changes for 2 NPCs"), lore("Save"));
        assertTrue(lore("Players by name").contains("Now: Steve"), lore("Players by name"));
        assertTrue(lore("Players by name").contains("players"), "the raw key shows: " + lore("Players by name"));
        click("Save", Click.LEFT);
        for (String id : List.of("guard", "baker")) {
            CombatSettings settings = combat(id);
            assertEquals(Set.of(TargetCategory.MONSTERS), settings.targets().categories());
            assertEquals(Set.of("steve"), settings.targets().players());
            assertEquals(Set.of("baker"), settings.targets().npcs());
            assertEquals(50, settings.targets().healthBelow().orElseThrow());
            assertTrue(settings.targets().patterns().players().isEmpty(), "the refused pattern was never added");
        }
        assertEquals(1, transactions(), alex.messages.toString());
        String saved = alex.lines.stream().filter((art.arcane.animus.core.text.ChatLine line) -> !line.transactions().isEmpty()).findFirst().orElseThrow().toString();
        assertTrue(saved.contains("Targets on 2 NPCs: Monsters") && saved.contains("Players by name: Steve"), "the reply lists the saved rules: " + saved);

        click("Players by name", Click.RIGHT);
        assertEquals("Players by name", title());
        click("Steve", Click.LEFT);
        click("Save", Click.LEFT);
        assertTrue(combat("guard").targets().players().isEmpty(), "a click on an entry removes it");
    }

    @Test void heldItemsAndWandPicksAreNamedItemsNotSneakClicks() {
        select("guard");
        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:emerald", 1, Optional.empty(), Optional.empty()));
        open((Toolkit<Player, Stack> menu, art.arcane.animus.core.gui.ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) ->
                new TargetRulesScreen<>(menu, viewer, lent, "targets"));
        for (String row : List.of("Player name pattern", "NPCs by id", "Main-hand item")) {
            assertTrue(lore(row).contains("Click: add one, right-click: list and remove"), lore(row));
            assertFalse(lore(row).contains("Sneak"), lore(row));
        }
        click("Main-hand item", Click.RIGHT);
        assertEquals(Map.of(22, "None yet; add one below", 36, "Add one", 37, "Add held item", 45, "Back", 52, "Saved", 53, "Help"), buttons());
        click("Add held item", Click.LEFT);
        assertTrue(shows("^minecraft:emerald$"));
        click("Back", Click.LEFT);
        assertTrue(lore(TargetRulesScreen.INFO_SLOT).contains("emerald"), lore(TargetRulesScreen.INFO_SLOT));
        click("Player name pattern", Click.RIGHT);
        assertFalse(shows("Add held item"));
        assertFalse(shows("Pick an NPC"));
    }

    @Test void sectionRowsStepTypeAndSaveInOneTransaction() {
        advanced();
        select("guard");
        open(CombatScreen::new);
        click("Melee", Click.LEFT);
        assertEquals("Melee", title());
        assertTrue(lore("Damage").contains("Now: 2 (default)"), lore("Damage"));
        assertTrue(lore("Damage").contains("melee.damage"), "the raw key shows: " + lore("Damage"));
        assertTrue(lore("Damage").contains("Sneak-click: type a value"), lore("Damage"));
        click("Damage", Click.LEFT);
        click("From equipment", Click.LEFT);
        assertTrue(lore("Damage").contains("Now: 3 (not saved)"), lore("Damage"));
        assertTrue(lore("Save").contains("2 changes"), lore("Save"));
        click("Back", Click.LEFT);
        assertEquals("Click again to leave unsaved", name(ToolScreen.BACK), "Back asks once with changes");
        click(ToolScreen.BACK, Click.LEFT);
        assertEquals("Combat", title());
        assertFalse(tx(), "leaving drops what was not saved");

        click("Melee", Click.LEFT);
        click("Damage", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("4"));
        click("Damage", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("400"));
        assertTrue(alex.messages.getLast().contains("Damage is from 0.01 to 100"), alex.messages.getLast());
        click("From equipment", Click.LEFT);
        click("Save", Click.LEFT);
        assertEquals(4, combat("guard").melee().damage());
        assertTrue(combat("guard").melee().fromEquipment());
        assertFalse(combat("guard").enabled(), "editing a section does not turn combat on");
        assertEquals("Saved", name(ToolScreen.SAVE));
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Melee: 2 changes on guard")) && tx(), alex.messages.toString());
    }

    @Test void ticksReadAndTypeAsSeconds() {
        advanced();
        select("guard");
        open(CombatScreen::new);
        click("Targeting", Click.LEFT);
        assertEquals("Targeting", title());
        assertTrue(buttons().values().stream().noneMatch((String name) -> name.toLowerCase(java.util.Locale.ROOT).contains("tick")), buttons().toString());
        String scan = lore("Scan interval");
        assertTrue(scan.contains("Now: 0.5 s (default)") && scan.contains("targeting.scanIntervalTicks") && !scan.contains("10 by default"), scan);
        click("Scan interval", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("2"));
        assertTrue(lore("Scan interval").contains("Now: 2 s (not saved)"), lore("Scan interval"));
        click("Save", Click.LEFT);
        assertEquals(40, combat("guard").targeting().scanIntervalTicks());
    }

    @Test void sectionsFillFromYouYourHandAndTheWand() {
        advanced();
        select("guard");
        open(CombatScreen::new);
        click(CombatScreen.SECTIONS + CombatFields.SECTIONS.indexOf("guard"), Click.LEFT);
        assertEquals("Guard", title());
        click("Player", Click.LEFT);
        click("NPC", Click.LEFT);
        pickNpc(BAKER);
        assertTrue(lore("Player").contains("Now: none (not saved)"), "guarding an NPC clears the guarded player: " + lore("Player"));
        click("Save", Click.LEFT);
        assertEquals(Optional.of(BAKER), combat("guard").guard().npc());
        assertEquals(Optional.empty(), combat("guard").guard().player());
        click("Back", Click.LEFT);

        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:emerald", 3, Optional.empty(), Optional.empty()));
        click("Melee", Click.LEFT);
        click("Weapon damage", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("7.5"));
        click("Save", Click.LEFT);
        assertEquals(7.5, combat("guard").melee().weaponDamage().get("minecraft:emerald"));
        click("Weapon damage", Click.RIGHT);
        assertEquals("Weapon damage", title());
        click("Emerald", Click.LEFT);
        click("Save", Click.LEFT);
        assertTrue(combat("guard").melee().weaponDamage().isEmpty(), "a click on an entry removes it");
        click("Back", Click.LEFT);
        click("Back", Click.LEFT);

        click("Ranged", Click.LEFT);
        String weapon = lore("Weapon");
        assertTrue(weapon.contains("Click: next") && weapon.contains("Right-click: previous") && !weapon.contains("Sneak"), weapon);
        click("Effect", Click.LEFT);
        assertEquals("Ranged effect", title());
        click("Id", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("minecraft:slowness"));
        assertTrue(lore("Duration").contains("10 s by default"), lore("Duration"));
        click("Back", Click.LEFT);
        for (int i = 0; i < 9; i++) click("Weapon", Click.LEFT);
        assertTrue(lore("Weapon").contains("Now: potion (not saved)"), lore("Weapon"));
        click("Save", Click.LEFT);
        assertEquals("minecraft:slowness", combat("guard").ranged().effect().orElseThrow().id(), "the nested screen shares the draft");
    }

    @Test void anInvalidCombinationIsRefusedWholeOnSave() {
        advanced();
        select("guard");
        open(CombatScreen::new);
        click("Ranged", Click.LEFT);
        for (int i = 0; i < 9; i++) click("Weapon", Click.LEFT);
        click("Save", Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("required for a potion")), alex.messages.toString());
        assertFalse(json("guard").has("modules"));
    }

    @Test void mountPickWritesTheModuleAndRefusesACycle() {
        select("guard");
        open(MountScreen::new);
        click("Ride another NPC", Click.LEFT);
        pickNpc(BAKER);
        assertNull(alex.window, "the window stays closed so the pick's reply shows");
        JsonObject mount = json("guard").getAsJsonObject("modules").getAsJsonObject("animus:mount");
        assertTrue(mount.get("enabled").getAsBoolean());
        assertEquals("baker", mount.get("vehicle").getAsString());
        open(MountScreen::new);
        assertTrue(lore("guard").contains("Rides baker"), lore("guard"));

        select("baker");
        open(MountScreen::new);
        assertTrue(lore("baker").contains("Carries guard"), lore("baker"));
        click("Ride another NPC", Click.LEFT);
        pickNpc(GUARD);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("cycle")), alex.messages.toString());
        assertFalse(json("baker").has("modules"));
        open(MountScreen::new);
        click("Ride another NPC", Click.LEFT);
        pickNpc(BAKER);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("cannot ride itself")), alex.messages.toString());

        select("guard");
        open(MountScreen::new);
        click("Dismount", Click.LEFT);
        click("Click again to confirm", Click.LEFT);
        assertFalse(json("guard").has("modules"), "dismount removes the module, and modules with it when nothing is left");
    }

    @Test void pairTwoNpcsPicksTheRiderThenTheVehicle() {
        select("m0");
        open(MountScreen::new);
        click("Pick a rider", Click.LEFT);
        pickNpc(GUARD);
        pickNpc(HORSE);
        assertEquals("horse", json("guard").getAsJsonObject("modules").getAsJsonObject("animus:mount").get("vehicle").getAsString());
    }

    @Test void ridingSettingsSaveForTheSelection() {
        select("horse");
        open(MountScreen::new);
        click("Riding", Click.LEFT);
        click("Enabled", Click.LEFT);
        click("Speed", Click.LEFT);
        click("Permission", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("town.ride"));
        click("Save", Click.LEFT);
        RideSettings ride = RideSettings.parse(json("horse")).orElseThrow();
        assertEquals(4.5, ride.speed());
        assertEquals("town.ride", ride.permission());
    }

    @Test void bulkChangesAboveTwentyAskTwice() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add("m" + i);
        select(many.toArray(String[]::new));
        open(CombatScreen::new);
        int enable = MockScreenHost.slot(alex, "Fights");
        click(enable, Click.LEFT);
        assertFalse(tx(), "the first click arms a change to 25 NPCs");
        assertTrue(lore("Click again").contains("25 NPCs affected"), lore("Click again"));
        click(enable, Click.LEFT);
        assertTrue(combat("m24").enabled());
    }
}
