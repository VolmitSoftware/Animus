package art.arcane.animus.core.quest;

import art.arcane.animus.core.behavior.BehaviorExpression;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.BehaviorValues;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.platform.MinecraftIds;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A quest file at {@code quests/<namespace>/<name>.json}: who offers it, what it asks in ordered stages and what it
 * pays. Every problem is reported as {@code $.path: message} and refuses the whole quest. The name is also the quest's
 * stage variable in behavior state, so behaviors of the same namespace read it with {@code quest('player', name)}.
 */
public record QuestDocument(String id, long revision, String title, String description, Optional<NpcItem> icon, List<String> givers,
                            Optional<String> turnIn, Requirements requirements, Repeat repeat, List<Stage> stages, Rewards rewards, Messages messages) {
    public static final int SCHEMA_VERSION = 1, MAXIMUM_STAGES = 32, MAXIMUM_OBJECTIVES = 16, MAXIMUM_GIVERS = 16, MAXIMUM_AMOUNT = 100_000;
    public static final String ID = "[a-z0-9_-]{1,64}:[a-z0-9][a-z0-9_-]{0,63}", NAME = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final String PERMISSION = "[A-Za-z0-9_-]+(\\.[A-Za-z0-9_*-]+)*";
    private static final BigDecimal MAXIMUM_MONEY = new BigDecimal("1000000000000");

    public enum Type {
        TALK, BRING, KILL, REACH, COLLECT, CUSTOM;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    public enum RepeatMode { NEVER, COOLDOWN, DAILY }

    /** {@code condition} is compiled against no variables; it reads this quest namespace's flags and quests. */
    public record Requirements(Optional<String> permission, List<String> quests, Optional<String> source, Optional<BehaviorExpression> condition) {
        public static final Requirements NONE = new Requirements(Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        public Requirements { quests = List.copyOf(quests); }
    }
    public record Repeat(RepeatMode mode, long seconds) {
        public static final Repeat NEVER = new Repeat(RepeatMode.NEVER, 0);
    }
    /** A point a reach objective needs the player within {@code radius} blocks of. */
    public record Point(String world, double x, double y, double z, double radius) {}
    /** {@code amount} is 1 for talk and reach. {@code npc} is the talk or bring NPC, or the NPC to kill instead of an entity type. */
    public record Objective(Type type, String id, Optional<String> description, Optional<String> npc, Optional<NpcItem> item,
                            Optional<String> entity, int amount, Optional<Point> point) {}
    public record Stage(String description, boolean ordered, List<Objective> objectives) {
        public Stage { objectives = List.copyOf(objectives); }
    }
    public record Goods(NpcItem item, int amount) {}
    public record Command(String command, boolean console) {}
    public record Flag(String name, BehaviorProgram.Value value, boolean global) {}
    public record Rewards(List<Goods> items, Optional<BigDecimal> money, int experience, List<Command> commands, List<Flag> flags) {
        public static final Rewards NONE = new Rewards(List.of(), Optional.empty(), 0, List.of(), List.of());
        public Rewards { items = List.copyOf(items); commands = List.copyOf(commands); flags = List.copyOf(flags); }
    }
    public record Messages(Optional<String> offer, Optional<String> accept, Optional<String> decline, Optional<String> complete) {
        public static final Messages NONE = new Messages(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    public record Result(Optional<QuestDocument> quest, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    public QuestDocument { givers = List.copyOf(givers); stages = List.copyOf(stages); }

    public String namespace() { return id.substring(0, id.indexOf(':')); }
    /** The stage variable in behavior state. */
    public String name() { return id.substring(id.indexOf(':') + 1); }
    /** The behavior stage value of a finished quest: one past the last stage. */
    public long finishedStage() { return stages.size() + 1L; }
    public boolean hasCommands() { return !rewards.commands().isEmpty(); }

    /** Every NPC this quest asks the player to click: givers, the turn-in NPC and talk and bring objectives. */
    public Set<String> npcs() {
        Set<String> npcs = new HashSet<>(givers);
        turnIn.ifPresent(npcs::add);
        for (Stage stage : stages) for (Objective objective : stage.objectives())
            if (objective.type() == Type.TALK || objective.type() == Type.BRING) objective.npc().ifPresent(npcs::add);
        return Set.copyOf(npcs);
    }

    public static Result parse(String id, String json) {
        try { return parse(id, JsonDocuments.object(json)); }
        catch (IOException invalid) { return new Result(Optional.empty(), List.of("$: " + invalid.getMessage())); }
    }

    public static Result parse(String expectedId, JsonObject object) {
        Reader reader = new Reader();
        reader.fields(object, Set.of("schemaVersion", "revision", "id", "title", "description", "icon", "givers", "turnIn", "requirements",
                "repeat", "stages", "rewards", "messages", "imported"), "$");
        if (!object.has("schemaVersion")) reader.problem("$.schemaVersion", "expected integer 1");
        else if (reader.integer(object, "schemaVersion", "$", 1, Integer.MAX_VALUE, SCHEMA_VERSION) != SCHEMA_VERSION)
            reader.problem("$.schemaVersion", "Animus reads quest schemaVersion " + SCHEMA_VERSION);
        long revision = reader.integer(object, "revision", "$", 1, Long.MAX_VALUE, 1);
        String id = object.has("id") ? reader.string(object, "id", "$", 128).orElse(expectedId) : expectedId;
        if (!id.matches(ID)) reader.problem("$.id", "expected namespace:name in lowercase letters, digits, _ and -, without /");
        else if (!id.equals(expectedId)) reader.problem("$.id", "id " + id + " does not match its file, which names " + expectedId);
        String title = reader.string(object, "title", "$", 128).orElse(id);
        String description = object.has("description") ? reader.text(object, "description", "$", 1024).orElse("") : "";
        Optional<NpcItem> icon = object.has("icon") ? NpcItem.parse(object.get("icon"), "$.icon", reader.problems) : Optional.empty();
        List<String> givers = reader.npcs(object.get("givers"), "$.givers");
        Optional<String> turnIn = object.has("turnIn") ? reader.npc(object.get("turnIn"), "$.turnIn") : Optional.empty();
        Requirements requirements = object.has("requirements") ? reader.requirements(object.get("requirements"), "$.requirements") : Requirements.NONE;
        for (int index = 0; index < requirements.quests().size(); index++)
            if (requirements.quests().get(index).equals(expectedId)) reader.problem("$.requirements.quests[" + index + "]", "a quest cannot require itself");
        Repeat repeat = object.has("repeat") ? reader.repeat(object.get("repeat"), "$.repeat") : Repeat.NEVER;
        List<Stage> stages = new ArrayList<>();
        JsonElement stageList = object.get("stages");
        if (stageList == null || !stageList.isJsonArray() || stageList.getAsJsonArray().isEmpty() || stageList.getAsJsonArray().size() > MAXIMUM_STAGES)
            reader.problem("$.stages", "expected 1 to " + MAXIMUM_STAGES + " stages");
        else {
            JsonArray array = stageList.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) reader.stage(array.get(index), "$.stages[" + index + "]").ifPresent(stages::add);
        }
        Rewards rewards = object.has("rewards") ? reader.rewards(object.get("rewards"), "$.rewards") : Rewards.NONE;
        Messages messages = object.has("messages") ? reader.messages(object.get("messages"), "$.messages") : Messages.NONE;
        if (object.has("imported") && !object.get("imported").isJsonObject()) reader.problem("$.imported", "expected object");
        if (!reader.problems.isEmpty()) return new Result(Optional.empty(), reader.problems);
        return new Result(Optional.of(new QuestDocument(id, revision, title, description, icon, givers, turnIn, requirements, repeat, stages, rewards, messages)), List.of());
    }

    private static final class Reader {
        final List<String> problems = new ArrayList<>();

        void problem(String path, String message) { problems.add(path + ": " + message); }

        void fields(JsonObject object, Set<String> known, String path) {
            for (String key : object.keySet()) if (!known.contains(key)) problem(path + "." + key, "unknown field");
        }

        Optional<JsonObject> object(JsonElement value, String path) {
            if (value != null && value.isJsonObject()) return Optional.of(value.getAsJsonObject());
            problem(path, "expected object");
            return Optional.empty();
        }

        Optional<String> string(JsonObject object, String field, String path, int maximum) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
                problem(path + "." + field, "expected nonblank string");
                return Optional.empty();
            }
            if (value.getAsString().length() > maximum) { problem(path + "." + field, "at most " + maximum + " characters"); return Optional.empty(); }
            return Optional.of(value.getAsString());
        }

        /** Like {@link #string} but an empty string is allowed. */
        Optional<String> text(JsonObject object, String field, String path, int maximum) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { problem(path + "." + field, "expected string"); return Optional.empty(); }
            if (value.getAsString().length() > maximum) { problem(path + "." + field, "at most " + maximum + " characters"); return Optional.empty(); }
            return Optional.of(value.getAsString());
        }

        long integer(JsonObject object, String field, String path, long minimum, long maximum, long fallback) {
            if (!object.has(field)) return fallback;
            JsonElement value = object.get(field);
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                long number = value.getAsBigDecimal().longValueExact();
                if (number < minimum || number > maximum) throw new ArithmeticException();
                return number;
            } catch (ArithmeticException | NumberFormatException invalid) {
                problem(path + "." + field, "expected integer " + minimum + ".." + maximum);
                return fallback;
            }
        }

        Optional<Double> number(JsonObject object, String field, String path, double minimum, double maximum) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { problem(path + "." + field, "expected number"); return Optional.empty(); }
            double number = value.getAsDouble();
            if (!Double.isFinite(number) || number < minimum || number > maximum) { problem(path + "." + field, "expected a number from " + minimum + " to " + maximum); return Optional.empty(); }
            return Optional.of(number);
        }

        Optional<String> npc(JsonElement value, String path) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches(NAME)) {
                problem(path, "expected an NPC id such as healer");
                return Optional.empty();
            }
            return Optional.of(value.getAsString());
        }

        List<String> npcs(JsonElement value, String path) {
            List<String> npcs = new ArrayList<>();
            if (value == null) return npcs;
            if (!value.isJsonArray() || value.getAsJsonArray().size() > MAXIMUM_GIVERS) { problem(path, "expected at most " + MAXIMUM_GIVERS + " NPC ids"); return npcs; }
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                Optional<String> npc = npc(array.get(index), path + "[" + index + "]");
                if (npc.isPresent() && npcs.contains(npc.get())) problem(path + "[" + index + "]", "NPC " + npc.get() + " is listed twice");
                else npc.ifPresent(npcs::add);
            }
            return npcs;
        }

        Requirements requirements(JsonElement value, String path) {
            Optional<JsonObject> object = object(value, path);
            if (object.isEmpty()) return Requirements.NONE;
            fields(object.get(), Set.of("permission", "quests", "condition"), path);
            Optional<String> permission = Optional.empty();
            if (object.get().has("permission")) {
                permission = string(object.get(), "permission", path, 128);
                if (permission.isPresent() && !permission.get().matches(PERMISSION)) { problem(path + ".permission", "expected a permission node such as town.quests.herbs"); permission = Optional.empty(); }
            }
            List<String> quests = new ArrayList<>();
            if (object.get().has("quests")) {
                JsonElement list = object.get().get("quests");
                if (!list.isJsonArray() || list.getAsJsonArray().size() > 32) problem(path + ".quests", "expected at most 32 quest ids");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++) {
                    JsonElement quest = list.getAsJsonArray().get(index);
                    if (!quest.isJsonPrimitive() || !quest.getAsJsonPrimitive().isString() || !quest.getAsString().matches(ID)) problem(path + ".quests[" + index + "]", "expected a quest id such as town:intro");
                    else if (quests.contains(quest.getAsString())) problem(path + ".quests[" + index + "]", "quest " + quest.getAsString() + " is listed twice");
                    else quests.add(quest.getAsString());
                }
            }
            Optional<String> source = object.get().has("condition") ? string(object.get(), "condition", path, 1024) : Optional.empty();
            Optional<BehaviorExpression> condition = Optional.empty();
            if (source.isPresent()) {
                try { condition = Optional.of(BehaviorExpression.compile(source.get(), Set.of(), path + ".condition")); }
                catch (BehaviorExpression.Invalid invalid) { problem(path + ".condition", invalid.getMessage()); }
            }
            return new Requirements(permission, quests, source, condition);
        }

        Repeat repeat(JsonElement value, String path) {
            Optional<JsonObject> object = object(value, path);
            if (object.isEmpty()) return Repeat.NEVER;
            fields(object.get(), Set.of("mode", "seconds"), path);
            Optional<String> mode = string(object.get(), "mode", path, 16);
            RepeatMode parsed = RepeatMode.NEVER;
            if (mode.isPresent()) {
                try { parsed = RepeatMode.valueOf(mode.get().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException unknown) { problem(path + ".mode", "expected never, cooldown or daily"); }
            }
            if (parsed == RepeatMode.COOLDOWN && !object.get().has("seconds")) problem(path + ".seconds", "a cooldown needs seconds");
            if (parsed != RepeatMode.COOLDOWN && object.get().has("seconds")) problem(path + ".seconds", "only a cooldown takes seconds");
            long seconds = integer(object.get(), "seconds", path, 1, 31_536_000L, 0);
            return new Repeat(parsed, seconds);
        }

        Optional<Stage> stage(JsonElement value, String path) {
            Optional<JsonObject> object = object(value, path);
            if (object.isEmpty()) return Optional.empty();
            fields(object.get(), Set.of("description", "order", "objectives"), path);
            String description = object.get().has("description") ? text(object.get(), "description", path, 512).orElse("") : "";
            boolean ordered = false;
            if (object.get().has("order")) {
                Optional<String> order = string(object.get(), "order", path, 16);
                if (order.isPresent() && !order.get().equals("any") && !order.get().equals("ordered")) problem(path + ".order", "expected any or ordered");
                ordered = order.map("ordered"::equals).orElse(false);
            }
            List<Objective> objectives = new ArrayList<>();
            JsonElement list = object.get().get("objectives");
            if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty() || list.getAsJsonArray().size() > MAXIMUM_OBJECTIVES) {
                problem(path + ".objectives", "expected 1 to " + MAXIMUM_OBJECTIVES + " objectives");
                return Optional.empty();
            }
            Set<String> ids = new HashSet<>();
            JsonArray array = list.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String at = path + ".objectives[" + index + "]";
                Optional<Objective> objective = objective(array.get(index), at, index);
                if (objective.isPresent() && !ids.add(objective.get().id())) problem(at + ".id", "objective id " + objective.get().id() + " is used twice in this stage");
                objective.ifPresent(objectives::add);
            }
            return Optional.of(new Stage(description, ordered, objectives));
        }

        Optional<Objective> objective(JsonElement value, String path, int index) {
            Optional<JsonObject> found = object(value, path);
            if (found.isEmpty()) return Optional.empty();
            JsonObject object = found.get();
            fields(object, Set.of("type", "id", "description", "npc", "item", "entity", "amount", "world", "x", "y", "z", "radius"), path);
            Optional<String> typeName = string(object, "type", path, 16);
            Type type;
            try { type = Type.valueOf(typeName.orElse("").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) {
                if (typeName.isPresent()) problem(path + ".type", "expected talk, bring, kill, reach, collect or custom");
                return Optional.empty();
            }
            Set<String> allowed = switch (type) {
                case TALK -> Set.of("npc");
                case BRING -> Set.of("npc", "item", "amount");
                case KILL -> Set.of("npc", "entity", "amount");
                case REACH -> Set.of("world", "x", "y", "z", "radius");
                case COLLECT -> Set.of("item", "amount");
                case CUSTOM -> Set.of("amount");
            };
            for (String field : List.of("npc", "item", "entity", "amount", "world", "x", "y", "z", "radius"))
                if (object.has(field) && !allowed.contains(field)) problem(path + "." + field, "a " + type.key() + " objective does not take " + field);
            Optional<String> id = Optional.empty();
            if (object.has("id")) {
                id = string(object, "id", path, 64);
                if (id.isPresent() && !id.get().matches(NAME)) { problem(path + ".id", "expected lowercase letters, digits, _ and -"); id = Optional.empty(); }
            } else if (type == Type.CUSTOM) problem(path + ".id", "a custom objective needs an id, which behaviors and commands name");
            Optional<String> description = object.has("description") ? string(object, "description", path, 256) : Optional.empty();
            Optional<String> npc = object.has("npc") && allowed.contains("npc") ? npc(object.get("npc"), path + ".npc") : Optional.empty();
            if ((type == Type.TALK || type == Type.BRING) && !object.has("npc")) problem(path + ".npc", "a " + type.key() + " objective needs an NPC id");
            Optional<NpcItem> item = object.has("item") && allowed.contains("item") ? NpcItem.parse(object.get("item"), path + ".item", problems) : Optional.empty();
            if ((type == Type.BRING || type == Type.COLLECT) && !object.has("item")) problem(path + ".item", "a " + type.key() + " objective needs an item");
            Optional<String> entity = Optional.empty();
            if (type == Type.KILL) {
                if (object.has("entity") == object.has("npc")) problem(path + ".entity", "a kill objective needs exactly one of entity or npc");
                if (object.has("entity")) {
                    entity = string(object, "entity", path, MinecraftIds.MAX_LENGTH).filter(MinecraftIds::valid).map(MinecraftIds::normalize);
                    if (entity.isEmpty()) problem(path + ".entity", "expected an entity type such as minecraft:zombie");
                }
            }
            int amount = (int) integer(object, "amount", path, 1, MAXIMUM_AMOUNT, 1);
            Optional<Point> point = Optional.empty();
            if (type == Type.REACH) {
                Optional<String> world = string(object, "world", path, 128);
                Optional<Double> x = number(object, "x", path, -30_000_000, 30_000_000), y = number(object, "y", path, -2048, 2048),
                        z = number(object, "z", path, -30_000_000, 30_000_000);
                double radius = object.has("radius") ? number(object, "radius", path, 1, 256).orElse(3.0) : 3.0;
                if (world.isPresent() && x.isPresent() && y.isPresent() && z.isPresent()) point = Optional.of(new Point(world.get(), x.get(), y.get(), z.get(), radius));
            }
            return Optional.of(new Objective(type, id.orElse(type.key() + "-" + (index + 1)), description, npc, item, entity, amount, point));
        }

        Rewards rewards(JsonElement value, String path) {
            Optional<JsonObject> found = object(value, path);
            if (found.isEmpty()) return Rewards.NONE;
            JsonObject object = found.get();
            fields(object, Set.of("items", "money", "experience", "commands", "flags"), path);
            List<Goods> items = new ArrayList<>();
            if (object.has("items")) {
                JsonElement list = object.get("items");
                if (!list.isJsonArray() || list.getAsJsonArray().size() > 27) problem(path + ".items", "expected at most 27 items");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++) {
                    String at = path + ".items[" + index + "]";
                    Optional<JsonObject> entry = object(list.getAsJsonArray().get(index), at);
                    if (entry.isEmpty()) continue;
                    fields(entry.get(), Set.of("item", "amount"), at);
                    Optional<NpcItem> item = NpcItem.parse(entry.get().get("item"), at + ".item", problems);
                    int amount = (int) integer(entry.get(), "amount", at, 1, 2304, item.map(NpcItem::count).orElse(1));
                    item.ifPresent((NpcItem parsed) -> items.add(new Goods(parsed, amount)));
                }
            }
            Optional<BigDecimal> money = Optional.empty();
            if (object.has("money")) {
                JsonElement amount = object.get("money");
                try {
                    if (!amount.isJsonPrimitive() || !amount.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
                    BigDecimal parsed = amount.getAsBigDecimal();
                    if (parsed.signum() <= 0 || parsed.compareTo(MAXIMUM_MONEY) > 0 || parsed.stripTrailingZeros().scale() > 4) throw new NumberFormatException();
                    money = Optional.of(parsed);
                } catch (NumberFormatException invalid) { problem(path + ".money", "expected a positive amount with at most 4 decimal places"); }
            }
            int experience = (int) integer(object, "experience", path, 1, 1_000_000, 0);
            List<Command> commands = new ArrayList<>();
            if (object.has("commands")) {
                JsonElement list = object.get("commands");
                if (!list.isJsonArray() || list.getAsJsonArray().size() > 16) problem(path + ".commands", "expected at most 16 commands");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++) {
                    String at = path + ".commands[" + index + "]";
                    Optional<JsonObject> entry = object(list.getAsJsonArray().get(index), at);
                    if (entry.isEmpty()) continue;
                    fields(entry.get(), Set.of("command", "as"), at);
                    Optional<String> command = string(entry.get(), "command", at, 1024);
                    if (command.isPresent() && command.get().startsWith("/")) { problem(at + ".command", "write the command without the slash"); command = Optional.empty(); }
                    boolean console = true;
                    if (entry.get().has("as")) {
                        Optional<String> as = string(entry.get(), "as", at, 16);
                        if (as.isPresent() && !as.get().equals("console") && !as.get().equals("player")) problem(at + ".as", "expected console or player");
                        console = !as.map("player"::equals).orElse(false);
                    }
                    if (command.isPresent()) commands.add(new Command(command.get(), console));
                }
            }
            List<Flag> flags = new ArrayList<>();
            if (object.has("flags")) {
                JsonElement list = object.get("flags");
                if (!list.isJsonArray() || list.getAsJsonArray().size() > 16) problem(path + ".flags", "expected at most 16 flags");
                else for (int index = 0; index < list.getAsJsonArray().size(); index++) flag(list.getAsJsonArray().get(index), path + ".flags[" + index + "]").ifPresent(flags::add);
            }
            return new Rewards(items, money, experience, commands, flags);
        }

        Optional<Flag> flag(JsonElement value, String path) {
            Optional<JsonObject> found = object(value, path);
            if (found.isEmpty()) return Optional.empty();
            JsonObject object = found.get();
            fields(object, Set.of("name", "value", "scope"), path);
            Optional<String> name = string(object, "name", path, 64);
            if (name.isPresent() && !name.get().matches(NAME)) { problem(path + ".name", "expected lowercase letters, digits, _ and -"); name = Optional.empty(); }
            boolean global = false;
            if (object.has("scope")) {
                Optional<String> scope = string(object, "scope", path, 16);
                if (scope.isPresent() && !scope.get().equals("player") && !scope.get().equals("global")) problem(path + ".scope", "expected player or global");
                global = scope.map("global"::equals).orElse(false);
            }
            BehaviorProgram.Value flag = new BehaviorProgram.Value(BehaviorProgram.Type.BOOLEAN, true);
            if (object.has("value") && !object.get("value").isJsonNull()) {
                try { flag = BehaviorValues.read(object.get("value")); }
                catch (IllegalArgumentException | ArithmeticException invalid) { problem(path + ".value", "the value is outside the flag value bounds"); }
            }
            BehaviorProgram.Value written = flag;
            boolean everyone = global;
            return name.map((String named) -> new Flag(named, written, everyone));
        }

        Messages messages(JsonElement value, String path) {
            Optional<JsonObject> found = object(value, path);
            if (found.isEmpty()) return Messages.NONE;
            JsonObject object = found.get();
            fields(object, Set.of("offer", "accept", "decline", "complete"), path);
            return new Messages(message(object, "offer", path), message(object, "accept", path), message(object, "decline", path), message(object, "complete", path));
        }

        Optional<String> message(JsonObject object, String field, String path) { return object.has(field) ? string(object, field, path, 512) : Optional.empty(); }
    }
}
