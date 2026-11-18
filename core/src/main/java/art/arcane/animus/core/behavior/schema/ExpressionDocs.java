package art.arcane.animus.core.behavior.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the expression editor shows for each behavior and standard function, and the type and fixed shape of each fact.
 * Tests hold it to {@code BehaviorExpression}, {@code GuiExpression} and the facts {@code SnapshotFacts} writes.
 */
public final class ExpressionDocs {
    /** {@code call} names the parameters, optional ones in brackets; {@code returns} is boolean, number, text, list, map or any. */
    public record Function(String call, String returns, String about) {}

    private static final String BOOLEAN = "boolean", NUMBER = "number", TEXT = "text", LIST = "list", MAP = "map", ANY = "any";

    public static final Map<String, Function> FUNCTIONS = functions();
    /** Each fact root's facts in the order the language lists them, with what an expression reads. */
    public static final Map<String, Map<String, String>> FACT_TYPES = factTypes();
    /** The shape of a fact's text, for the facts whose text always has one. */
    public static final Map<String, String> FACT_FORMATS = factFormats();

    private ExpressionDocs() {}

    private static Map<String, Function> functions() {
        Map<String, Function> docs = new LinkedHashMap<>();
        doc(docs, "get(list_or_map, key, [fallback])", ANY, "The item at a 0-based index of a list or a key of a map; without a fallback, a missing item fails.");
        doc(docs, "has(map, key)", BOOLEAN, "Whether the map has the key.");
        doc(docs, "keys(map)", LIST, "The keys of the map.");
        doc(docs, "values(map)", LIST, "The values of the map.");
        doc(docs, "put(map, key, value)", MAP, "A new map with the key set to the value.");
        doc(docs, "remove(list_or_map, key)", ANY, "A new list or map without the item at that 0-based index or key.");
        doc(docs, "append(list, value)", LIST, "A new list with the value added at the end.");
        doc(docs, "insert(list, index, value)", LIST, "A new list with the value added at the 0-based index.");
        doc(docs, "index_of(list, value)", NUMBER, "The 0-based index of the value in the list, or -1 when it is not there.");
        doc(docs, "first(list)", ANY, "The first item of the list; an empty list fails.");
        doc(docs, "last(list)", ANY, "The last item of the list; an empty list fails.");
        doc(docs, "slice(list_or_text, from, to)", ANY, "The items or characters from the index from up to but not including the index to.");
        doc(docs, "reverse(list_or_text)", ANY, "The list or text in reverse order.");
        doc(docs, "sort(list)", LIST, "A list of only numbers or only text, in order.");
        doc(docs, "sum(list)", NUMBER, "The total of a list of numbers.");
        doc(docs, "join(list, separator)", TEXT, "The items as text with the separator between them.");
        doc(docs, "split(text, separator)", LIST, "The text cut at each separator.");
        doc(docs, "range(from, to)", LIST, "The whole numbers from from to to, both included, at most 256.");
        doc(docs, "trim(text)", TEXT, "The text without spaces around it.");
        doc(docs, "replace(text, find, with)", TEXT, "The text with every find replaced by with.");
        doc(docs, "starts_with(text, prefix)", BOOLEAN, "Whether the text starts with the prefix.");
        doc(docs, "ends_with(text, suffix)", BOOLEAN, "Whether the text ends with the suffix.");
        doc(docs, "num(value)", NUMBER, "A number from a number or numeric text; anything else fails.");
        doc(docs, "int(value)", NUMBER, "The number rounded toward zero.");
        doc(docs, "truthy(value)", BOOLEAN, "True for true, the text true in any case, and numbers other than zero.");
        doc(docs, "same(a, b)", BOOLEAN, "Equal as numbers when both are numbers or numeric text, otherwise equal as text ignoring case.");
        doc(docs, "same_world(a, b)", BOOLEAN, "Whether both name the same world, each by its name, UUID or dimension key such as minecraft:the_nether.");
        doc(docs, "text(value)", TEXT, "Any value as text.");
        doc(docs, "random(min, max)", NUMBER, "A whole number from min to max, both included.");
        doc(docs, "random_decimal()", NUMBER, "A number from 0 up to but not including 1.");
        doc(docs, "chance(percent)", BOOLEAN, "True with that percent chance.");
        doc(docs, "pick(list)", ANY, "A random item of the list; an empty list fails.");
        doc(docs, "flag(scope, name, [fallback])", ANY, "A flag of this behavior's namespace in the scope player, npc, relationship or global; without a fallback it must be set.");
        doc(docs, "has_flag(scope, name)", BOOLEAN, "Whether that flag of this behavior's namespace is set.");
        doc(docs, "quest(scope, name)", NUMBER, "A quest stage of this behavior's namespace, 0 when unset.");
        doc(docs, "players(value)", LIST, "The player UUIDs in a list or a single value, in order and without repeats, at most 100.");
        doc(docs, "player_info(player)", MAP, "Another online player's facts as a map with the player fact keys; an empty map when that player is offline.");
        doc(docs, "player_flag(player, name, [fallback])", ANY, "A player flag of this behavior's namespace for another player; without a fallback it must be set.");
        doc(docs, "player_has_flag(player, name)", BOOLEAN, "Whether another player has that player flag of this behavior's namespace.");
        doc(docs, "npcs()", LIST, "The UUIDs of every NPC the server knows, spawned or not, at most 1,024.");
        doc(docs, "npc_info(npc)", MAP, "An NPC's facts as a map with the npc fact keys; an empty map for anything that is no NPC.");
        doc(docs, "npc_flag(npc, name, [fallback])", ANY, "An npc flag of this behavior's namespace for another NPC; without a fallback it must be set.");
        doc(docs, "npc_has_flag(npc, name)", BOOLEAN, "Whether another NPC has that npc flag of this behavior's namespace.");
        doc(docs, "distance(from, to)", NUMBER, "The distance in blocks between two locations written x,y,z or x,y,z,world; locations in different worlds are 1,000,000,000 apart.");
        doc(docs, "duration_seconds(value)", NUMBER, "The seconds in a number or in duration text such as 10m, 30t, 1h30m or d@1d.");
        doc(docs, "duration_text(seconds)", TEXT, "Seconds written as text such as 2h 30m, or 0s for none.");
        doc(docs, "flag_expiry(scope, name)", NUMBER, "When a flag of this behavior's namespace expires, in epoch milliseconds; 0 when it never expires or is unset.");
        doc(docs, "player_flag_expiry(player, name)", NUMBER, "When another player's player flag of this behavior's namespace expires, in epoch milliseconds; 0 when it never expires or is unset.");
        doc(docs, "type_of(value)", TEXT, "The value's type: list, map, text, number or boolean.");
        doc(docs, "has_player()", BOOLEAN, "Whether a player started the run; a command run from the console has none.");
        doc(docs, "offset(location, x, y, z)", TEXT, "The location moved by those blocks, written x,y,z,world to two decimals.");
        doc(docs, "players_near(location, radius)", LIST, "The UUIDs of the online players within radius blocks of the location in its world, nearest first, at most 100.");
        doc(docs, "within(location, area)", BOOLEAN, "Whether the location is inside an area map with a world, a shape of cuboid or ellipsoid and six bounds.");
        doc(docs, "now()", NUMBER, "The time now in epoch milliseconds, read as the expression runs.");
        doc(docs, "player_named(name)", TEXT, "The UUID of the online player with that name ignoring case, else the shortest name that starts with it, then one that contains it; empty text for none.");
        doc(docs, "abs(x)", NUMBER, "The number without its sign.");
        doc(docs, "align(text, width, mode)", TEXT, "The text padded with spaces to width visible characters, where mode is left, center, middle or right.");
        doc(docs, "alpha(color, alpha)", NUMBER, "The color with its alpha channel set to alpha, from 0 to 255.");
        doc(docs, "argb(alpha, red, green, blue)", NUMBER, "A color from four channels, each from 0 to 255.");
        doc(docs, "bar(value, maximum, width, filled, empty)", TEXT, "A progress bar width characters wide: filled text for the share value has of maximum, empty text for the rest.");
        doc(docs, "ceil(x)", NUMBER, "The smallest whole number not below x.");
        doc(docs, "clamp(x, low, high)", NUMBER, "x held between low and high.");
        doc(docs, "contains(list_or_text, value)", BOOLEAN, "Whether the list holds the value, or the text contains it.");
        doc(docs, "cos(radians)", NUMBER, "The cosine of an angle in radians.");
        doc(docs, "fixed(x, digits)", TEXT, "The number written with that many decimals, from 0 to 20.");
        doc(docs, "flash(first, second, step)", TEXT, "first on even steps and second on odd steps.");
        doc(docs, "floor(x)", NUMBER, "The largest whole number not above x.");
        doc(docs, "hex(color)", TEXT, "The color written [RRGGBB].");
        doc(docs, "len(list_or_text)", NUMBER, "The number of items in the list or characters in the text.");
        doc(docs, "lerp(a, b, t)", NUMBER, "The number t of the way from a to b; t is not held between 0 and 1.");
        doc(docs, "lower(text)", TEXT, "The text in lowercase.");
        doc(docs, "marquee(text, width, step)", TEXT, "The text scrolling left through a window of width characters, from 1 to 64.");
        doc(docs, "max(a, b)", NUMBER, "The larger of the two numbers.");
        doc(docs, "min(a, b)", NUMBER, "The smaller of the two numbers.");
        doc(docs, "mix(first, second, t)", NUMBER, "A blend of two colors channel by channel, t of the way from the first to the second.");
        doc(docs, "mod(a, b)", NUMBER, "What is left of a after taking away whole multiples of b, with the sign of b; b of 0 fails.");
        doc(docs, "number(text)", NUMBER, "The number in a number or in formatted text, with color codes and thousands commas ignored.");
        doc(docs, "odometer(from, to, progress, digits)", TEXT, "The whole number progress of the way from from to to, padded with zeros to digits, from 1 to 16.");
        doc(docs, "palette(list, index)", NUMBER, "The number at the index of the list, counting around past its end.");
        doc(docs, "plain(text)", TEXT, "The text without its & color codes.");
        doc(docs, "pow(base, exponent)", NUMBER, "base raised to the power exponent.");
        doc(docs, "readable(text)", TEXT, "A key such as IRON_ORE written as Iron Ore.");
        doc(docs, "rgb(red, green, blue)", NUMBER, "An opaque color from three channels, each from 0 to 255.");
        doc(docs, "round(x)", NUMBER, "x rounded to the nearest whole number, halves rounded up.");
        doc(docs, "scanner(text, base_style, highlight_style, step)", TEXT, "The text with one highlighted character moving across it a step at a time.");
        doc(docs, "scramble(text, step)", TEXT, "Random characters that settle into the text a step at a time.");
        doc(docs, "select(list, index)", ANY, "The item at the index of the list, counting around past its end.");
        doc(docs, "sin(radians)", NUMBER, "The sine of an angle in radians.");
        doc(docs, "smoothstep(edge0, edge1, x)", NUMBER, "A smooth step from 0 to 1 as x moves from edge0 to edge1.");
        doc(docs, "str(value)", TEXT, "The value as text.");
        doc(docs, "timeline(scenes, seconds)", TEXT, "The text of the scene showing after that many seconds of a looping list of [text, seconds] scenes.");
        doc(docs, "typewriter(text, step, hold_steps)", TEXT, "The text typed out, held for hold_steps and erased, a character a step.");
        doc(docs, "upper(text)", TEXT, "The text in uppercase.");
        doc(docs, "wave(text, styles, step)", TEXT, "The text with a list of 1 to 16 color styles moving across its characters.");
        doc(docs, "wipe(text, step)", TEXT, "The text revealed and hidden a character a step, keeping its width.");
        return Collections.unmodifiableMap(docs);
    }
    private static void doc(Map<String, Function> docs, String call, String returns, String about) {
        docs.put(call.substring(0, call.indexOf('(')), new Function(call, returns, about));
    }

    private static Map<String, Map<String, String>> factTypes() {
        Map<String, Map<String, String>> roots = new LinkedHashMap<>();
        roots.put("player", facts("name", TEXT, "uuid", TEXT, "online", BOOLEAN, "health", NUMBER, "max_health", NUMBER, "food", NUMBER, "level", NUMBER, "world", TEXT,
                "x", NUMBER, "y", NUMBER, "z", NUMBER, "location", TEXT, "gamemode", TEXT, "op", BOOLEAN, "sneaking", BOOLEAN, "flying", BOOLEAN, "item_in_hand", TEXT,
                "item_in_hand_amount", NUMBER));
        roots.put("npc", facts("name", TEXT, "id", TEXT, "citizens_id", TEXT, "uuid", TEXT, "spawned", BOOLEAN, "health", NUMBER, "world", TEXT, "x", NUMBER, "y", NUMBER,
                "z", NUMBER, "location", TEXT, "behaviors", LIST));
        roots.put("server", facts("online_players", NUMBER, "players", LIST, "max_players", NUMBER, "tick", NUMBER, "time_millis", NUMBER));
        return Collections.unmodifiableMap(roots);
    }

    private static Map<String, String> factFormats() {
        String world = "world name such as world_nether on Bukkit, dimension key such as minecraft:the_nether on mod servers";
        return facts("player.uuid", "player UUID such as 069a79f4-44e9-4726-a5be-fca90e38aaf5", "player.world", world, "player.location", "x,y,z,world",
                "player.gamemode", "survival, creative, adventure or spectator", "player.item_in_hand", "namespaced item id such as minecraft:wheat, minecraft:air for an empty hand",
                "npc.uuid", "NPC UUID such as 2f1c9a54-7e3b-4d0a-9b8e-5a6c7d8e9f01", "npc.world", world, "npc.location", "x,y,z,world",
                "npc.behaviors", "list of behavior ids such as town:greeter", "server.players", "list of player UUIDs", "server.time_millis", "epoch milliseconds");
    }

    private static Map<String, String> facts(String... pairs) {
        Map<String, String> facts = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) facts.put(pairs[index], pairs[index + 1]);
        return Collections.unmodifiableMap(facts);
    }
}
