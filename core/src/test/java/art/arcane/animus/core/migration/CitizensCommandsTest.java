package art.arcane.animus.core.migration;

import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.schema.OfflineCapabilities;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CitizensCommandsTest {
    @TempDir Path temporary;
    private int catalogs;

    /** The generated document loads in a real catalog with the built-in capabilities and their argument schemas. */
    private JsonObject accepted(CitizensBehaviors.Result result) {
        assertEquals(1, result.documents().size(), result.warnings().toString());
        String content = result.documents().getFirst().content();
        try (OfflineCapabilities capabilities = new OfflineCapabilities()) {
            BehaviorCatalog.CapabilityResolver offline = capabilities.resolver();
            BehaviorCatalog.CapabilityResolver granting = new BehaviorCatalog.CapabilityResolver() {
                @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) { return offline.resolve(requirement); }
                @Override public List<art.arcane.animus.api.Diagnostic> validate(BehaviorCatalog.Requirement requirement, String arguments) { return offline.validate(requirement, arguments); }
                @Override public BehaviorCatalog.Capability authorize(BehaviorCatalog.Requirement requirement, String behavior) { return BehaviorCatalog.Capability.present(); }
            };
            JsonObject document = JsonDocuments.object(content);
            Path root = temporary.resolve("catalog-" + catalogs++);
            Path file = root.resolve("behaviors/" + document.get("id").getAsString().replace(':', '/') + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            BehaviorCatalog catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), granting, BehaviorCheckpoints.disabled());
            assertTrue(catalog.reload(0, Set.of()).published());
            for (BehaviorCatalog.Entry entry : catalog.snapshot().entries().values())
                assertEquals(List.of(), entry.diagnostics(), content);
            return document;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static JsonObject json(String text) {
        try { return JsonDocuments.object(text); } catch (IOException invalid) { throw new AssertionError(invalid); }
    }
    private static CitizensBehaviors.Result map(String traits, CitizensCommands.Addons addons) {
        return CitizensBehaviors.map("7", "Guard", "world", json(traits), json("{}"), CitizensBehaviors.defaults(json("{\"1\":{\"traits\":{}}}")), addons);
    }
    private static JsonObject routine(JsonObject behavior, String name) { return behavior.getAsJsonObject("routines").getAsJsonObject(name); }

    @Test void useLimitsGlobalCooldownsAndCostsGateEachCommand() {
        String emerald = "{\"type\":\"EMERALD\",\"amount\":2}";
        JsonObject behavior = accepted(map("{\"commandtrait\":{\"commands\":{\"0\":{\"command\":\"give <p> diamond\",\"hand\":\"RIGHT\",\"n\":3,\"gn\":10,\"globalcooldown\":60,"
                + "\"cost\":2.5,\"experienceCost\":4,\"itemCost\":{\"0\":" + emerald + "}}}}}", CitizensCommands.Addons.NONE));
        JsonObject node = routine(behavior, "right");
        assertEquals(json("{\"name\":\"commands-7-0-global-cooldown\",\"scope\":\"npc\"}"), node.getAsJsonObject("arguments"));
        JsonObject uses = node.getAsJsonObject("else");
        assertEquals(json("{\"name\":\"commands-7-0-uses\",\"scope\":\"relationship\",\"atLeast\":3}"), uses.getAsJsonObject("arguments"));
        assertEquals(json("{\"text\":\"You have reached the maximum number of uses (3).\"}"), uses.getAsJsonObject("then").getAsJsonObject("arguments"));
        JsonObject globalUses = uses.getAsJsonObject("else");
        assertEquals(json("{\"name\":\"commands-7-0-global-uses\",\"scope\":\"npc\",\"atLeast\":10}"), globalUses.getAsJsonObject("arguments"));
        JsonObject money = globalUses.getAsJsonObject("else");
        assertEquals(json("{\"money\":2.5}"), money.getAsJsonObject("arguments"));
        assertEquals(json("{\"text\":\"You need at least $2.5.\"}"), money.getAsJsonObject("else").getAsJsonObject("arguments"));
        JsonObject levels = money.getAsJsonObject("then");
        assertEquals(json("{\"levels\":4}"), levels.getAsJsonObject("arguments"));
        JsonObject items = levels.getAsJsonObject("then");
        assertEquals(json("{\"text\":\"Missing 2 Emerald\"}"), items.getAsJsonObject("else").getAsJsonObject("arguments"));
        JsonArray run = items.getAsJsonObject("then").getAsJsonArray("steps");
        assertEquals(json("{\"money\":2.5,\"levels\":4,\"items\":[{\"item\":{\"id\":\"minecraft:emerald\"},\"amount\":2}]}"), run.get(0).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals(json("{\"name\":\"commands-7-0-global-cooldown\",\"scope\":\"npc\",\"expireSeconds\":60}"), run.get(1).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals("animus:advance-quest-state", run.get(2).getAsJsonObject().get("capability").getAsString());
        assertEquals(json("{\"command\":\"give {player_name} diamond\",\"as\":\"console\"}"), run.get(4).getAsJsonObject().getAsJsonObject("arguments"));
    }

    @Test void hiddenErrorsSendNothingAndCustomMessagesReplaceTheDefaults() {
        JsonObject hidden = accepted(map("{\"commandtrait\":{\"hideErrorMessages\":true,\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"BOTH\",\"permissions\":[\"x.y\"]}}}}", CitizensCommands.Addons.NONE));
        assertFalse(routine(hidden, "click").has("else"));
        JsonObject custom = accepted(map("{\"commandtrait\":{\"customErrorMessages\":{\"NO_PERMISSION\":\"Members only, <p>.\"},\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"BOTH\",\"permissions\":[\"x.y\"]}}}}", CitizensCommands.Addons.NONE));
        assertEquals(json("{\"text\":\"Members only, {player_name}.\"}"), routine(custom, "click").getAsJsonObject("else").getAsJsonObject("arguments"));
    }

    @Test void commandsRunAsTheNpcNameTheNpcForThePlayer() {
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"commands\":{\"0\":{\"command\":\"msg <p> hi\",\"hand\":\"BOTH\",\"npc\":true,\"player\":true}}}}", CitizensCommands.Addons.NONE);
        JsonObject behavior = accepted(result);
        assertEquals(json("{\"command\":\"msg Guard hi\",\"as\":\"console\"}"), routine(behavior, "click").getAsJsonObject("arguments"));
        assertTrue(result.warnings().contains("/traits/commandtrait/commands/0/npc: the NPC's own player body ran the command; it now runs from the console, with the NPC's name for the player"), result.warnings().toString());
    }

    @Test void itemCostsThatCannotBeMatchedExactlyAreNotConverted() {
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"BOTH\",\"itemCost\":{\"0\":{\"type\":\"STONE\",\"amount\":1,\"odd\":1}}}}}}", CitizensCommands.Addons.NONE);
        assertTrue(result.documents().isEmpty());
        assertTrue(result.warnings().contains("/traits/commandtrait/commands/0/itemCost: an item cost cannot be matched exactly; not converted"), result.warnings().toString());
    }

    @Test void citizensCmdCommandsJoinTheSameBehaviorWithItsPriceAndCooldown() {
        JsonObject section = json("{\"cooldown\":30,\"price\":10.0,\"permission\":\"vip.use\",\"right-click-commands\":[\"[console] give %p% bread\",\"[none(2)] spawn\",\"[message] Hi from {name}\",\"[server] lobby\"],"
                + "\"left-click-commands\":[]}");
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"RIGHT\"}}}}", new CitizensCommands.Addons(Optional.of(section), List.of()));
        JsonObject behavior = accepted(result);
        JsonArray steps = routine(behavior, "right").getAsJsonArray("steps");
        assertEquals("a", steps.get(0).getAsJsonObject().getAsJsonObject("arguments").get("command").getAsString(), "the Citizens command runs first");
        JsonObject permission = steps.get(1).getAsJsonObject();
        assertEquals(json("{\"permission\":\"vip.use\"}"), permission.getAsJsonObject("arguments"));
        JsonObject cooling = permission.getAsJsonObject("then");
        assertEquals(json("{\"name\":\"citizenscmd-7-cooldown\",\"scope\":\"relationship\"}"), cooling.getAsJsonObject("arguments"));
        JsonObject afford = cooling.getAsJsonObject("else");
        assertEquals(json("{\"money\":10}"), afford.getAsJsonObject("arguments"));
        JsonArray body = afford.getAsJsonObject("then").getAsJsonArray("steps");
        assertEquals("animus:charge", body.get(0).getAsJsonObject().get("capability").getAsString());
        assertEquals(json("{\"command\":\"give {player_name} bread\",\"as\":\"console\"}"), body.get(1).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals(40, body.get(2).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().get("ticks").getAsInt());
        assertEquals(json("{\"text\":\"Hi from Guard\"}"), body.get(3).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals(json("{\"name\":\"citizenscmd-7-cooldown\",\"scope\":\"relationship\",\"expireSeconds\":30}"), body.get(4).getAsJsonObject().getAsJsonObject("arguments"));
        assertTrue(result.warnings().contains("/addons/citizenscmd/right-click-commands/3: server lines are not supported; not converted"), result.warnings().toString());
    }

    @Test void commandNpcLinesOfEachSavedVersionConvert() {
        List<String> lines = List.of("1~~~heal %name~~~heal.use~~~RIGHT~~~&cWait!~~~true~~~false~~~false~~~5.0~~~0~~~100~~~false~~~true",
                "2~~~spawn~~~~~~LEFT~~~false~~~false~~~false~~~0.0~~~20",
                "3~~~server hub~~~~~~BOTH~~~~~~true~~~false~~~false~~~0.0~~~0~~~0");
        CitizensBehaviors.Result result = map("{}", new CitizensCommands.Addons(Optional.empty(), lines));
        JsonObject behavior = accepted(result);
        JsonObject heal = routine(behavior, "right");
        assertEquals(json("{\"permission\":\"heal.use\"}"), heal.getAsJsonObject("arguments"));
        JsonObject cooling = heal.getAsJsonObject("then");
        assertEquals(json("{\"text\":\"&cWait!\"}"), cooling.getAsJsonObject("then").getAsJsonObject("arguments"));
        JsonObject afford = cooling.getAsJsonObject("else");
        assertFalse(afford.has("else"), "ignoreMoneyMsg sends nothing");
        JsonArray run = afford.getAsJsonObject("then").getAsJsonArray("steps");
        assertEquals(json("{\"command\":\"heal {player_name}\",\"as\":\"console\"}"), run.get(1).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals(json("{\"name\":\"commandnpc-7-1-cooldown\",\"scope\":\"relationship\",\"expireSeconds\":5}"), run.get(2).getAsJsonObject().getAsJsonObject("arguments"));
        JsonArray spawn = routine(behavior, "left").getAsJsonArray("steps");
        assertEquals(20, spawn.get(0).getAsJsonObject().get("ticks").getAsInt());
        assertEquals(json("{\"command\":\"spawn\",\"as\":\"player\"}"), spawn.get(1).getAsJsonObject().getAsJsonObject("arguments"));
        assertTrue(result.warnings().contains("/addons/commandnpc/2: BungeeCord server switches are not supported; not converted"), result.warnings().toString());
    }
}
