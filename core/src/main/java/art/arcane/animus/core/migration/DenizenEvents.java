package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenExpressions.Atom;
import art.arcane.animus.core.migration.DenizenExpressions.Capability;
import art.arcane.animus.core.migration.DenizenExpressions.Cond;
import com.google.gson.JsonArray;
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
 * A right click on an NPC, and a hit on one, is an NPC event instead: an {@code interaction} trigger of a behavior bound
 * to every NPC, whose run reads the NPC as {@code context.entity}. Area events take their bounds from the noted areas.
 * An event or switch without an Animus equivalent gives the reason instead. The browser converter has a line-for-line copy.
 */
final class DenizenEvents {
    private DenizenEvents() {}

    /**
     * A mapped event: trigger fields without id and routine, wrapping conditions, readable context keys, whether it is
     * an NPC event and whether its platform event can be cancelled; or a reason. The context key {@code entity} maps to
     * {@code npc} on NPC events: the clicked entity is the NPC.
     */
    record Mapped(JsonObject trigger, List<Cond> guards, Map<String, String> context, boolean npc, boolean cancellable, String reason) {
        static Mapped fail(String reason) { return new Mapped(null, List.of(), Map.of(), false, false, reason); }
    }

    /** A noted area: a cuboid's two corners or an ellipsoid's centre and radii, in one world. */
    record Note(String name, String world, boolean cuboid, double[] bounds) {}

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

    /** Denizen damage causes as vanilla damage type patterns. */
    private static final Map<String, String> CAUSES = Map.ofEntries(Map.entry("fall", "fall"), Map.entry("lava", "lava"), Map.entry("fire", "in_fire"),
            Map.entry("fire_tick", "on_fire"), Map.entry("drowning", "drown"), Map.entry("entity_attack", "*_attack"), Map.entry("void", "out_of_world"),
            Map.entry("starvation", "starve"), Map.entry("suffocation", "in_wall"), Map.entry("lightning", "lightning_bolt"), Map.entry("magic", "magic"),
            Map.entry("wither", "wither"), Map.entry("contact", "cactus"), Map.entry("block_explosion", "*explosion"), Map.entry("entity_explosion", "*explosion"),
            Map.entry("freeze", "freeze"), Map.entry("hot_floor", "hot_floor"), Map.entry("cramming", "cramming"), Map.entry("fly_into_wall", "fly_into_wall"),
            Map.entry("dragon_breath", "dragon_breath"), Map.entry("thorns", "thorns"), Map.entry("sonic_boom", "sonic_boom"), Map.entry("campfire", "campfire"),
            Map.entry("projectile", "arrow"), Map.entry("poison", "magic"));
    private static final String NAME = "[a-z0-9_*]{1,64}";

    /** {@code key} with the areas Denizen notes; {@code npcEntity} says the script treats the entity as an NPC ({@code is_npc}). */
    static Mapped event(String key, Map<String, Note> notes, boolean npcEntity) {
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
        boolean player = true, npc = false, cancellable = false;
        String event;
        String[] w = words.toArray(String[]::new);
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
            case "player damages npc", "npc damaged by player" -> {
                event = "interaction";
                npc = true;
                trigger.addProperty("action", "attack");
                context.put("entity", "npc");
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
                } else if (w.length == 3 && w[0].equals("player") && (w[1].equals("enters") || w[1].equals("exits"))) {
                    Note note = notes.get(w[2]);
                    if (note == null) return Mapped.fail(List.of("area", "cuboid", "ellipsoid", "polygon").contains(w[2]) ? "an event for every area has no Animus equivalent; name the noted area" : "area " + w[2] + " is not a noted cuboid or ellipsoid");
                    if (!w[2].matches("[a-z0-9][a-z0-9_-]{0,63}")) return Mapped.fail("area name " + w[2] + " cannot be an Animus area name");
                    event = "player_area";
                    trigger.addProperty("phase", w[1].equals("enters") ? "enter" : "leave");
                    trigger.addProperty("area", w[2]);
                    trigger.addProperty("world", note.world());
                    JsonArray bounds = new JsonArray();
                    for (double value : note.bounds()) bounds.add(value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) (long) value : (Number) value);
                    trigger.add(note.cuboid() ? "cuboid" : "ellipsoid", bounds);
                    context.put("area", "area");
                    context.put("cause", "cause");
                } else if (w.length == 3 && w[0].equals("player") && (w[1].equals("opens") || w[1].equals("closes"))) {
                    event = w[1].equals("opens") ? "player_inventory_open" : "player_inventory_close";
                    cancellable = w[1].equals("opens");
                    if (!w[2].equals("inventory")) {
                        if (!w[2].matches(NAME)) return Mapped.fail("inventory type " + w[2] + " has no Animus equivalent");
                        trigger.addProperty("inventory", w[2]);
                    }
                    context.put("inventory", "inventory_type");
                } else if (w.length >= 3 && w[0].equals("player") && w[1].equals("clicks") && w[w.length - 1].equals("inventory") && (w.length == 4 && w[2].equals("in") || w.length == 5 && w[3].equals("in"))) {
                    event = "player_inventory_click";
                    cancellable = true;
                    if (w.length == 5 && !w[2].equals("item")) {
                        if (!w[2].matches(NAME)) return Mapped.fail("item " + w[2] + " has no Animus equivalent");
                        trigger.addProperty("item", w[2]);
                    }
                    for (String name : List.of("click", "slot", "item")) context.put(name, name);
                    context.put("raw_slot", "slot");
                    context.put("inventory", "inventory_type");
                } else if (w.length == 4 && w[0].equals("player") && w[1].equals("picks") && w[2].equals("up") || w.length == 3 && w[0].equals("player") && (w[1].equals("drops") || w[1].equals("consumes"))) {
                    String item = w[w.length - 1];
                    event = w[1].equals("drops") ? "player_item_drop" : w[1].equals("consumes") ? "player_item_consume" : "player_item_pickup";
                    cancellable = true;
                    if (!item.equals("item")) {
                        if (!item.matches(NAME)) return Mapped.fail("item " + item + " has no Animus equivalent");
                        trigger.addProperty("item", item);
                    }
                    context.put("item", "item");
                } else if (w.length >= 3 && w[0].equals("player") && (w.length == 3 && w[1].equals("clicks") || w.length == 4 && (w[1].equals("right") || w[1].equals("left")) && w[2].equals("clicks"))) {
                    String block = w[w.length - 1];
                    if (List.of("entity", "npc", "player", "item", "in").contains(block)) return Mapped.fail("no Animus server trigger for the event " + phrase);
                    event = "player_block_click";
                    cancellable = true;
                    if (w.length == 4) trigger.addProperty("click", w[1]);
                    if (!block.equals("block")) {
                        if (!block.matches(NAME)) return Mapped.fail("block " + block + " has no Animus equivalent");
                        trigger.addProperty("block", block);
                    }
                    context.put("location", "location");
                    context.put("hand", "hand");
                    context.put("click_type", "click");
                } else if (w.length == 3 && w[0].equals("player") && (w[1].equals("places") || w[1].equals("breaks"))) {
                    event = w[1].equals("places") ? "player_block_place" : "player_block_break";
                    cancellable = true;
                    if (!w[2].equals("block")) {
                        if (!w[2].matches(NAME)) return Mapped.fail("block " + w[2] + " has no Animus equivalent");
                        trigger.addProperty("block", w[2]);
                    }
                    context.put("location", "location");
                    context.put("material", "block");
                } else if (w.length >= 2 && w[0].equals("player") && w[1].equals("damaged") && (w.length == 2 || w.length == 4 && w[2].equals("by"))) {
                    event = "player_damaged";
                    cancellable = true;
                    if (w.length == 4 && !w[3].equals("entity")) {
                        if (!w[3].matches(NAME)) return Mapped.fail("damage cause " + w[3] + " has no Animus equivalent");
                        if (CAUSES.containsKey(w[3])) trigger.addProperty("cause", CAUSES.get(w[3]));
                        else trigger.addProperty("damager", w[3]);
                    }
                    context.put("damage", "damage");
                    context.put("final_damage", "damage");
                    context.put("cause", "cause");
                    context.put("damager", "damager");
                } else if (w.length == 3 && w[0].equals("player") && w[1].equals("damages") || w.length == 4 && w[1].equals("damaged") && w[2].equals("by") && w[3].equals("player")) {
                    String entity = w[1].equals("damages") ? w[2] : w[0];
                    if (npcEntity) {
                        event = "interaction";
                        npc = true;
                        trigger.addProperty("action", "attack");
                        context.put("entity", "npc");
                        context.put("sneaking", "sneaking");
                    } else {
                        event = "player_damage_entity";
                        cancellable = true;
                        if (!entity.equals("entity")) {
                            if (!entity.matches(NAME)) return Mapped.fail("entity " + entity + " has no Animus equivalent");
                            trigger.addProperty("entity", entity);
                        }
                        context.put("entity", "entity");
                        context.put("damage", "damage");
                        context.put("final_damage", "damage");
                        context.put("cause", "cause");
                    }
                } else if (w.length == 3 && w[0].equals("player") && w[1].equals("kills") || w.length == 4 && w[1].equals("killed") && w[2].equals("by") && w[3].equals("player")) {
                    String entity = w[1].equals("kills") ? w[2] : w[0];
                    if (entity.equals("npc")) return Mapped.fail("NPC deaths are NPC death triggers; Animus binds a behavior to each NPC instead");
                    event = "player_kill";
                    if (!entity.equals("entity")) {
                        if (!entity.matches(NAME)) return Mapped.fail("entity " + entity + " has no Animus equivalent");
                        trigger.addProperty("entity", entity);
                    }
                    context.put("entity", "entity");
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
        boolean inventory = event.startsWith("player_inventory_");
        List<Cond> guards = new ArrayList<>();
        for (String[] option : switches.values()) {
            String name = option[0], value = option[1];
            switch (name) {
                case "every" -> { if (!event.equals("timer")) return Mapped.fail("switch every: belongs to delta time"); }
                case "priority", "bukkit_priority", "ignorecancelled" -> { /* Animus has one order, and its triggers see no cancelled events */ }
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
                case "with" -> {
                    if (!event.equals("player_block_click") && !event.equals("interaction")) return Mapped.fail("switch with: has no Animus equivalent on this event");
                    if (!value.matches("[a-z0-9_]{1,64}")) return Mapped.fail("switch with:" + value + " names an item Animus cannot check");
                    guards.add(new Atom("same(player.item_in_hand, " + DenizenExpressions.quote(value.equals("air") ? "minecraft:air" : "minecraft:" + value) + ")"));
                }
                case "click" -> {
                    if (!event.equals("player_inventory_click") || !value.matches(NAME)) return Mapped.fail("switch click:" + value + " has no Animus equivalent");
                    fields.addProperty("click", value);
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
                    if (inventory) {
                        if (!value.matches(NAME)) return Mapped.fail("switch in:" + value + " names an inventory Animus cannot check");
                        if (!value.equals("inventory")) fields.addProperty("inventory", value);
                        continue;
                    }
                    if (!player) return Mapped.fail("switch in: needs a player; " + event + " has none");
                    if (!value.matches("[a-z0-9_-]{1,64}")) return Mapped.fail("switch in:" + value + " names an area Animus cannot check");
                    if (notes.containsKey(value)) return Mapped.fail("switch in:" + value + " names a noted area; Animus checks areas only as enter and exit events");
                    guards.add(new Atom("same_world(player.world, " + DenizenExpressions.quote(value) + ")"));
                }
                default -> { return Mapped.fail("event switch " + name + ": has no Animus equivalent"); }
            }
        }
        return new Mapped(fields, guards, context, npc, cancellable || npc, null);
    }

    /** The context keys of a command script's run. */
    static Map<String, String> commandContext() {
        TreeMap<String, String> context = new TreeMap<>();
        for (String key : List.of("alias", "args", "command", "raw_args")) context.put(key, key);
        return context;
    }

    // ---- Notes ----

    /**
     * The cuboids and ellipsoids Denizen's {@code notables.yml} keeps: {@code cu@world,x1,y1,z1,x2,y2,z2} (or the older
     * {@code cu@x,y,z,world|x,y,z,world}) and {@code ellipsoid@x,y,z,world,rx,ry,rz}, by lowercase name. Cuboids with more
     * than one box and other notables are skipped with an issue each.
     */
    static TreeMap<String, Note> notables(JsonObject root, List<String> issues) {
        TreeMap<String, Note> notes = new TreeMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> section : root.entrySet()) {
            String kind = section.getKey().toLowerCase(Locale.ROOT);
            if (!section.getValue().isJsonObject() || !kind.equals("cuboids") && !kind.equals("ellipsoids")) continue;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : section.getValue().getAsJsonObject().entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                com.google.gson.JsonElement value = entry.getValue();
                com.google.gson.JsonElement written = value.isJsonObject() ? value.getAsJsonObject().get("object") : value;
                String object = written != null && written.isJsonPrimitive() ? written.getAsString() : "";
                Optional<Note> note = note(name, object);
                if (note.isEmpty()) issues.add("notables.yml: " + kind.substring(0, kind.length() - 1) + " " + name + " is not one box or one ellipsoid with literal numbers; its area events are kept");
                else notes.put(name, note.get());
            }
        }
        return notes;
    }

    /** A literal cuboid or ellipsoid: {@code cu@...}, {@code ellipsoid@...}, {@code cuboid[...]} or {@code ellipsoid[...]}. */
    static Optional<Note> note(String name, String raw) {
        String text = raw.trim();
        String lowered = text.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("<") && lowered.endsWith("]>")) {
            int open = text.indexOf('[');
            if (open < 0) return Optional.empty();
            String base = lowered.substring(1, open);
            text = (base.equals("cuboid") ? "cu@" : base.equals("ellipsoid") ? "ellipsoid@" : "?@") + text.substring(open + 1, text.length() - 2);
            lowered = text.toLowerCase(Locale.ROOT);
        }
        if (lowered.startsWith("cu@")) {
            String body = text.substring(3);
            String[] parts;
            String world;
            if (body.contains("|")) {
                String[] corners = body.split("\\|", -1);
                if (corners.length != 2) return Optional.empty();
                String[] a = corners[0].split(",", -1), b = corners[1].split(",", -1);
                if (a.length != 4 || b.length != 4 || !a[3].equals(b[3])) return Optional.empty();
                world = a[3];
                parts = new String[] {a[0], a[1], a[2], b[0], b[1], b[2]};
            } else {
                String[] all = body.split(",", -1);
                if (all.length != 7) return Optional.empty();
                world = all[0];
                parts = java.util.Arrays.copyOfRange(all, 1, 7);
            }
            return numbers(parts).filter((double[] ignored) -> world.matches("[A-Za-z0-9_./-]{1,64}")).map((double[] bounds) -> new Note(name, world, true, bounds));
        }
        if (lowered.startsWith("ellipsoid@")) {
            String[] all = text.substring(10).split(",", -1);
            if (all.length != 7 || !all[3].matches("[A-Za-z0-9_./-]{1,64}")) return Optional.empty();
            String world = all[3];
            return numbers(new String[] {all[0], all[1], all[2], all[4], all[5], all[6]})
                    .filter((double[] bounds) -> bounds[3] >= 0.1 && bounds[4] >= 0.1 && bounds[5] >= 0.1 && bounds[3] <= 100_000 && bounds[4] <= 100_000 && bounds[5] <= 100_000)
                    .map((double[] bounds) -> new Note(name, world, false, bounds));
        }
        return Optional.empty();
    }

    /** Coordinates within the world border, rounded to thousandths of a block so both converters write the same digits. */
    private static Optional<double[]> numbers(String[] parts) {
        double[] values = new double[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].trim();
            if (!part.matches("-?[0-9]{1,8}(?:\\.[0-9]{1,20})?")) return Optional.empty();
            double value = Math.round(Double.parseDouble(part) * 1000) / 1000.0;
            if (Math.abs(value) > 1_000_000) return Optional.empty();
            values[index] = value;
        }
        return Optional.of(values);
    }
}
