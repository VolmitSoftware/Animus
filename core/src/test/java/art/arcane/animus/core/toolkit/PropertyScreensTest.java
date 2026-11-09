package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandActor;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The schema-driven property editor: each field kind, bulk edits, refusals and renames, through the mock host. */
class PropertyScreensTest {
    static final UUID GUARD = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    @TempDir Path root;
    DocumentStore store;
    NpcRepository npcs;
    final MockScreenHost host = new MockScreenHost();
    final Player alex = new Player("Alex");
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final List<Runnable> queue = new ArrayList<>();
    Toolkit<Player, Stack> toolkit;
    final Toolkit.Commands commands = new Toolkit.Commands((CommandActor actor, String id) -> { }, (CommandActor actor) -> { }, (CommandActor actor, String id) -> {});

    @BeforeEach void setUp() throws IOException {
        store = new DocumentStore(root);
        List<DocumentStore.Change> files = new ArrayList<>(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", ToolkitScreensTest.npc("guard", GUARD, ",\"pose\":\"sneaking\""))));
        for (int i = 0; i < 21; i++)
            files.add(new DocumentStore.Change("npcs/town/extra" + i + ".json", "missing", ToolkitScreensTest.npc("extra" + i, UUID.nameUUIDFromBytes(("extra" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "")));
        store.commit(files);
        npcs = new NpcRepository(store);
        assertTrue(npcs.reload().isEmpty());
        alex.permissions.add(PermissionNodes.ADMIN);
        alex.pose = new ScreenHost.Pose(Set.of("world"), 0, 64, 0, 65.62, 0, 0, false);
        alex.wand = true;
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        toolkit = Toolkit.install(new Toolkit.Parts<>(() -> npcs, () -> store, manager, living, queue::add, (Player player) -> Optional.empty(), Optional.empty(), () -> 0L));
    }

    @AfterEach void tearDown() throws IOException { toolkit.close(); store.close(); }

    void drain() { while (!queue.isEmpty()) queue.removeFirst().run(); }

    void edit(String... ids) { toolkit.open(alex.id, commands, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands lent) -> new PropertyScreen<>(kit, viewer, lent, List.of(ids))); }

    /** Clicks the item named exactly {@code name}, else the first whose name contains it; frames keep no slot order. */
    void click(String name, Click click) {
        int slot = alex.frame.items().entrySet().stream().filter((java.util.Map.Entry<Integer, ScreenFrame.Item> item) -> item.getValue().name().map(RichText::plain).orElse("").equals(name))
                .map(java.util.Map.Entry::getKey).findFirst().orElseGet(() -> MockScreenHost.slot(alex, name));
        manager.click(alex, alex.window, slot, click);
    }

    String title() { return RichText.plain(alex.frame.title()); }

    String lore(String name) {
        ScreenFrame.Item item = alex.frame.items().get(MockScreenHost.slot(alex, name));
        return String.join("\n", item.lore().stream().map(RichText::plain).toList());
    }

    boolean shows(String name) { return alex.frame.items().values().stream().anyMatch((ScreenFrame.Item item) -> item.name().map(RichText::plain).orElse("").contains(name)); }

    JsonObject stored(String path) throws IOException {
        String text = store.read(path);
        assertNotNull(text, path + " is missing");
        return JsonDocuments.object(text);
    }

    JsonObject guard() throws IOException { return stored("npcs/town/guard.json"); }

    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    @Test void theSectionsOpenAndEveryFieldShowsItsSchemaDescription() {
        edit("guard");
        assertEquals("Edit guard", title());
        for (String section : List.of("Identity", "Body", "Position", "Appearance", "Movement", "Attributes", "Modules", "Use where I stand")) assertTrue(shows(section), section);
        click("Body", Click.LEFT);
        assertTrue(lore("glowing").contains("An outline glow in the team colour."), lore("glowing"));
        assertTrue(lore("glowing").contains("Sneak reset to default"), lore("glowing"));
        assertFalse(shows("armorStand"), "settings of other bodies are hidden");
        click("Back", Click.LEFT);
        assertEquals("Edit guard", title());
    }

    @Test void aToggleFlipsAndASneakClickResetsIt() throws IOException {
        edit("guard");
        click("Body", Click.LEFT);
        click("glowing", Click.LEFT);
        drain();
        assertTrue(guard().get("glowing").getAsBoolean());
        assertTrue(last().startsWith("Set glowing to true on guard; tx "), last());
        assertTrue(lore("glowing").contains("Now on"));
        click("glowing", Click.SHIFT_LEFT);
        drain();
        assertFalse(guard().has("glowing"));
        assertTrue(last().startsWith("Reset glowing on guard; tx "), last());
    }

    @Test void shortOptionListsCycleAndLongOnesOpenAPicker() throws IOException {
        edit("guard");
        click("Body", Click.LEFT);
        click("pose", Click.LEFT);
        drain();
        assertEquals("sleeping", guard().get("pose").getAsString(), "left moves to the next option");
        click("pose", Click.RIGHT);
        drain();
        assertEquals("sneaking", guard().get("pose").getAsString(), "right moves back");
        click("teamColor", Click.LEFT);
        assertTrue(title().endsWith("teamColor"), title());
        click("light_purple", Click.LEFT);
        drain();
        assertEquals("light_purple", guard().get("teamColor").getAsString());
        assertTrue(shows("glowing"), "the picker goes back to the fields");
    }

    @Test void numbersStepTakeATypedValueAndReset() throws IOException {
        edit("guard");
        click("Movement", Click.LEFT);
        assertTrue(lore("speed").contains("From 1 to 5"), lore("speed"));
        click("speed", Click.LEFT);
        drain();
        assertEquals(1.1, guard().getAsJsonObject("movement").get("speed").getAsDouble(), 1e-9);
        click("speed", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("4.5"));
        drain();
        assertEquals(4.5, guard().getAsJsonObject("movement").get("speed").getAsDouble(), 1e-9);
        click("speed", Click.SHIFT_LEFT);
        MockScreenHost.answer(alex, Optional.of("9"));
        drain();
        assertEquals("9 is not a number from 1 to 5", last());
        click("speed", Click.SHIFT_RIGHT);
        drain();
        assertFalse(guard().has("movement"), "resetting the only field drops the emptied object");
    }

    @Test void textAsksInChatAndAnInvalidValueIsRefusedWhole() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        click("name", Click.LEFT);
        assertEquals("name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("&6Captain"));
        drain();
        assertEquals("&6Captain", guard().get("name").getAsString());
        String before = store.read("npcs/town/guard.json");
        click("type", Click.RIGHT);
        MockScreenHost.answer(alex, Optional.of("Not A Type"));
        drain();
        assertTrue(last().startsWith("guard: "), last());
        assertEquals(before, store.read("npcs/town/guard.json"), "nothing is written");
    }

    @Test void aNewTypeDropsWhatOnlyTheOldBodyCouldHave() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        click("type", Click.LEFT);
        drain();
        JsonObject guard = guard();
        assertEquals("minecraft:turtle", guard.get("type").getAsString(), "left moves to the next listed body");
        assertFalse(guard.has("pose"), "only player bodies take a pose other than standing");
    }

    @Test void listsAddRemoveAndMoveEntries() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        click("tags", Click.LEFT);
        click("Add an entry", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("vip"));
        drain();
        click("Add an entry", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("night"));
        drain();
        assertEquals("[\"vip\",\"night\"]", guard().get("tags").toString());
        assertTrue(last().startsWith("Added night to tags on guard; tx "), last());
        click("#2 night", Click.SHIFT_LEFT);
        drain();
        assertEquals("[\"night\",\"vip\"]", guard().get("tags").toString());
        click("#1 night", Click.RIGHT);
        drain();
        assertEquals("[\"vip\"]", guard().get("tags").toString());
    }

    @Test void aModuleTurnsOnAndOffKeepingItsSettings() throws IOException {
        edit("guard");
        click("Modules", Click.LEFT);
        click("animus:look-close: off", Click.LEFT);
        drain();
        assertTrue(guard().getAsJsonObject("modules").getAsJsonObject("animus:look-close").get("enabled").getAsBoolean());
        click("Edit animus:look-close", Click.LEFT);
        click("range", Click.LEFT);
        drain();
        click("Back", Click.LEFT);
        click("animus:look-close: on", Click.LEFT);
        drain();
        JsonObject look = guard().getAsJsonObject("modules").getAsJsonObject("animus:look-close");
        assertFalse(look.get("enabled").getAsBoolean());
        assertTrue(look.has("range"), "turning off keeps the settings");
        click("animus:shop: off", Click.LEFT);
        assertTrue(title().endsWith("animus:shop"), "a module without a switch opens its fields: " + title());
    }

    @Test void aBulkChangeAboveTwentyAsksTwiceAndIsOneTransaction() throws IOException {
        List<String> ids = new ArrayList<>(List.of("guard"));
        for (int i = 0; i < 21; i++) ids.add("extra" + i);
        edit(ids.toArray(String[]::new));
        assertTrue(lore("Edit guard").contains("Changes apply to all 22 NPCs"), lore("Edit guard"));
        click("Body", Click.LEFT);
        click("glowing", Click.LEFT);
        drain();
        assertFalse(guard().has("glowing"), "the first click only arms it");
        assertTrue(shows("Click again to change 22 NPCs"));
        click("Click again", Click.LEFT);
        drain();
        for (String id : ids) assertTrue(stored("npcs/town/" + id + ".json").get("glowing").getAsBoolean(), id);
        assertTrue(last().startsWith("Set glowing to true on 22 NPCs; tx "), last());
        assertEquals(1, alex.messages.stream().filter((String line) -> line.contains("; tx ")).count());
        click("Back", Click.LEFT);
        click("Position", Click.LEFT);
        click("x", Click.LEFT);
        drain();
        assertEquals(1, guard().getAsJsonObject("position").get("x").getAsDouble(), 1e-9, "position goes to the first NPC at once");
        assertEquals(0, stored("npcs/town/extra0.json").getAsJsonObject("position").get("x").getAsDouble(), 1e-9);
    }

    @Test void aNewIdRenamesTheFileAndTheScreenFollows() throws IOException {
        edit("guard");
        click("Identity", Click.LEFT);
        click("id", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("watcher"));
        drain();
        assertNull(store.read("npcs/town/guard.json"));
        assertEquals(GUARD.toString(), stored("npcs/town/watcher.json").get("uuid").getAsString());
        assertTrue(last().startsWith("Renamed guard to watcher; tx "), last());
        click("Back", Click.LEFT);
        assertEquals("Edit watcher", title());
    }

    @Test void attributesListWhatTheBodyHasAndStepWithinBounds() throws IOException {
        edit("guard");
        click("Attributes", Click.LEFT);
        assertFalse(shows("follow_range"), "player bodies have no follow range");
        assertTrue(lore("scale").contains("Now vanilla default 1"), lore("scale"));
        click("scale", Click.LEFT);
        drain();
        assertEquals(1.1, guard().getAsJsonObject("attributes").get("scale").getAsDouble(), 1e-9);
        assertTrue(alex.messages.getLast().contains("Set attributes.scale to 1.1 on guard"), alex.messages.getLast());
        click("scale", Click.LEFT);
        drain();
        assertTrue(alex.messages.getLast().contains("Set attributes.scale to 1.2 on guard"), alex.messages.getLast());
        click("scale", Click.SHIFT_RIGHT);
        drain();
        assertFalse(guard().has("attributes"));
        assertTrue(alex.messages.getLast().contains("Reset attributes.scale on guard"), "the reset names the attribute: " + alex.messages.getLast());
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
        for (String path : List.of("name", "tags[]", "position.x", "armorStand.head[]", "poses.*.yaw", "hologram.lines[]", "movement.stuck.afterTicks",
                "attributes.minecraft:scale", "modules.animus:combat.enabled", "modules.animus:health.death.drops[].item.id", "modules.animus:combat.melee.weaponDamage.*"))
            assertTrue(covered.contains(path), path);
        assertFalse(covered.contains("equipment.main_hand.id"), "items belong to the Equipment screen");
    }
}
