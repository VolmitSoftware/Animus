package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.shop.ShopService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HoloServiceTest {
    @TempDir Path directory;
    private AnimusRuntime runtime;
    private final MockScreenHost host = new MockScreenHost();
    private final Player alex = new Player("Alex");
    private final RecordingDisplays<Player> displays = new RecordingDisplays<>();
    private final List<String> log = new ArrayList<>();
    private ShopService<Player, Stack> shops;
    private HoloService<Player, Stack> holograms;

    @BeforeEach void start() throws IOException {
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", directory));
        runtime.start();
        FakeNpcRuntime<Player> living = new FakeNpcRuntime<>((java.util.UUID id) -> id.equals(alex.id) ? Optional.of(alex) : Optional.empty());
        shops = new ShopService<>(runtime, host, living, Runnable::run, (String name) -> Optional.empty(), log::add);
        holograms = new HoloService<>(runtime, shops, displays, living, Runnable::run, GlossBridge.absent("Gloss is not installed"), log::add);
        alex.pose = new ScreenHost.Pose(Set.of("world", "minecraft:overworld"), 0, 64, 0, 65.62, 0, 0, false);
    }

    @AfterEach void stop() { holograms.close(); shops.close(); runtime.closeAsync(); }

    private void write(String folder, String id, String json) throws IOException {
        Path file = directory.resolve(folder).resolve(id.replace(':', '/') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private void reload() {
        shops.reload((art.arcane.animus.core.shop.ShopCatalog ignored) -> {});
        holograms.reload((HoloCatalog ignored) -> {});
        assertEquals(List.of(), holograms.catalog().problems());
    }

    private void tick(int ticks) { for (int index = 0; index < ticks; index++) holograms.tick(alex); }

    /** Aims the eye at a point. */
    private void look(double x, double y, double z) {
        double dx = x - alex.pose.x(), dy = y - alex.pose.eyeY(), dz = z - alex.pose.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        alex.pose = new ScreenHost.Pose(alex.pose.worlds(), alex.pose.x(), alex.pose.y(), alex.pose.z(), alex.pose.eyeY(), yaw, pitch, false);
    }

    private void move(double x, double y, double z) {
        alex.pose = new ScreenHost.Pose(alex.pose.worlds(), x, y, z, y + 1.62, alex.pose.yaw(), alex.pose.pitch(), false);
    }

    private static final String SIGN = """
            {"schemaVersion": 3, "revision": 1, "anchor": {"world": "world", "position": [0, 64, 5]},
             "pages": [{"id": "one", "lines": ["&6Page one for {{ viewer.name }}"]}, {"id": "two", "lines": ["&ePage |animation.blink|"]}],
             "actions": [{"type": "navigate", "mode": "page", "target": "next"}], "viewDistance": 16}
            """;

    @Test void aHologramAppearsInRangeTurnsPagesOnAimedClicksAndLeavesOutOfRange() throws IOException {
        write("gui/holograms", "town:sign", SIGN);
        write("gui/text-animations", "town:blink", "{\"schemaVersion\": 1, \"revision\": 1, \"mode\": \"ascend\", \"frameIntervalMs\": 60000, \"frames\": [\"two\"]}");
        reload();
        tick(1);
        assertEquals(List.of("Page one for Alex"), displays.texts(alex));
        DisplayEntity box = displays.hitboxes(alex).getFirst();
        look(10, 64, 0);
        displays.click(alex, box.id(), false);
        tick(1);
        assertEquals(List.of("Page one for Alex"), displays.texts(alex), "a click while looking away is ignored");
        look(0, 64.125, 5);
        displays.click(alex, box.id(), false);
        tick(1);
        assertEquals(List.of("Page two"), displays.texts(alex), "the page animation resolves in the hologram's namespace");
        move(0, 64, 100);
        tick(10);
        assertTrue(displays.of(alex).isEmpty());
    }

    @Test void aHologramNeedsItsAnimationAndCommandsNeedTheAllowList() throws IOException {
        write("gui/holograms", "town:sign", SIGN);
        write("gui/holograms", "town:cmd", SIGN.replace("{\"type\": \"navigate\", \"mode\": \"page\", \"target\": \"next\"}", "{\"type\": \"command\", \"command\": \"say hi\"}"));
        holograms.reload((HoloCatalog ignored) -> {});
        List<String> problems = holograms.catalog().problems();
        assertTrue(problems.contains("gui/holograms/town/sign.json $: shows text animation 'town:blink', which is not loaded"), problems.toString());
        assertTrue(problems.stream().anyMatch((String problem) -> problem.startsWith("gui/holograms/town/cmd.json $: runs commands")), problems.toString());
        assertTrue(holograms.catalog().holograms().isEmpty());
    }

    private static final String HALL = """
            {"offset": [0, 1.5, 2], "vars": {"volume": "5"}, "components": [
              {"id": "hello", "offset": [0, 0, 0], "data": {"type": "button", "highlightModifier": 0.1, "hoverDurationTicks": 2,
                "icon": {"type": "text", "text": "&aHello"}, "actions": [{"type": "message", "message": "hi {{ viewer.name }}"}]}},
              {"id": "light", "offset": [1, 0, 0], "data": {"type": "toggle", "condition": "session.light", "expectedValue": "on",
                "trueIcon": {"type": "text", "text": "ON"}, "falseIcon": {"type": "text", "text": "OFF"},
                "trueActions": [{"type": "setSession", "var": "light", "value": "on"}], "falseActions": [{"type": "setSession", "var": "light", "value": "off"}]}},
              {"id": "shop", "offset": [-1, 0, 0], "data": {"type": "button", "icon": {"type": "item", "item": "minecraft:emerald"},
                "actions": [{"type": "inventory", "id": "counter"}]}},
              {"id": "volume", "offset": [0, -1, 0], "data": {"type": "slider", "var": "volume", "min": 0, "max": 10, "label": "Volume {{ session.volume }}"}}
            ]}
            """;

    /** Some box of the menu: the server picks the component by where the viewer aims, not by which box was hit. */
    private int box(String text) {
        for (DisplayEntity entity : displays.of(alex))
            if (entity.content() instanceof DisplayEntity.Text shown && RichText.plain(shown.text()).equals(text)) {
                for (DisplayEntity hitbox : displays.hitboxes(alex))
                    if (Math.abs(hitbox.x() - entity.x()) < 1.0E-6 && Math.abs(hitbox.z() - entity.z()) < 0.2) return hitbox.id();
            }
        throw new AssertionError("no box for " + text + " in " + displays.of(alex));
    }

    @Test void aMenuOpensInFrontAndRunsAimedClicks() throws IOException {
        write("gui/menus", "town:hall", HALL);
        write("gui/inventories", "town:counter", "{\"schemaVersion\": 1, \"revision\": 1, \"title\": \"&8Counter\", \"resolution\": \"9x1\"}");
        reload();
        alex.pose = new ScreenHost.Pose(alex.pose.worlds(), 0, 64, 0, 65.62, 0, -60, false);
        assertTrue(holograms.openMenu(alex, "town:hall", Map.of(), Optional.empty()));
        tick(1);
        assertTrue(displays.texts(alex).containsAll(List.of("Hello", "OFF", "Volume 5")), displays.texts(alex).toString());
        DisplayEntity hello = displays.of(alex).stream().filter((DisplayEntity entity) -> entity.content() instanceof DisplayEntity.Text text
                && RichText.plain(text.text()).equals("Hello")).findFirst().orElseThrow();
        assertEquals(0, hello.x(), 1.0E-9);
        assertEquals(2, hello.z(), 1.0E-9, "the menu is two blocks in front of the viewer");
        look(0, 65.5, 2);
        tick(2);
        DisplayEntity hovered = displays.of(alex).stream().filter((DisplayEntity entity) -> entity.content() instanceof DisplayEntity.Text text
                && RichText.plain(text.text()).equals("Hello")).findFirst().orElseThrow();
        assertEquals(1.9, hovered.z(), 1.0E-6, "a hovered button moves toward the viewer by its highlight");
        displays.click(alex, box("Hello"), false);
        assertEquals(List.of("hi Alex"), alex.messages);
        look(-1, 65.5, 2);
        displays.click(alex, box("OFF"), false);
        tick(1);
        assertTrue(displays.texts(alex).contains("ON"), displays.texts(alex).toString());
        look(0, 64.5, 2);
        displays.click(alex, box("Volume 5"), true);
        tick(1);
        assertTrue(displays.texts(alex).contains("Volume 6"), displays.texts(alex).toString());
        look(1, 65.5, 2);
        displays.click(alex, box("Hello"), false);
        assertNotNull(alex.frame, "the inventory action opens the chest GUI in the menu's namespace");
        assertEquals("Counter", RichText.plain(alex.frame.title()));
        tick(1);
        assertTrue(displays.of(alex).isEmpty(), "opening the chest GUI closed the menu");
    }

    @Test void aClickThatMissesEveryPlaneDoesNothing() throws IOException {
        write("gui/menus", "town:hall", HALL);
        write("gui/inventories", "town:counter", "{\"schemaVersion\": 1, \"revision\": 1, \"title\": \"x\", \"resolution\": \"9x1\"}");
        reload();
        holograms.openMenu(alex, "town:hall", Map.of(), Optional.empty());
        tick(1);
        int hello = box("Hello");
        look(0, 80, 2);
        displays.click(alex, hello, false);
        assertTrue(alex.messages.isEmpty());
    }

    @Test void menusCloseOutOfRangeAndRefuseGlossOnlyFeatures() throws IOException {
        write("gui/menus", "town:hall", HALL.replace("\"vars\"", "\"maxDistance\": 3, \"vars\""));
        write("gui/menus", "town:field", "{\"offset\": [0, 1, 2], \"components\": [{\"id\": \"name\", \"offset\": [0, 0, 0], \"data\": {\"type\": \"field\", \"var\": \"name\"}}]}");
        write("gui/inventories", "town:counter", "{\"schemaVersion\": 1, \"revision\": 1, \"title\": \"x\", \"resolution\": \"9x1\"}");
        holograms.reload((HoloCatalog ignored) -> {});
        assertTrue(holograms.catalog().problems().contains("gui/menus/town/field.json $.components[0].data.type: component 'field' opens a sign, anvil or chat prompt, which is not available in Animus"),
                holograms.catalog().problems().toString());
        holograms.openMenu(alex, "town:hall", Map.of(), Optional.empty());
        tick(1);
        assertFalse(displays.of(alex).isEmpty());
        move(30, 64, 0);
        tick(1);
        assertTrue(displays.of(alex).isEmpty());
    }

    @Test void glossKindsAreRefusedWithoutGloss() {
        assertEquals("gloss-unavailable", holograms.refusal(GuiBinding.Kind.GLOSS).orElseThrow().diagnostics().getFirst().code());
        assertTrue(holograms.refusal(GuiBinding.Kind.MENU).isEmpty());
        assertFalse(holograms.open(alex, GuiBinding.Kind.GLOSS, "lobby", Map.of(), Optional.empty()));
    }

    @Test void npcBindingsParseWithExactFields() {
        JsonObject definition = JsonParser.parseString("""
                {"modules": {"animus:gui": {"open": {"kind": "menu", "id": "town:hall", "openOn": "both"}, "hologram": {"id": "town:sign", "offset": [0, 2.5, 0]}}}}
                """).getAsJsonObject();
        GuiBinding binding = GuiBinding.parse(definition).orElseThrow();
        assertEquals(GuiBinding.Kind.MENU, binding.open().orElseThrow().kind());
        assertTrue(binding.open().orElseThrow().opensOn(true));
        assertEquals(2.5, binding.hologram().orElseThrow().offset().orElseThrow()[1]);
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> GuiBinding.parse(JsonParser.parseString(
                "{\"modules\": {\"animus:gui\": {\"open\": {\"kind\": \"gloss\", \"id\": \"town:hall\"}}}}").getAsJsonObject()));
        assertEquals("$.modules[\"animus:gui\"].open.id: expected a Gloss menu id such as lobby/main", wrong.getMessage());
        assertEquals("$.modules[\"animus:gui\"].colour: unknown field", assertThrows(IllegalArgumentException.class, () -> GuiBinding.parse(JsonParser.parseString(
                "{\"modules\": {\"animus:gui\": {\"colour\": 1}}}").getAsJsonObject())).getMessage());
    }
}
