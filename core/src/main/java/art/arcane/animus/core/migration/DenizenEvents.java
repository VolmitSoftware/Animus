package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenExpressions.Atom;
import art.arcane.animus.core.migration.DenizenExpressions.Capability;
import art.arcane.animus.core.migration.DenizenExpressions.Cond;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * World script event names as server triggers. An event such as {@code on player chats message:hello*} becomes the
 * trigger fields ({@code "event": "player_chat", "pattern": "hello*"}), the conditions its other switches add around
 * the routine ({@code flagged:}, {@code permission:}, {@code in:}), and the {@code context.*} keys its script may read.
 * A right click on an NPC is an NPC event instead: an {@code interaction} trigger of a behavior bound to every NPC,
 * whose run reads the NPC as {@code context.entity}. An event or switch without an Animus equivalent gives the reason
 * instead. The browser converter has a line-for-line copy.
 */
final class DenizenEvents {
    private DenizenEvents() {}

    /**
     * A mapped event: trigger fields without id and routine, wrapping conditions, readable context keys, and whether it is
     * an NPC event; or a reason. The context key {@code entity} maps to {@code npc}: the clicked entity is the NPC.
     */
    record Mapped(JsonObject trigger, List<Cond> guards, Map<String, String> context, boolean npc, String reason) {
        static Mapped fail(String reason) { return new Mapped(null, List.of(), Map.of(), false, reason); }
    }

    /** The phrase of an event key without its {@code on}/{@code after} word and switches, for routine names and reports. */
    static String phrase(String key) {
        List<String> words = new ArrayList<>();
        String[] tokens = key.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim().split(" ");
        for (int index = 0; index < tokens.length; index++) {
            if (index == 0 && (tokens[0].equals("on") || tokens[0].equals("after"))) continue;
            if (!switchToken(tokens[index])) words.add(tokens[index]);
        }
        return String.join(" ", words);
    }

    private static boolean switchToken(String token) { return token.matches("[a-z_]+:.+"); }

    static Mapped event(String key) {
        String[] tokens = key.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim().split(" ");
        if (!tokens[0].equals("on") && !tokens[0].equals("after")) return Mapped.fail("world events start with on or after");
        List<String> words = new ArrayList<>();
        TreeMap<Integer, String[]> switches = new TreeMap<>();
        for (int index = 1; index < tokens.length; index++) {
            if (switchToken(tokens[index])) switches.put(index, new String[] {tokens[index].substring(0, tokens[index].indexOf(':')), tokens[index].substring(tokens[index].indexOf(':') + 1)});
            else words.add(tokens[index]);
        }
        String phrase = String.join(" ", words);
        JsonObject trigger = new JsonObject();
        TreeMap<String, String> context = new TreeMap<>();
        boolean player = true, npc = false;
        String event;
        switch (phrase) {
            case "player joins", "player join", "player logs in" -> event = "player_join";
            case "player quits", "player quit" -> event = "player_quit";
            case "player chats" -> { event = "player_chat"; context.put("message", "message"); }
            case "server start", "server starts" -> { event = "server_start"; player = false; }
            case "player dies", "player death" -> { event = "player_death"; context.put("message", "message"); }
            case "player respawns" -> event = "player_respawn";
            case "delta time secondly", "delta time minutely", "delta time hourly" -> { event = "timer"; player = false; }
            case "player right clicks entity", "player right clicks npc" -> {
                event = "interaction";
                npc = true;
                trigger.addProperty("action", "interact");
                context.put("entity", "npc");
                context.put("hand", "hand");
                context.put("sneaking", "sneaking");
            }
            default -> {
                if (phrase.startsWith("player changes world")) {
                    event = "player_world_change";
                    String[] rest = phrase.substring("player changes world".length()).trim().split(" ");
                    for (int index = 0; index + 1 < rest.length; index += 2) {
                        if (!rest[index].equals("from") && !rest[index].equals("to") || !rest[index + 1].matches("[a-z0-9_./-]{1,64}"))
                            return Mapped.fail("world change form " + phrase + " has no Animus equivalent");
                        trigger.addProperty(rest[index], rest[index + 1]);
                    }
                    if (rest.length % 2 == 1 && !rest[0].isEmpty()) return Mapped.fail("world change form " + phrase + " has no Animus equivalent");
                    context.put("origin_world", "from");
                    context.put("destination_world", "to");
                } else if (phrase.contains("npc")) return Mapped.fail("this event covers every NPC; Animus binds a behavior to each NPC instead");
                else return Mapped.fail("no Animus server trigger for the event " + phrase);
            }
        }
        JsonObject fields = new JsonObject();
        fields.addProperty("event", event);
        if (event.equals("timer")) {
            long every = 1;
            for (String[] option : switches.values()) if (option[0].equals("every")) {
                if (!option[1].matches("[0-9]{1,5}")) return Mapped.fail("every:" + option[1] + " needs a whole number");
                every = Long.parseLong(option[1]);
            }
            long unit = phrase.endsWith("secondly") ? 20 : phrase.endsWith("minutely") ? 1200 : 72_000;
            long ticks = every * unit;
            if (ticks < 20 || ticks > 72_000) return Mapped.fail("a timer every " + ticks + " ticks is outside 20 to 72000");
            fields.addProperty("intervalTicks", ticks);
        }
        for (Map.Entry<String, com.google.gson.JsonElement> field : trigger.entrySet()) fields.add(field.getKey(), field.getValue());
        List<Cond> guards = new ArrayList<>();
        for (String[] option : switches.values()) {
            String name = option[0], value = option[1];
            switch (name) {
                case "every" -> { if (!event.equals("timer")) return Mapped.fail("switch every: belongs to delta time"); }
                case "priority", "bukkit_priority", "ignorecancelled" -> { /* order and cancellation have no meaning without cancellation */ }
                case "cancelled" -> { if (!value.equals("false")) return Mapped.fail("switch cancelled:" + value + " has no Animus equivalent; Animus triggers see no cancelled events"); }
                case "message" -> {
                    if (!event.equals("player_chat")) return Mapped.fail("switch message: belongs to player chats");
                    if (value.length() > 256 || value.contains("<")) return Mapped.fail("chat pattern " + value + " has no Animus equivalent");
                    fields.addProperty("pattern", value);
                }
                case "from", "to" -> {
                    if (!event.equals("player_world_change") || !value.matches("[a-z0-9_./-]{1,64}")) return Mapped.fail("switch " + name + ":" + value + " has no Animus equivalent");
                    fields.addProperty(name, value);
                }
                case "flagged", "server_flagged" -> {
                    Optional<String> flag = DenizenConverter.flagName(value);
                    if (flag.isEmpty()) return Mapped.fail("flag name " + value + " cannot be an Animus flag name");
                    if (name.equals("flagged") && !player) return Mapped.fail("switch flagged: needs a player; " + event + " has none");
                    guards.add(new Atom("has_flag(" + DenizenExpressions.quote(name.equals("flagged") ? "player" : "global") + ", " + DenizenExpressions.quote(flag.get()) + ")"));
                }
                case "permission" -> {
                    if (!player) return Mapped.fail("switch permission: needs a player; " + event + " has none");
                    JsonObject arguments = new JsonObject();
                    arguments.addProperty("permission", value);
                    guards.add(new Capability("animus:has-permission", arguments));
                }
                case "in" -> {
                    if (!player) return Mapped.fail("switch in: needs a player; " + event + " has none");
                    if (!value.matches("[a-z0-9_-]{1,64}")) return Mapped.fail("switch in:" + value + " names an area Animus cannot check");
                    guards.add(new Atom("same(player.world, " + DenizenExpressions.quote(value) + ")"));
                }
                default -> { return Mapped.fail("event switch " + name + ": has no Animus equivalent"); }
            }
        }
        return new Mapped(fields, guards, context, npc, null);
    }

    /** The context keys of a command script's run. */
    static Map<String, String> commandContext() {
        TreeMap<String, String> context = new TreeMap<>();
        for (String key : List.of("alias", "args", "command", "raw_args")) context.put(key, key);
        return context;
    }
}
