package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Home's New NPC: name, id, body and skin, then one undoable transaction that makes the NPC in front of the player. */
class CreateFlowTest extends ToolkitHarness {
    static final String SPARE_TEXT = JsonDocuments.write(JsonParser.parseString("{\"textureRaw\":\"e30=\",\"label\":\"Spare\"}")), SPARE = JsonDocuments.hash(SPARE_TEXT);
    /** Two rows around the centre column: nothing a double-click where the window opens could pick; Player first, a gap after it. */
    static final Map<Integer, String> BODY = Map.ofEntries(Map.entry(9, "Player"), Map.entry(11, "Villager"), Map.entry(12, "Zombie"), Map.entry(14, "Skeleton"),
            Map.entry(15, "Iron golem"), Map.entry(16, "Armor stand"), Map.entry(17, "Allay"), Map.entry(18, "Cat"), Map.entry(19, "Wolf"), Map.entry(20, "Horse"),
            Map.entry(21, "Pig"), Map.entry(23, "Cow"), Map.entry(24, "Sheep"), Map.entry(25, "Chicken"), Map.entry(36, "All bodies"), Map.entry(45, "Back"),
            Map.entry(46, "Search"), Map.entry(53, "Help"));
    final List<String> looked = new ArrayList<>();
    SkinLookup.Found answer = SkinLookup.Found.missing("unset");
    final SkinLookup lookup = (String name) -> { looked.add(name); return CompletableFuture.completedFuture(answer); };

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ""), file("skins/" + SPARE + ".json", SPARE_TEXT)), true);
        alex.pose = new ScreenHost.Pose(Set.of("world", "minecraft:overworld"), 12.3, 64, -7.8, 65.62, 90, 0, false);
    }

    void advanced() { toolkit.mode(alex.id, ToolkitMode.ADVANCED); }

    /** Home, New NPC, and the name typed. */
    void named(String name) {
        toolkit.home(alex.id);
        click("New NPC", Click.LEFT);
        assertEquals("Name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of(name));
    }

    /** The Body step opened straight from a flow whose skin lookups answer {@link #answer}. */
    void flow(String name, String id) {
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) ->
                new BodyScreen<>(kit, viewer, given, new CreateFlow(name, id, lookup)));
    }

    String last() { return alex.messages.getLast().replaceFirst("^\\[Animus\\]: ", ""); }

    /** The last {@code Made <id>} chat line. */
    String made() { return alex.messages.stream().filter((String message) -> message.contains("Made ")).reduce((String a, String b) -> b).orElseThrow().replaceFirst("^\\[Animus\\]: ", ""); }

    String tx() { return made().substring(made().indexOf("tx ") + 3, made().indexOf("tx ") + 39); }

    @Test void theIdComesFromTheName() {
        assertEquals("greeter-bob", CreateFlow.id("Greeter Bob", Set.of()));
        assertEquals("greeter-bob-2", CreateFlow.id("Greeter Bob", Set.of("greeter-bob")));
        assertEquals("greeter-bob-3", CreateFlow.id("Greeter  Bob!", Set.of("greeter-bob", "greeter-bob-2")));
        assertEquals("sir_lancelot-the-2nd", CreateFlow.id("  Sir_Lancelot the 2nd! ", Set.of()));
        assertEquals("npc", CreateFlow.id("!!!", Set.of()));
        assertEquals("npc", CreateFlow.id("__", Set.of()));
        assertEquals("npc-2", CreateFlow.id("&&", Set.of("npc")));
        String id = CreateFlow.id("a".repeat(70), Set.of("a".repeat(64)));
        assertEquals("a".repeat(62) + "-2", id);
        assertTrue(id.matches(art.arcane.animus.core.storage.NpcPaths.SEGMENT));
    }

    @Test void theCommonBodiesAreBodiesAnimusShowsPlayerFirst() {
        assertEquals(14, BodyScreen.COMMON.size());
        assertEquals(ToolkitFiles.PLAYER, BodyScreen.COMMON.getFirst());
        for (String body : BodyScreen.COMMON) assertTrue(BodyRules.TYPES.contains(body), body);
        assertEquals(BodyRules.TYPES.size(), BodyScreen.EVERY.size());
    }

    @Test void simpleNamesPicksABodyAndMakesItInFrontOfThePlayer() {
        named("Greeter Bob");
        assertEquals("Body", title());
        Map<Integer, String> shown = new java.util.TreeMap<>(BODY);
        shown.put(4, "Greeter Bob");
        assertEquals(shown, buttons());
        assertTrue(lore("Greeter Bob").contains("New NPC"));
        assertFalse(lore("Greeter Bob").contains("greeter-bob"), "Simple shows no id");
        assertTrue(lore("Villager").contains("Click: pick it"));
        click("Villager", Click.LEFT);
        drain();
        NpcDefinition made = npcs.find("greeter-bob").orElseThrow();
        assertEquals("Greeter Bob", made.name());
        assertEquals("minecraft:villager", made.json().get("type").getAsString());
        assertTrue(made.spawned());
        assertEquals(java.util.Optional.of(alex.id), made.owner(), "the maker owns it");
        JsonObject position = made.json().getAsJsonObject("position");
        assertEquals("world", position.get("world").getAsString(), "the world NPC positions already use");
        assertEquals(10.3, position.get("x").getAsDouble(), "two blocks ahead, where the player looks");
        assertEquals(-90f, position.get("yaw").getAsFloat(), "facing the player");
        assertEquals(List.of("greeter-bob"), toolkit.selected(alex.id));
        assertNull(alex.window, "the window closes so the NPC shows");
        assertEquals("Made Greeter Bob /animus tools menu greeter-bob", made(), "[Edit] opens its own screen by id; Simple names it and shows no change id");
        assertTrue(alex.lines.stream().anyMatch((art.arcane.animus.core.text.ChatLine line) -> line.visible().equals("[Animus]: Made Greeter Bob [Edit] [Undo]")),
                alex.lines.stream().map(art.arcane.animus.core.text.ChatLine::visible).toList().toString());
        assertTrue(alex.messages.contains("bar:Right-click it with the wand to edit"), alex.messages.toString());
        assertEquals(0, alex.wands, "a player holding the wand gets no other");
    }

    @Test void closingTheWindowWhileItSavesStillFinishesTheNpc() {
        alex.wand = false;
        named("Greeter Bob");
        click("Villager", Click.LEFT);
        manager.close(alex);
        assertNull(alex.window, "closed before the file lane answers");
        drain();
        NpcDefinition made = npcs.find("greeter-bob").orElseThrow();
        assertTrue(made.spawned());
        assertEquals(List.of("greeter-bob"), toolkit.selected(alex.id), "it is still selected");
        assertEquals(1, changes().size(), "[Undo] is still offered: " + alex.messages);
        assertTrue(toolkit.changes().latest(alex.id).isPresent(), "and /animus undo can revert it");
        assertTrue(alex.messages.contains("bar:Wand given; right-click it to edit"), alex.messages.toString());
        assertEquals(1, alex.wands, "a player without a wand still gets one");
    }

    @Test void aPlayerBodyPicksASkinNext() {
        named("Smith");
        click("Player", Click.LEFT);
        drain();
        assertEquals("Skin", title());
        assertEquals(Map.of(4, "Smith", 9, "Spare", 36, "My skin", 37, "Player name", 38, "Default", 45, "Back", 46, "Search", 53, "Help"), buttons(),
                "the header sits at 4, as on the Body step, and the skins start below it");
        assertTrue(lore("Spare").contains("Click: pick it"));
        assertFalse(lore("Spare").contains(SPARE.substring(0, 12)), "Simple shows no hash");
        click("Default", Click.LEFT);
        drain();
        NpcDefinition made = npcs.find("smith").orElseThrow();
        assertEquals(ToolkitFiles.PLAYER, made.json().get("type").getAsString());
        assertFalse(made.json().has("skin"));
        assertNull(alex.window);
    }

    @Test void aSavedSkinIsWorn() {
        named("Smith");
        click("Player", Click.LEFT);
        drain();
        click("Spare", Click.LEFT);
        drain();
        assertEquals(SPARE, npcs.find("smith").orElseThrow().json().get("skin").getAsString());
    }

    @Test void aLookedUpSkinIsSavedWithTheNpcAndOneUndoTakesBothBack() throws IOException {
        advanced();
        String texture = Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(StandardCharsets.UTF_8));
        answer = SkinLookup.Found.skin("Notch", new BodySkin(texture, Base64.getEncoder().encodeToString(new byte[]{1, 2})));
        flow("Notch", "notch");
        click("Player", Click.LEFT);
        drain();
        click("Player name", Click.LEFT);
        assertEquals("Player name", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("notch"));
        drain();
        assertEquals(List.of("notch"), looked);
        String hash = npcs.find("notch").orElseThrow().json().get("skin").getAsString();
        String tx = tx();
        assertTrue(made().startsWith("Made Notch (notch); tx "), "Advanced shows the id beside the name and the change id: " + made());
        assertEquals(Set.of("npcs/notch.json", "skins/" + hash + ".json"), Set.copyOf(store.paths(tx).stream().filter((String path) -> path.startsWith("npcs/") || path.startsWith("skins/")).toList()));
        store.rollback(tx);
        assertNull(store.read("npcs/notch.json"));
        assertNull(store.read("skins/" + hash + ".json"), "Undo takes the skin file back too");
    }

    @Test void mySkinWithoutASignatureIsRefusedAndMakesNothing() {
        named("Smith");
        click("Player", Click.LEFT);
        drain();
        click("My skin", Click.LEFT);
        drain();
        assertEquals("Your skin has no signature; offline-mode servers cannot copy it", last());
        assertTrue(npcs.find("smith").isEmpty());
        assertEquals("Skin", title());
    }

    @Test void aSkinLookupThatAnswersAfterStepBackMakesNothing() {
        CompletableFuture<SkinLookup.Found> later = new CompletableFuture<>();
        toolkit.open(alex.id, (Toolkit<Player, Stack> kit, ScreenManager<Player, Stack>.Viewer viewer, Toolkit.Commands given) ->
                new BodyScreen<>(kit, viewer, given, new CreateFlow("Notch", "notch", (String name) -> later)));
        click("Player", Click.LEFT);
        drain();
        click("Player name", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("notch"));
        click("Back", Click.LEFT);
        assertEquals("Body", title());
        later.complete(SkinLookup.Found.skin("Notch", new BodySkin("e30=", null)));
        drain();
        assertTrue(npcs.find("notch").isEmpty());
    }

    @Test void advancedAsksForTheIdAndCanSaveItDespawned() {
        advanced();
        named("Greeter Bob");
        assertEquals("Id", alex.prompt.label());
        assertEquals("greeter-bob", alex.prompt.initial(), "prefilled with the id made from the name");
        MockScreenHost.answer(alex, Optional.of("bob"));
        assertEquals("Body", title());
        assertTrue(lore("Greeter Bob").contains("bob"), "Advanced shows the id");
        assertEquals("Type an id", name(BodyScreen.TYPED));
        assertEquals("Spawned: on", name(BodyScreen.SPAWNED));
        click("Spawned: on", Click.LEFT);
        assertEquals("Spawned: off", name(BodyScreen.SPAWNED));
        assertTrue(lore("Cow").contains("minecraft:cow"), "Advanced shows the raw id");
        click("Cow", Click.LEFT);
        drain();
        NpcDefinition made = npcs.find("bob").orElseThrow();
        assertEquals("minecraft:cow", made.json().get("type").getAsString());
        assertFalse(made.spawned());
    }

    @Test void advancedRefusesABadOrTakenId() {
        advanced();
        named("Bob");
        MockScreenHost.answer(alex, Optional.of("Bad Id"));
        assertEquals("An id is 1 to 64 lowercase letters, digits, _ or -", last());
        assertEquals("Animus", title());
        named("Bob");
        MockScreenHost.answer(alex, Optional.of("guard"));
        assertEquals("An NPC named guard already exists", last());
    }

    @Test void advancedTypesAnyBodyId() {
        advanced();
        named("Fox");
        MockScreenHost.answer(alex, Optional.of("fox"));
        click("Type an id", Click.LEFT);
        assertEquals("Body id", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("Bad Body!"));
        assertEquals("'Bad Body!' is not a body id such as minecraft:fox", last());
        click("Type an id", Click.LEFT);
        MockScreenHost.answer(alex, Optional.of("fox"));
        drain();
        assertEquals("minecraft:fox", npcs.find("fox").orElseThrow().json().get("type").getAsString());
    }

    @Test void cancelAtAnyStepMakesNothing() {
        int before = npcs.stored().size();
        toolkit.home(alex.id);
        click("New NPC", Click.LEFT);
        MockScreenHost.answer(alex, Optional.empty());
        assertEquals("Cancelled; nothing changed", last());
        assertEquals("Animus", title());
        named("   ");
        assertEquals("A name needs at least one character", last());
        named("Bob");
        click("Back", Click.LEFT);
        assertEquals("Animus", title());
        named("Bob");
        click("Player", Click.LEFT);
        drain();
        click("Back", Click.LEFT);
        assertEquals("Body", title());
        click("Back", Click.LEFT);
        click("Close", Click.LEFT);
        drain();
        assertEquals(before, npcs.stored().size());
        assertTrue(queue.isEmpty());
    }

    @Test void aNameLikeAnotherNpcsGetsANumber() {
        named("Guard");
        click("Pig", Click.LEFT);
        drain();
        assertEquals("Guard", npcs.find("guard-2").orElseThrow().name());
        assertEquals("guard", npcs.find("guard").orElseThrow().name(), "the other NPC is untouched");
    }

    @Test void allBodiesIsPagedSearchableAndNamedForPeople() {
        named("Fox");
        click("All bodies", Click.LEFT);
        assertEquals("All bodies", title());
        assertEquals("Search", name(ToolScreen.SEARCH));
        assertEquals("Next page", name(ToolScreen.NEXT));
        assertTrue(shows("Armor stand"));
        assertFalse(shows("minecraft:"), "names read as words");
        click("Back", Click.LEFT);
        click("Search", Click.LEFT);
        assertEquals("Search", alex.prompt.label());
        MockScreenHost.answer(alex, Optional.of("fox"));
        assertEquals("All bodies", title());
        assertEquals("Now: fox", lore("Search").lines().findFirst().orElseThrow());
        assertTrue(shows("Fox"));
        assertFalse(shows("Cow"));
        click("Fox", Click.LEFT);
        drain();
        assertEquals("minecraft:fox", npcs.find("fox").orElseThrow().json().get("type").getAsString());
    }

    @Test void aPlayerWithoutAWandGetsOne() {
        alex.wand = false;
        named("Bob");
        click("Cow", Click.LEFT);
        drain();
        assertEquals(1, alex.wands);
        assertEquals(List.of("bar:Wand given; right-click it to edit"), alex.messages.stream().filter((String line) -> line.contains("ight-click")).toList(),
                "one hint, above the hotbar: " + alex.messages);
    }

    @Test void withoutTheCreateNodeNothingStarts() {
        alex.permissions.clear();
        alex.permissions.add(PermissionNodes.TOOLS);
        toolkit.home(alex.id);
        assertTrue(lore("New NPC").startsWith("No permission"), "greyed out: " + lore("New NPC"));
        click("New NPC", Click.LEFT);
        assertNull(alex.prompt);
    }

    @Test void theBodyAndSkinStepsKeepTheirSlotsOffTheCentreForNothingDestructive() {
        named("Bob");
        for (int slot : List.of(13, 22, 31)) assertFalse(Set.of("Delete", "Close", "Remove", "Clear all").contains(name(slot)), name(slot));
    }
}
