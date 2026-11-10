package art.arcane.animus.core.migration;

import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.catalog.BehaviorCheckpoints;
import art.arcane.animus.core.behavior.schema.OfflineCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitizensBehaviorsTest {
    @TempDir Path temporary;
    private int catalogs;

    /** The generated document loads in a real catalog with the built-in capabilities and their argument schemas. */
    private void accepted(String content) {
        try (OfflineCapabilities capabilities = new OfflineCapabilities()) {
            BehaviorCatalog.CapabilityResolver offline = capabilities.resolver();
            BehaviorCatalog.CapabilityResolver granting = new BehaviorCatalog.CapabilityResolver() {
                @Override public BehaviorCatalog.Capability resolve(BehaviorCatalog.Requirement requirement) { return offline.resolve(requirement); }
                @Override public List<art.arcane.animus.api.Diagnostic> validate(BehaviorCatalog.Requirement requirement, String arguments) { return offline.validate(requirement, arguments); }
                @Override public BehaviorCatalog.Capability authorize(BehaviorCatalog.Requirement requirement, String behavior) { return BehaviorCatalog.Capability.present(); }
            };
            JsonObject document = JsonDocuments.object(content);
            String id = document.get("id").getAsString();
            Path root = temporary.resolve("catalog-" + catalogs++);
            Path file = root.resolve("behaviors/" + id.replace(':', '/') + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            BehaviorCatalog catalog = new BehaviorCatalog(root, BehaviorCatalog.Limits.defaults(), granting, BehaviorCheckpoints.disabled());
            assertTrue(catalog.reload(0, Set.of()).published());
            for (BehaviorCatalog.Entry entry : catalog.snapshot().entries().values()) {
                assertEquals(BehaviorCatalog.SourceState.VALID, entry.state(), entry.diagnostics() + "\n" + content);
                assertEquals(List.of(), entry.diagnostics(), content);
            }
            assertEquals(1, catalog.snapshot().entries().size());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static final JsonObject DEFAULTS = defaults("{\"1\":{\"traits\":{}}}");

    private static JsonObject json(String text) {
        try { return JsonDocuments.object(text); } catch (IOException invalid) { throw new AssertionError(invalid); }
    }
    private static JsonObject defaults(String npcs) { return CitizensBehaviors.defaults(json(npcs)); }
    private static CitizensBehaviors.Result map(String traits) { return map(traits, "{}"); }
    private static CitizensBehaviors.Result map(String traits, String record) {
        return CitizensBehaviors.map("7", "Guard", "world", json(traits), json(record), DEFAULTS);
    }
    private static String location(int x, int y, int z) { return "{\"location\":{\"world\":\"world\",\"x\":" + x + ".7,\"y\":" + y + ".0,\"z\":" + z + "}}"; }

    @Test void linearWaypointsBecomeALoopingRouteWithDelaysOnTheirPoints() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"provider\":\"linear\",\"linear\":{\"points\":[" + location(1, 64, 2) + ","
                + "{\"location\":{\"world\":\"world\",\"x\":9,\"y\":64.5,\"z\":-3.2},\"triggers\":[{\"type\":\"delay\",\"delay\":40},{\"type\":\"delay\",\"delay\":20},{\"type\":\"chat\",\"lines\":[\"hi\"]}]},"
                + "{\"location\":{\"world\":\"elsewhere\",\"x\":0,\"y\":64,\"z\":0}}]}}}", "{\"navigator\":{\"speedmodifier\":0.4}}");
        JsonObject navigation = result.navigation().orElseThrow();
        assertEquals(json("{\"enabled\":false,\"reviewAccepted\":false,\"mode\":\"walk\",\"speed\":1.72,\"order\":\"sequence\",\"loop\":true,\"waitTicks\":0,"
                + "\"points\":[{\"x\":1.5,\"y\":64,\"z\":2.5},{\"x\":9.5,\"y\":64,\"z\":-3.5,\"waitTicks\":60}]}"), navigation);
        assertEquals(2, result.points());
        assertEquals(List.of("citizens:waypoints-7"), result.behaviors());
        assertTrue(result.warnings().contains("/traits/waypoints/linear: 1 points in another world or without usable coordinates were left out"));
        assertTrue(result.converted().contains("/traits/waypoints/linear/points/1/triggers/0/delay"));
        assertTrue(result.converted().contains("/traits/waypoints/linear/points/0/location/x"));
        assertFalse(result.converted().contains("/traits/waypoints/linear/points/2/location/x"));
    }

    @Test void cycleBecomesPingpongAndLongLegsNeedNoWarning() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"linear\":{\"cycle\":true,\"points\":{\"1\":" + location(100, 64, 0) + ",\"0\":" + location(0, 64, 0) + "}}}}");
        JsonObject navigation = result.navigation().orElseThrow();
        assertEquals("pingpong", navigation.get("order").getAsString());
        assertFalse(navigation.has("loop"));
        assertEquals(4.3, navigation.get("speed").getAsDouble());
        assertEquals(0.5, navigation.getAsJsonArray("points").get(0).getAsJsonObject().get("x").getAsDouble(), "map keys in numeric order");
        assertTrue(result.warnings().stream().noneMatch((String warning) -> warning.contains("legs are longer")), "a long leg is walked: " + result.warnings());
    }

    @Test void guidedWaypointsBecomeARandomRouteThroughGuides() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"provider\":\"guided\",\"guided\":{\"distance\":20.0,"
                + "\"destinations\":[{\"location\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0},\"triggers\":[{\"type\":\"delay\",\"delay\":100}]}," + location(30, 64, 0) + "," + location(200, 64, 0) + "],"
                + "\"guides\":[" + location(15, 64, 0) + "]}}}", "{\"navigator\":{\"pathfindingrange\":60.0}}");
        JsonObject navigation = result.navigation().orElseThrow();
        assertEquals("random", navigation.get("order").getAsString());
        assertEquals(20.0, navigation.get("linkDistance").getAsDouble());
        assertEquals(1, navigation.getAsJsonArray("guides").size());
        assertEquals(100, navigation.getAsJsonArray("points").get(0).getAsJsonObject().get("waitTicks").getAsInt());
        assertEquals(1, result.guides());
        assertTrue(result.warnings().stream().noneMatch((String warning) -> warning.contains("destinations have no guide")), "an isolated destination is reached: " + result.warnings());
    }

    @Test void guidedWithoutDistanceUsesTheNavigatorRange() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"provider\":\"guided\",\"guided\":{\"availablewaypoints\":[" + location(0, 64, 0) + "],\"helperwaypoints\":{\"0\":" + location(3, 64, 0) + "}}}}",
                "{\"navigator\":{\"pathfindingrange\":60.0}}");
        JsonObject navigation = result.navigation().orElseThrow();
        assertEquals(60.0, navigation.get("linkDistance").getAsDouble());
        assertEquals(1, navigation.getAsJsonArray("guides").size());
        assertTrue(result.converted().contains("/navigator/pathfindingrange"));
    }

    @Test void wanderKeepsItsRangesAndDelay() {
        JsonObject navigation = map("{\"waypoints\":{\"provider\":\"wander\",\"wander\":{\"xrange\":40,\"yrange\":2,\"delay\":-1,\"pathfind\":true}}}").navigation().orElseThrow();
        assertEquals(json("{\"enabled\":false,\"reviewAccepted\":false,\"mode\":\"walk\",\"speed\":4.3,\"order\":\"wander\",\"waitTicks\":60,\"points\":[],\"wander\":{\"radius\":32,\"height\":2}}"), navigation);
    }

    @Test void unknownProvidersAndEmptyRoutesStayPreservedOnly() {
        CitizensBehaviors.Result unknown = map("{\"waypoints\":{\"provider\":\"custom\"}}");
        assertTrue(unknown.navigation().isEmpty());
        assertEquals(List.of("/traits/waypoints/provider: waypoint provider custom is not supported; kept in citizens:review"), unknown.warnings());
        assertTrue(map("{\"waypoints\":{\"provider\":\"linear\"}}").navigation().isEmpty());
        assertTrue(map("{\"waypoints\":{\"provider\":\"guided\",\"guided\":{\"destinations\":[]}}}").navigation().isEmpty());
    }

    @Test void lookCloseUsesWrittenValuesAndInferredDefaults() {
        JsonObject onlyFalse = defaults("{\"1\":{\"traits\":{\"lookclose\":{\"enabled\":false}}},\"2\":{\"traits\":{\"lookclose\":{\"range\":5}}}}");
        assertEquals(json("{\"lookclose\":{\"enabled\":true,\"realisticlooking\":false,\"disablewhilenavigating\":true},\"inferred\":[\"enabled\"]}"), onlyFalse);
        CitizensBehaviors.Result unset = CitizensBehaviors.map("2", "Guard", "world", json("{\"lookclose\":{\"range\":5,\"targetnpcs\":true}}"), json("{}"), onlyFalse);
        assertEquals(json("{\"enabled\":true,\"range\":5.0,\"lineOfSight\":false,\"whileNavigating\":false,\"npcs\":true}"), unset.lookClose().orElseThrow());
        assertEquals(List.of(), unset.warnings());
        assertTrue(unset.converted().contains("/traits/lookclose/targetnpcs"));
        assertFalse(CitizensBehaviors.map("1", "Guard", "world", json("{\"lookclose\":{\"enabled\":false}}"), json("{}"), onlyFalse).lookClose().orElseThrow().get("enabled").getAsBoolean());
        assertFalse(map("{\"lookclose\":{\"range\":5}}").lookClose().orElseThrow().get("enabled").getAsBoolean(), "Citizens default is off");
        JsonObject explicit = map("{\"lookclose\":{\"enabled\":true,\"range\":100,\"realisticlooking\":true,\"disablewhilenavigating\":false}}").lookClose().orElseThrow();
        assertEquals(json("{\"enabled\":true,\"range\":64.0,\"lineOfSight\":true,\"whileNavigating\":true}"), explicit);
        JsonObject mixed = defaults("{\"1\":{\"traits\":{\"lookclose\":{\"enabled\":false}}},\"2\":{\"traits\":{\"lookclose\":{\"enabled\":true}}}}");
        assertEquals(0, mixed.getAsJsonArray("inferred").size());
    }

    @Test void anArmorStandTurnsToPlayersOnlyWhenItsSaveSaysSo() {
        JsonObject onlyFalse = defaults("{\"1\":{\"traits\":{\"lookclose\":{\"enabled\":false}}},\"2\":{\"traits\":{\"lookclose\":{\"range\":5}}}}");
        CitizensBehaviors.Result inferred = CitizensBehaviors.map("2", "Statue", "world", json("{\"type\":\"ARMOR_STAND\",\"lookclose\":{\"range\":5}}"), json("{}"), onlyFalse);
        assertFalse(inferred.lookClose().orElseThrow().get("enabled").getAsBoolean(), "the server default does not turn a posed stand");
        assertEquals(List.of("/traits/lookclose/enabled: an armor stand keeps its own facing and pose, so the server's look-close default is not applied; "
                + "set enabled in animus:look-close to turn it toward players"), inferred.warnings());
        CitizensBehaviors.Result written = CitizensBehaviors.map("3", "Statue", "world", json("{\"type\":\"minecraft:armor_stand\",\"lookclose\":{\"enabled\":true}}"), json("{}"), onlyFalse);
        assertTrue(written.lookClose().orElseThrow().get("enabled").getAsBoolean(), "a stand the save turns on still turns");
        assertEquals(List.of(), written.warnings());
        CitizensBehaviors.Result player = CitizensBehaviors.map("4", "Guard", "world", json("{\"type\":\"PLAYER\",\"lookclose\":{\"range\":5}}"), json("{}"), onlyFalse);
        assertTrue(player.lookClose().orElseThrow().get("enabled").getAsBoolean(), "other bodies keep the server default");
    }

    @Test void randomTalkCloseTextBecomesAProximityBehavior() throws IOException {
        CitizensBehaviors.Result result = map("{\"text\":{\"text\":[\"&aHello <player>!\",\"Line one<br>line two\"],\"talk-close\":true,\"random-talker\":true,\"range\":6,\"delay\":200}}");
        assertEquals(List.of("citizens:text-7"), result.behaviors());
        CitizensBehaviors.Document document = result.documents().getFirst();
        assertEquals("behaviors/citizens/text-7.json", document.path());
        JsonObject behavior = JsonDocuments.object(document.content());
        assertEquals(json("{\"id\":\"near\",\"event\":\"proximity\",\"phase\":\"enter\",\"radius\":6.0,\"playerCooldownTicks\":200}"), behavior.getAsJsonArray("triggers").get(0));
        JsonArray steps = behavior.getAsJsonObject("routines").getAsJsonObject("main").getAsJsonArray("steps");
        assertEquals("animus:set-quest-state", steps.get(0).getAsJsonObject().get("capability").getAsString());
        assertEquals(2, steps.get(0).getAsJsonObject().getAsJsonObject("arguments").get("random").getAsInt());
        assertEquals("&aHello {player_name}!", steps.get(1).getAsJsonObject().getAsJsonObject("then").getAsJsonObject("arguments").get("text").getAsString());
        assertEquals(2, steps.get(2).getAsJsonObject().getAsJsonObject("then").getAsJsonArray("steps").size(), "<br> splits a line into two messages");
        accepted(document.content());
    }

    @Test void sequentialClickTextTakesTurns() throws IOException {
        CitizensBehaviors.Result result = map("{\"text\":{\"text\":[\"One\",\"Two\",\"Three\"]}}");
        JsonObject behavior = JsonDocuments.object(result.documents().getFirst().content());
        assertEquals("interaction", behavior.getAsJsonArray("triggers").get(0).getAsJsonObject().get("event").getAsString());
        assertEquals(100, behavior.getAsJsonArray("triggers").get(0).getAsJsonObject().get("playerCooldownTicks").getAsInt(), "each player waits on their own, as in Citizens");
        JsonArray steps = behavior.getAsJsonObject("routines").getAsJsonObject("main").getAsJsonArray("steps");
        assertEquals(5, steps.size());
        assertEquals("animus:advance-quest-state", steps.get(3).getAsJsonObject().get("capability").getAsString());
        assertEquals("text-7", steps.get(3).getAsJsonObject().getAsJsonObject("arguments").get("name").getAsString());
        accepted(result.documents().getFirst().content());
        CitizensBehaviors.Result single = map("{\"text\":{\"text\":[\"Only\"]}}");
        assertEquals("animus:say", JsonDocuments.object(single.documents().getFirst().content()).getAsJsonObject("routines").getAsJsonObject("main").get("capability").getAsString());
    }

    @Test void aTalkItemGuardsClickTextOnTheHeldItem() throws IOException {
        CitizensBehaviors.Result result = map("{\"text\":{\"text\":[\"Pass\"],\"talkitem\":\"PAPER, minecraft:map\"}}");
        String content = result.documents().getFirst().content();
        accepted(content);
        JsonObject main = JsonDocuments.object(content).getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("condition", main.get("type").getAsString());
        assertEquals("same(player.item_in_hand, 'minecraft:paper') || same(player.item_in_hand, 'minecraft:map')", main.get("expression").getAsString());
        assertTrue(result.converted().contains("/traits/text/talkitem"));
        CitizensBehaviors.Result close = map("{\"text\":{\"text\":[\"Near\"],\"talk-close\":true,\"talkitem\":\"PAPER\"}}");
        assertEquals("animus:say", JsonDocuments.object(close.documents().getFirst().content()).getAsJsonObject("routines").getAsJsonObject("main").get("capability").getAsString(),
                "Citizens checks the talk item only on clicks");
    }

    @Test void commandsBecomeABehaviorPerClick() throws IOException {
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"executionMode\":\"LINEAR\",\"commands\":{"
                + "\"1\":{\"command\":\"/give <p> minecraft:bread 1\",\"hand\":\"RIGHT\",\"player\":false,\"cooldown\":5,\"permissions\":{\"0\":\"town.bread\"}},"
                + "\"0\":{\"command\":\"say <npc> greets you\",\"hand\":\"BOTH\",\"player\":true,\"delay\":10},"
                + "\"2\":{\"command\":\"spawn\",\"hand\":\"SHIFT_RIGHT\"},"
                + "\"3\":{\"command\":\"eco give <p> 5\",\"hand\":\"LEFT\",\"cost\":10.0},"
                + "\"4\":{\"command\":\"heal\",\"hand\":\"LEFT\",\"player\":true,\"op\":true}}}}");
        assertEquals(List.of("citizens:commands-7"), result.behaviors());
        String content = result.documents().getFirst().content();
        accepted(content);
        JsonObject behavior = JsonDocuments.object(content);
        assertEquals("right", behavior.get("entry").getAsString());
        JsonArray triggers = behavior.getAsJsonArray("triggers");
        assertEquals(json("{\"id\":\"right\",\"event\":\"interaction\",\"action\":\"interact\",\"routine\":\"right\"}"), triggers.get(0));
        assertEquals("attack", triggers.get(1).getAsJsonObject().get("action").getAsString());
        JsonArray right = behavior.getAsJsonObject("routines").getAsJsonObject("right").getAsJsonArray("steps");
        JsonObject delayed = right.get(0).getAsJsonObject();
        assertEquals("wait", delayed.getAsJsonArray("steps").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(json("{\"command\":\"say Guard greets you\",\"as\":\"player\"}"), delayed.getAsJsonArray("steps").get(1).getAsJsonObject().getAsJsonObject("arguments"));
        JsonObject gated = right.get(1).getAsJsonObject();
        assertEquals("animus:has-permission", gated.get("capability").getAsString());
        JsonObject cooling = gated.getAsJsonObject("then");
        assertEquals(json("{\"name\":\"commands-7-1-cooldown\",\"scope\":\"relationship\"}"), cooling.getAsJsonObject("arguments"), "the per-player cooldown is a flag per NPC and player");
        JsonArray ready = cooling.getAsJsonObject("else").getAsJsonArray("steps");
        assertEquals(json("{\"name\":\"commands-7-1-cooldown\",\"scope\":\"relationship\",\"expireSeconds\":5}"), ready.get(0).getAsJsonObject().getAsJsonObject("arguments"));
        assertEquals(json("{\"command\":\"give {player_name} minecraft:bread 1\",\"as\":\"console\"}"), ready.get(1).getAsJsonObject().getAsJsonObject("arguments"));
        JsonArray left = behavior.getAsJsonObject("routines").getAsJsonObject("left").getAsJsonArray("steps");
        assertEquals(3, left.size(), "the costed command converts too");
        assertEquals("animus:can-afford", left.get(1).getAsJsonObject().get("capability").getAsString());
        assertEquals("console", left.get(2).getAsJsonObject().getAsJsonObject("arguments").get("as").getAsString(), "operator commands run from the console");
        List<String> warnings = result.warnings();
        assertEquals(json("{\"id\":\"sneak-right\",\"event\":\"interaction\",\"action\":\"sneak_interact\",\"routine\":\"sneak-right\"}"), triggers.get(2));
        assertEquals("spawn", behavior.getAsJsonObject("routines").getAsJsonObject("sneak-right").getAsJsonObject("arguments").get("command").getAsString());
        assertTrue(warnings.contains("/traits/commandtrait: LEFT, RIGHT and BOTH commands also run on a sneaking click, beside the SHIFT ones"), warnings.toString());
        assertTrue(warnings.contains("/traits/commandtrait/commands/4/op: the command ran as an operator player; it now runs from the console"));
        assertTrue(warnings.contains("/traits/commandtrait: commands stay denied until citizens:commands-7 is on the [behaviors.actions] commandBehaviors list in config.toml"));
        assertTrue(warnings.stream().noneMatch((String warning) -> warning.contains("cannot charge")), warnings.toString());
    }

    @Test void aPlayerCastingAMythicSkillBecomesTheMythicCastAction() throws IOException {
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"commands\":{"
                + "\"0\":{\"command\":\"/mm test cast GreetSkill\",\"hand\":\"RIGHT\",\"player\":true},"
                + "\"1\":{\"command\":\"mythicmobs t c Fire_Burst-2 -s\",\"hand\":\"LEFT\",\"op\":true},"
                + "\"2\":{\"command\":\"mm test cast FromConsole\",\"hand\":\"SHIFT_RIGHT\"}}}}");
        JsonObject routines = JsonDocuments.object(result.documents().getFirst().content()).getAsJsonObject("routines");
        assertEquals(json("{\"type\":\"action\",\"capability\":\"mythic:cast-skill\",\"arguments\":{\"skill\":\"GreetSkill\",\"caster\":\"player\"}}"), routines.get("right"));
        assertEquals("Fire_Burst-2", routines.getAsJsonObject("left").getAsJsonObject("arguments").get("skill").getAsString());
        assertEquals("animus:command", routines.getAsJsonObject("sneak-right").get("capability").getAsString(), "the console has no body to cast from");
        List<String> warnings = result.warnings();
        assertTrue(warnings.contains("/traits/commandtrait/commands/0/command: the MythicMobs skill cast became mythic:cast-skill, which needs MythicMobs on the server"), warnings.toString());
        assertFalse(warnings.contains("/traits/commandtrait/commands/1/op: the command ran as an operator player; it now runs from the console"));
        CitizensBehaviors.Result castsOnly = map("{\"commandtrait\":{\"commands\":{\"0\":{\"command\":\"mm test cast GreetSkill\",\"player\":true}}}}");
        assertTrue(castsOnly.warnings().contains("/traits/commandtrait: commands stay denied until citizens:commands-7 is on the [behaviors.actions] commandBehaviors list in config.toml"),
                "a cast needs the command allow-list too: " + castsOnly.warnings());
    }

    @Test void commandModesTakeTurnsOrPickAtRandom() throws IOException {
        for (String mode : List.of("RANDOM", "CYCLE", "SEQUENTIAL")) {
            CitizensBehaviors.Result result = map("{\"commandtrait\":{\"executionMode\":\"" + mode + "\",\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"BOTH\"},\"1\":{\"command\":\"b\",\"hand\":\"BOTH\"}}}}");
            String content = result.documents().getFirst().content();
            accepted(content);
            JsonObject behavior = JsonDocuments.object(content);
            assertEquals(json("{\"id\":\"click\",\"event\":\"interaction\",\"action\":\"any\",\"routine\":\"click\"}"), behavior.getAsJsonArray("triggers").get(0));
            JsonArray steps = behavior.getAsJsonObject("routines").getAsJsonObject("click").getAsJsonArray("steps");
            JsonElement scope = steps.get(1).getAsJsonObject().getAsJsonObject("arguments").get("scope");
            assertEquals(mode.equals("SEQUENTIAL") ? "relationship" : "npc", scope.getAsString(), mode);
            assertEquals(mode.equals("RANDOM") ? "animus:set-quest-state" : "animus:quest-state", steps.get(0).getAsJsonObject().get("capability").getAsString(), mode);
        }
    }

    @Test void aCommandTraitCostIsChargedOncePerClick() throws IOException {
        CitizensBehaviors.Result result = map("{\"commandtrait\":{\"cost\":5.0,\"commands\":{\"0\":{\"command\":\"a\",\"hand\":\"BOTH\"}}}}");
        String content = result.documents().getFirst().content();
        accepted(content);
        JsonObject click = JsonDocuments.object(content).getAsJsonObject("routines").getAsJsonObject("click");
        assertEquals(json("{\"money\":5}"), click.getAsJsonObject("arguments"));
        assertEquals("animus:charge", click.getAsJsonObject("then").getAsJsonArray("steps").get(0).getAsJsonObject().get("capability").getAsString());
        assertEquals(json("{\"text\":\"You need at least $5.\"}"), click.getAsJsonObject("else").getAsJsonObject("arguments"));
    }

    @Test void randomLookKeepsItsDelayAndRangesAndCosmeticOptionsGetReasons() {
        CitizensBehaviors.Result result = map("{\"lookclose\":{\"enabled\":true,\"randomlookdelay\":120,\"randomYawRange\":[0.0,360.0],\"randomPitchRange\":[-8.0,8.0],"
                + "\"headonly\":true,\"perplayer\":false}}");
        assertEquals(json("{\"enabled\":false,\"delayTicks\":120,\"yaw\":[0.0,360.0],\"pitch\":[-8.0,8.0]}"), result.lookClose().orElseThrow().get("randomLook"));
        for (String path : List.of("/traits/lookclose/randomlookdelay", "/traits/lookclose/randomYawRange/0", "/traits/lookclose/randomPitchRange/1", "/traits/lookclose/perplayer"))
            assertTrue(result.converted().contains(path), path);
        assertTrue(result.converted().contains("/traits/lookclose/headonly"));
        assertTrue(result.lookClose().orElseThrow().get("headOnly").getAsBoolean());
        assertEquals(List.of(), result.warnings());
    }

    @Test void speedAndTeleportTriggersBecomePointSettings() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"provider\":\"linear\",\"linear\":{\"points\":[" + location(1, 64, 2) + ","
                + "{\"location\":{\"world\":\"world\",\"x\":9,\"y\":64,\"z\":3},\"triggers\":[{\"type\":\"speed\",\"speed\":0.5},"
                + "{\"type\":\"teleport\",\"location\":{\"world\":\"world\",\"x\":40.25,\"y\":70.5,\"z\":-8.0,\"yaw\":450.0,\"pitch\":12.34567}}]},"
                + "{\"location\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0},\"triggers\":[{\"type\":\"teleport\",\"location\":{\"world\":\"nether\",\"x\":0,\"y\":64,\"z\":0}}]}]}}}");
        JsonObject point = result.navigation().orElseThrow().getAsJsonArray("points").get(1).getAsJsonObject();
        assertEquals(2.15, point.get("speed").getAsDouble(), "Citizens' modifier times 4.3 blocks a second");
        assertEquals(json("{\"x\":40.25,\"y\":70.5,\"z\":-8.0,\"yaw\":90.0,\"pitch\":12.346}"), point.getAsJsonObject("teleport"));
        assertEquals(json("{\"x\":0.0,\"y\":64.0,\"z\":0.0,\"world\":\"nether\"}"), result.navigation().orElseThrow().getAsJsonArray("points").get(2).getAsJsonObject().getAsJsonObject("teleport"),
                "a teleport to another world names it");
        for (String path : List.of("/traits/waypoints/linear/points/1/triggers/0/speed", "/traits/waypoints/linear/points/1/triggers/1/location/yaw"))
            assertTrue(result.converted().contains(path), path);
        assertEquals(List.of(), result.behaviors(), "speed and teleport need no behavior");
    }

    @Test void chatAndCommandTriggersRunOnArrival() {
        CitizensBehaviors.Result result = map("{\"waypoints\":{\"provider\":\"linear\",\"linear\":{\"points\":["
                + "{\"location\":{\"world\":\"elsewhere\",\"x\":0,\"y\":64,\"z\":0}}," + location(1, 64, 2) + ","
                + "{\"location\":{\"world\":\"world\",\"x\":9,\"y\":64,\"z\":3},\"triggers\":[{\"type\":\"chat\",\"radius\":5.0,\"lines\":[\"&aHello from <npc>\",\"Hi <p>\"]},"
                + "{\"type\":\"chat\",\"lines\":[\"Everyone\"]},{\"type\":\"command\",\"commands\":[\"/say <id> arrived\"]},"
                + "{\"type\":\"animation\",\"animations\":[\"ARM_SWING\",\"START_ELYTRA\"],\"at\":{\"world\":\"world\",\"x\":9.5,\"y\":64.0,\"z\":4.5}}]}]}}}");
        assertEquals(List.of("citizens:waypoints-7"), result.behaviors());
        String content = result.documents().getFirst().content();
        accepted(content);
        JsonObject document = json(content);
        assertEquals("behaviors/citizens/waypoints-7.json", result.documents().getFirst().path());
        assertEquals(json("{\"id\":\"point-1\",\"event\":\"waypoint\",\"waypoint\":1,\"routine\":\"point-1\"}"), document.getAsJsonArray("triggers").get(0), "the index of the converted point");
        assertEquals(json("""
                {"type":"sequence","steps":[
                 {"type":"action","capability":"animus:message","arguments":{"text":"&aHello from Guard","to":"nearby","radius":5.0}},
                 {"type":"action","capability":"animus:message","arguments":{"text":"Everyone","to":"world"}},
                 {"type":"action","capability":"animus:command","arguments":{"command":"say 7 arrived","as":"console"}},
                 {"type":"action","capability":"animus:animate","arguments":{"animation":"swing"}}]}
                """), document.getAsJsonObject("routines").get("point-1"));
        assertTrue(result.warnings().contains("/traits/waypoints/linear/points/2/triggers/0/lines/1: a waypoint has no player to name; left out"), result.warnings().toString());
        assertTrue(result.warnings().contains("/traits/waypoints/linear/points/2/triggers/3/animations/1: the START_ELYTRA animation has no Animus animation; left out"), result.warnings().toString());
        assertEquals(json("{\"x\":9.5,\"y\":64.0,\"z\":4.5}"), result.navigation().orElseThrow().getAsJsonArray("points").get(1).getAsJsonObject().getAsJsonObject("teleport"),
                "the animation's place becomes the point's teleport");
        assertTrue(result.warnings().stream().anyMatch((String warning) -> warning.contains("waypoint commands stay denied until citizens:waypoints-7")));
    }

    @Test void lookCloseKeepsHeadOnlyPerPlayerFiltersAndGradualTurning() {
        CitizensBehaviors.Result result = map("""
                {"lookclose":{"enabled":true,"headonly":true,"perplayer":true,"filter":"type=PLAYER permission=town.vip,town.guest group=Staff"},
                 "rotationtrait":{"globalParameters":{"maxYawPerTick":5.0,"lockPitch":true}}}
                """);
        JsonObject look = result.lookClose().orElseThrow();
        assertTrue(look.get("headOnly").getAsBoolean());
        assertTrue(look.get("perPlayer").getAsBoolean());
        assertEquals("[\"town.vip\",\"town.guest\",\"group.staff\"]", look.get("permissions").toString());
        assertEquals(json("{\"yaw\":5.0,\"pitch\":0.0}"), look.getAsJsonObject("turnRate"));
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/lookclose/filter").status());
        assertFalse(map("{\"lookclose\":{\"enabled\":true,\"headonly\":true,\"linkedbody\":true}}").lookClose().orElseThrow().has("headOnly"), "a linked body turns with the head");
        assertFalse(map("{\"lookclose\":{\"enabled\":true,\"filter\":\"type=ZOMBIE\"}}").lookClose().orElseThrow().get("enabled").getAsBoolean(), "no player passes");
        assertFalse(map("{\"lookclose\":{\"enabled\":true},\"rotationtrait\":{\"globalParameters\":{\"maxYawPerTick\":40.0}}}").lookClose().orElseThrow().has("turnRate"),
                "Citizens' own rates turn as fast as Animus looks");
    }

    @Test void waypointFacingWorldIdsAndGuideTriggersCountAsConverted() {
        CitizensBehaviors.Result result = map("""
                {"location":{"world":"world"},"waypoints":{"provider":"guided","guided":{"destinations":[{"location":{"world":"world","worldid":"x","x":0,"y":64,"z":0,"yaw":5,"pitch":1}}],
                 "guides":[{"location":{"world":"world","x":3,"y":64,"z":0,"yaw":1,"pitch":2},"triggers":[{"type":"delay","delay":20}]}]},"wander":{"xrange":5}}}
                """);
        for (String path : List.of("/traits/waypoints/guided/destinations/0/location/yaw", "/traits/waypoints/guided/destinations/0/location/worldid",
                "/traits/waypoints/guided/guides/0/location/pitch")) assertTrue(result.converted().contains(path), path);
        assertEquals(CitizensImporter.Status.CONVERTED, result.notes().get("/traits/waypoints/guided/guides/0/triggers").status());
        assertEquals(CitizensImporter.Status.UNSUPPORTED_PRESERVED, result.notes().get("/traits/waypoints/wander").status());
    }
}
