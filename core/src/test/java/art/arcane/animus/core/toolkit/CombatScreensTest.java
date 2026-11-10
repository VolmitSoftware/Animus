package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Combat, target rules, mounts, riding and equipment from the toolkit, each change one transaction. */
class CombatScreensTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc"), BAKER = UUID.fromString("22345678-1234-1234-1234-123456789abc"),
            OLD = UUID.fromString("32345678-1234-1234-1234-123456789abc"), HORSE = UUID.fromString("42345678-1234-1234-1234-123456789abc");
    static final String IMPORTED = ",\"migration\":{\"importId\":\"a\",\"citizensId\":1}";
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<String> ids = new ArrayList<>();
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> {}, (CommandActor actor) -> {}, (CommandActor actor, String id) -> {});

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        List<DocumentStore.Change> files = new ArrayList<>(List.of(
                new DocumentStore.Change("npcs/town/guard.json", "missing", ToolkitScreensTest.npc("guard", GUARD, "")),
                new DocumentStore.Change("npcs/town/baker.json", "missing", ToolkitScreensTest.npc("baker", BAKER, "")),
                new DocumentStore.Change("npcs/town/old.json", "missing", ToolkitScreensTest.npc("old", OLD, IMPORTED)),
                new DocumentStore.Change("npcs/town/horse.json", "missing", ToolkitScreensTest.npc("horse", HORSE, "").replace(ToolkitFiles.PLAYER, "minecraft:horse"))));
        for (int i = 0; i < 25; i++)
            files.add(new DocumentStore.Change("npcs/town/m" + i + ".json", "missing", ToolkitScreensTest.npc("m" + i, UUID.nameUUIDFromBytes(("m" + i).getBytes()), "")));
        store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty(), npcs.diagnostics().toString());
        alex.permissions.add(PermissionNodes.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty()) {
            @Override public Optional<ActiveNpc> active(UUID npc) {
                return npcs.stored().stream().filter((NpcDefinition definition) -> definition.uuid().equals(npc)).findFirst()
                        .map((NpcDefinition definition) -> new ActiveNpc(definition.id(), definition.id(), ToolkitScreensTest.at(npc, 3)));
            }
        };
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, Runnable::run, (Player player) -> Optional.empty(), Optional.empty(), System::nanoTime));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void select(String... chosen) {
        toolkit.selection().clear(alex.id);
        for (String id : chosen) toolkit.selection().toggle(alex.id, id);
    }

    void open(Toolkit.ScreenFactory<Player, Stack> factory) { toolkit.open(alex.id, commands, factory); }

    void click(String name, Click click) { click(MockScreenHost.slot(alex, name), click); }
    void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    String title() { return RichText.plain(alex.frame.title()); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    JsonObject json(String id) { return npcs.find(id).orElseThrow().json(); }

    CombatSettings combat(String id) { return CombatSettings.parse(json(id)).orElseThrow(); }

    boolean tx() { return alex.messages.stream().anyMatch((String line) -> line.contains("; tx ")); }

    void pickNpc(UUID npc) { assertTrue(toolkit.interacted(alex, npc, false, false)); }

    @Test void enableWritesLiteralTrueForNativeAndImportedNpcs() {
        select("guard", "old");
        open(CombatScreen::new);
        assertEquals("Combat", title());
        click("Enable combat", Click.LEFT);
        assertTrue(json("guard").getAsJsonObject("modules").getAsJsonObject(CombatSettings.MODULE).get("enabled").getAsJsonPrimitive().isBoolean());
        assertTrue(combat("guard").enabled());
        assertTrue(combat("old").enabled(), "an imported NPC needs nothing more than enabled");
        assertTrue(CombatSettings.active(npcs.find("old").orElseThrow()).isPresent());
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Combat on for") && line.contains("; tx ")), alex.messages.toString());
        assertTrue(lore("selected").contains("Combat on 2 of 2"), lore("selected"));

        click("Disable combat", Click.LEFT);
        assertFalse(CombatFiles.module(json("guard"), CombatSettings.MODULE).has("enabled"), "disable removes the literal true");
        assertTrue(CombatSettings.active(npcs.find("guard").orElseThrow()).isEmpty());
    }

    @Test void liveButtonsRunTheCombatCommandsForTheSelection() {
        select("guard", "baker");
        open(CombatScreen::new);
        click("Engage", Click.LEFT);
        click("Status", Click.LEFT);
        click("Status", Click.RIGHT);
        click("Guard me", Click.LEFT);
        click("Guard me", Click.RIGHT);
        assertEquals(List.of("animus combat engage guard", "animus combat engage baker", "animus combat status npc=guard", "animus combat status",
                "animus combat guard guard " + alex.id, "animus combat guard baker " + alex.id, "animus combat guard guard stop", "animus combat guard baker stop"), alex.commands);
    }

    @Test void aRulesEditWritesWhatTargetRulesParses() {
        select("guard", "baker");
        open(CombatScreen::new);
        click("Targets", Click.LEFT);
        assertEquals("Targets", title());
        click("Monsters", Click.LEFT);
        click("Players by name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("Steve"));
        click("NPCs by id", Click.SHIFT_LEFT);
        pickNpc(BAKER);
        click("Health below", Click.LEFT);
        click("Player name pattern", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("(unclosed"));
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("invalid pattern")), alex.messages.toString());
        assertFalse(tx(), "nothing is written before Save");
        assertTrue(lore("Save").contains("monsters") && lore("Save").contains("players: Steve"), lore("Save"));
        click("Save", Click.LEFT);
        for (String id : List.of("guard", "baker")) {
            CombatSettings settings = combat(id);
            assertEquals(Set.of(TargetCategory.MONSTERS), settings.targets().categories());
            assertEquals(Set.of("steve"), settings.targets().players());
            assertEquals(Set.of("baker"), settings.targets().npcs());
            assertEquals(50, settings.targets().healthBelow().orElseThrow());
            assertTrue(settings.targets().patterns().players().isEmpty(), "the refused pattern was never added");
        }
        assertEquals(1, alex.messages.stream().filter((String line) -> line.contains("; tx ")).count(), alex.messages.toString());
        String saved = alex.messages.stream().filter((String line) -> line.contains("; tx ")).findFirst().orElseThrow();
        assertTrue(saved.contains("Combat targets on 2 NPCs: monsters") && saved.contains("players: Steve"), "the reply lists the saved rules: " + saved);

        click("Players by name", Click.RIGHT);
        assertEquals("Players by name", title());
        click("Steve", Click.LEFT);
        click("Back", Click.LEFT);
        click("Save", Click.LEFT);
        assertTrue(combat("guard").targets().players().isEmpty(), "a click on an entry removes it");
    }

    @Test void sneakingOnAPatternRowListsItsEntriesInsteadOfAddingTheHeldItem() {
        select("guard");
        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:emerald", 1, Optional.empty(), Optional.empty()));
        open(CombatScreen::new);
        click("Targets", Click.LEFT);
        for (String row : List.of("Player name pattern", "NPC name pattern", "Mob name pattern")) {
            click(row, Click.SHIFT_LEFT);
            assertEquals(row, title(), row + " lists its entries");
            click("Back", Click.LEFT);
        }
        assertFalse(lore("Save").contains("emerald"), lore("Save"));
        click("Main-hand item", Click.SHIFT_LEFT);
        assertTrue(lore("Save").contains("emerald"), "item rows still add the held item: " + lore("Save"));
    }

    @Test void sectionFieldsStepCycleAndSaveInOneTransaction() {
        select("guard");
        open(CombatScreen::new);
        click("Melee", Click.LEFT);
        assertEquals("Combat: melee", title());
        assertTrue(lore("Damage").contains("2 by default."), lore("Damage"));
        click("Damage", Click.LEFT);
        click("From equipment", Click.LEFT);
        assertTrue(lore("Save").contains("2 changes"), lore("Save"));
        click("Back", Click.LEFT);
        assertFalse(tx(), "Back discards what was not saved");
        click("Melee", Click.LEFT);
        click("Damage", Click.LEFT);
        click("Damage", Click.LEFT);
        click("From equipment", Click.LEFT);
        click("Save", Click.LEFT);
        assertEquals(4, combat("guard").melee().damage());
        assertTrue(combat("guard").melee().fromEquipment());
        assertFalse(combat("guard").enabled(), "editing a profile does not turn combat on");
    }

    @Test void sectionsFillFromYouAndYourHand() {
        select("guard");
        open(CombatScreen::new);
        click(CombatScreen.SECTIONS + CombatFields.SECTIONS.indexOf("guard"), Click.LEFT);
        assertEquals("Combat: guard", title());
        click("Player", Click.LEFT);
        click("NPC:", Click.LEFT);
        pickNpc(BAKER);
        assertTrue(lore("Player").contains("Changed"), "guarding an NPC clears the guarded player");
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
        click("Back", Click.LEFT);
        click("Ranged", Click.LEFT);
        click("Effect", Click.LEFT);
        assertEquals("Combat: ranged.effect", title());
        click("Id", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("minecraft:slowness"));
        click("Back", Click.LEFT);
        for (int i = 0; i < 9; i++) click("Weapon", Click.LEFT);
        assertTrue(RichText.plain(alex.frame.items().get(MockScreenHost.slot(alex, "Weapon")).name().orElseThrow()).endsWith("potion"));
        click("Save", Click.LEFT);
        assertEquals("minecraft:slowness", combat("guard").ranged().effect().orElseThrow().id(), "the nested screen shares the draft");
    }

    @Test void anInvalidCombinationIsRefusedWholeOnSave() {
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
        assertFalse(json("guard").has("modules"), "dismount removes the module, and modules with it when nothing is left");
    }

    @Test void pairTwoNpcsPicksTheRiderThenTheVehicle() {
        select("m0");
        open(MountScreen::new);
        click("Pair two NPCs", Click.LEFT);
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

    @Test void equipmentFromTheHandWritesTheItemWithItsComponents() {
        select("guard");
        open(EquipmentScreen::new);
        click("Main hand", Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("Hold the item")), alex.messages.toString());
        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:diamond_sword", 1, Optional.of("[enchantment_glint_override=true]"), Optional.empty()));
        click("Main hand", Click.LEFT);
        JsonObject item = json("guard").getAsJsonObject("equipment").getAsJsonObject("main_hand");
        assertEquals("minecraft:diamond_sword", item.get("id").getAsString());
        assertEquals("[enchantment_glint_override=true]", item.get("components").getAsString());
        assertEquals("minecraft:diamond_sword", alex.frame.items().get(MockScreenHost.slot(alex, "Main hand")).item().id(), "the slot shows the item itself");
        assertTrue(alex.frame.items().get(MockScreenHost.slot(alex, "Main hand")).item().components().orElseThrow().startsWith("[enchantment_glint_override=true,"), "the icon keeps its components");
        assertTrue(lore("Main hand").startsWith("Diamond Sword"), lore("Main hand"));

        alex.worn.put(EquipmentSlot.HEAD, new NpcItem("minecraft:iron_helmet", 1, Optional.empty(), Optional.empty()));
        alex.worn.remove(EquipmentSlot.MAIN_HAND);
        click("Feet", Click.SHIFT_LEFT);
        JsonObject equipment = json("guard").getAsJsonObject("equipment");
        assertEquals(Set.of("head"), equipment.keySet(), "sneak-left mirrors your hands and armor, empty slots included");

        click("Head", Click.RIGHT);
        assertFalse(json("guard").has("equipment"));
        assertTrue(lore("Saddle").replace("\n", " ").contains("cannot show saddle"), lore("Saddle"));
        click("Saddle", Click.LEFT);
        assertTrue(alex.messages.stream().anyMatch((String line) -> line.contains("cannot show saddle")), alex.messages.toString());
    }

    @Test void bulkChangesAboveTwentyAskTwice() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add("m" + i);
        select(many.toArray(String[]::new));
        open(CombatScreen::new);
        int enable = MockScreenHost.slot(alex, "Enable combat");
        click(enable, Click.LEFT);
        assertFalse(tx(), "the first click arms a change to 25 NPCs");
        assertTrue(lore("Click again").contains("25 NPCs affected"), lore("Click again"));
        click(enable, Click.LEFT);
        assertTrue(combat("m24").enabled());

        alex.messages.clear();
        open(EquipmentScreen::new);
        click("Bulk", Click.LEFT);
        alex.worn.put(EquipmentSlot.MAIN_HAND, new NpcItem("minecraft:carved_pumpkin", 1, Optional.empty(), Optional.empty()));
        int head = MockScreenHost.slot(alex, "Head");
        click(head, Click.LEFT);
        assertFalse(tx(), "the first click arms a change to 25 NPCs");
        click(head, Click.LEFT);
        assertTrue(json("m24").has("equipment"), alex.messages.toString());
        assertEquals("minecraft:carved_pumpkin", json("m24").getAsJsonObject("equipment").getAsJsonObject("head").get("id").getAsString());
        assertEquals(1, alex.messages.stream().filter((String line) -> line.contains("; tx ")).count(), "one transaction for all 25");
    }
}
