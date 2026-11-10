package art.arcane.animus.core.migration;

import static art.arcane.animus.core.migration.CitizensBehaviors.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citizens commands, and the commands the CitizensCMD and CommandNPC add-ons saved for the same NPC, as the routines
 * and triggers of one {@code citizens:commands-<id>} behavior. Costs become {@code animus:can-afford} and
 * {@code animus:charge}, cooldowns become expiring flags and use limits become quest stages, all in the citizens
 * namespace and keyed by NPC and command.
 */
final class CitizensCommands {
    static final String BASE = "/traits/commandtrait";
    /** A player casting a MythicMobs skill, {@code mm test cast <skill>}, which {@code mythic:cast-skill} does without the command permission. */
    private static final Pattern MYTHIC_CAST = Pattern.compile("(?i)(?:mythicmobs:)?(?:mm|mythicmobs)\\s+(?:test|t)\\s+(?:cast|c)\\s+([A-Za-z0-9_.-]{1,128})(?:\\s+-s)?\\s*");
    private static final Set<String> HANDS = Set.of("LEFT", "RIGHT", "BOTH", "SHIFT_LEFT", "SHIFT_RIGHT", "SHIFT_BOTH");
    private static final long MAX_EXPIRY = 31_536_000L;
    /** Citizens' default error messages, by its error name. {0} and {1} are filled in where the cost is known. */
    private static final Map<String, String> MESSAGES = Map.of(
            "NO_PERMISSION", "You don't have permission to do that.",
            "ON_COOLDOWN", "Please wait before using this again.",
            "ON_GLOBAL_COOLDOWN", "Please wait before using this again.",
            "MAXIMUM_TIMES_USED", "You have reached the maximum number of uses ({0}).",
            "GLOBAL_MAXIMUM_TIMES_USED", "You have reached the maximum number of uses ({0}).",
            "MISSING_MONEY", "You need at least ${0}.",
            "MISSING_EXPERIENCE", "You need at least {0} experience.",
            "MISSING_ITEM", "Missing {1} {0}");

    /** What the add-ons saved for one NPC: the CitizensCMD {@code npc-<id>} section and the CommandNPC command lines. */
    record Addons(Optional<JsonObject> citizensCmd, List<String> commandNpc) {
        static final Addons NONE = new Addons(Optional.empty(), List.of());
        Addons { citizensCmd = citizensCmd.map(JsonObject::deepCopy); commandNpc = List.copyOf(commandNpc); }
        boolean any() { return citizensCmd.isPresent() || !commandNpc.isEmpty(); }
    }
    /** {@code entry} is empty when nothing converted. */
    record Result(Optional<String> entry, JsonObject routines, JsonArray triggers) {}

    private record Command(int id, String hand, JsonObject node) {}

    private final String key, name;
    private final List<String> warnings;
    private final Set<String> converted;
    private boolean hide;
    private final Map<String, String> custom = new HashMap<>();

    private CitizensCommands(String key, String name, List<String> warnings, Set<String> converted) {
        this.key = key; this.name = name; this.warnings = warnings; this.converted = converted;
    }

    static Result map(String key, String name, JsonObject traits, Addons addons, List<String> warnings, Set<String> converted) {
        return new CitizensCommands(key, name, warnings, converted).convert(object(traits.get("commandtrait")), addons);
    }

    private Result convert(JsonObject trait, Addons addons) {
        Map<String, List<JsonObject>> clicks = new LinkedHashMap<>();
        for (String action : List.of("interact", "attack", "sneak_interact", "sneak_attack")) clicks.put(action, new ArrayList<>());
        if (trait != null) citizens(trait, clicks);
        addons.citizensCmd().ifPresent((JsonObject section) -> citizensCmd(section, clicks));
        if (!addons.commandNpc().isEmpty()) commandNpc(addons.commandNpc(), clicks);
        JsonObject routines = new JsonObject();
        JsonArray triggers = new JsonArray();
        List<JsonObject> right = clicks.get("interact"), left = clicks.get("attack");
        if (!right.isEmpty() && right.equals(left)) routine("click", "any", right, routines, triggers);
        else {
            routine("right", "interact", right, routines, triggers);
            routine("left", "attack", left, routines, triggers);
        }
        routine("sneak-right", "sneak_interact", clicks.get("sneak_interact"), routines, triggers);
        routine("sneak-left", "sneak_attack", clicks.get("sneak_attack"), routines, triggers);
        if (routines.isEmpty()) return new Result(Optional.empty(), routines, triggers);
        warnings.add(BASE + ": commands stay denied until " + NAMESPACE + ":commands-" + key + " is on the [behaviors.actions] commandBehaviors list in config.toml");
        return new Result(Optional.of(routines.keySet().iterator().next()), routines, triggers);
    }

    private static void routine(String routine, String action, List<JsonObject> steps, JsonObject routines, JsonArray triggers) {
        if (steps.isEmpty()) return;
        routines.add(routine, sequence(steps));
        JsonObject trigger = new JsonObject();
        trigger.addProperty("id", routine);
        trigger.addProperty("event", "interaction");
        trigger.addProperty("action", action);
        trigger.addProperty("routine", routine);
        triggers.add(trigger);
    }

    // ---- Citizens command trait ----

    private void citizens(JsonObject trait, Map<String, List<JsonObject>> clicks) {
        hide = bool(trait.get("hideErrorMessages"), false);
        if (trait.has("hideErrorMessages")) converted.add(BASE + "/hideErrorMessages");
        JsonObject messages = object(trait.get("customErrorMessages"));
        if (messages != null) for (Map.Entry<String, JsonElement> message : messages.entrySet())
            if (MESSAGES.containsKey(message.getKey()) && message.getValue().isJsonPrimitive()) {
                custom.put(message.getKey(), message.getValue().getAsString());
                converted.add(BASE + "/customErrorMessages/" + pointer(message.getKey()));
            }
        if (!entries(trait.get("temporaryPermissions")).isEmpty())
            warnings.add(BASE + "/temporaryPermissions: temporary permissions are not granted; player commands run with the player's own permissions");
        Optional<Charge> clickCost = cost(trait.get("cost"), trait.get("experienceCost"), trait.get("itemRequirements"), BASE, "itemRequirements");
        if (clickCost == null) { warnings.add(BASE + ": the click cost has items that cannot be matched exactly, so no commands were converted"); return; }
        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entries(trait.get("commands"))) command(entry.getKey(), object(entry.getValue())).ifPresent(commands::add);
        if (commands.isEmpty()) return;
        commands.sort(Comparator.comparingInt(Command::id));
        String mode = string(trait.get("executionMode"), "LINEAR").toUpperCase(Locale.ROOT);
        if (trait.has("executionMode")) converted.add(BASE + "/executionMode");
        if (!Set.of("LINEAR", "RANDOM", "CYCLE", "SEQUENTIAL").contains(mode)) { warnings.add(BASE + "/executionMode: " + mode + " is not known; commands run in order"); mode = "LINEAR"; }
        Map<String, List<Command>> hands = new LinkedHashMap<>();
        hands.put("interact", hand(commands, "RIGHT", "BOTH"));
        hands.put("attack", hand(commands, "LEFT", "BOTH"));
        hands.put("sneak_interact", hand(commands, "SHIFT_RIGHT", "SHIFT_BOTH"));
        hands.put("sneak_attack", hand(commands, "SHIFT_LEFT", "SHIFT_BOTH"));
        Map<String, String> routines = Map.of("interact", "right", "attack", "left", "sneak_interact", "sneak-right", "sneak_attack", "sneak-left");
        List<Command> right = hands.get("interact");
        for (Map.Entry<String, List<Command>> hand : hands.entrySet()) {
            if (hand.getValue().isEmpty()) continue;
            String routine = hand.getKey().equals("attack") && right.equals(hand.getValue()) ? "right" : routines.get(hand.getKey());
            JsonObject body = mode(hand.getValue(), mode, "commands-" + key + "-" + routine);
            if (clickCost.isPresent()) body = clickCost.get().gate(body);
            clicks.get(hand.getKey()).add(body);
        }
        if ((!hands.get("sneak_interact").isEmpty() || !hands.get("sneak_attack").isEmpty()) && commands.stream().anyMatch((Command command) -> !command.hand().startsWith("SHIFT_")))
            warnings.add(BASE + ": LEFT, RIGHT and BOTH commands also run on a sneaking click, beside the SHIFT ones");
    }

    private JsonObject mode(List<Command> commands, String mode, String quest) {
        List<JsonObject> nodes = new ArrayList<>();
        for (Command command : commands.subList(0, Math.min(commands.size(), MAX_CHOICES))) nodes.add(command.node());
        if (commands.size() > MAX_CHOICES) warnings.add(BASE + "/commands: only the first " + MAX_CHOICES + " commands for one click were converted");
        return switch (mode) {
            case "RANDOM" -> select(nodes, quest, "npc", true);
            case "CYCLE" -> select(nodes, quest, "npc", false);
            case "SEQUENTIAL" -> select(nodes, quest, "relationship", false);
            default -> sequence(nodes);
        };
    }

    private static List<Command> hand(List<Command> commands, String hand, String both) {
        return commands.stream().filter((Command command) -> command.hand().equals(hand) || command.hand().equals(both)).toList();
    }

    private Optional<Command> command(String id, JsonObject data) {
        String at = BASE + "/commands/" + id;
        if (data == null || !data.has("command") || !data.get("command").isJsonPrimitive()) return Optional.empty();
        String hand = string(data.get("hand"), "BOTH").toUpperCase(Locale.ROOT);
        if (!HANDS.contains(hand)) { warnings.add(at + "/hand: " + hand + " is not a Citizens click; not converted"); return Optional.empty(); }
        Optional<Charge> cost = cost(data.get("cost"), data.get("experienceCost"), data.get("itemCost"), at, "itemCost");
        if (cost == null) { warnings.add(at + "/itemCost: an item cost cannot be matched exactly; not converted"); return Optional.empty(); }
        boolean npc = bool(data.get("npc"), false), player = bool(data.get("player"), false), op = bool(data.get("op"), false);
        String text = data.get("command").getAsString();
        if (text.startsWith("/")) text = text.substring(1);
        text = placeholders(text, at + "/command", npc);
        if (text.isEmpty() || text.length() > MAX_TEXT) { warnings.add(at + "/command: empty or longer than 1024 characters; not converted"); return Optional.empty(); }
        Matcher mythic = MYTHIC_CAST.matcher(text);
        JsonObject run;
        if ((player || op) && !npc && mythic.matches()) {
            JsonObject arguments = arguments("skill", mythic.group(1));
            arguments.addProperty("caster", "player");
            run = action("mythic:cast-skill", arguments);
            warnings.add(at + "/command: the MythicMobs skill cast became mythic:cast-skill, which needs MythicMobs on the server");
        } else {
            if (op) warnings.add(at + "/op: the command ran as an operator player; it now runs from the console");
            if (npc && player) warnings.add(at + "/npc: the NPC's own player body ran the command; it now runs from the console, with the NPC's name for the player");
            JsonObject arguments = arguments("command", text);
            arguments.addProperty("as", player && !op && !npc ? "player" : "console");
            run = action("animus:command", arguments);
        }
        String flag = "commands-" + key + "-" + id;
        long cooldown = seconds(data.get("cooldown"), at + "/cooldown"), globalCooldown = seconds(data.get("globalcooldown"), at + "/globalcooldown");
        long uses = whole(data.get("n"), at + "/n"), globalUses = whole(data.get("gn"), at + "/gn");
        List<JsonObject> effects = new ArrayList<>();
        cost.ifPresent((Charge charge) -> effects.add(charge.take()));
        if (cooldown > 0) effects.add(setFlag(flag + "-cooldown", "relationship", cooldown));
        if (globalCooldown > 0) effects.add(setFlag(flag + "-global-cooldown", "npc", globalCooldown));
        if (uses > 0) effects.add(action("animus:advance-quest-state", quest(flag + "-uses", "relationship")));
        if (globalUses > 0) effects.add(action("animus:advance-quest-state", quest(flag + "-global-uses", "npc")));
        effects.add(run);
        JsonObject node = sequence(effects);
        if (cost.isPresent()) node = cost.get().afford(node);
        if (globalUses > 0) node = refuse(stage(flag + "-global-uses", "npc", globalUses), "GLOBAL_MAXIMUM_TIMES_USED", Long.toString(globalUses), "", node);
        if (uses > 0) node = refuse(stage(flag + "-uses", "relationship", uses), "MAXIMUM_TIMES_USED", Long.toString(uses), "", node);
        if (globalCooldown > 0) node = refuse(flagSet(flag + "-global-cooldown", "npc"), "ON_GLOBAL_COOLDOWN", "", "", node);
        if (cooldown > 0) node = refuse(flagSet(flag + "-cooldown", "relationship"), "ON_COOLDOWN", "", "", node);
        List<Map.Entry<String, JsonElement>> permissions = entries(data.get("permissions"));
        for (int index = permissions.size() - 1; index >= 0; index--) {
            JsonElement permission = permissions.get(index).getValue();
            if (!permission.isJsonPrimitive()) continue;
            node = otherwise(condition("animus:has-permission", arguments("permission", permission.getAsString()), node), message("NO_PERMISSION", "", ""));
            converted.add(at + "/permissions/" + permissions.get(index).getKey());
        }
        if (number(data.get("delay")) && data.get("delay").getAsDouble() >= 1) {
            JsonObject wait = new JsonObject();
            wait.addProperty("type", "wait");
            wait.addProperty("ticks", clamp((long) Math.floor(data.get("delay").getAsDouble()), 1, MAX_COOLDOWN));
            node = sequence(List.of(wait, node));
            converted.add(at + "/delay");
        }
        for (String field : List.of("command", "hand", "player", "op", "npc")) if (data.has(field)) converted.add(at + "/" + field);
        return Optional.of(new Command(Integer.parseInt(id), hand, node));
    }

    /** Whole seconds of a cooldown, at most a year. */
    private long seconds(JsonElement value, String at) {
        if (!number(value)) return 0;
        converted.add(at);
        return clamp((long) Math.floor(value.getAsDouble()), 0, MAX_EXPIRY);
    }

    private long whole(JsonElement value, String at) {
        if (!number(value)) return 0;
        converted.add(at);
        return clamp((long) Math.floor(value.getAsDouble()), 0, 1_000_000);
    }

    /**
     * A command cost: money, experience levels and items. Empty when there is none; null when an item cannot be matched
     * exactly, so the command cannot charge it.
     */
    private Optional<Charge> cost(JsonElement money, JsonElement levels, JsonElement items, String at, String itemField) {
        JsonObject arguments = new JsonObject();
        List<String> failures = new ArrayList<>();
        if (positive(money)) {
            arguments.addProperty("money", CitizensStacks.money(money.getAsDouble()));
            converted.add(at + "/cost");
        }
        if (positive(levels)) {
            arguments.addProperty("levels", clamp((long) Math.floor(levels.getAsDouble()), 1, 100_000));
            converted.add(at + "/experienceCost");
        }
        JsonArray wanted = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : entries(items)) {
            Optional<CitizensStacks.Stack> stack = CitizensStacks.read(entry.getValue());
            if (stack.isEmpty()) continue;
            if (stack.get().inexact().isPresent()) { failures.add(stack.get().inexact().get()); continue; }
            JsonObject item = new JsonObject();
            item.add("item", stack.get().item());
            item.addProperty("amount", Math.min(stack.get().amount(), 6400));
            wanted.add(item);
            converted.add(at + "/" + itemField + "/" + entry.getKey());
        }
        if (!failures.isEmpty()) return null;
        if (!wanted.isEmpty()) arguments.add("items", wanted);
        return arguments.isEmpty() ? Optional.empty() : Optional.of(new Charge(arguments));
    }

    /** One cost: checked part by part so the player hears which part is missing, then taken at once. */
    private final class Charge {
        private final JsonObject arguments;
        Charge(JsonObject arguments) { this.arguments = arguments; }

        JsonObject take() { return action("animus:charge", arguments.deepCopy()); }

        /** Runs {@code then} only when every part is held; otherwise says what is missing. */
        JsonObject afford(JsonObject then) {
            JsonObject node = then;
            if (arguments.has("items")) {
                JsonArray items = arguments.getAsJsonArray("items");
                for (int index = items.size() - 1; index >= 0; index--) {
                    JsonObject part = new JsonObject();
                    JsonArray one = new JsonArray();
                    one.add(items.get(index).deepCopy());
                    part.add("items", one);
                    JsonObject item = items.get(index).getAsJsonObject();
                    String id = item.getAsJsonObject("item").get("id").getAsString();
                    node = otherwise(condition("animus:can-afford", part, node), message("MISSING_ITEM", readable(id), item.get("amount").getAsString()));
                }
            }
            if (arguments.has("levels")) node = otherwise(condition("animus:can-afford", only("levels"), node), message("MISSING_EXPERIENCE", arguments.get("levels").getAsString(), ""));
            if (arguments.has("money")) node = otherwise(condition("animus:can-afford", only("money"), node), message("MISSING_MONEY", arguments.get("money").getAsBigDecimal().toPlainString(), ""));
            return node;
        }

        /** {@code body} behind {@link #afford}, with the cost taken first. */
        JsonObject gate(JsonObject body) { return afford(sequence(List.of(take(), body))); }

        private JsonObject only(String field) {
            JsonObject part = new JsonObject();
            part.add(field, arguments.get(field).deepCopy());
            return part;
        }
    }

    private static String readable(String id) {
        String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    // ---- CitizensCMD ----

    private void citizensCmd(JsonObject section, Map<String, List<JsonObject>> clicks) {
        String at = "/addons/citizenscmd";
        long cooldown = number(section.get("cooldown")) ? (long) Math.floor(section.get("cooldown").getAsDouble()) : 0;
        double price = positive(section.get("price")) ? section.get("price").getAsDouble() : 0;
        String permission = string(section.get("permission"), "");
        String flag = "citizenscmd-" + key + "-cooldown";
        for (String click : List.of("right", "left")) {
            List<JsonObject> steps = new ArrayList<>();
            for (Map.Entry<String, JsonElement> line : entries(section.get(click + "-click-commands"))) {
                if (!line.getValue().isJsonPrimitive()) continue;
                citizensCmdLine(line.getValue().getAsString(), at + "/" + click + "-click-commands/" + line.getKey()).ifPresent(steps::add);
            }
            if (steps.isEmpty()) continue;
            List<JsonObject> body = new ArrayList<>();
            if (price > 0 && click.equals("right")) {
                JsonObject cost = new JsonObject();
                cost.addProperty("money", CitizensStacks.money(price));
                body.add(action("animus:charge", cost));
            }
            body.addAll(steps);
            if (cooldown != 0) body.add(cooldown > 0 ? setFlag(flag, "relationship", Math.min(cooldown, MAX_EXPIRY)) : setFlag(flag, "relationship", 0));
            JsonObject node = sequence(body);
            if (price > 0 && click.equals("right")) {
                JsonObject cost = new JsonObject();
                cost.addProperty("money", CitizensStacks.money(price));
                node = otherwise(condition("animus:can-afford", cost, node), plain("You don't have enough money to use this."));
            }
            if (cooldown != 0) node = otherwise(condition("animus:flag-state", flagArguments(flag, "relationship"), plain(cooldown > 0 ? "Please wait before clicking again." : "You can only use this once.")), node);
            if (!permission.isEmpty()) node = condition("animus:has-permission", arguments("permission", permission), node);
            clicks.get(click.equals("right") ? "interact" : "attack").add(node);
        }
        if (price > 0) {
            warnings.add(at + "/price: right clicks charge at once instead of asking for a second click to confirm");
            if (!entries(section.get("left-click-commands")).isEmpty()) warnings.add(at + "/price: left-click commands run without charging, as CitizensCMD runs them after a cancelled payment");
        }
    }

    /** One {@code [kind(delay)] command} line. */
    private Optional<JsonObject> citizensCmdLine(String line, String at) {
        Matcher main = Pattern.compile("\\[([^]]*)] (.*)").matcher(line);
        if (!main.find()) { warnings.add(at + ": not a [type] command line; not converted"); return Optional.empty(); }
        String kind = main.group(1), command = main.group(2).replace("%p%", "{player_name}").replace("%player%", "{player_name}");
        long delay = 0;
        Matcher timed = Pattern.compile("(.*)\\(([^]]*)\\)").matcher(kind);
        if (kind.contains("(") && timed.find()) {
            kind = timed.group(1);
            try { delay = Math.round(Double.parseDouble(timed.group(2)) * 20); }
            catch (NumberFormatException invalid) { warnings.add(at + ": the delay is not a number; it runs at once"); }
        }
        if (command.isEmpty() || command.length() > MAX_TEXT) { warnings.add(at + ": empty or longer than 1024 characters; not converted"); return Optional.empty(); }
        JsonObject node;
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "console", "none" -> {
                if (command.startsWith("/")) command = command.substring(1);
                JsonObject arguments = arguments("command", command);
                arguments.addProperty("as", kind.equalsIgnoreCase("console") ? "console" : "player");
                node = action("animus:command", arguments);
            }
            case "message" -> {
                String text = command.replace("{name}", name).replace("{display}", "");
                node = action("animus:message", arguments("text", text));
            }
            case "server", "sound" -> { warnings.add(at + ": " + kind + " lines are not supported; not converted"); return Optional.empty(); }
            default -> {
                if (command.startsWith("/")) command = command.substring(1);
                JsonObject arguments = arguments("command", command);
                arguments.addProperty("as", "player");
                node = action("animus:command", arguments);
                warnings.add(at + ": the permission " + kind + " is not granted for the command; it runs with the player's own permissions");
            }
        }
        if (command.matches("(?s).*%[A-Za-z0-9_]+%.*")) warnings.add(at + ": PlaceholderAPI placeholders other than %p% and %player% are not filled in");
        if (delay < 1) return Optional.of(node);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "wait");
        wait.addProperty("ticks", clamp(delay, 1, MAX_COOLDOWN));
        return Optional.of(sequence(List.of(wait, node)));
    }

    // ---- CommandNPC ----

    private void commandNpc(List<String> lines, Map<String, List<JsonObject>> clicks) {
        String base = "/addons/commandnpc";
        Map<String, List<JsonObject>> hands = new LinkedHashMap<>();
        hands.put("interact", new ArrayList<>());
        hands.put("attack", new ArrayList<>());
        boolean random = false;
        for (int index = 0; index < lines.size(); index++) {
            String at = base + "/" + index;
            String[] fields = lines.get(index).split("~~~", -1);
            if (!Set.of(8, 9, 11, 13).contains(fields.length)) { warnings.add(at + ": a CommandNPC line of " + fields.length + " fields is not a known version; not converted"); continue; }
            boolean modern = fields.length >= 11;
            String command = fields[1], permission = fields[2], click = fields[3].toUpperCase(Locale.ROOT);
            String cooldownMessage = modern ? fields[4] : "";
            int offset = modern ? 5 : 4;
            boolean console = Boolean.parseBoolean(fields[offset]), op = Boolean.parseBoolean(fields[offset + 1]);
            boolean pick = fields.length != 8 && Boolean.parseBoolean(fields[offset + 2]);
            int numbers = fields.length == 8 ? offset + 2 : offset + 3;
            double cost;
            long delay, cooldown;
            try {
                cost = Double.parseDouble(fields[numbers]);
                delay = Long.parseLong(fields[numbers + 1]);
                cooldown = modern ? Long.parseLong(fields[numbers + 2]) : 0;
            } catch (NumberFormatException invalid) { warnings.add(at + ": a number in the line cannot be read; not converted"); continue; }
            boolean quietPermission = fields.length == 13 && Boolean.parseBoolean(fields[11]), quietMoney = fields.length == 13 && Boolean.parseBoolean(fields[12]);
            random |= pick;
            if (command.toLowerCase(Locale.ROOT).startsWith("server ")) { warnings.add(at + ": BungeeCord server switches are not supported; not converted"); continue; }
            command = command.replace("%name", "{player_name}");
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isEmpty() || command.length() > MAX_TEXT) { warnings.add(at + ": empty or longer than 1024 characters; not converted"); continue; }
            if (op && !console) warnings.add(at + ": the command ran as an operator player; it now runs from the console");
            JsonObject arguments = arguments("command", command);
            arguments.addProperty("as", console || op ? "console" : "player");
            String flag = "commandnpc-" + key + "-" + fields[0].replaceAll("[^0-9]", "") + "-cooldown";
            List<JsonObject> body = new ArrayList<>();
            JsonObject money = new JsonObject();
            if (cost > 0) {
                money.addProperty("money", CitizensStacks.money(cost));
                body.add(action("animus:charge", money.deepCopy()));
            }
            if (delay > 0) {
                JsonObject wait = new JsonObject();
                wait.addProperty("type", "wait");
                wait.addProperty("ticks", clamp(delay, 1, MAX_COOLDOWN));
                body.add(wait);
            }
            body.add(action("animus:command", arguments));
            if (cooldown > 0) body.add(setFlag(flag, "relationship", Math.max(1, (cooldown + 19) / 20)));
            JsonObject node = sequence(body);
            if (cost > 0) node = quietMoney ? condition("animus:can-afford", money, node) : otherwise(condition("animus:can-afford", money, node), plain("You do not have enough money."));
            if (cooldown > 0) node = cooldownMessage.isEmpty() ? otherwise(condition("animus:flag-state", flagArguments(flag, "relationship"), sequence(List.of())), node)
                    : otherwise(condition("animus:flag-state", flagArguments(flag, "relationship"), plain(cooldownMessage)), node);
            if (!permission.isEmpty() && !permission.equalsIgnoreCase("noPerm")) {
                JsonObject gated = condition("animus:has-permission", arguments("permission", permission), node);
                node = quietPermission ? gated : otherwise(gated, plain("You do not have permission."));
            }
            for (String hand : click.equals("LEFT") || click.equals("PUNCH") ? List.of("attack") : click.equals("BOTH") || click.equals("ALL") ? List.of("interact", "attack") : List.of("interact"))
                hands.get(hand).add(node);
        }
        for (Map.Entry<String, List<JsonObject>> hand : hands.entrySet()) {
            if (hand.getValue().isEmpty()) continue;
            clicks.get(hand.getKey()).add(random ? select(hand.getValue(), "commandnpc-" + key + "-" + hand.getKey(), "npc", true) : sequence(hand.getValue()));
        }
    }

    // ---- Nodes ----

    private JsonObject message(String error, String first, String second) {
        if (hide) return null;
        String text = custom.getOrDefault(error, MESSAGES.get(error)).replace("{0}", first).replace("{1}", second);
        if (text.isBlank()) return null;
        return plain(placeholders(text, BASE + "/customErrorMessages/" + error, false));
    }

    private static JsonObject plain(String text) { return action("animus:message", arguments("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text)); }

    /** {@code then} when the check holds; otherwise {@code otherwise}, when there is one. */
    private static JsonObject otherwise(JsonObject condition, JsonObject otherwise) {
        if (otherwise != null) condition.add("else", otherwise);
        return condition;
    }

    /** Runs {@code message} when the check holds and {@code node} otherwise. */
    private JsonObject refuse(JsonObject check, String error, String first, String second, JsonObject node) {
        JsonObject message = message(error, first, second);
        check.add("then", message == null ? sequence(List.of()) : message);
        check.add("else", node);
        return check;
    }

    private static JsonObject flagSet(String name, String scope) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "condition");
        node.addProperty("capability", "animus:flag-state");
        node.add("arguments", flagArguments(name, scope));
        return node;
    }

    private static JsonObject stage(String name, String scope, long atLeast) {
        JsonObject arguments = quest(name, scope);
        arguments.addProperty("atLeast", atLeast);
        JsonObject node = new JsonObject();
        node.addProperty("type", "condition");
        node.addProperty("capability", "animus:quest-state");
        node.add("arguments", arguments);
        return node;
    }

    private static JsonObject flagArguments(String name, String scope) { return quest(name, scope); }

    /** Sets the flag; with {@code seconds} above 0 it expires then. */
    private static JsonObject setFlag(String name, String scope, long seconds) {
        JsonObject arguments = quest(name, scope);
        if (seconds > 0) arguments.addProperty("expireSeconds", Math.max(1, seconds));
        return action("animus:set-flag", arguments);
    }

    /** Citizens' clicking-player placeholders become {player_name}, or the NPC's name when the NPC runs it; the NPC's own name and id are written in. */
    private String placeholders(String text, String path, boolean npc) {
        String player = npc ? name : "{player_name}";
        String result = text.replace("<player>", player).replace("<p>", player).replace("<npc>", name).replace("<id>", key);
        if (result.matches("(?s).*(<[a-z_]+>|%[a-z_]+%).*")) warnings.add(path + ": placeholders other than <p>, <player>, <npc> and <id> are not filled in");
        return result;
    }
}
