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
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_count\",\"value\":{\"expression\":\"(num((same(text(d_count), '') ? 0 : d_count)) + 1)\"}}"), loop.getAsJsonObject("body"));
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
                  - flag <player.location> x
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
        assertEquals(9, output.report().get("converterVersion").getAsInt());
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
        assertEquals("has_flag('player', replace(lower(('done_' + text(npc.citizens_id))), '.', '-'))", condition.get("expression").getAsString());
    }

    @Test void writtenFlagNamesWithTagsMatchTheirReadsAndImportedNames() throws IOException {
        JsonObject write = main("t:\n  type: task\n  script:\n  - flag player done_<npc.id>\n");
        assertTrue(write.getAsJsonObject("arguments").get("name").isJsonNull());
        assertEquals("replace(lower(('done_' + text(npc.citizens_id))), '.', '-')", write.getAsJsonObject("expressions").get("/name").getAsString());
        JsonObject whole = main("t:\n  type: task\n  definitions: quest\n  script:\n  - flag player <[quest]>:3\n");
        assertEquals("replace(lower(text(d_quest)), '.', '-')", whole.getAsJsonObject("expressions").get("/name").getAsString(), "a name that is all tag converts too");
    }

    @Test void computedSoundNamesBecomeKeysAsTheSoundPlays() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: note
                  script:
                  - playsound <player> sound:<[note]> sound_category:records volume:0.5
                  - narrate ""
                """);
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"expression\":\"lower(text(d_note))\"}}"), step(main, 0));
        JsonObject sound = step(main, 1);
        assertEquals("(contains(t_1, ':') ? t_1 : 'minecraft:' + (contains(t_1, '.') ? t_1 : first(split(t_1, '_')) + '.' + join(slice(split(t_1, '_'), 1, len(split(t_1, '_')) - 1), '_') + '.' + last(split(t_1, '_'))))",
                sound.getAsJsonObject("expressions").get("/sound").getAsString());
        assertEquals(0.5, sound.getAsJsonObject("arguments").get("volume").getAsDouble());
        assertEquals("&r", step(main, 2).getAsJsonObject("arguments").get("text").getAsString(), "a blank narrate is an empty line");
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

    @Test void animateArmSwingAndPosesBecomeTheAnimateAction() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - animate <npc> animation:arm_swing
                  - animate npc animation:STOP_SITTING
                  - animate <npc> animation:hurt
                  - animate <[someone]> animation:arm_swing
                  - animate <npc> animation:totem_resurrect
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"action\",\"capability\":\"animus:animate\",\"arguments\":{\"animation\":\"swing\"}}"), step(main, 0));
        assertEquals("stand", step(main, 1).getAsJsonObject("arguments").get("animation").getAsString());
        assertEquals("hurt", step(main, 2).getAsJsonObject("arguments").get("animation").getAsString());
        assertEquals("only NPCs held in a definition, such as an item of an NPC list, can be turned or animated", reasonAt(output, "a.dsc:7"));
        assertEquals("animation totem_resurrect has no Animus equivalent", reasonAt(output, "a.dsc:8"));
    }

    @Test void aQuotedDefineKeepsItsNameAndDataAction() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  script:
                  - define "lines:->:<&a>Hello <player.name>"
                  - define "greeting:Good morning, traveller"
                  - define "count:++"
                """);
        assertEquals("append((same(text(d_lines), '') ? [] : d_lines), ('&aHello ' + player.name))", step(main, 0).getAsJsonObject("value").get("expression").getAsString());
        assertEquals(object("{\"type\":\"set\",\"variable\":\"d_greeting\",\"value\":\"Good morning, traveller\"}"), step(main, 1));
        assertEquals("(num((same(text(d_count), '') ? 0 : d_count)) + 1)", step(main, 2).getAsJsonObject("value").get("expression").getAsString());
    }

    @Test void soundVolumeAndPitchTakeTagsAndClampLikeTheGame() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: loud
                  script:
                  - playsound <player> sound:block_note_block_bass volume:<[loud].mul[2]> pitch:3
                  - playsound <player> sound:entity_villager_no volume:6 pitch:<util.random.decimal[0.8].to[1.2]>
                """);
        JsonObject first = step(main, 0);
        assertEquals(object("{\"sound\":\"minecraft:block.note_block.bass\",\"volume\":null,\"pitch\":2.0,\"at\":\"player\"}"), first.getAsJsonObject("arguments"));
        assertEquals("clamp((num(d_loud) * 2), 0, 4)", first.getAsJsonObject("expressions").get("/volume").getAsString());
        JsonObject second = step(main, 1);
        assertEquals(4.0, second.getAsJsonObject("arguments").get("volume").getAsDouble());
        assertEquals("clamp((0.8 + random_decimal() * (1.2 - 0.8)), 0.5, 2)", second.getAsJsonObject("expressions").get("/pitch").getAsString());
    }

    @Test void fallbacksCoverDefinitionsListReadsAndDataKeys() throws IOException {
        JsonObject command = document(convert("""
                c:
                  type: command
                  name: pick
                  script:
                  - define a <[delay].if_null[5]>
                  - define b <context.args.first||none>
                  - define c <context.args.get[2].if_null[0]>
                  - define d <script[prices].data_key[missing]||7>
                  - define e <script[prices].data_key[item]||7>
                prices:
                  type: data
                  item: bread
                """), "denizen:command/c");
        JsonObject main = command.getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("(same(text(d_delay), '') ? 5 : d_delay)", step(main, 0).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("get(context.args, 0, 'none')", step(main, 1).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("get(context.args, 2 - 1, 0)", step(main, 2).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("7", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("'bread'", step(main, 4).getAsJsonObject("value").get("expression").getAsString());
    }

    @Test void aComputedDataKeyReadsTheHoistedMapIgnoringCase() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: item
                  script:
                  - define price <script[prices].data_key[<[item]>].if_null[0]>
                  - define tier <script[prices].data_key[tiers.<[item]>]>
                prices:
                  type: data
                  Sword: 12
                  shield: 5
                  tiers:
                    sword: gold
                """);
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"sword\":12,\"shield\":5,\"tiers\":{\"sword\":\"gold\"}}}"), step(main, 0));
        assertEquals("get(t_1, lower(text(d_item)), 0)", step(main, 1).getAsJsonObject("value").get("expression").getAsString());
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_2\",\"value\":{\"sword\":\"gold\"}}"), step(main, 2));
        assertEquals("get(t_2, lower(text(d_item)))", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
    }

    @Test void flagsOnPlayersHeldInADefinitionLoopOverThosePlayers() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: target
                  script:
                  - flag <[target]> quest.status:done
                  - flag <[target]> visits:++ expire:1h
                  - flag <server.online_players> event_seen
                  - flag <[target].location> marked
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"foreach\",\"list\":{\"expression\":\"players(d_target)\"},\"as\":\"t_1\",\"maximum\":100,\"body\":"
                + "{\"type\":\"action\",\"capability\":\"animus:set-flag\",\"arguments\":{\"name\":\"quest-status\",\"scope\":\"player\",\"value\":\"done\",\"player\":null},\"expressions\":{\"/player\":\"t_1\"}}}"), step(main, 0));
        JsonObject visits = step(main, 1).getAsJsonObject("body");
        assertEquals("(num(player_flag(t_2, 'visits', 0)) + 1)", visits.getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals(3600, visits.getAsJsonObject("arguments").get("expireSeconds").getAsInt());
        assertEquals("players(server.players)", step(main, 2).getAsJsonObject("list").get("expression").getAsString());
        assertEquals("flags on other objects have no Animus equivalent", reasonAt(output, "a.dsc:8"));
    }

    @Test void particlesTakeAHeightAndFollowPlayersHeldInDefinitions() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: target
                  script:
                  - playeffect effect:heart at:<player.location.above[2]> quantity:5 offset:0.3
                  - playeffect effect:villager_angry at:<[target].location.above[1.5]> quantity:3 targets:<[target]>
                  - playsound <[target].location> sound:entity_villager_yes
                  - playeffect effect:flame at:<[target].cursor_on>
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"particle\":\"minecraft:heart\",\"count\":5,\"spread\":0.3,\"height\":2.0,\"at\":\"player\"}"), step(main, 0).getAsJsonObject("arguments"));
        JsonObject angry = step(main, 1);
        assertEquals("players(d_target)", angry.getAsJsonObject("list").get("expression").getAsString());
        assertEquals(object("{\"particle\":\"minecraft:angry_villager\",\"count\":3,\"height\":1.5,\"at\":\"player\",\"player\":null}"), angry.getAsJsonObject("body").getAsJsonObject("arguments"));
        assertEquals("players(d_target)", step(main, 2).getAsJsonObject("list").get("expression").getAsString());
        assertEquals("particles show only at the NPC or a player in Animus", reasonAt(output, "a.dsc:8"));
    }

    @Test void inventoryChecksBecomeCanAffordConditions() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: target
                  script:
                  - if <player.inventory.contains_item[iron_ingot].quantity[3]>:
                    - take iron_ingot quantity:3
                  - if !<[target].inventory.contains_item[minecraft:diamond]>:
                    - narrate "No diamond"
                  - if <player.inventory.contains_item[<[target].flag[metal]>]>:
                    - narrate "You have it"
                """);
        JsonObject first = step(main, 0);
        assertEquals("animus:can-afford", first.get("capability").getAsString());
        assertEquals(object("{\"items\":[{\"item\":{\"id\":\"minecraft:iron_ingot\"},\"amount\":3}],\"match\":\"material\"}"), first.getAsJsonObject("arguments"));
        assertEquals("animus:take-item", first.getAsJsonObject("then").get("capability").getAsString());
        JsonObject second = step(main, 1);
        assertEquals("get(players(d_target), 0, '')", second.getAsJsonObject("expressions").get("/player").getAsString());
        assertTrue(second.getAsJsonObject("arguments").get("player").isJsonNull());
        assertEquals("animus:message", second.getAsJsonObject("else").get("capability").getAsString());
        JsonObject third = step(main, 2);
        assertEquals("(contains(lower(replace(text(player_flag(d_target, 'metal', '')), 'i@', '')), ':') ? lower(replace(text(player_flag(d_target, 'metal', '')), 'i@', '')) : 'minecraft:' + lower(replace(text(player_flag(d_target, 'metal', '')), 'i@', '')))",
                third.getAsJsonObject("expressions").get("/items/0/item/id").getAsString());
    }

    @Test void npcListsFilterAndLoopOverNpcsReadingTheirFlags() throws IOException {
        DenizenConverter.Output output = convert("""
                guards:
                  type: procedure
                  script:
                  - determine <server.npcs.filter_tag[<[filter_value].has_flag[post]>].filter_tag[<[filter_value].is_spawned>]>
                t:
                  type: task
                  script:
                  - define posted <proc[guards]>
                  - foreach <[posted]> as:guard:
                    - narrate "<[guard].name> (#<[guard].id>) guards <[guard].flag[post]>"
                    - flag <[guard]> checked:true
                  - foreach <server.npcs_flagged[post]> as:npc:
                    - execute as_server "npc select <[npc].id>"
                  - narrate "<server.online_players_flagged[vip].size> VIPs online"
                """);
        JsonObject task = document(output, "denizen:task/t");
        JsonObject routines = task.getAsJsonObject("routines");
        String procedure = routines.getAsJsonObject("proc-guards").toString();
        assertTrue(procedure.contains("\"list\":{\"expression\":\"npcs()\"}"), procedure);
        assertTrue(procedure.contains("npc_has_flag(t_"), procedure);
        assertTrue(procedure.contains("get(npc_info(t_"), procedure);
        JsonObject main = routines.getAsJsonObject("main");
        JsonObject guard = step(main, 2).getAsJsonObject("body");
        String text = step(guard, 0).getAsJsonObject("templates").get("/text").getAsString();
        assertEquals("{{ get(npc_info(d_guard), 'name', '') }} (#{{ get(npc_info(d_guard), 'citizens_id', '') }}) guards {{ npc_flag(d_guard, 'post', '') }}", text);
        assertEquals(object("{\"name\":\"checked\",\"scope\":\"npc\",\"value\":true,\"npc\":null}"), step(guard, 1).getAsJsonObject("arguments"));
        assertEquals("d_guard", step(guard, 1).getAsJsonObject("expressions").get("/npc").getAsString());
        assertEquals("[]", step(main, 3).get("value").toString());
        assertTrue(step(main, 4).toString().contains("npc_has_flag(t_"), step(main, 4).toString());
        assertTrue(step(main, 5).getAsJsonObject("body").toString().contains("get(npc_info(d_npc), 'citizens_id', '')"), step(main, 5).toString());
        assertTrue(main.toString().contains("player_has_flag(t_"), main.toString());
        assertEquals("complete", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString(), output.report().get("preserved").toString());
    }

    @Test void durationsFromTagsBecomeComputedWaitsAndExpiries() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: delay
                  script:
                  - wait <[delay]>s
                  - wait <util.random.int[2].to[5]>
                  - flag player cooldown expire:<[delay]>m
                  - flag player timer expire:<script[d].data_key[time]>
                  - wait <[delay]>
                d:
                  type: data
                  time: 10m
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"wait\",\"ticks\":{\"expression\":\"clamp(round(num(d_delay) * 20), 0, 72000)\"}}"), step(main, 0));
        assertEquals("clamp(round(random(2, 5) * 20), 0, 72000)", step(main, 1).getAsJsonObject("ticks").get("expression").getAsString());
        assertEquals("clamp(ceil(round(num(d_delay) * 1200) / 20), 1, 31536000)", step(main, 2).getAsJsonObject("expressions").get("/expireSeconds").getAsString());
        assertEquals(600, step(main, 3).getAsJsonObject("arguments").get("expireSeconds").getAsInt());
        assertEquals("clamp(round(duration_seconds(d_delay) * 20), 0, 72000)", step(main, 4).getAsJsonObject("ticks").get("expression").getAsString());
    }

    @Test void locationsCompareByDistanceAndWorld() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: p
                  script:
                  - if <player.location.distance[<npc.location>]> > 8:
                    - narrate far
                  - if <[p].location.world.name> != <npc.location.world.name>:
                    - narrate elsewhere
                  - define near <server.online_players.filter_tag[<[filter_value].location.distance[<npc.location>].is_less_than[5]>]>
                """);
        assertEquals("distance(player.location, npc.location) > 8", step(main, 0).get("expression").getAsString());
        assertEquals("!same_world(get(split(get(player_info(d_p), 'location', ''), ','), 3, ''), npc.world)", step(main, 1).get("expression").getAsString());
        assertEquals("(distance(get(player_info(t_2), 'location', ''), npc.location) < 5)", step(main, 3).getAsJsonObject("body").get("expression").getAsString());
        JsonObject exists = main("t:\n  type: task\n  definitions: target\n  script:\n  - if !<[target].exists>:\n    - stop\n  - narrate ok\n");
        assertEquals("!((len(text(d_target)) > 0))", step(exists, 0).get("expression").getAsString());
    }

    @Test void sentinelTargetsHeldInADefinitionNameThatPlayer() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: intruder
                  script:
                  - execute as_server "sentinel addtarget player:<[intruder].name> --id <npc.id>"
                  - execute as_server "sentinel removetarget player:<[intruder]>"
                """);
        assertEquals(object("{\"operation\":\"target\",\"target\":null}"), step(main, 0).getAsJsonObject("arguments"));
        assertEquals("get(players(d_intruder), 0, '')", step(main, 0).getAsJsonObject("expressions").get("/target").getAsString());
        assertEquals(object("{\"operation\":\"release\"}"), step(main, 1).getAsJsonObject("arguments"));
    }

    @Test void aRandomBlockRunsOneOfItsLinesChosenEvenly() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - random:
                    - narrate one
                    - if <player.is_op>:
                      - narrate two
                    - narrate three
                  - narrate after
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"expression\":\"random(1, 3)\"}}"), step(main, 0));
        JsonObject chain = step(main, 1);
        assertEquals("(t_1 == 1)", chain.get("expression").getAsString());
        assertEquals("(t_1 == 2)", chain.getAsJsonObject("else").get("expression").getAsString());
        assertEquals("(t_1 == 3)", chain.getAsJsonObject("else").getAsJsonObject("else").get("expression").getAsString());
        assertEquals("complete", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString(), output.report().get("preserved").toString());
    }

    @Test void timesAndDurationsReadAsDenizenDoes() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: length|who
                  script:
                  - flag player started:<util.time_now>
                  - define took <util.time_now.duration_since[<player.flag[started]>].in_seconds>
                  - define clock <duration[<[length]>].formatted>
                  - define minutes <duration[90s].in_minutes>
                  - repeat <[length].as[duration].in_seconds.round>:
                    - narrate tick
                  - actionbar "<player.flag_expiration[cooldown].from_now.formatted.if_null[ready]>"
                  - define theirs <[who].flag_expiration[cooldown].from_now.in_seconds.if_null[0]>
                  - flag player cooldown duration:<[length]>
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("now()", step(main, 0).getAsJsonObject("expressions").get("/value").getAsString());
        assertEquals("max(0, (now() - num(flag('player', 'started', 0))) / 1000)", step(main, 1).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("duration_text(duration_seconds(d_length))", step(main, 2).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("(90 / 60)", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
        assertTrue(main.toString().contains("round(duration_seconds(d_length))"), main.toString());
        String expiry = "flag_expiry('player', 'cooldown')";
        assertTrue(main.toString().contains("(" + expiry + " > 0 ? duration_text(max(0, (" + expiry + " - now()) / 1000)) : 'ready')"), main.toString());
        String theirs = "player_flag_expiry(d_who, 'cooldown')";
        assertTrue(main.toString().contains("(" + theirs + " > 0 ? max(0, (" + theirs + " - now()) / 1000) : 0)"), main.toString());
        assertEquals("clamp(ceil(round(duration_seconds(d_length) * 20) / 20), 1, 31536000)", step(main, 7).getAsJsonObject("expressions").get("/expireSeconds").getAsString());
        assertEquals("complete", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString(), output.report().get("preserved").toString());
    }

    @Test void textAroundOtherTextAndSimpleLocations() throws IOException {
        JsonObject main = main("""
                t:
                  type: task
                  definitions: key
                  script:
                  - define tail <[key].after[:]>
                  - define head <[key].before[:]>
                  - define end <[key].after_last[:]>
                  - define start <[key].before_last[:]>
                  - narrate "<player.location.simple>"
                """);
        String found = "contains(lower(text(d_key)), lower(':'))", parts = "split(lower(text(d_key)), lower(':'))";
        assertEquals("(" + found + " ? slice(text(d_key), len(first(" + parts + ")) + len(':'), len(text(d_key))) : '')", step(main, 0).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("slice(text(d_key), 0, len(first(" + parts + ")))", step(main, 1).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("(" + found + " ? slice(text(d_key), len(text(d_key)) - len(last(" + parts + ")), len(text(d_key))) : '')", step(main, 2).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("(" + found + " ? slice(text(d_key), 0, len(text(d_key)) - len(last(" + parts + ")) - len(':')) : text(d_key))", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
        String axis = "text(floor(num(get(split(player.location, ','), %d, 0))))";
        assertEquals("{{ (" + axis.formatted(0) + " + ',' + " + axis.formatted(1) + " + ',' + " + axis.formatted(2) + " + ',' + get(split(player.location, ','), 3, '')) }}",
                step(main, 4).getAsJsonObject("templates").get("/text").getAsString());
    }

    @Test void colorGradientsColorEachCharacterAsDenizenDoes() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - narrate "<element[ab].color_gradient[from=#FF0000;to=#0000FF]>!"
                  - narrate "<element[ab].color_gradient[from=red;to=blue;style=hsb]>"
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("&#ff0000a&#bc00bcb!", main.getAsJsonObject("arguments").get("text").getAsString());
        assertEquals("tag attribute color_gradient has no Animus expression", reasonAt(output, "a.dsc:5"));
    }

    @Test void effectsAtAHeldPlayerStayForThePlayersTheCommandNames() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: target|crowd|npcs
                  script:
                  - playeffect effect:heart at:<[target].location.above[2]> quantity:3 targets:<player>
                  - playeffect effect:heart at:<[target].location> quantity:3
                  - playeffect at:<[target].location.above[2]> effect:angry_villager quantity:5 targets:<[crowd]>
                  - playsound <[target].location> sound:entity_villager_yes
                  - playsound <[target]> sound:entity_villager_yes
                  - foreach <server.npcs> as:helper:
                    - playeffect effect:heart at:<[helper].location> targets:<[helper]>
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        JsonObject toTrigger = step(main, 0).getAsJsonObject("body");
        assertEquals(object("{\"particle\":\"minecraft:heart\",\"count\":3,\"height\":2.0,\"at\":\"player\",\"atPlayer\":null}"), toTrigger.getAsJsonObject("arguments"));
        assertEquals("t_1", toTrigger.getAsJsonObject("expressions").get("/atPlayer").getAsString());
        assertEquals(object("{\"particle\":\"minecraft:heart\",\"count\":3,\"at\":\"player\",\"atPlayer\":null,\"audience\":\"nearby\"}"), step(main, 1).getAsJsonObject("body").getAsJsonObject("arguments"));
        JsonObject crowd = step(main, 2).getAsJsonObject("body");
        assertEquals("players(d_crowd)", crowd.getAsJsonObject("list").get("expression").getAsString());
        assertEquals(object("{\"/player\":\"t_4\",\"/atPlayer\":\"t_3\"}"), crowd.getAsJsonObject("body").getAsJsonObject("expressions"));
        assertEquals("nearby", step(main, 3).getAsJsonObject("body").getAsJsonObject("arguments").get("audience").getAsString());
        assertFalse(step(main, 4).getAsJsonObject("body").getAsJsonObject("arguments").has("atPlayer"));
        assertEquals("playeffect for NPCs has no viewer; only players see particles and hear sounds", reasonAt(output, "a.dsc:11"));
    }

    @Test void flagsOnALocationLiveOnItsBlockAndFlagsOnUnconvertedDefinitionsAreKept() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  script:
                  - define spot <player.location>
                  - flag <[spot]> claimed:<player.name>
                  - flag <[spot]> visits:++
                  - flag <[spot]> claimed:!
                  - if <[spot].has_flag[claimed]>:
                    - narrate "<[spot].flag[claimed]||nobody> at <[spot].simple>"
                  - define aim <player.cursor_on>
                  - flag <[aim]> marked
                  - flag <[spot]> claimed expire:1h
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        String key = "(text(floor(num(get(split(d_spot, ','), 0, 0)))) + ',' + text(floor(num(get(split(d_spot, ','), 1, 0)))) + ',' + text(floor(num(get(split(d_spot, ','), 2, 0)))) + ',' + get(split(d_spot, ','), 3, ''))";
        String blocks = "(has_flag('global', 'locations-claimed') ? flag('global', 'locations-claimed', 0) : t_1)";
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{}}"), step(main, 1));
        JsonObject claimed = step(main, 2);
        assertEquals(object("{\"name\":null,\"scope\":\"global\",\"value\":null}"), claimed.getAsJsonObject("arguments"));
        assertEquals("'locations-claimed'", claimed.getAsJsonObject("expressions").get("/name").getAsString());
        assertEquals("put(" + blocks + ", " + key + ", player.name)", claimed.getAsJsonObject("expressions").get("/value").getAsString());
        String visits = step(main, 4).getAsJsonObject("expressions").get("/value").getAsString();
        assertTrue(visits.startsWith("put((has_flag('global', 'locations-visits') ? flag('global', 'locations-visits', 0) : t_2), ") && visits.endsWith(" + 1))"), visits);
        assertEquals("remove((has_flag('global', 'locations-claimed') ? flag('global', 'locations-claimed', 0) : t_3), " + key + ")", step(main, 6).getAsJsonObject("expressions").get("/value").getAsString());
        String set = "(has_flag('global', 'locations-claimed') && has(flag('global', 'locations-claimed', 0), " + key + "))";
        JsonObject test = step(main, 7);
        assertEquals(set, test.get("expression").getAsString());
        assertEquals("{{ (" + set + " ? get(flag('global', 'locations-claimed', 0), " + key + ") : 'nobody') }} at {{ " + key + " }}", test.getAsJsonObject("then").getAsJsonObject("templates").get("/text").getAsString());
        assertTrue(main.toString().contains("animus:ray-trace"), "the block the player looks at is found before its flag is set");
        assertEquals("a flag on a location takes no expire", reasonAt(output, "a.dsc:12"));
    }

    @Test void itemValuesTakeTheirMechanismsAndAreGivenTestedAndReadFromTheInventory() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: rewards
                  script:
                  - define sword <item[diamond_sword]>
                  - adjust def:sword "enchantments:[sharpness=5;unbreaking=3]"
                  - adjust def:sword "display:<&6>Old Blade"
                  - define bread <item[bread[quantity=3]]>
                  - give <[sword]>
                  - foreach <[rewards]> as:reward:
                    - if <player.inventory.can_fit[<[reward]>]>:
                      - give <[reward]>
                  - foreach <player.inventory.list_contents> as:held:
                    - if <[held].material.name> == pufferfish_bucket && <[held].display.strip_color||none> == Puff:
                      - narrate found
                  - adjust def:sword patterns:stripe
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"item\":\"minecraft:diamond_sword\",\"amount\":1}}"), step(main, 0));
        assertEquals(object("{\"expression\":\"t_1\"}"), step(main, 1).get("value"));
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_2\",\"value\":{\"minecraft:sharpness\":5,\"minecraft:unbreaking\":3}}"), step(main, 2));
        assertEquals("put(d_sword, 'enchantments', t_2)", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("put(d_sword, 'name', ('&6Old Blade'))", step(main, 4).getAsJsonObject("value").get("expression").getAsString());
        assertEquals(object("{\"item\":\"minecraft:bread\",\"amount\":3}"), step(main, 5).get("value"));
        JsonObject give = step(main, 7);
        assertEquals("animus:give-item", give.get("capability").getAsString());
        assertEquals("d_sword", give.getAsJsonObject("expressions").get("/stack").getAsString());
        JsonObject fit = step(main, 8).getAsJsonObject("body");
        assertTrue(fit.toString().contains("animus:can-fit"), fit.toString());
        assertTrue(fit.toString().contains("(same(type_of(d_reward), 'map') ? d_reward : put("), fit.toString());
        JsonObject read = step(main, 9);
        assertEquals("animus:read-inventory", read.get("capability").getAsString());
        assertEquals("t_7", read.get("result").getAsString());
        JsonObject loop = step(main, 10);
        assertEquals("get(t_7, 'items', [])", loop.getAsJsonObject("list").get("expression").getAsString());
        assertTrue(loop.toString().contains("replace(get(d_held, 'item', ''), 'minecraft:', '')"), loop.toString());
        assertTrue(loop.toString().contains("(has(d_held, 'name') ? plain(get(d_held, 'name', '')) : 'none')"), loop.toString());
        assertEquals("item mechanism patterns has no Animus equivalent", reasonAt(output, "a.dsc:16"));
    }

    @Test void locationsMoveFindNearbyPlayersAndTestNotedAreas() throws IOException {
        DenizenConverter.Output output = convert("""
                setup:
                  type: task
                  script:
                  - note cu@town,0,60,0,10,70,10 as:hall
                t:
                  type: task
                  definitions: guest
                  script:
                  - define head <npc.location.above[2]>
                  - define near <npc.location.find_players_within[8]>
                  - define room <cuboid[hall].if_null[none]>
                  - define gone <cuboid[attic].if_null[none]>
                  - if <[guest].location.is_within[<[room]>]>:
                    - narrate inside
                  - if !<player.exists>:
                    - stop
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals("offset(npc.location, 0, 2, 0)", step(main, 0).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("players_near(npc.location, 8)", step(main, 1).getAsJsonObject("value").get("expression").getAsString());
        assertEquals(object("{\"type\":\"set\",\"variable\":\"t_1\",\"value\":{\"world\":\"town\",\"shape\":\"cuboid\",\"bounds\":[0,60,0,10,70,10]}}"), step(main, 2));
        assertEquals("t_1", step(main, 3).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("'none'", step(main, 4).getAsJsonObject("value").get("expression").getAsString());
        assertEquals("within(get(player_info(d_guest), 'location', ''), d_room)", step(main, 5).get("expression").getAsString());
        assertEquals("!(has_player())", step(main, 6).get("expression").getAsString());
        assertEquals("complete", output.report().getAsJsonObject("behaviorStatus").get("denizen:task/t").getAsString(), output.report().get("preserved").toString());
    }

    @Test void bossBarsAndSidebarsBelongToThePlayersTheyAreFor() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: crew|guest
                  script:
                  - bossbar create forge<player.uuid> "title:<&6>Forging" players:<player> progress:0.5 color:yellow style:segmented_10
                  - bossbar update forge<player.uuid> progress:<[guest].health.div[20]>
                  - bossbar remove forge<[guest].uuid>
                  - sidebar set "title:<&e>Crew" "values:<[crew]>" players:<[crew]>
                  - sidebar remove players:<player>
                  - bossbar remove everyone
                  - bossbar create forge<player.uuid> color:teal
                """);
        JsonObject main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main");
        assertEquals(object("{\"type\":\"action\",\"capability\":\"animus:bossbar\",\"arguments\":{\"id\":\"forge\",\"title\":\"&6Forging\",\"progress\":0.5,\"color\":\"yellow\",\"style\":\"segmented_10\"}}"), step(main, 0));
        assertEquals("clamp((get(player_info(d_guest), 'health', 0) / 20), 0, 1)", step(main, 1).getAsJsonObject("expressions").get("/progress").getAsString());
        JsonObject removed = step(main, 2);
        assertEquals("players(d_guest)", removed.getAsJsonObject("list").get("expression").getAsString());
        assertEquals(object("{\"id\":\"forge\",\"operation\":\"remove\",\"player\":null}"), removed.getAsJsonObject("body").getAsJsonObject("arguments"));
        JsonObject board = step(main, 3).getAsJsonObject("body");
        assertEquals("slice(split((same(type_of(d_crew), 'list') ? join(d_crew, '|') : text(d_crew)), '|'), 0, 15)", board.getAsJsonObject("expressions").get("/lines").getAsString());
        assertEquals("&eCrew", board.getAsJsonObject("arguments").get("title").getAsString());
        assertEquals(object("{\"operation\":\"remove\"}"), step(main, 4).getAsJsonObject("arguments"));
        assertEquals(object("{\"id\":\"everyone\",\"operation\":\"remove\"}"), step(main, 5).getAsJsonObject("arguments"));
        assertEquals("bossbar color teal has no Animus equivalent", reasonAt(output, "a.dsc:11"));
    }

    @Test void aPlaceOfUnknownKindExplodesAtItsLocationTextWithoutTheDenizenPrefix() throws IOException {
        JsonObject blast = main("t:\n  type: task\n  definitions: spot\n  script:\n  - explode <[spot]>\n");
        assertEquals("animus:explode", blast.get("capability").getAsString());
        assertEquals(object("{\"location\":null}"), blast.getAsJsonObject("arguments"));
        assertEquals("replace(text(d_spot), 'l@', '')", blast.getAsJsonObject("expressions").get("/location").getAsString());
    }

    @Test void otherEntitiesCursorsMatchedPlayersAndRunsForOthersConvert() throws IOException {
        DenizenConverter.Output output = convert("""
                t:
                  type: task
                  definitions: guest|who
                  script:
                  - define near <server.match_player[<[who]>]||nobody>
                  - define aim <player.cursor_on[20]>
                  - flag <[aim]> marked
                  - define started <util.time_now>
                  - foreach <server.npcs> as:helper:
                    - look <[helper]> <player.location> duration:2s
                    - animate <[helper]> animation:arm_swing
                    - playeffect effect:heart at:<[helper].location.above[2]> quantity:3
                  - define base <npc.location>
                  - teleport <npc> <[base].above[1]>
                  - lookclose state:true range:6 realistic
                  - explode power:2 <[base]> fire
                  - sidebar add "values:One|Two"
                  - sidebar set_line scores:2 "values:Second"
                  - run greet player:<[guest]>
                  - lookclose range:4
                greet:
                  type: task
                  script:
                  - narrate "Hi <player.name>"
                w:
                  type: world
                  events:
                    on player closes inventory:
                    - if !<context.inventory.location.exists>:
                      - stop
                    - foreach <context.inventory.list_contents> as:item:
                      - if <[item].material.name> != air:
                        - give <[item]>
                    - foreach <server.npcs> as:npc:
                      - run greet npc:<[npc]>
                """);
        String main = document(output, "denizen:task/t").getAsJsonObject("routines").getAsJsonObject("main").toString();
        assertTrue(main.contains("(len(player_named(text(d_who))) > 0) ? player_named(text(d_who)) : 'nobody')"), main);
        assertTrue(main.contains("{\"type\":\"action\",\"capability\":\"animus:ray-trace\",\"arguments\":{\"range\":20.0},\"result\":\"t_1\"}"), main);
        assertTrue(main.contains("'locations-marked'"), main);
        assertTrue(main.contains("\"expression\":\"now()\""), main);
        assertTrue(main.contains("\"capability\":\"animus:look\",\"arguments\":{\"target\":\"player\",\"npc\":null},\"expressions\":{\"/npc\":\"d_helper\"}"), main);
        assertTrue(main.contains("\"capability\":\"animus:animate\",\"arguments\":{\"animation\":\"swing\",\"npc\":null},\"expressions\":{\"/npc\":\"d_helper\"}"), main);
        assertTrue(main.contains("\"capability\":\"animus:spawn-particle\",\"arguments\":{\"particle\":\"minecraft:heart\",\"count\":3,\"height\":2.0,\"audience\":\"nearby\",\"npc\":null},\"expressions\":{\"/npc\":\"d_helper\"}"), main);
        assertTrue(main.contains("\"capability\":\"animus:teleport\",\"arguments\":{\"target\":\"npc\",\"location\":null},\"expressions\":{\"/location\":\"offset(d_base, 0, 1, 0)\"}"), main);
        assertTrue(main.contains("{\"type\":\"action\",\"capability\":\"animus:look-close\",\"arguments\":{\"enabled\":true,\"range\":6.0,\"lineOfSight\":true}}"), main);
        assertTrue(main.contains("\"capability\":\"animus:explode\",\"arguments\":{\"location\":null,\"power\":2.0,\"fire\":true},\"expressions\":{\"/location\":\"d_base\"}"), main);
        assertTrue(main.contains("\"capability\":\"animus:sidebar\",\"arguments\":{\"operation\":\"add\",\"lines\":null}"), main);
        assertTrue(main.contains("\"arguments\":{\"operation\":\"set_line\",\"line\":2,\"lines\":null},\"expressions\":{\"/lines\":\"slice(split(join(['Second'], '|'), '|'), 0, 1)\"}"), main);
        assertTrue(main.contains("{\"type\":\"call\",\"routine\":\"task-greet\",\"player\":{\"expression\":\"get(players(d_guest), 0, '')\"}}"), main);
        assertEquals("lookclose without state: toggles, which has no Animus equivalent; write state:true or state:false", reasonAt(output, "a.dsc:20"));
        JsonObject world = document(output, "denizen:world/w");
        String closes = world.toString();
        assertTrue(closes.contains("!((len(context.inventory_location) > 0))"), closes);
        assertTrue(closes.contains("\"list\":{\"expression\":\"context.inventory_items\"}"), closes);
        assertTrue(closes.contains("\"routine\":\"task-greet-npc\",\"npc\":{\"expression\":\"d_npc\"}"), closes);
        assertTrue(world.getAsJsonObject("routines").has("task-greet-npc"), closes);
        assertEquals("complete", output.report().getAsJsonObject("behaviorStatus").get("denizen:world/w").getAsString(), output.report().get("preserved").toString());
    }
}
