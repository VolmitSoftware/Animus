package art.arcane.animus.core.behavior.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A behavior read as a conversation, the shape the web editor's Conversation tab edits: each block is one routine, a
 * line is {@code animus:say} (with an optional wait before it and a plain sound and animation after it), a question is
 * {@code animus:choices}, and each answer leads to a block through its {@code dialogue_choice} trigger. At most one
 * start, an {@code interaction} or {@code proximity} enter trigger, runs the entry block.
 *
 * <p>This mirrors {@code animus-web/lib/logic/conversation/conversation_json.dart} rule for rule, so a document either
 * side writes reads the same on the other. Every step keeps the JSON it was read from, so keys the conversation does
 * not edit survive a rewrite.
 */
public final class ConversationDocument {
    public static final String SAY = "animus:say", CHOICES = "animus:choices", SOUND = "animus:play-sound", ANIMATION = "animus:play-animation";
    /** Placeholders a line may use; the writer turns the ones the server does not fill into template holes. */
    public static final String NPC_NAME = "{npc.name}", PLAYER_NAME = "{player_name}";
    public static final int MAXIMUM_OPTIONS = 8, SAY_LIMIT = 1024, PROMPT_LIMIT = 512, OPTION_LIMIT = 128, ROUTINE_LIMIT = 32, TRIGGER_LIMIT = 32;
    private static final String SNEAKING = "context.sneaking";
    private static final Pattern NPC_HOLE = Pattern.compile("\\{\\{\\s*npc\\.name\\s*\\}\\}"), PLAYER_HOLE = Pattern.compile("\\{\\{\\s*player\\.name\\s*\\}\\}");
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*"), OPTION_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    /** What starts the conversation, in the editor's order. */
    public enum StartKind {
        CLICK("Right-click", "interact"), SNEAK_CLICK("Sneak + right-click", "sneak_interact"), LEFT_CLICK("Left-click", "attack"),
        SNEAK_LEFT_CLICK("Sneak + left-click", "sneak_attack"), DROP("Drop key (Q)", "drop"), SNEAK_DROP("Sneak + drop key", "sneak_drop"),
        SWAP_HANDS("Swap hands (F)", "swap_hands"), SNEAK_SWAP_HANDS("Sneak + swap hands", "sneak_swap_hands"), PROXIMITY("Walk near", null), MANUAL("Command", null);

        private final String label, action;

        StartKind(String label, String action) { this.label = label; this.action = action; }

        public String label() { return label; }

        /** The interaction trigger's {@code action}; empty for a start that is no click or key. */
        public Optional<String> action() { return Optional.ofNullable(action); }
    }

    /** The start trigger. {@code trigger} is the trigger as written, so its cooldown and other keys stay. */
    public static final class Start {
        public StartKind kind;
        /** Blocks, for {@link StartKind#PROXIMITY}. */
        public double radius;
        JsonObject trigger;
        /** The routine an older sneak start used to check sneaking; not a block, and a rewrite drops it. */
        String gate;

        Start(StartKind kind, double radius, JsonObject trigger, String gate) { this.kind = kind; this.radius = radius; this.trigger = trigger; this.gate = gate; }
    }

    /** Steps in order. {@code sequence} is false for a routine written as one bare node, which stays bare while it holds one. */
    public static final class Block {
        public final List<Step> steps;
        boolean sequence;

        public Block(List<Step> steps, boolean sequence) { this.steps = new ArrayList<>(steps); this.sequence = sequence; }
    }

    /** One step of a block; {@code raw} is the node as written. */
    public sealed interface Step permits Line, Choices, Branch, Action, Goto, End {}

    /** The NPC says {@code text}, after {@code delayTicks}, with an optional sound and animation. */
    public static final class Line implements Step {
        public String text;
        public Integer delayTicks;
        public String sound, animation;
        final JsonObject raw;

        public Line(String text) { this(text, new JsonObject()); }
        Line(String text, JsonObject raw) { this.text = text; this.raw = raw; }
    }

    /** One answer. {@code id} is kept once made, so renaming the text keeps the trigger matching; {@code next} null ends the talk. */
    public static final class Option {
        public String id, text, color, hover, style, next, trigger;
        final JsonObject raw;
        JsonObject triggerRaw = new JsonObject();

        Option(String id, String text, JsonObject raw) { this.id = id; this.text = text; this.raw = raw; }
    }

    /** The player picks one of {@code options}; {@code dialogue} is the prompt id the answer triggers match. */
    public static final class Choices implements Step {
        public String dialogue, prompt;
        public final List<Option> options = new ArrayList<>();
        final JsonObject raw;

        Choices(String dialogue, String prompt, JsonObject raw) { this.dialogue = dialogue; this.prompt = prompt; this.raw = raw; }
    }

    /** A condition: a capability with arguments, or an expression. Kept as read; the game edits only its lines and answers. */
    public static final class Branch implements Step {
        public final String capability, expression;
        final JsonObject arguments, raw;
        public final Block then, otherwise;

        Branch(String capability, JsonObject arguments, String expression, Block then, Block otherwise, JsonObject raw) {
            this.capability = capability; this.arguments = arguments; this.expression = expression; this.then = then; this.otherwise = otherwise; this.raw = raw;
        }
    }

    /** Any other action capability, kept as read. */
    public static final class Action implements Step {
        public final String capability;
        final JsonObject arguments, raw;

        Action(String capability, JsonObject arguments, JsonObject raw) { this.capability = capability; this.arguments = arguments; this.raw = raw; }
    }

    /** Continues at another block: a {@code call} as the last step. */
    public static final class Goto implements Step {
        public final String target;
        final JsonObject raw;

        Goto(String target, JsonObject raw) { this.target = target; this.raw = raw; }
    }

    /** Stops here: a bare {@code return} as the last step. */
    public static final class End implements Step {
        final JsonObject raw;

        End(JsonObject raw) { this.raw = raw; }
    }

    /** What reading a behavior gave. */
    public sealed interface Read permits Fits, Misfit {}

    public record Fits(ConversationDocument conversation) implements Read {}

    /** The first field that does not fit, as {@code $.routines.main.steps[2]}, and why. */
    public record Misfit(String path, String reason) implements Read {}

    public String entry;
    public final Start start;
    /** Blocks by routine name, in the document's order. */
    public final Map<String, Block> blocks;

    private ConversationDocument(String entry, Start start, Map<String, Block> blocks) { this.entry = entry; this.start = start; this.blocks = blocks; }

    /** Reads {@code behavior} as a conversation; the document is copied, so later edits never touch it. */
    public static Read read(JsonObject behavior) {
        try { return new Fits(new Reader(behavior.deepCopy()).read()); }
        catch (Unfit unfit) { return new Misfit(unfit.path, unfit.reason); }
    }

    /** A conversation that says {@code line} on a right click, with the editor's start id and cooldown. */
    public static ConversationDocument started(String line) {
        JsonObject trigger = new JsonObject();
        trigger.addProperty("id", "start");
        trigger.addProperty("cooldownTicks", 40);
        Map<String, Block> blocks = new LinkedHashMap<>();
        blocks.put("main", new Block(List.of(new Line(line)), true));
        return new ConversationDocument("main", new Start(StartKind.CLICK, 6, trigger, null), blocks);
    }

    // --- edits -------------------------------------------------------------------------------------------------------

    /** Every prompt id in use. */
    public Set<String> dialogues() {
        Set<String> ids = new LinkedHashSet<>();
        for (Block block : blocks.values()) for (Choices choices : choicesIn(block)) ids.add(choices.dialogue);
        return ids;
    }

    /** Every trigger id in use, the start's included. */
    public Set<String> triggerIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (start.kind != StartKind.MANUAL) ids.add(Objects.requireNonNullElse(string(start.trigger.get("id")), "start"));
        for (Block block : blocks.values()) for (Choices choices : choicesIn(block)) for (Option option : choices.options) if (option.trigger != null) ids.add(option.trigger);
        return ids;
    }

    /** Routine names in use, the sneak gate's included. */
    public Set<String> routineNames() {
        Set<String> names = new LinkedHashSet<>(blocks.keySet());
        if (start.gate != null) names.add(start.gate);
        return names;
    }

    /** Changes how the conversation starts; the trigger keeps its id and cooldown. */
    public void start(StartKind kind) {
        if (start.kind == StartKind.MANUAL && kind != StartKind.MANUAL && !start.trigger.has("id")) {
            start.trigger = new JsonObject();
            start.trigger.addProperty("id", freeId("start", triggerIds(), 64));
        }
        start.kind = kind;
    }

    /** The first question at the top of {@code block}, where the game edits answers. */
    public Optional<Choices> question(String block) {
        Block steps = blocks.get(block);
        if (steps != null) for (Step step : steps.steps) if (step instanceof Choices choices) return Optional.of(choices);
        return Optional.empty();
    }

    /**
     * Adds an answer to the block's question, asking {@code prompt} when the block has none yet, and a new empty block
     * it leads to, named after the answer. Returns the new block's name.
     */
    public String addOption(String block, String text, String prompt) {
        Block steps = blocks.get(block);
        if (steps == null) throw new IllegalArgumentException("No block " + block);
        Choices choices = question(block).orElseGet(() -> {
            Choices made = new Choices(freeId("ask", dialogues(), 64), prompt, new JsonObject());
            steps.steps.add(made);
            return made;
        });
        if (choices.options.size() >= MAXIMUM_OPTIONS) throw new IllegalArgumentException("A question takes " + MAXIMUM_OPTIONS + " answers");
        Set<String> taken = new HashSet<>();
        for (Option option : choices.options) taken.add(option.id);
        Option option = new Option(freeId(identifier(text, "option", 32), taken, 32), text, new JsonObject());
        choices.options.add(option);
        return followUp(choices, option);
    }

    /** The block the answer leads to, made empty and named after the answer when it leads nowhere yet. */
    public String followUp(Choices choices, Option option) {
        if (option.next != null && blocks.containsKey(option.next)) return option.next;
        String name = freeId(identifier(option.id, "answer", 32), routineNames(), 64);
        blocks.put(name, new Block(List.of(), true));
        option.next = name;
        if (option.trigger == null) option.trigger = freeId(identifier(choices.dialogue + "-" + option.id, "answer", 64), triggerIds(), 64);
        return name;
    }

    /**
     * Removes answer {@code index} of the question and every block that only it reached; a question left without
     * answers goes too. Returns the blocks removed.
     */
    public List<String> removeOption(Choices choices, int index) {
        Set<String> reached = new HashSet<>(blocks.keySet());
        reached.removeAll(unreached());
        choices.options.remove(index);
        if (choices.options.isEmpty()) for (Block block : blocks.values()) block.steps.remove(choices);
        List<String> removed = new ArrayList<>();
        for (String name : unreached()) if (reached.contains(name)) removed.add(name);
        for (String name : removed) blocks.remove(name);
        return removed;
    }

    /** Blocks no start, answer or go-to reaches. */
    public List<String> unreached() {
        Set<String> seen = new HashSet<>();
        List<String> pending = new ArrayList<>(List.of(entry));
        while (!pending.isEmpty()) {
            String name = pending.removeLast();
            if (!seen.add(name)) continue;
            Block block = blocks.get(name);
            if (block != null) targets(block, pending);
        }
        List<String> left = new ArrayList<>();
        for (String name : blocks.keySet()) if (!seen.contains(name)) left.add(name);
        return left;
    }

    /** How many answers and go-tos lead to each block. */
    public Map<String, Integer> incoming() {
        List<String> targets = new ArrayList<>();
        for (Block block : blocks.values()) targets(block, targets);
        Map<String, Integer> counts = new HashMap<>();
        for (String target : targets) counts.merge(target, 1, Integer::sum);
        return counts;
    }

    private static void targets(Block block, List<String> into) {
        for (Step step : block.steps) {
            switch (step) {
                case Choices choices -> { for (Option option : choices.options) if (option.next != null) into.add(option.next); }
                case Goto jump -> into.add(jump.target);
                case Branch branch -> { targets(branch.then, into); targets(branch.otherwise, into); }
                default -> {}
            }
        }
    }

    /**
     * The errors the server or the editor would refuse, in the editor's words, each led by its block. Warnings the
     * editor shows (long lines, unreached blocks) are left out.
     */
    public List<String> errors() {
        List<String> errors = new ArrayList<>();
        if (blocks.size() > ROUTINE_LIMIT) errors.add(blocks.size() + " blocks. A behavior holds " + ROUTINE_LIMIT + " routines.");
        int triggers = triggerIds().size();
        if (triggers > TRIGGER_LIMIT) errors.add(triggers + " answers and starts. A behavior holds " + TRIGGER_LIMIT + " triggers.");
        if (start.kind == StartKind.PROXIMITY && (start.radius <= 0 || start.radius > 64)) errors.add("Radius is above 0 and at most 64 blocks.");
        for (Map.Entry<String, Block> block : blocks.entrySet()) check(block.getKey(), block.getValue(), errors);
        return errors;
    }

    private void check(String name, Block block, List<String> errors) {
        for (int index = 0; index < block.steps.size(); index++) {
            String at = name + " step " + (index + 1) + ": ";
            boolean last = index == block.steps.size() - 1;
            switch (block.steps.get(index)) {
                case Line line -> {
                    if (line.text.isBlank()) errors.add(at + "Empty line.");
                    else if (line.text.length() > SAY_LIMIT) errors.add(at + line.text.length() + " characters. A line takes " + SAY_LIMIT + ".");
                    if (line.text.codePoints().anyMatch((int rune) -> rune < 0x20 || (rune >= 0x7f && rune < 0xa0))) errors.add(at + "Control characters.");
                }
                case Choices choices -> {
                    if (choices.prompt.isBlank()) errors.add(at + "Empty question.");
                    if (choices.prompt.length() > PROMPT_LIMIT) errors.add(at + choices.prompt.length() + " characters. A question takes " + PROMPT_LIMIT + ".");
                    if (choices.options.isEmpty()) errors.add(at + "No answers.");
                    if (choices.options.size() > MAXIMUM_OPTIONS) errors.add(at + choices.options.size() + " answers. A question takes " + MAXIMUM_OPTIONS + ".");
                    Set<String> ids = new HashSet<>();
                    for (Option option : choices.options) {
                        if (option.text.isBlank()) errors.add(at + "Empty answer.");
                        if (option.text.length() > OPTION_LIMIT) errors.add(at + option.text.length() + " characters. An answer takes " + OPTION_LIMIT + ".");
                        if (!OPTION_ID.matcher(option.id).matches() || option.id.length() > 32) errors.add(at + "Id " + option.id + " needs lowercase letters, digits, _ or -.");
                        if (!ids.add(option.id)) errors.add(at + "Two answers share the id " + option.id + ".");
                        if (option.next != null && !blocks.containsKey(option.next)) errors.add(at + "Leads to " + option.next + ", which is gone.");
                    }
                }
                case Branch branch -> { check(name, branch.then, errors); check(name, branch.otherwise, errors); }
                case Action action -> { if (action.capability.isEmpty()) errors.add(at + "Pick an action."); }
                case Goto jump -> {
                    if (!blocks.containsKey(jump.target)) errors.add(at + (jump.target.isEmpty() ? "Pick where to go." : "Goes to " + jump.target + ", which is gone."));
                    if (!last) errors.add(at + "Go to must be last.");
                }
                case End end -> { if (!last) errors.add(at + "End must be last."); }
            }
        }
    }

    /** {@code base} as an identifier of at most {@code length} characters. */
    static String identifier(String base, String fallback, int length) {
        String slug = base.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-+", "-").replaceAll("^[-_]+|[-_]+$", "");
        if (slug.length() > length) slug = slug.substring(0, length).replaceAll("[-_]+$", "");
        return slug.isEmpty() ? fallback : slug;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}... the first one not taken. */
    static String freeId(String base, Set<String> taken, int length) {
        if (!taken.contains(base)) return base;
        for (int index = 2; ; index++) {
            String suffix = "-" + index;
            String name = (base.length() + suffix.length() > length ? base.substring(0, length - suffix.length()) : base) + suffix;
            if (!taken.contains(name)) return name;
        }
    }

    /** Every question in {@code block}, branches included. */
    static List<Choices> choicesIn(Block block) {
        List<Choices> found = new ArrayList<>();
        for (Step step : block.steps) {
            if (step instanceof Choices choices) found.add(choices);
            if (step instanceof Branch branch) { found.addAll(choicesIn(branch.then)); found.addAll(choicesIn(branch.otherwise)); }
        }
        return found;
    }

    // --- writing -----------------------------------------------------------------------------------------------------

    /** {@code behavior} with its routines, entry and triggers rewritten from this conversation. Other keys stay where they were. */
    public JsonObject write(JsonObject behavior) {
        JsonObject routines = new JsonObject();
        for (Map.Entry<String, Block> block : blocks.entrySet()) routines.add(block.getKey(), list(block.getValue()));
        JsonArray triggers = new JsonArray();
        if (start.kind != StartKind.MANUAL) triggers.add(startTrigger());
        for (Block block : blocks.values()) for (Choices choices : choicesIn(block)) for (Option option : choices.options) {
            if (option.next == null) continue;
            JsonObject trigger = copy(option.triggerRaw);
            trigger.addProperty("id", option.trigger != null ? option.trigger : choices.dialogue + "-" + option.id);
            trigger.addProperty("event", "dialogue_choice");
            trigger.addProperty("dialogue", choices.dialogue);
            trigger.addProperty("choice", option.id);
            trigger.addProperty("routine", option.next);
            triggers.add(trigger);
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : behavior.entrySet()) {
            switch (field.getKey()) {
                case "entry" -> out.addProperty("entry", entry);
                case "routines" -> out.add("routines", routines);
                case "triggers" -> { if (!triggers.isEmpty()) out.add("triggers", triggers); }
                default -> out.add(field.getKey(), field.getValue().deepCopy());
            }
        }
        if (!out.has("entry")) out.addProperty("entry", entry);
        if (!out.has("routines")) out.add("routines", routines);
        if (!triggers.isEmpty() && !out.has("triggers")) out.add("triggers", triggers);
        return out;
    }

    private JsonObject startTrigger() {
        JsonObject raw = start.trigger, out = new JsonObject();
        boolean proximity = start.kind == StartKind.PROXIMITY;
        String event = proximity ? "proximity" : "interaction";
        boolean sameEvent = event.equals(string(raw.get("event")));
        out.addProperty("id", Objects.requireNonNullElse(string(raw.get("id")), "start"));
        out.addProperty("event", event);
        start.kind.action().filter((String action) -> !action.equals("interact")).ifPresent((String action) -> out.addProperty("action", action));
        if (sameEvent) for (Map.Entry<String, JsonElement> field : raw.entrySet())
            if (!Set.of("id", "event", "action", "routine", "phase", "radius", "consume").contains(field.getKey())) out.add(field.getKey(), field.getValue().deepCopy());
        if (!sameEvent && present(raw.get("cooldownTicks"))) out.add("cooldownTicks", raw.get("cooldownTicks").deepCopy());
        // A sneak start claims the click, so a shop or GUI on the NPC opens only without sneaking.
        if (start.kind == StartKind.SNEAK_CLICK) out.addProperty("consume", "true");
        if (raw.has("routine") && !Objects.equals(string(raw.get("routine")), start.gate)) out.addProperty("routine", entry);
        if (proximity) {
            out.addProperty("phase", "enter");
            out.add("radius", number(start.radius));
        }
        return out;
    }

    private static JsonElement list(Block block) {
        List<JsonObject> nodes = new ArrayList<>();
        for (Step step : block.steps) nodes.addAll(step(step));
        if (nodes.size() == 1 && !block.sequence) return nodes.getFirst();
        JsonObject sequence = new JsonObject();
        sequence.addProperty("type", "sequence");
        JsonArray steps = new JsonArray();
        for (JsonObject node : nodes) steps.add(node);
        sequence.add("steps", steps);
        return sequence;
    }

    private static List<JsonObject> step(Step step) {
        return switch (step) {
            case Line line -> {
                List<JsonObject> nodes = new ArrayList<>();
                if (line.delayTicks != null && line.delayTicks > 0) {
                    JsonObject wait = new JsonObject();
                    wait.addProperty("type", "wait");
                    wait.addProperty("ticks", line.delayTicks);
                    nodes.add(wait);
                }
                nodes.add(text(line.raw, SAY, "text", line.text, Map.of(), false));
                if (line.sound != null && !line.sound.isEmpty()) nodes.add(plainAction(SOUND, "sound", line.sound));
                if (line.animation != null && !line.animation.isEmpty()) nodes.add(plainAction(ANIMATION, "animation", line.animation));
                yield nodes;
            }
            case Choices choices -> {
                JsonArray options = new JsonArray();
                for (Option option : choices.options) {
                    JsonObject written = copy(option.raw);
                    written.addProperty("id", option.id);
                    written.addProperty("text", option.text);
                    optional(written, "color", option.color);
                    optional(written, "hover", option.hover);
                    optional(written, "style", option.style);
                    List<String> nulls = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> field : written.entrySet()) if (field.getValue().isJsonNull()) nulls.add(field.getKey());
                    for (String key : nulls) written.remove(key);
                    options.add(written);
                }
                Map<String, JsonElement> arguments = new LinkedHashMap<>();
                arguments.put("id", new JsonPrimitive(choices.dialogue));
                arguments.put("options", options);
                yield List.of(text(choices.raw, CHOICES, "prompt", choices.prompt, arguments, true));
            }
            case Branch branch -> {
                JsonObject node = new JsonObject();
                for (Map.Entry<String, JsonElement> field : branch.raw.entrySet())
                    if (!Set.of("capability", "arguments", "expression", "then", "else").contains(field.getKey())) node.add(field.getKey(), field.getValue().deepCopy());
                node.addProperty("type", "condition");
                if (branch.expression != null) node.addProperty("expression", branch.expression);
                else {
                    node.add("capability", branch.capability == null ? JsonNull.INSTANCE : new JsonPrimitive(branch.capability));
                    if (!branch.arguments.isEmpty() || branch.raw.has("arguments")) node.add("arguments", branch.arguments.deepCopy());
                }
                node.add("then", list(branch.then));
                if (!branch.otherwise.steps.isEmpty()) node.add("else", list(branch.otherwise));
                yield List.of(node);
            }
            case Action action -> {
                JsonObject node = copy(action.raw);
                node.addProperty("type", "action");
                node.addProperty("capability", action.capability);
                if (!action.arguments.isEmpty() || action.raw.has("arguments")) node.add("arguments", action.arguments.deepCopy());
                yield List.of(node);
            }
            case Goto jump -> {
                JsonObject node = copy(jump.raw);
                node.addProperty("type", "call");
                node.addProperty("routine", jump.target);
                yield List.of(node);
            }
            case End end -> {
                JsonObject node = copy(end.raw);
                node.addProperty("type", "return");
                yield List.of(node);
            }
        };
    }

    private static void optional(JsonObject node, String key, String value) {
        if (value == null) node.remove(key); else node.addProperty(key, value);
    }

    private static JsonObject plainAction(String capability, String key, String value) {
        JsonObject node = new JsonObject(), arguments = new JsonObject();
        node.addProperty("type", "action");
        node.addProperty("capability", capability);
        arguments.addProperty(key, value);
        node.add("arguments", arguments);
        return node;
    }

    /** A node from {@code raw} whose argument {@code key} holds {@code text}, as a template when a placeholder needs one. */
    private static JsonObject text(JsonObject raw, String capability, String key, String text, Map<String, JsonElement> set, boolean player) {
        boolean template = text.contains(NPC_NAME) || text.contains("{{") || (player && text.contains(PLAYER_NAME));
        JsonObject templates = copy(object(raw.get("templates")));
        templates.remove("/" + key);
        if (template) {
            String named = text.replace(NPC_NAME, "{{ npc.name }}");
            templates.addProperty("/" + key, player ? named.replace(PLAYER_NAME, "{{ player.name }}") : named);
        }
        JsonObject arguments = copy(object(raw.get("arguments")));
        for (Map.Entry<String, JsonElement> field : set.entrySet()) arguments.add(field.getKey(), field.getValue());
        arguments.add(key, template ? JsonNull.INSTANCE : new JsonPrimitive(text));
        JsonObject node = copy(raw);
        node.addProperty("type", "action");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        if (templates.isEmpty()) node.remove("templates"); else node.add("templates", templates);
        return node;
    }

    private static JsonElement number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? new JsonPrimitive((long) value) : new JsonPrimitive(value);
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    private static final class Unfit extends RuntimeException {
        final String path, reason;

        Unfit(String path, String reason) { super(path + ": " + reason, null, false, false); this.path = path; this.reason = reason; }
    }

    private record Node(JsonElement value, String path) {}

    private record Answer(int index, JsonObject trigger) {}

    private static final class Reader {
        private final JsonObject behavior;

        Reader(JsonObject behavior) { this.behavior = behavior; }

        ConversationDocument read() {
            JsonElement server = behavior.get("server");
            if (server != null && server.isJsonPrimitive() && server.getAsJsonPrimitive().isBoolean() && server.getAsBoolean())
                throw new Unfit("$.server", "A server behavior has no NPC to talk.");
            JsonObject routines = object(behavior.get("routines"));
            JsonArray triggers = array(behavior.get("triggers"));
            String entry = string(behavior.get("entry"));
            if (entry == null) entry = routines.isEmpty() ? "main" : routines.keySet().iterator().next();
            Start start = new Start(StartKind.MANUAL, 6, new JsonObject(), null);
            List<Answer> answers = new ArrayList<>();
            for (int index = 0; index < triggers.size(); index++) {
                JsonObject trigger = object(triggers.get(index));
                String path = "$.triggers[" + index + "]", event = string(trigger.get("event"));
                if ("dialogue_choice".equals(event)) {
                    if (string(trigger.get("choice")) == null) throw new Unfit(path + ".choice", "Answers any option of its prompt.");
                    answers.add(new Answer(index, trigger));
                } else if ("interaction".equals(event) || "proximity".equals(event)) {
                    if (start.kind != StartKind.MANUAL) throw new Unfit(path, "A second start.");
                    start = start(trigger, path, routines);
                    String routine = string(trigger.get("routine"));
                    if (start.gate != null) entry = string(object(object(routines.get(start.gate)).get("then")).get("routine"));
                    else if (routine != null) entry = routine;
                } else throw new Unfit(path + ".event", "Starts on " + shown(trigger.get("event")) + ".");
            }
            Map<String, Block> blocks = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> routine : routines.entrySet())
                if (!routine.getKey().equals(start.gate)) blocks.put(routine.getKey(), list(routine.getValue(), child("$.routines", routine.getKey())));
            if (!blocks.containsKey(entry)) throw new Unfit("$.entry", "The entry routine " + entry + " is missing.");
            ConversationDocument conversation = new ConversationDocument(entry, start, blocks);
            link(conversation, answers);
            return conversation;
        }

        private static Start start(JsonObject trigger, String path, JsonObject routines) {
            if ("proximity".equals(string(trigger.get("event")))) {
                if (!"enter".equals(string(trigger.get("phase")))) throw new Unfit(path + ".phase", "Starts on leaving.");
                JsonElement radius = trigger.get("radius");
                return new Start(StartKind.PROXIMITY, radius != null && radius.isJsonPrimitive() && radius.getAsJsonPrimitive().isNumber() ? radius.getAsDouble() : 6, trigger, null);
            }
            String action = Objects.requireNonNullElse(string(trigger.get("action")), "interact");
            StartKind kind = null;
            for (StartKind candidate : StartKind.values()) if (candidate.action().map(action::equals).orElse(false)) { kind = candidate; break; }
            if (kind == null) throw new Unfit(path + ".action", "Starts on " + action + ".");
            JsonElement consume = trigger.get("consume");
            if (kind == StartKind.SNEAK_CLICK && (!present(consume) || "true".equals(string(consume)))) return new Start(kind, 6, trigger, null);
            if (kind != StartKind.CLICK) {
                if (trigger.has("consume")) throw new Unfit(path + ".consume", "Claims clicks.");
                return new Start(kind, 6, trigger, null);
            }
            String routine = string(trigger.get("routine"));
            JsonObject gate = routine == null ? new JsonObject() : object(routines.get(routine));
            JsonObject call = object(gate.get("then"));
            boolean gated = "condition".equals(string(gate.get("type"))) && SNEAKING.equals(string(gate.get("expression"))) && !present(gate.get("else"))
                    && Set.of("type", "expression", "then").containsAll(gate.keySet()) && "call".equals(string(call.get("type"))) && string(call.get("routine")) != null
                    && Set.of("type", "routine").containsAll(call.keySet());
            if (gated) return new Start(StartKind.SNEAK_CLICK, 6, trigger, routine);
            if (trigger.has("consume")) throw new Unfit(path + ".consume", "Claims clicks.");
            return new Start(StartKind.CLICK, 6, trigger, null);
        }

        /** Joins each answer trigger to its option. */
        private static void link(ConversationDocument conversation, List<Answer> answers) {
            Map<String, Choices> asked = new HashMap<>();
            for (Map.Entry<String, Block> block : conversation.blocks.entrySet()) for (Choices choices : choicesIn(block.getValue())) {
                if (asked.containsKey(choices.dialogue)) throw new Unfit(child("$.routines", block.getKey()), "Two prompts share the id " + choices.dialogue + ".");
                asked.put(choices.dialogue, choices);
            }
            for (Answer answer : answers) {
                String path = "$.triggers[" + answer.index() + "]";
                JsonObject trigger = answer.trigger();
                Choices choices = asked.get(string(trigger.get("dialogue")));
                Option option = null;
                if (choices != null) for (Option candidate : choices.options) if (candidate.id.equals(string(trigger.get("choice")))) { option = candidate; break; }
                if (option == null) throw new Unfit(path, "Answers " + shown(trigger.get("dialogue")) + "/" + shown(trigger.get("choice")) + ", which no line asks.");
                if (option.trigger != null) throw new Unfit(path, "A second answer to " + shown(trigger.get("dialogue")) + "/" + option.id + ".");
                String routine = Objects.requireNonNullElse(string(trigger.get("routine")), conversation.entry);
                if (!conversation.blocks.containsKey(routine)) throw new Unfit(path + ".routine", "Runs " + routine + ", which is not a routine here.");
                option.next = routine;
                option.trigger = string(trigger.get("id"));
                option.triggerRaw = trigger;
            }
        }

        private static Block list(JsonElement value, String path) {
            JsonObject node = object(value);
            if (!"sequence".equals(string(node.get("type")))) return new Block(steps(List.of(new Node(node, path))), false);
            if (!Set.of("type", "steps").containsAll(node.keySet())) throw new Unfit(path, "Extra fields on a sequence.");
            return new Block(steps(flatten(node, path)), true);
        }

        /** A sequence's children, nested sequences opened in place. */
        private static List<Node> flatten(JsonObject sequence, String path) {
            JsonArray children = array(sequence.get("steps"));
            List<Node> nodes = new ArrayList<>();
            for (int index = 0; index < children.size(); index++) {
                JsonObject child = object(children.get(index));
                String at = path + ".steps[" + index + "]";
                if ("sequence".equals(string(child.get("type"))) && Set.of("type", "steps").containsAll(child.keySet())) nodes.addAll(flatten(child, at));
                else nodes.add(new Node(children.get(index), at));
            }
            return nodes;
        }

        private static List<Step> steps(List<Node> nodes) {
            List<Step> steps = new ArrayList<>();
            for (int index = 0; index < nodes.size(); index++) {
                JsonObject node = object(nodes.get(index).value());
                String path = nodes.get(index).path();
                boolean last = index == nodes.size() - 1;
                String type = string(node.get("type"));
                switch (type == null ? "" : type) {
                    case "wait" -> {
                        JsonObject say = index + 1 < nodes.size() ? object(nodes.get(index + 1).value()) : new JsonObject();
                        Integer ticks = integer(node.get("ticks"));
                        if (!action(say, SAY) || !Set.of("type", "ticks").containsAll(node.keySet()) || ticks == null) throw new Unfit(path, "A wait that does not come before a line.");
                        Line line = line(say, nodes.get(index + 1).path());
                        line.delayTicks = ticks;
                        index += 1 + extras(line, nodes, index + 2);
                        steps.add(line);
                    }
                    case "action" -> {
                        String capability = Objects.requireNonNullElse(string(node.get("capability")), "");
                        if (capability.equals(SAY)) {
                            Line line = line(node, path);
                            index += extras(line, nodes, index + 1);
                            steps.add(line);
                        } else if (capability.equals(CHOICES)) steps.add(choices(node, path));
                        else {
                            plain(node, path, Set.of("type", "capability", "arguments"));
                            steps.add(new Action(capability, object(node.get("arguments")).deepCopy(), node));
                        }
                    }
                    case "condition" -> {
                        plain(node, path, Set.of("type", "capability", "arguments", "expression", "then", "else"));
                        steps.add(new Branch(string(node.get("capability")), object(node.get("arguments")).deepCopy(), string(node.get("expression")), list(node.get("then"), path + ".then"),
                                present(node.get("else")) ? list(node.get("else"), path + ".else") : new Block(List.of(), true), node));
                    }
                    case "call" -> {
                        if (!last) throw new Unfit(path, "A call before other steps.");
                        if (!Set.of("type", "routine").containsAll(node.keySet())) throw new Unfit(path, "A call with arguments or a result.");
                        steps.add(new Goto(Objects.requireNonNullElse(string(node.get("routine")), ""), node));
                    }
                    case "return" -> {
                        if (!last) throw new Unfit(path, "A return before other steps.");
                        if (node.has("value")) throw new Unfit(path + ".value", "A return with a value.");
                        steps.add(new End(node));
                    }
                    default -> throw new Unfit(path, "Uses " + (present(node.get("type")) ? shown(node.get("type")) : "a node without a type") + ".");
                }
            }
            return steps;
        }

        private static boolean action(JsonObject node, String capability) {
            return "action".equals(string(node.get("type"))) && capability.equals(string(node.get("capability")));
        }

        /** Rejects bindings, expressions and other keys outside {@code allowed}. */
        private static void plain(JsonObject node, String path, Set<String> allowed) {
            for (String key : node.keySet()) if (!allowed.contains(key)) throw new Unfit(child(path, key), "Uses " + key + ".");
        }

        private static Line line(JsonObject say, String path) {
            plain(say, path, Set.of("type", "capability", "arguments", "templates"));
            JsonObject arguments = object(say.get("arguments")), templates = object(say.get("templates"));
            if (!Set.of("/text").containsAll(templates.keySet())) throw new Unfit(path + ".templates", "Fills arguments other than the text.");
            String text = string(arguments.get("text"));
            if (text == null && string(templates.get("/text")) != null) text = fromTemplate(string(templates.get("/text")));
            if (text == null) throw new Unfit(path + ".arguments.text", "A line without text.");
            return new Line(text, say);
        }

        /** Folds a plain sound and animation that follow a line into it; returns how many nodes it took. */
        private static int extras(Line line, List<Node> nodes, int from) {
            int taken = 0;
            if (from < nodes.size()) {
                JsonObject next = object(nodes.get(from).value());
                if (action(next, SOUND) && only(next, "sound")) { line.sound = string(object(next.get("arguments")).get("sound")); taken++; }
            }
            if (from + taken < nodes.size()) {
                JsonObject next = object(nodes.get(from + taken).value());
                if (action(next, ANIMATION) && only(next, "animation")) { line.animation = string(object(next.get("arguments")).get("animation")); taken++; }
            }
            return taken;
        }

        private static boolean only(JsonObject node, String key) {
            JsonObject arguments = object(node.get("arguments"));
            return Set.of("type", "capability", "arguments").containsAll(node.keySet()) && arguments.size() == 1 && string(arguments.get(key)) != null;
        }

        private static Choices choices(JsonObject node, String path) {
            plain(node, path, Set.of("type", "capability", "arguments", "templates"));
            JsonObject arguments = object(node.get("arguments")), templates = object(node.get("templates"));
            if (!Set.of("/prompt").containsAll(templates.keySet())) throw new Unfit(path + ".templates", "Fills arguments other than the prompt.");
            String prompt = string(arguments.get("prompt"));
            if (prompt == null) prompt = string(templates.get("/prompt")) != null ? fromTemplate(string(templates.get("/prompt"))) : "";
            Choices choices = new Choices(Objects.requireNonNullElse(string(arguments.get("id")), ""), prompt, node);
            for (JsonElement raw : array(arguments.get("options"))) {
                JsonObject written = object(raw);
                Option option = new Option(Objects.requireNonNullElse(string(written.get("id")), ""), Objects.requireNonNullElse(string(written.get("text")), ""), written);
                option.color = string(written.get("color"));
                option.hover = string(written.get("hover"));
                option.style = string(written.get("style"));
                choices.options.add(option);
            }
            return choices;
        }

        private static String fromTemplate(String template) {
            String named = NPC_HOLE.matcher(template).replaceAll(Matcher.quoteReplacement(NPC_NAME));
            return PLAYER_HOLE.matcher(named).replaceAll(Matcher.quoteReplacement(PLAYER_NAME));
        }
    }

    // --- JSON helpers ------------------------------------------------------------------------------------------------

    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }

    private static JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray(); }

    private static String string(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null; }

    private static Integer integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        double number = value.getAsDouble();
        return number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE ? (int) number : null;
    }

    private static boolean present(JsonElement value) { return value != null && !value.isJsonNull(); }

    /** A value as the editor's messages print it. */
    private static String shown(JsonElement value) {
        if (!present(value)) return "null";
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String child(String path, String key) { return PLAIN_KEY.matcher(key).matches() ? path + "." + key : path + "[\"" + key + "\"]"; }

    /** A shallow copy that keeps the key order. */
    private static JsonObject copy(JsonObject source) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> field : source.entrySet()) copy.add(field.getKey(), field.getValue());
        return copy;
    }
}
