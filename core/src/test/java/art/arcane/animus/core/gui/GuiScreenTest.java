package art.arcane.animus.core.gui;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiScreenTest {
    private final MockScreenHost host = new MockScreenHost();
    private final Set<UUID> activeNpcs = new java.util.HashSet<>();
    private final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, activeNpcs::contains, (String warning) -> { throw new AssertionError(warning); });
    private final Map<String, GuiDocument> documents = new HashMap<>();
    private final Player alex = new Player("Alex");

    private final Screens<Player, Stack> screens = new Screens<>() {
        @Override public Optional<Screen> gui(ScreenManager<Player, Stack>.Viewer viewer, String id, Map<String, String> args) {
            GuiDocument document = documents.get(id);
            return document == null ? Optional.empty() : Optional.of(new GuiScreen<>(document, viewer, this, args));
        }
        @Override public Optional<Screen> shop(ScreenManager<Player, Stack>.Viewer viewer, String id) { return Optional.empty(); }
        @Override public Object npc(ScreenManager<Player, Stack>.Viewer viewer, String property) { return property.equals("name") ? "Baker" : null; }
    };

    private void add(String id, String json) {
        GuiDocument.Result result = GuiDocument.parse(id, json);
        assertEquals(java.util.List.of(), result.problems());
        documents.put(id, result.document().orElseThrow());
    }

    private boolean open(String id) { return manager.open(alex, Optional.empty(), (ScreenManager<Player, Stack>.Viewer viewer) -> screens.gui(viewer, id, Map.of("tier", "gold"))); }

    private void click(int slot, Click click) { manager.click(alex, alex.window, slot, click); }

    @Test void theGlossExampleRendersMaskKeysSlotsAndList() {
        add("town:menu", GuiDocumentTest.GLOSS);
        alex.facts.put("level", 7);
        assertTrue(open("town:menu"));
        assertEquals("Shop - Alex", GuiText.plain(alex.frame.title()));
        assertEquals("minecraft:gray_stained_glass_pane", alex.frame.items().get(0).item().id());
        assertEquals("minecraft:stone", alex.frame.items().get(10).item().id(), "list cells draw over explicit slots, as in Gloss");
        assertEquals("minecraft:oak_log", alex.frame.items().get(12).item().id());
        assertNull(alex.frame.items().get(13));
    }

    @Test void showHidesASlotForThisViewer() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "decoration", "show": "viewer.level >= 5", "icon": {"type": "item", "item": "minecraft:diamond"}}}}
                """);
        alex.facts.put("level", 2);
        open("town:menu");
        assertNull(alex.frame.items().get(0));
        alex.facts.put("level", 9);
        manager.viewer(alex.id).orElseThrow().refresh();
        assertEquals("minecraft:diamond", alex.frame.items().get(0).item().id());
    }

    @Test void buttonsRunTheirActionsWithTheViewerInScope() {
        add("town:menu", GuiDocumentTest.GLOSS);
        alex.facts.put("level", 7);
        open("town:menu");
        click(11, Click.LEFT);
        click(49, Click.LEFT);
        assertEquals(java.util.List.of("You picked dirt."), alex.messages);
        assertNull(alex.window, "the close button closed the window");
    }

    @Test void listPagesClampAtBothEnds() {
        add("town:list", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "mask": ["..<   >  "],
                 "keys": {"<": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow"}, "actions": [{"type": "navigate", "mode": "page", "target": "prev"}]},
                          ">": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow", "name": "Page {{ page }}/{{ pages }}"}, "actions": [{"type": "navigate", "mode": "page", "target": "next"}]}},
                 "list": {"area": ".", "var": "entry", "source": "['a','b','c','d','e']", "template": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:paper", "name": "{{ entry }}"}}}}
                """);
        open("town:list");
        assertEquals("Page 1/3", GuiText.plain(alex.frame.items().get(6).name().orElseThrow()));
        click(6, Click.LEFT); click(6, Click.LEFT); click(6, Click.LEFT);
        assertEquals("e", alex.frame.items().get(0).name().orElseThrow());
        assertNull(alex.frame.items().get(1));
        click(2, Click.LEFT); click(2, Click.LEFT); click(2, Click.LEFT);
        assertEquals("a", alex.frame.items().get(0).name().orElseThrow());
    }

    @Test void triggersWhenAndSessionVariablesShapeWhatRuns() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:stone", "name": "{{ session.choice }}"},
                  "actions": [{"type": "setSession", "var": "choice", "value": "left", "trigger": "left_click"},
                              {"type": "setSession", "var": "choice", "value": "right", "trigger": "right_click"},
                              {"type": "message", "message": "tier {{ args.tier }}", "when": "session.choice == 'right'"}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        assertEquals("left", alex.frame.items().get(0).name().orElseThrow());
        assertTrue(alex.messages.isEmpty());
        click(0, Click.RIGHT);
        assertEquals("right", alex.frame.items().get(0).name().orElseThrow());
        assertEquals(java.util.List.of("tier gold"), alex.messages);
    }

    @Test void togglesShowAndRunTheMatchingSide() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "toggle", "condition": "session.on", "expectedValue": "yes",
                  "trueIcon": {"type": "item", "item": "minecraft:lime_dye"}, "falseIcon": {"type": "item", "item": "minecraft:gray_dye"},
                  "trueActions": [{"type": "setSession", "var": "on", "value": "no"}], "falseActions": [{"type": "setSession", "var": "on", "value": "yes"}]}}}
                """);
        open("town:menu");
        assertEquals("minecraft:gray_dye", alex.frame.items().get(0).item().id());
        click(0, Click.LEFT);
        assertEquals("minecraft:lime_dye", alex.frame.items().get(0).item().id());
        click(0, Click.LEFT);
        assertEquals("minecraft:gray_dye", alex.frame.items().get(0).item().id());
    }

    @Test void takeGatesTheRestOfTheListAndGiveAddsItems() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:bread"},
                  "actions": [{"type": "take", "item": {"type": "item", "item": "minecraft:emerald"}, "amount": "2", "denyMessage": "&cTwo emeralds"},
                              {"type": "give", "item": {"type": "item", "item": "minecraft:bread"}, "amount": "3"}]}}}
                """);
        open("town:menu");
        alex.inventory.with(0, Stack.of("minecraft:emerald", 3));
        click(0, Click.LEFT);
        click(0, Click.LEFT);
        assertEquals(1, alex.inventory.total("minecraft:emerald"));
        assertEquals(3, alex.inventory.total("minecraft:bread"));
        assertEquals(java.util.List.of("Two emeralds"), alex.messages);
    }

    @Test void economyActionsFailClosedWithoutAProvider() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:gold_ingot"},
                  "actions": [{"type": "economy", "op": "withdraw", "amount": "5", "denyMessage": "No money"}, {"type": "give", "item": {"type": "item", "item": "minecraft:cake"}}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        assertEquals(0, alex.inventory.total("minecraft:cake"));
        assertEquals(java.util.List.of("No money"), alex.messages);
    }

    @Test void delaysResumeOnLaterTicksAndStopWhenClosed() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:clock"},
                  "actions": [{"type": "message", "message": "now"}, {"type": "delay", "ticks": 3}, {"type": "message", "message": "later"}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        manager.tick(alex); manager.tick(alex);
        assertEquals(java.util.List.of("now"), alex.messages);
        manager.tick(alex);
        assertEquals(java.util.List.of("now", "later"), alex.messages);
        click(0, Click.LEFT);
        manager.close(alex);
        for (int tick = 0; tick < 5; tick++) manager.tick(alex);
        assertEquals(java.util.List.of("now", "later", "now"), alex.messages);
    }

    @Test void navigationPushesAndGoesBack() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "title": "Menu", "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:book"},
                  "actions": [{"type": "navigate", "mode": "push", "target": "town:more"}]}}}
                """);
        add("town:more", """
                {"schemaVersion": 1, "revision": 1, "title": "More {{ npc.name }}", "resolution": "9x2", "slots": {"9": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow"},
                  "actions": [{"type": "navigate", "mode": "back"}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        assertEquals("More Baker", alex.frame.title());
        click(9, Click.LEFT);
        assertEquals("Menu", alex.frame.title());
    }

    @Test void commandsRenderTheirTemplatesAndRunAsTheirSource() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:command_block"},
                  "actions": [{"type": "command", "command": "give {{ viewer.name }} minecraft:cake"}, {"type": "command", "command": "spawn", "source": "player"}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        assertEquals(java.util.List.of("give Alex minecraft:cake"), host.console);
        assertEquals(java.util.List.of("spawn"), alex.commands);
    }

    @Test void stopEndsTheListAndCallRunsNamedActions() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "resolution": "9x1", "actions": {"greet": [{"type": "message", "message": "hello"}, {"type": "stop"}, {"type": "message", "message": "never"}]},
                 "slots": {"0": {"type": "button", "icon": {"type": "item", "item": "minecraft:stone"}, "actions": [{"type": "call", "action": "greet"}, {"type": "message", "message": "after"}]}}}
                """);
        open("town:menu");
        click(0, Click.LEFT);
        assertEquals(java.util.List.of("hello"), alex.messages);
    }

    @Test void variantsReplaceThePresentation() {
        add("town:menu", """
                {"schemaVersion": 1, "revision": 1, "title": "Plain", "resolution": "9x1", "slots": {"0": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:stone"}}},
                 "variants": [{"id": "vip", "priority": 1, "when": "viewer.vip", "presentation": {"title": "VIP", "slots": {"1": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:gold_block"}}}}}]}
                """);
        alex.facts.put("vip", true);
        open("town:menu");
        assertEquals("VIP", alex.frame.title());
        assertNull(alex.frame.items().get(0));
        assertEquals("minecraft:gold_block", alex.frame.items().get(1).item().id());
    }
}
