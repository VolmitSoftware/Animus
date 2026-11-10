package art.arcane.animus.core.migration;

import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The Denizen reader and converter on small scripts; the shared golden fixtures cover the rest. */
class DenizenConverterTest {
    private static DenizenConverter.Output convert(String script) throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        scripts.put("a.dsc", script);
        return DenizenConverter.convert(new DenizenConverter.Input(scripts, Optional.empty(), Optional.empty(), new TreeMap<>()));
    }

    private static JsonObject document(DenizenConverter.Output output, String id) throws IOException {
        String text = output.files().get("behaviors/" + id.replace(':', '/') + ".json");
        assertNotNull(text, "no behavior " + id + " in " + output.files().keySet() + "\n" + output.report().get("preserved"));
        return JsonDocuments.object(text);
    }

    private static JsonObject behavior(String script, String id) throws IOException { return document(convert(script), id); }

    private static JsonObject main(String script) throws IOException { return behavior(script, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main"); }

    private static JsonObject step(JsonObject sequence, int index) { return sequence.getAsJsonArray("steps").get(index).getAsJsonObject(); }

    private static String reasonAt(DenizenConverter.Output output, String source) {
        for (JsonElement entry : output.report().getAsJsonArray("preserved"))
            if (entry.getAsJsonObject().get("source").getAsString().equals(source)) return entry.getAsJsonObject().get("reason").getAsString();
        return fail("nothing preserved at " + source + ": " + output.report().get("preserved"));
    }

    private static JsonObject object(String json) { return com.google.gson.JsonParser.parseString(json).getAsJsonObject(); }

    @Test void commandsKeepQuotesPrefixesAndTagsTogether() {
        DenizenArgs.Command command = DenizenArgs.command("~narrate \"Hi <player.name>\" targets:<player> format:\"a b\" <element[x y].length>");
        assertEquals("narrate", command.name());
        assertEquals(List.of("Hi <player.name>", "<player>", "a b", "<element[x y].length>"), command.args().stream().map(DenizenArgs.Arg::value).toList());
        assertEquals("targets", command.args().get(1).prefix());
        assertTrue(command.args().getFirst().quoted());
    }

    @Test void durationsRoundHalfUpToTicks() {
        assertEquals(40, DenizenArgs.ticks("2s").orElseThrow());
        assertEquals(1, DenizenArgs.ticks("0.025s").orElseThrow());
        assertEquals(60, DenizenArgs.ticks("3").orElseThrow());
        assertTrue(DenizenArgs.ticks("<[time]>").isEmpty());
    }

    @Test void flagFilesFlattenSubFlagsAndReadExpiry() {
        DenizenFlags.Values values = DenizenFlags.file("a:true\n\\exb:map@[__value=5;__expiration=time@2030/01/01_00:00:00:0000_+00:00]\nc:map@[__value=map@[d=map@[__value=e&scf]]]\nk\\cox:1\nm:map@[__value=map@[x=1;y=two]]\n");
        assertEquals(List.of("a", "b", "c.d", "k:x", "m"), List.copyOf(values.flags().keySet()));
        assertTrue(values.flags().get("b").expires());
        assertEquals(1_893_456_000_000L, values.flags().get("b").expiresAt());
        assertEquals("e;f", values.flags().get("c.d").value());
        assertEquals("map@[x=1;y=two]", values.flags().get("m").value(), "a __value map of plain values is the flag's map value");
    }

    @Test void flagValuesBecomeTypedJson() {
        assertEquals("boolean", DenizenFlags.typed("true").type());
        assertEquals("integer", DenizenFlags.typed("-12").type());
        assertEquals("decimal", DenizenFlags.typed("2.5").type());
        assertEquals("string", DenizenFlags.typed("Bob").type());
        DenizenFlags.Typed list = DenizenFlags.typed("li@gold|3|a&pipeb|");
        assertEquals("list", list.type());
        assertEquals("[\"gold\",3,\"a|b\"]", list.value().toString());
        DenizenFlags.Typed map = DenizenFlags.typed("map@[name=Ann;level=4;tags=li@x|y|]");
        assertEquals("map", map.type());
        assertEquals("{\"level\":4,\"name\":\"Ann\",\"tags\":[\"x\",\"y\"]}", map.value().toString());
        assertFalse(DenizenFlags.typed("li@" + "x|".repeat(300)).reason().isEmpty(), "lists above 256 items are refused");
        assertEquals(0, DenizenFlags.expiration("time@soon"));
    }

    @Test void definitionsAndConditionsBecomeExpressions() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - define count 3
                  - if <[count]> >= 2 && ( <player.has_flag[vip]> || <server.flag[open]> == true ):
                    - narrate "Count <[count].add[1]>"
                  - else if !<npc.is_spawned> || <player.name> matches st*|*ve:
                    - narrate none
                """);
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_count\",\"value\":3}"), step(main, 0));
        JsonObject condition = step(main, 1);
        assertEquals("(num(d_count) >= 2 && (has_flag('player', 'vip') || same(flag('global', 'open', ''), true)))", condition.get("expression").getAsString());
        assertEquals(object("{\"type\":\"action\",\"capability\":\"animus:message\",\"arguments\":{\"text\":null},\"templates\":{\"/text\":\"Count {{ (num(d_count) + 1) }}\"}}"), condition.getAsJsonObject("then"));
        assertEquals("(!(npc.spawned) || (starts_with(lower(player.name), 'st') || ends_with(lower(player.name), 've')))", condition.getAsJsonObject("else").get("expression").getAsString());
    }

    @Test void worldComparisonsResolveBothSidesOnEveryPlatform() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - if <player.world.name> == world_nether:
                    - narrate hot
                  - if <npc.location.world> != <player.world>:
                    - narrate far
                  - if <player.world.name.equals[world]>:
                    - narrate home
                  - if <player.world> matches world_the_end|skylands:
                    - narrate end
                  - if <player.name> == world:
                    - narrate named
                """);
        assertEquals("same_world(player.world, 'world_nether')", step(main, 0).get("expression").getAsString());
        assertEquals("!same_world(npc.world, player.world)", step(main, 1).get("expression").getAsString());
        assertEquals("same_world(player.world, 'world')", step(main, 2).get("expression").getAsString());
        assertEquals("(same_world(player.world, 'world_the_end') || same_world(player.world, 'skylands'))", step(main, 3).get("expression").getAsString());
        assertEquals("same(player.name, 'world')", step(main, 4).get("expression").getAsString());
    }

    @Test void inWorldSwitchesResolveTheWorld() throws IOException {
        JsonObject world = document(convert("""
                lobby:
                  type: world
                  events:
                    on player chats in:world_nether:
                    - narrate hot
                """), "denizen:world/lobby");
        assertEquals("same_world(player.world, 'world_nether')", world.getAsJsonObject("routines").getAsJsonObject("on-player-chats").get("expression").getAsString());
    }

    @Test void permissionAtomsStayCapabilityConditions() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - if <player.has_permission[town.gate]> && <player.flag[visits]||0> > 2:
                    - narrate yes
                  - else:
                    - narrate no
                """);
        assertEquals("animus:has-permission", main.get("capability").getAsString());
        assertEquals("num(flag('player', 'visits', 0)) > 2", main.getAsJsonObject("then").get("expression").getAsString());
        assertEquals("no", main.getAsJsonObject("else").getAsJsonObject("arguments").get("text").getAsString());
        assertEquals("no", main.getAsJsonObject("then").getAsJsonObject("else").getAsJsonObject("arguments").get("text").getAsString());
    }

    @Test void chooseReadsItsValueOnceIntoATemporary() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - choose <util.random.int[1].to[3]>:
                    - case 1:
                      - narrate one
                    - case 2 3:
                      - narrate more
                    - default:
                      - narrate none
                """);
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"expression\":\"random(1, 3)\"}}"), step(main, 0));
        JsonObject chain = step(main, 1);
        assertEquals("same(t_1, 1)", chain.get("expression").getAsString());
        assertEquals("(same(t_1, 2) || same(t_1, 3))", chain.getAsJsonObject("else").get("expression").getAsString());
        assertEquals("none", chain.getAsJsonObject("else").getAsJsonObject("else").getAsJsonObject("arguments").get("text").getAsString());
    }

    @Test void loopsMapWithTheirCountersAndStopAndNext() throws IOException {
        JsonObject document = behavior("""
                t:
                  type: task
                  script:
                  - foreach <list[a|b|c]> as:word:
                    - if <[word]> == b:
                      - foreach next
                    - narrate "<[loop_index]>: <[word]>"
                  - repeat <[times]> as:n:
                    - narrate "pass <[n]>"
                    - repeat stop
                  - while <[count]> < 5:
                    - define count:++
                  - foreach stop
                """, "denizen:task/t");
        JsonObject main = document.getAsJsonObject("routines").getAsJsonObject("main");
        JsonObject foreach = step(main, 0);
        assertEquals("['a', 'b', 'c']", foreach.getAsJsonObject("list").get("expression").getAsString());
        assertEquals("d_word", foreach.get("as").getAsString());
        assertEquals("i_1", foreach.get("index").getAsString());
        assertEquals("continue", step(foreach.getAsJsonObject("body"), 0).getAsJsonObject("then").get("type").getAsString());
        assertEquals("{{ (i_1 + 1) }}: {{ d_word }}", step(foreach.getAsJsonObject("body"), 1).getAsJsonObject("templates").get("/text").getAsString());
        JsonObject repeat = step(main, 1);
        assertEquals(object("{\"expression\":\"int(d_times)\"}"), repeat.get("count"));
        assertEquals(1024, repeat.get("maximum").getAsInt());
        assertEquals("i_2", repeat.get("index").getAsString());
        assertEquals("pass {{ (i_2 + 1) }}", step(repeat.getAsJsonObject("body"), 0).getAsJsonObject("templates").get("/text").getAsString());
        assertEquals("break", step(repeat.getAsJsonObject("body"), 1).get("type").getAsString());
        JsonObject loop = step(main, 2);
        assertEquals("while", loop.get("type").getAsString());
        assertEquals("num(d_count) < 5", loop.get("expression").getAsString());
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_count\",\"value\":{\"expression\":\"(num(d_count) + 1)\"}}"), loop.getAsJsonObject("body"));
        assertEquals("integer", document.getAsJsonObject("variables").getAsJsonObject("i_1").get("type").getAsString());
        assertEquals("any", document.getAsJsonObject("variables").getAsJsonObject("d_word").get("type").getAsString());
        assertEquals(4, main.getAsJsonArray("steps").size(), "foreach stop outside a loop is kept, and the routine ends there");
        assertEquals("return", step(main, 3).get("type").getAsString());
    }

    @Test void flagCommandsSetTypedValuesExpiryAndClear() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - flag player visits:++
                  - flag player tags:->:gold
                  - flag server boost expire:1h
                  - flag player met:!
                  - flag npc mood:<player.name>
                  - flag player score:*:2
                  - flag player words:|:a|b
                  - flag <[target]> x
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("(num(flag('player', 'visits', 0)) + 1)", step(main, 0).getAsJsonObject("expressions").get("/value").getAsString());
        assertTrue(step(main, 0).getAsJsonObject("arguments").get("value").isJsonNull());
        assertEquals("append(flag('player', 'tags', []), 'gold')", step(main, 1).getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals(object("{\"name\":\"boost\",\"scope\":\"global\",\"expireSeconds\":3600}"), step(main, 2).getAsJsonObject("arguments"));
        assertEquals(object("{\"name\":\"met\",\"scope\":\"player\",\"clear\":true}"), step(main, 3).getAsJsonObject("arguments"));
        assertEquals("player.name", step(main, 4).getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals("(num(flag('player', 'score', 0)) * 2)", step(main, 5).getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals("append(append(flag('player', 'words', []), 'a'), 'b')", step(main, 6).getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals("flags on other objects have no Animus equivalent", reasonAt(output, "a.dsc:11"));
    }

    @Test void presentationCommandsUseMessageTitleActionbarAndPlayerPlacement() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - narrate "<&a>Hi <player.name>" targets:<server.online_players>
                  - announce "Event!"
                  - title title:<&6>Guard subtitle:<player.name> fade_in:1s stay:2s
                  - actionbar "Logged"
                  - playsound <player> sound:entity_villager_yes
                  - playeffect effect:flame at:<player.location> quantity:4
                """);
        assertEquals(object("{\"text\":null,\"to\":\"all\"}"), step(main, 0).getAsJsonObject("arguments"));
        assertEquals("&aHi {{ player.name }}", step(main, 0).getAsJsonObject("templates").get("/text").getAsString());
        assertEquals(object("{\"type\":\"action\",\"capability\":\"animus:message\",\"arguments\":{\"text\":\"Event!\",\"to\":\"all\"}}"), step(main, 1));
        assertEquals(object("{\"title\":\"&6Guard\",\"subtitle\":null,\"fadeIn\":20,\"stay\":40}"), step(main, 2).getAsJsonObject("arguments"));
        assertEquals("animus:actionbar", step(main, 3).get("capability").getAsString());
        assertEquals(object("{\"sound\":\"minecraft:entity.villager.yes\",\"at\":\"player\"}"), step(main, 4).getAsJsonObject("arguments"));
        assertEquals(object("{\"particle\":\"minecraft:flame\",\"count\":4,\"at\":\"player\"}"), step(main, 5).getAsJsonObject("arguments"));
    }

    /** Sentinel is replaced by animus:combat, so its commands from Denizen become combat-control actions on the behavior's NPC. */
    @Test void sentinelCommandsBecomeCombatControl() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - execute as_server "sentinel addtarget player:<player.name> --id <npc.id>"
                  - execute as_server "sentinel removetarget player:<player.name>"
                  - execute as_op "sentinel forgive"
                  - execute as_server "sentinel guard <player.name>"
                  - execute as_server "sentinel guard"
                  - trait state:false sentinel
                  - trait state:true sentinel
                  - execute as_server "sentinel addtarget monsters"
                  - execute as_server "sentinel removetarget player:<player.name> --id 12"
                  - execute as_server "sentinel range 20"
                  - trait sentinel
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        List<String> operations = new java.util.ArrayList<>();
        for (JsonElement step : main.getAsJsonArray("steps")) {
            JsonObject action = step.getAsJsonObject();
            assertEquals("animus:combat-control", action.get("capability").getAsString(), action.toString());
            JsonObject arguments = action.getAsJsonObject("arguments");
            operations.add(arguments.get("operation").getAsString() + (arguments.has("target") ? " " + arguments.get("target").getAsString() : ""));
        }
        assertEquals(List.of("target player", "release", "release", "guard player", "guard", "disable", "enable"), operations);
        assertEquals("sentinel addtarget monsters: target types are animus:combat settings; set them in the NPC's animus:combat targets", reasonAt(output, "a.dsc:11"));
        assertEquals("sentinel names another NPC with --id; animus:combat acts on the behavior's own NPC", reasonAt(output, "a.dsc:12"));
        assertEquals("sentinel range has no combat action; set it in the NPC's animus:combat settings", reasonAt(output, "a.dsc:13"));
        assertEquals("trait toggles Sentinel without saying on or off; use state:true or state:false", reasonAt(output, "a.dsc:14"));
        assertFalse(output.report().getAsJsonArray("commandBehaviors").toString().contains("denizen:task/t"), "no server command is left to allow");
    }

    @Test void quotedTitleArgumentsKeepTheirPrefix() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - title "title:<&6>Town gate" "subtitle:Welcome home" stay:2s
                """);
        assertEquals(object("{\"title\":\"&6Town gate\",\"subtitle\":\"Welcome home\",\"stay\":40}"), main.getAsJsonObject("arguments"));
    }

    @Test void aSpaceAloneStaysABlankTitleAndABlankBookAuthorFallsBack() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - title title:<&sp> subtitle:Welcome
                  - actionbar <&sp>
                  - give blank_book
                blank_book:
                  type: book
                  title: <&sp>
                  author: <&sp>
                  text:
                  - Hello.
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"title\":\" \",\"subtitle\":\"Welcome\"}"), step(main, 0).getAsJsonObject("arguments"));
        assertEquals(object("{\"text\":\" \"}"), step(main, 1).getAsJsonObject("arguments"));
        assertEquals(object("{\"title\":\"blank_book\",\"author\":\"Server\",\"pages\":[\"Hello.\"]}"), step(main, 2).getAsJsonObject("arguments"));
    }

    @Test void itemsBooksTeleportAndAdjustUseTheNewActions() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - give diamond quantity:2
                  - give lucky_coin
                  - take item:dirt quantity:<[n]>
                  - give guide_book
                  - teleport <player> l@10,64,-5,world_nether
                  - teleport <npc> <player.location>
                  - adjust <npc> name:Captain
                  - adjust <npc> item_in_hand:iron_sword
                  - adjust <player> show_book:guide_book
                  - adjust <npc> glowing:true
                lucky_coin:
                  type: item
                  material: gold_nugget
                  display name: <&e>Lucky coin
                  lore:
                  - Found in a well
                guide_book:
                  type: book
                  title: Town guide
                  author: Mayor
                  signed: true
                  text:
                  - Welcome, <player.name>.
                  - The gate opens at dawn.
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"item\":\"minecraft:diamond\",\"amount\":2}"), step(main, 0).getAsJsonObject("arguments"));
        assertEquals(object("{\"item\":\"minecraft:gold_nugget\",\"name\":\"&eLucky coin\",\"lore\":[\"Found in a well\"]}"), step(main, 1).getAsJsonObject("arguments"));
        assertEquals("int(d_n)", step(main, 2).getAsJsonObject("expressions").get("/amount").getAsString());
        JsonObject book = step(main, 3);
        assertEquals("animus:give-book", book.get("capability").getAsString());
        assertEquals(object("{\"title\":\"Town guide\",\"author\":\"Mayor\",\"pages\":[null,\"The gate opens at dawn.\"]}"), book.getAsJsonObject("arguments"));
        assertEquals("Welcome, {{ player.name }}.", book.getAsJsonObject("templates").get("/pages/0").getAsString());
        assertEquals(object("{\"x\":10.0,\"y\":64.0,\"z\":-5.0,\"world\":\"world_nether\"}"), step(main, 4).getAsJsonObject("arguments"));
        assertEquals(object("{\"target\":\"npc\",\"to\":\"player\"}"), step(main, 5).getAsJsonObject("arguments"));
        assertEquals(object("{\"name\":\"Captain\"}"), step(main, 6).getAsJsonObject("arguments"));
        assertEquals("animus:set-equipment", step(main, 7).get("capability").getAsString());
        assertEquals("animus:give-book", step(main, 8).get("capability").getAsString());
        assertEquals("NPC mechanism glowing has no Animus equivalent", reasonAt(output, "a.dsc:13"));
        JsonObject server = document(output, "denizen:book/guide_book");
        assertTrue(server.get("server").getAsBoolean());
        assertEquals("animus:give-book", server.getAsJsonObject("routines").getAsJsonObject("main").get("capability").getAsString());
        assertEquals(List.of("denizen:book/guide_book"), output.report().getAsJsonArray("serverBehaviors").asList().stream().map(JsonElement::getAsString).toList());
    }

    @Test void runPassesDefinitionsAndProceduresReturnValues() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - run helper def:5|<player.name> save:out
                  - narrate "<entry[out].created_queue.determination.first>"
                  - run helper def.label:x
                  - narrate "Double: <proc[double].context[<[n]>]>"
                helper:
                  type: task
                  definitions: amount|label
                  script:
                  - determine <[amount].mul[<[label].length>]>
                double:
                  type: procedure
                  definitions: number
                  script:
                  - determine <[number].mul[2]>
                """);
        JsonObject routines = document(output, "denizen:task/t").getAsJsonObject("routines");
        JsonObject main = routines.getAsJsonObject("main");
        assertEquals(object("{\"type\":\"call\",\"routine\":\"task-helper\",\"arguments\":{\"d_amount\":5,\"d_label\":{\"expression\":\"player.name\"}},\"result\":\"r_out\"}"), step(main, 0));
        assertEquals("{{ r_out }}", step(main, 1).getAsJsonObject("templates").get("/text").getAsString());
        assertEquals(object("{\"type\":\"call\",\"routine\":\"task-helper\",\"arguments\":{\"d_label\":\"x\"}}"), step(main, 2));
        assertEquals(object("{\"type\":\"call\",\"routine\":\"proc-double\",\"arguments\":{\"d_number\":{\"expression\":\"d_n\"}},\"result\":\"t_1\"}"), step(main, 3));
        assertEquals("Double: {{ t_1 }}", step(main, 4).getAsJsonObject("templates").get("/text").getAsString());
        assertEquals(object("{\"type\":\"return\",\"value\":{\"expression\":\"(num(d_number) * 2)\"}}"), routines.getAsJsonObject("proc-double"));
        assertEquals(object("{\"type\":\"return\",\"value\":{\"expression\":\"(num(d_amount) * len(d_label))\"}}"), routines.getAsJsonObject("task-helper"));
    }

    @Test void stopBecomesReturnAndDeadLinesAreKept() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - if <player.has_flag[seen]>:
                    - narrate "Again."
                    - stop
                    - narrate "Never."
                  - narrate "First."
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("return", step(step(main, 0).getAsJsonObject("then"), 1).get("type").getAsString());
        assertEquals("First.", step(main, 1).getAsJsonObject("arguments").get("text").getAsString());
        assertEquals("never runs: after stop", reasonAt(output, "a.dsc:7"));
    }

    @Test void worldScriptsBecomeServerBehaviorsWithMappedEvents() throws IOException {
        DenizenConverter.Output output = convert("""
                lobby:
                  type: world
                  events:
                    after player joins flagged:vip permission:lobby.vip:
                    - narrate "Welcome back, <player.name>."
                    on player chats message:hello*:
                    - narrate "You said <context.message>"
                    on delta time minutely every:5:
                    - announce "Five minutes."
                    on player changes world from world to world_nether:
                    - narrate "Hot."
                    on player fishes:
                    - narrate "No."
                    on player chats:
                    - determine cancelled
                """);
        JsonObject world = document(output, "denizen:world/lobby");
        assertTrue(world.get("server").getAsBoolean());
        JsonArray triggers = world.getAsJsonArray("triggers");
        assertEquals(object("{\"id\":\"on-player-joins\",\"event\":\"player_join\",\"routine\":\"on-player-joins\"}"), triggers.get(0));
        assertEquals(object("{\"id\":\"on-player-chats\",\"event\":\"player_chat\",\"pattern\":\"hello*\",\"routine\":\"on-player-chats\"}"), triggers.get(1));
        assertEquals(object("{\"id\":\"on-delta-time-minutely\",\"event\":\"timer\",\"intervalTicks\":6000,\"routine\":\"on-delta-time-minutely\"}"), triggers.get(2));
        assertEquals(object("{\"id\":\"on-player-changes-world-from-world-to-world_nether\",\"event\":\"player_world_change\",\"from\":\"world\",\"to\":\"world_nether\",\"routine\":\"on-player-changes-world-from-world-to-world_nether\"}"), triggers.get(3));
        JsonObject joins = world.getAsJsonObject("routines").getAsJsonObject("on-player-joins");
        assertEquals("has_flag('player', 'vip')", joins.get("expression").getAsString());
        assertEquals("animus:has-permission", joins.getAsJsonObject("then").get("capability").getAsString());
        assertEquals("You said {{ context.message }}", world.getAsJsonObject("routines").getAsJsonObject("on-player-chats").getAsJsonObject("templates").get("/text").getAsString());
        assertEquals("no Animus server trigger for the event player fishes", reasonAt(output, "a.dsc:12"));
        assertEquals("return", world.getAsJsonObject("routines").getAsJsonObject("on-player-chats-2").get("type").getAsString());
        assertTrue(reasonAt(output, "a.dsc:15").startsWith("determine changes the event's outcome"));
    }

    @Test void serverBehaviorsRefuseNpcCommands() throws IOException {
        DenizenConverter.Output output = convert("""
                lobby:
                  type: world
                  events:
                    on server start:
                    - chat "Hello"
                    - narrate "<npc.name>"
                    - flag server started
                """);
        assertEquals("chat makes the NPC speak; server behaviors have no NPC", reasonAt(output, "a.dsc:5"));
        assertEquals("tag npc.name needs an NPC; server behaviors have none", reasonAt(output, "a.dsc:6"));
        JsonObject start = document(output, "denizen:world/lobby").getAsJsonObject("routines").getAsJsonObject("on-server-start");
        assertEquals("animus:set-flag", start.get("capability").getAsString());
    }

    @Test void commandScriptsRegisterACommandTrigger() throws IOException {
        DenizenConverter.Output output = convert("""
                warp_command:
                  type: command
                  name: warp
                  description: Warps you
                  usage: /warp <&lt>name<&gt>
                  aliases:
                  - w
                  permission: town.warp
                  tab completions:
                    1: spawn|market
                  script:
                  - if <context.args.size> == 0:
                    - narrate "Usage: /warp name"
                    - stop
                  - narrate "Warping to <context.args.get[1]>"
                """);
        JsonObject command = document(output, "denizen:command/warp_command");
        assertTrue(command.get("server").getAsBoolean());
        assertEquals(object("{\"id\":\"command\",\"event\":\"command\",\"name\":\"warp\",\"aliases\":[\"w\"],\"permission\":\"town.warp\",\"description\":\"Warps you\",\"usage\":\"/warp <&lt>name<&gt>\",\"routine\":\"main\"}"), command.getAsJsonArray("triggers").get(0));
        JsonObject main = command.getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("same(len(context.args), 0)", step(main, 0).get("expression").getAsString());
        assertEquals("Warping to {{ get(context.args, 1 - 1) }}", step(main, 1).getAsJsonObject("templates").get("/text").getAsString());
        assertEquals("command setting tab completions has no Animus equivalent", reasonAt(output, "a.dsc:9"));
    }

    @Test void dataConstantsAreInlinedAtTheirReads() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - narrate "Bring <script[prices].data_key[item]>"
                  - define list <script[prices].data_key[stock]>
                  - define table <script[prices].data_key[table]>
                prices:
                  type: data
                  item: iron ingots
                  stock:
                  - bread
                  - 5
                  table:
                    bread: 2
                    cake: 7
                """);
        assertEquals("Bring iron ingots", step(main, 0).getAsJsonObject("arguments").get("text").getAsString());
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_list\",\"value\":{\"expression\":\"['bread', 5]\"}}"), step(main, 1));
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"bread\":2,\"cake\":7}}"), step(main, 2));
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_table\",\"value\":{\"expression\":\"t_1\"}}"), step(main, 3));
    }

    @Test void inventoryScriptsBecomeChestGuisOpenedByOpenGui() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - inventory open d:bakery_menu
                bakery_menu:
                  type: inventory
                  inventory: chest
                  title: <&8>Bakery
                  size: 18
                  gui: true
                  definitions:
                    pane: gray_stained_glass_pane
                  slots:
                  - [pane] [] [bread[quantity=3]] [lucky_coin] [] [] [] [] [pane]
                lucky_coin:
                  type: item
                  material: gold_nugget
                  display name: Coin
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"action\",\"capability\":\"animus:open-gui\",\"arguments\":{\"gui\":\"denizen:bakery_menu\"}}"), main);
        String gui = output.files().get("guis/denizen/bakery_menu.json");
        assertNotNull(gui);
        JsonObject parsed = JsonDocuments.object(gui);
        assertEquals("9x2", parsed.get("resolution").getAsString());
        assertEquals("&8Bakery", parsed.get("title").getAsString());
        assertEquals("[\"A BC    A\",\"         \"]", parsed.getAsJsonArray("mask").toString());
        assertEquals(3, parsed.getAsJsonObject("keys").getAsJsonObject("B").getAsJsonObject("icon").get("count").getAsInt());
        GuiDocument.Result checked = GuiDocument.parse("denizen:bakery_menu", gui);
        assertTrue(checked.problems().isEmpty(), checked.problems().toString());
        assertEquals("[\"guis/denizen/bakery_menu.json\"]", output.report().getAsJsonArray("guis").toString());
    }

    @Test void onAssignmentRunsOnceOnSpawnBehindAnImportedGuard() throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        scripts.put("a.dsc", """
                keeper:
                  type: assignment
                  actions:
                    on assignment:
                    - trigger name:click state:true
                    - equip <npc> hand:iron_sword
                    - flag npc ready
                    on click:
                    - narrate hi
                """);
        String save = """
                npc:
                  0:
                    name: Keeper
                    uuid: 00000000-0000-4000-8000-000000000001
                    traits:
                      assignment:
                        assignment: keeper
                """;
        DenizenConverter.Output output = DenizenConverter.convert(new DenizenConverter.Input(scripts, Optional.of(save), Optional.empty(), new TreeMap<>()));
        JsonObject keeper = document(output, "denizen:assignment/keeper");
        JsonObject routine = keeper.getAsJsonObject("routines").getAsJsonObject("on-assignment");
        assertEquals("!has_flag('npc', 'assigned-keeper')", routine.get("expression").getAsString());
        assertEquals(object("{\"name\":\"assigned-keeper\",\"scope\":\"npc\"}"), step(routine.getAsJsonObject("then"), 0).getAsJsonObject("arguments"));
        assertEquals(3, routine.getAsJsonObject("then").getAsJsonArray("steps").size());
        assertTrue(keeper.getAsJsonArray("triggers").contains(object("{\"id\":\"on-assignment\",\"event\":\"spawn\",\"routine\":\"on-assignment\"}")));
        JsonObject state = JsonDocuments.object(output.files().get("state/behavior-state/npc/00000000-0000-4000-8000-000000000001/animus/flags/denizen.json"));
        assertEquals(object("{\"assigned-keeper\":{\"type\":\"boolean\",\"value\":true}}"), state.getAsJsonObject("values"));
    }

    @Test void flagDataImportsTypedValuesWithExpiry() throws IOException {
        TreeMap<String, String> players = new TreeMap<>();
        players.put("00000000-0000-4000-8000-0000000000a1", "visits:2\nnickname:Bob\nbag:li@apple|pear|\n\\exboost:map@[__value=true;__expiration=time@2030/01/01_00:00:00:0000_+00:00]\n__secret:1\n");
        DenizenConverter.Output output = DenizenConverter.convert(new DenizenConverter.Input(new TreeMap<>(), Optional.empty(), Optional.of("open:false\nratio:0.5\n"), players));
        JsonObject player = JsonDocuments.object(output.files().get("state/behavior-state/player/00000000-0000-4000-8000-0000000000a1/animus/flags/denizen.json")).getAsJsonObject("values");
        assertEquals(object("{\"type\":\"list\",\"value\":[\"apple\",\"pear\"]}"), player.get("bag"));
        assertEquals(object("{\"type\":\"boolean\",\"value\":true,\"expires\":1893456000000}"), player.get("boost"));
        assertEquals(object("{\"type\":\"string\",\"value\":\"Bob\"}"), player.get("nickname"));
        assertEquals(object("{\"type\":\"integer\",\"value\":2}"), player.get("visits"));
        JsonObject global = JsonDocuments.object(output.files().get("state/behavior-state/global/animus/flags/denizen.json")).getAsJsonObject("values");
        assertEquals(object("{\"type\":\"decimal\",\"value\":0.5}"), global.get("ratio"));
        assertEquals("Denizen's own bookkeeping flag", reasonAt(output, "player_flags/00000000-0000-4000-8000-0000000000a1.dat"));
        assertEquals(6, output.report().getAsJsonObject("flags").getAsJsonObject("imported").get("player").getAsInt() + output.report().getAsJsonObject("flags").getAsJsonObject("imported").get("global").getAsInt());
    }

    @Test void unconvertibleLinesAreKeptWithTheirReason() throws IOException {
        DenizenConverter.Output output = convert("t:\n  type: task\n  script:\n  - narrate \"<player.money>\"\n  - narrate ok\n");
        JsonObject kept = output.report().getAsJsonArray("preserved").get(0).getAsJsonObject();
        assertEquals("a.dsc:4", kept.get("source").getAsString());
        assertEquals("tag player.money has no Animus expression", kept.get("reason").getAsString());
        assertEquals("partial", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString());
        assertEquals(8, output.report().get("converterVersion").getAsInt());
    }

    @Test void limitsCountLoopBodiesAndMissingElses() {
        JsonObject document = object("{\"routines\":{\"main\":{\"type\":\"foreach\",\"list\":{\"expression\":\"[]\"},\"as\":\"x\",\"body\":{\"type\":\"condition\",\"expression\":\"true\",\"then\":{\"type\":\"break\"}}}}}");
        assertTrue(BehaviorLimits.check(document, document.toString()).isEmpty());
        JsonObject deep = object("{\"type\":\"break\"}");
        for (int depth = 0; depth < 33; depth++) deep = object("{\"type\":\"while\",\"expression\":\"true\",\"body\":" + deep + "}");
        JsonObject nested = new JsonObject();
        JsonObject routines = new JsonObject();
        routines.add("main", deep);
        nested.add("routines", routines);
        assertEquals(Optional.of("nests deeper than 32"), BehaviorLimits.check(nested, nested.toString()));
        JsonObject variables = new JsonObject();
        for (int index = 0; index < 129; index++) variables.add("v" + index, object("{\"type\":\"any\",\"initial\":\"\"}"));
        document.add("variables", variables);
        assertEquals(Optional.of("has more than 128 variables"), BehaviorLimits.check(document, document.toString()));
    }

    private static JsonObject clickTrigger(String events) throws IOException {
        return behavior("w:\n    type: world\n    events:\n" + events, "denizen:world-npc/w").getAsJsonArray("triggers").get(0).getAsJsonObject();
    }

    @Test void npcClickEventsBecomeAnNpcBehaviorThatConsumesWhereTheyCancel() throws IOException {
        JsonObject trigger = clickTrigger("""
                        on player right clicks npc:
                        - if !<npc.has_flag[ambient]||false>:
                            - stop
                        - determine cancelled
                """);
        assertEquals("interaction", trigger.get("event").getAsString());
        assertEquals("has_flag('npc', 'ambient')", trigger.get("consume").getAsString());
        assertFalse(trigger.has("cooldownTicks"));
    }

    @Test void clickEventsThatNeverCancelOrCannotDecideInTimeConsumeNothing() throws IOException {
        assertFalse(clickTrigger("""
                        on player right clicks entity:
                        - narrate "Hi"
                """).has("consume"));
        assertFalse(clickTrigger("""
                        on player right clicks entity:
                        - if !<player.has_permission[town.talk]>:
                            - stop
                        - determine cancelled
                """).has("consume"), "a permission check is a capability, not a click-time expression");
        assertFalse(clickTrigger("""
                        on player right clicks entity:
                        - wait 1t
                        - determine passively cancelled
                """).has("consume"), "after a wait the click is long gone");
    }

    @Test void aCancelAfterAPlainCommandStillConsumesAndPassivelyGoesOn() throws IOException {
        JsonObject behavior = behavior("""
                w:
                    type: world
                    events:
                        on player right clicks entity:
                        - if !<player.is_sneaking>:
                            - actionbar "Sneak to talk"
                            - stop
                        - ratelimit <player> 3s
                        - determine passively cancelled
                        - narrate "Talking"
                """, "denizen:world-npc/w");
        JsonObject trigger = behavior.getAsJsonArray("triggers").get(0).getAsJsonObject();
        assertEquals("context.sneaking && !has_flag('player', 'ratelimit-w-8')", trigger.get("consume").getAsString(), "a rate-limited click is left to shops, as Denizen stops before it cancels");
        JsonObject routine = behavior.getAsJsonObject("routines").getAsJsonObject("on-player-right-clicks-entity");
        assertEquals("animus:actionbar", step(step(routine, 0).getAsJsonObject("then"), 0).get("capability").getAsString(), "a sneak prompt stays on the action bar");
        assertEquals("has_flag('player', 'ratelimit-w-8')", step(routine, 1).get("expression").getAsString());
        assertEquals(3, step(routine, 2).getAsJsonObject("arguments").get("expireSeconds").getAsInt());
        assertEquals("animus:message", step(routine, 3).get("capability").getAsString());
    }

    @Test void runningTheTaskAFlagNamesChoosesAmongTheTasksScriptsGiveIt() throws IOException {
        DenizenConverter.Output output = convert("""
                w:
                    type: world
                    events:
                        on player right clicks npc:
                        - run <npc.flag[story]>
                a_assignment:
                    type: assignment
                    actions:
                        on assignment:
                        - flag <npc> story:first_story
                first_story:
                    type: task
                    script:
                    - narrate "One"
                second_story:
                    type: task
                    script:
                    - narrate "Two"
                b_task:
                    type: task
                    script:
                    - flag npc story:second_story
                """);
        JsonObject routines = document(output, "denizen:world-npc/w").getAsJsonObject("routines");
        JsonObject chosen = routines.getAsJsonObject("on-player-right-clicks-npc");
        assertEquals("same(lower(text(flag('npc', 'story', ''))), 'first_story')", chosen.get("expression").getAsString());
        assertEquals("task-first_story", chosen.getAsJsonObject("then").get("routine").getAsString());
        assertEquals("task-second_story", chosen.getAsJsonObject("else").getAsJsonObject("then").get("routine").getAsString());
        assertTrue(routines.has("task-second_story"));
    }

    @Test void flagNamesMayJoinTextAndTags() throws IOException {
        JsonObject condition = step(main("""
                t:
                    type: task
                    script:
                    - if <player.has_flag[done_<npc.id>]>:
                        - stop
                    - narrate "x"
                """), 0);
        assertEquals("has_flag('player', ('done_' + text(npc.citizens_id)))", condition.get("expression").getAsString());
    }

    @Test void writtenFlagNamesWithTagsMatchTheirReadsAndImportedNames() throws IOException {
        JsonObject write = main("t:\n  type: task\n  script:\n  - flag player done_<npc.id>\n");
        assertTrue(write.getAsJsonObject("arguments").get("name").isJsonNull());
        assertEquals("('done_' + text(npc.citizens_id))", write.getAsJsonObject("expressions").get("/name").getAsString());
    }

    @Test void notesReadCuboidsAndEllipsoidsInBothForms() {
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6}, DenizenEvents.note("a", "cu@world,1,2,3,4,5,6").orElseThrow().bounds());
        assertEquals("world_nether", DenizenEvents.note("a", "cu@1,2,3,world_nether|4,5,6,world_nether").orElseThrow().world());
        assertFalse(DenizenEvents.note("a", "ellipsoid@0,64,0,world,5,3,5").orElseThrow().cuboid());
        assertTrue(DenizenEvents.note("a", "<cuboid[world,0,0,0,1,1,1]>").isPresent());
        assertTrue(DenizenEvents.note("a", "cu@world,0,0,0,1,1,1,2,2,2,3,3,3").isEmpty(), "a cuboid of several boxes is kept");
    }

    private static String fields(String key) {
        DenizenEvents.Mapped mapped = DenizenEvents.event(key, Map.of(), false, false);
        return mapped.reason() != null ? mapped.reason() : mapped.trigger() + (mapped.cancellable() ? " cancellable" : "");
    }

    @Test void projectileAndBowEventsBecomeProjectileTriggers() {
        assertEquals("{\"event\":\"projectile_hit\"} cancellable", fields("on projectile hits"));
        assertEquals("{\"event\":\"projectile_hit\",\"projectile\":\"arrow\",\"block\":\"*:*\"} cancellable", fields("on arrow hits block"));
        assertEquals("{\"event\":\"projectile_hit\",\"projectile\":\"snowball\",\"entity\":\"*:*\"} cancellable", fields("on snowball hits entity"));
        assertEquals("{\"event\":\"projectile_hit\",\"projectile\":\"trident\",\"entity\":\"zombie\"} cancellable", fields("on trident collides with zombie"));
        assertEquals("{\"event\":\"projectile_hit\",\"shooter\":\"player\",\"block\":\"target\"} cancellable", fields("on player shoots target"));
        assertEquals("{\"event\":\"projectile_hit\",\"entity\":\"creeper\",\"shooter\":\"skeleton\"} cancellable", fields("on projectile hits entity:creeper shooter:skeleton"));
        assertEquals("{\"event\":\"projectile_launch\",\"projectile\":\"ender_pearl\"} cancellable", fields("on ender_pearl launched"));
        assertEquals("{\"event\":\"entity_shoot_bow\",\"shooter\":\"player\",\"bow\":\"bow\"} cancellable", fields("on player shoots bow"));
        assertEquals("{\"event\":\"entity_shoot_bow\",\"shooter\":\"pillager\",\"bow\":\"crossbow\"} cancellable", fields("on pillager shoots item with:crossbow"));
        assertEquals("{\"event\":\"entity_shoot_bow\",\"bow\":\"bow\"} cancellable",fields("on entity shoots bow"));
        assertTrue(fields("on npc shoots bow").startsWith("Animus NPCs shoot combat helper projectiles"));
        assertTrue(fields("on arrow hits npc").startsWith("Animus NPCs are virtual bodies"));
        assertTrue(fields("on projectile hits entity:npc").startsWith("Animus NPCs are virtual bodies"));
        assertEquals("switch with: has no Animus equivalent on this event", fields("on projectile hits with:bow"));
        assertEquals("switch entity: has no Animus equivalent on this event", fields("on arrow launched entity:zombie"));
        assertEquals(List.of(new DenizenExpressions.Atom("same_world(context.world, 'world_nether')")), DenizenEvents.event("on projectile hits in:world_nether", Map.of(), false, false).guards());
    }

    @Test void projectileEventsReadTheirContextAndCancel() throws IOException {
        JsonObject behavior = behavior("""
                w:
                    type: world
                    events:
                        on player shoots bow:
                        - if <context.force> >= 1:
                            - stop
                        - narrate "Weak <context.bow.material.name> shot from <context.entity.name>"
                        - determine cancelled
                        on arrow hits entity:
                        - narrate "<context.projectile.uuid> hit <context.hit_entity.entity_type> shot by <context.shooter.entity_type>"
                        on arrow hits block:
                        - narrate "Struck <context.hit_block.material.name> at <context.hit_block.x>"
                """, "denizen:world/w");
        JsonArray triggers = behavior.getAsJsonArray("triggers");
        assertEquals("!(context.force >= 1)",triggers.get(0).getAsJsonObject().get("cancel").getAsString());
        String text = behavior.getAsJsonObject("routines").toString();
        assertTrue(text.contains("replace(context.bow, 'minecraft:', '')"), text);
        assertTrue(text.contains("replace(context.shooter, 'minecraft:', '')"), text);
        assertTrue(text.contains("context.projectile_uuid"), text);
        assertTrue(text.contains("replace(context.entity, 'minecraft:', '')"), text);
        assertTrue(text.contains("replace(context.block, 'minecraft:', '')") && text.contains("context.x"), text);
    }

    @Test void aSneakingOnlyClickOnTheNpcBecomesASneakInteractTrigger() throws IOException {
        JsonObject behavior = behavior("""
                w:
                    type: world
                    events:
                        on player right clicks entity:
                        - if !<context.entity.is_npc||false>:
                            - stop
                        - if !<player.is_sneaking>:
                            - stop
                        - narrate "Psst."
                        on player damages entity:
                        - if !<context.entity.is_npc||false>:
                            - stop
                        - narrate "Ow."
                """, "denizen:world-npc/w");
        JsonArray triggers = behavior.getAsJsonArray("triggers");
        assertEquals("sneak_interact", triggers.get(0).getAsJsonObject().get("action").getAsString());
        assertEquals("attack", triggers.get(1).getAsJsonObject().get("action").getAsString(), "is_npc with a fallback still marks the entity as the NPC");
        JsonObject shown = behavior("""
                w:
                    type: world
                    events:
                        on player right clicks entity:
                        - if !<player.is_sneaking>:
                            - narrate "Sneak to talk"
                            - stop
                        - narrate "Psst."
                """, "denizen:world-npc/w");
        assertEquals("interact", shown.getAsJsonArray("triggers").get(0).getAsJsonObject().get("action").getAsString(), "a plain click still does something");
    }

    @Test void interactCooldownsAndEngageBecomeTriggerSettings() throws IOException {
        DenizenConverter.Output output = convert("""
                q:
                    type: interact
                    steps:
                        start*:
                            click trigger:
                                script:
                                - engage
                                - cooldown 10s
                                - narrate "Hello."
                                - disengage
                """);
        JsonObject click = document(output, "denizen:interact/q").getAsJsonArray("triggers").get(0).getAsJsonObject();
        assertEquals(200, click.get("playerCooldownTicks").getAsInt());
        assertFalse(click.has("cooldownTicks"));
        JsonObject global = document(convert("q:\n    type: interact\n    steps:\n        start*:\n            click trigger:\n                script:\n                - cooldown 3s global\n                - narrate \"Hi\"\n"),
                "denizen:interact/q").getAsJsonArray("triggers").get(0).getAsJsonObject();
        assertEquals(60, global.get("cooldownTicks").getAsInt());
    }

    @Test void dropAndSwapEventsAtATargetedNpcBecomeNpcKeyTriggers() throws IOException {
        JsonObject behavior = behavior("""
                w:
                    type: world
                    events:
                        on player drops item:
                        - if !<player.target.is_npc||false>:
                            - stop
                        - if !<player.is_sneaking>:
                            - stop
                        - determine cancelled
                        on player swaps items:
                        - if !<player.target.is_npc||false>:
                            - stop
                        - narrate "Swapped at <player.target.name>"
                """, "denizen:world-npc/w");
        JsonArray triggers = behavior.getAsJsonArray("triggers");
        JsonObject drop = triggers.get(0).getAsJsonObject();
        assertEquals("interaction", drop.get("event").getAsString());
        assertEquals("drop", drop.get("action").getAsString());
        assertTrue(drop.get("consume").getAsString().contains("context.sneaking"), drop.toString());
        assertEquals("swap_hands", triggers.get(1).getAsJsonObject().get("action").getAsString());
        assertEquals("false", triggers.get(1).getAsJsonObject().get("consume").getAsString(), "Denizen let the swap happen");
        assertTrue(behavior.getAsJsonObject("routines").toString().contains("npc.name"), behavior.toString());
        assertEquals("{\"event\":\"player_item_drop\"} cancellable", fields("on player drops item"), "without a targeted NPC a drop stays a server event");
    }

    @Test void playerListTargetsFanOutWithinOneHundredPlayers() throws IOException {
        JsonObject loop = main("t:\n  type: task\n  script:\n  - title \"title:Hi\" targets:<server.online_players.filter[is_op]>\n");
        assertEquals("foreach", loop.get("type").getAsString());
        assertEquals("players(server.players)", loop.getAsJsonObject("list").get("expression").getAsString());
        assertEquals(100, loop.get("maximum").getAsInt());
        JsonObject guarded = loop.getAsJsonObject("body");
        assertEquals("get(player_info(t_1), 'op', false)", guarded.get("expression").getAsString());
        assertEquals("t_1", guarded.getAsJsonObject("then").getAsJsonObject("expressions").get("/player").getAsString());
    }
}
