package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.text.RichText;
import art.arcane.volmlib.util.director.parse.DirectorConfidence;
import art.arcane.volmlib.util.director.parse.DirectorParser;
import art.arcane.volmlib.util.director.parse.DirectorParserRegistry;
import art.arcane.volmlib.util.director.parse.DirectorValue;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeParameter;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every reply in the code base that names an {@code /animus} command, run through {@link ChatLine} as a player sees it:
 * each command is a chip, the chip is a command the Director reads with its values, the word after the chip is not one
 * more value of it, and no word of the sentence went into a text value. A value the code fills in at run time reads as
 * {@value #VALUE}; a text value the reply writes itself is quoted, a placeholder, or listed in {@link #VALUES}.
 */
class ReplyCommandsTest {
    private static final String VALUE = "town_guard";
    private static final List<String> ROOTS = List.of("core", "bukkit", "converter", "adapters/mod-common", "adapters/fabric", "adapters/forge", "adapters/neoforge");
    private static final Pattern COMMAND = Pattern.compile("(?<![\\w./])/" + BuildIdentity.ID + "(?=$|[\\s.,;:!?)'\"])");
    private static final Set<String> HELP = Set.of("help", "?");
    /** The words replies write as a command's text values, by command; any other is the sentence read as a value. */
    private static final Map<String, Set<String>> VALUES = Map.of(
            "delete", Set.of("confirm"),
            "import denizen release", Set.of("confirm"),
            "npc anchor", Set.of("add", "gate"),
            "npc health", Set.of("killable", "on"),
            "npc model", Set.of("model"),
            "combat mob", Set.of("stop"),
            "behaviors state delete behavior", Set.of("animus:flags/denizen", "animus:quests/denizen"));
    /** Text that never reaches chat: the server log's summary line and the web editor's field help. */
    private static final Set<String> NOT_CHAT = Set.of("AnimusLog.java", "NpcSchemaGenerator.java");
    private static final String CLOSERS = ".,;:!?)'\"";
    private static final DirectorRuntimeEngine ENGINE = new AnimusCommandTree(null).director().engine();

    @Test void everyCommandInAReplyIsAChipThatEndsWithTheCommand() throws IOException {
        List<String> replies = replies();
        assertTrue(replies.size() > 100, "found only " + replies.size() + " replies naming a command");
        List<String> wrong = new ArrayList<>();
        for (String reply : replies) wrong.addAll(problems(reply));
        assertEquals(List.of(), wrong, wrong.size() + " problems in " + replies.size() + " replies");
    }

    @Test void aReplyThatNamesACommandChipsTheCommandAlone() {
        List<String> wrong = new ArrayList<>();
        List<DirectorRuntimeNode> nodes = new ArrayList<>(List.of(ENGINE.getRoot()));
        for (int index = 0; index < nodes.size(); index++) nodes.addAll(nodes.get(index).getChildren());
        for (DirectorRuntimeNode node : nodes) {
            String path = node.path();
            for (String reply : List.of(AnimusDirector.failed(path), AnimusDirector.needsPlugin(path, "LibsDisguises"), "Unexpected \"again\"; " + AnimusDirector.complete(path))) {
                Component line = ChatLine.parse(Palette.error(reply)).component();
                List<String> chips = line.children().stream().filter((Component child) -> child.clickEvent() != null).map((Component child) -> ((TextComponent) child).content()).toList();
                if (!chips.equals(List.of(path))) wrong.add(chips + " in: " + reply);
            }
        }
        assertTrue(nodes.size() > 200, "walked only " + nodes.size() + " commands");
        assertEquals(List.of(), wrong);
    }

    /** What is wrong with the chips of one reply: a command left as text, a chip the Director cannot read, or one that stops short. */
    private static List<String> problems(String reply) {
        List<String> found = new ArrayList<>();
        Component line = ChatLine.parse(RichText.colorize(Palette.reply(reply))).component();
        StringBuilder plain = new StringBuilder();
        Map<Integer, String> chips = new LinkedHashMap<>();
        for (Component child : line.children()) {
            String text = ((TextComponent) child).content();
            ClickEvent click = child.clickEvent();
            if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND && text.startsWith("/")) chips.put(plain.length(), text);
            plain.append(text);
        }
        Matcher command = COMMAND.matcher(plain);
        while (command.find()) {
            String chip = chips.get(command.start());
            if (chip == null) { found.add("no chip for the command in: " + reply); continue; }
            Optional<Reading> reading = read(chip);
            if (reading.isEmpty()) found.add("\"" + chip + "\" is not a command in: " + reply);
            else for (String word : reading.get().text())
                if (!VALUES.getOrDefault(reading.get().path(), Set.of()).contains(word)) found.add("\"" + chip + "\" reads \"" + word + "\" as a value in: " + reply);
            Optional<String> next = nextWord(plain.toString(), command.start() + chip.length());
            if (next.isPresent() && reads(chip + " " + next.get())) found.add("\"" + chip + "\" stops before its value \"" + next.get() + "\" in: " + reply);
        }
        return found;
    }

    /** The word right after a chip in the same clause, without the punctuation that closes it; empty after punctuation or the end. */
    private static Optional<String> nextWord(String text, int end) {
        if (end >= text.length() || text.charAt(end) != ' ') return Optional.empty();
        List<String> words = CommandWords.tokens(text.substring(end + 1));
        if (words.isEmpty()) return Optional.empty();
        String word = words.getFirst();
        int cut = word.length();
        while (cut > 0 && CLOSERS.indexOf(word.charAt(cut - 1)) >= 0) cut--;
        if (cut == 0) return Optional.empty();
        return Optional.of(text.charAt(end + 1) == '"' ? '"' + word + '"' : word.substring(0, cut));
    }

    /**
     * How the Director reads {@code command}: as a group, help, or a command with values it takes, where each bare word
     * fills a required value, then one {@link Positional} lists, or three coordinates; every key is the command's and every
     * value converts. A placeholder stands for any value, and a required value may be left for the player to type. Empty
     * when it cannot read it.
     */
    private static Optional<Reading> read(String command) {
        List<String> words = CommandWords.tokens(command.substring(1));
        if (words.isEmpty() || !words.getFirst().equals(BuildIdentity.ID)) return Optional.empty();
        DirectorRuntimeNode node = ENGINE.getRoot();
        int index = 1;
        for (; index < words.size() && !node.isInvocable(); index++) {
            String word = words.get(index);
            if (HELP.contains(word.toLowerCase(Locale.ROOT))) return help(node, words.subList(index + 1, words.size())) ? Optional.of(new Reading("help", List.of())) : Optional.empty();
            node = AnimusDirector.named(node, word);
            if (node == null) return Optional.empty();
        }
        String path = node.path().substring(BuildIdentity.ID.length() + 1).strip();
        if (!node.isInvocable()) return index == words.size() ? Optional.of(new Reading(path, List.of())) : Optional.empty();
        List<String> given = new ArrayList<>();
        for (String word : words.subList(index, words.size())) {
            if (word.startsWith("<") && word.endsWith(">")) { given.add(PLACEHOLDER); continue; }
            if (word.startsWith("[") && word.endsWith("]")) {
                String inside = word.substring(1, word.length() - 1);
                String key = inside.contains("=") ? inside.substring(0, inside.indexOf('=')) : inside;
                DirectorRuntimeParameter named = AnimusDirector.parameter(node, key);
                if (named == null || named.getDescriptor().isRequired()) return Optional.empty();
                given.add(key + "=" + PLACEHOLDER);
                continue;
            }
            int equals = word.indexOf('=');
            if (equals > 0 && AnimusDirector.parameter(node, word.substring(0, equals)) == null) return Optional.empty();
            given.add(word.replaceAll("^([A-Za-z][\\w-]*=)<[^>]*>$", "$1" + PLACEHOLDER));
        }
        List<String> placed = AnimusDirector.coordinates(node, given);
        List<String> bound = AnimusDirector.positional(node, placed);
        if (AnimusDirector.stray(node, bound).isPresent()) return Optional.empty();
        List<DirectorRuntimeParameter> required = new ArrayList<>(node.getParameters().stream().filter((DirectorRuntimeParameter parameter) ->
                parameter.getDescriptor().isRequired() && !parameter.getDescriptor().isContextual()).toList());
        List<String> text = new ArrayList<>();
        for (int at = 0; at < bound.size(); at++) {
            String word = bound.get(at);
            int equals = word.indexOf('=');
            DirectorRuntimeParameter keyed = equals > 0 ? AnimusDirector.parameter(node, word.substring(0, equals)) : null;
            if (keyed == null) continue;
            required.remove(keyed);
            String value = word.substring(equals + 1);
            if (!value.equals(VALUE) && !converts(keyed, value)) return Optional.empty();
            if (!placed.get(at).equals(word)) written(keyed, value, command, text);
        }
        int bare = 0;
        for (String word : bound) {
            if (word.indexOf('=') > 0 && AnimusDirector.parameter(node, word.substring(0, word.indexOf('='))) != null) continue;
            if (bare < required.size()) {
                if (!converts(required.get(bare), word)) return Optional.empty();
                written(required.get(bare), word, command, text);
            }
            bare++;
        }
        return Optional.of(new Reading(path, text));
    }

    /** {@code value} counts as written into the reply when it fills a text value bare: not quoted, and not supplied by the code. */
    private static void written(DirectorRuntimeParameter parameter, String value, String command, List<String> text) {
        if (parameter.getDescriptor().getType() == String.class && !value.equals(PLACEHOLDER) && !value.equals(VALUE) && !command.contains('"' + value + '"')) text.add(value);
    }

    /** The command's words after {@code /animus}, and the bare words the reply itself gives as its text values. */
    private record Reading(String path, List<String> text) {}

    private static boolean reads(String command) { return read(command).isPresent(); }

    private static final String PLACEHOLDER = "\u0000";

    /** {@code help} with nothing after it, {@code all} or a page at the root, a page in a group, or a topic's names. */
    private static boolean help(DirectorRuntimeNode group, List<String> rest) {
        if (rest.isEmpty() || rest.size() == 1 && rest.getFirst().matches("\\d{1,4}")) return true;
        if (group != ENGINE.getRoot()) return false;
        if (rest.size() == 1 && rest.getFirst().equalsIgnoreCase("all")) return true;
        DirectorRuntimeNode topic = group;
        for (String word : rest) {
            if (topic.isInvocable()) return false;
            topic = AnimusDirector.named(topic, word);
            if (topic == null) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean converts(DirectorRuntimeParameter parameter, String value) {
        if (value.equals(PLACEHOLDER)) return true;
        Class<?> type = parameter.getDescriptor().getType();
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) if (((Enum<?>) constant).name().equalsIgnoreCase(value)) return true;
            return false;
        }
        DirectorParserRegistry parsers = ENGINE.getParsers();
        Optional<DirectorParser<Object>> parser = parsers.get((Class<Object>) type);
        if (parser.isEmpty()) return type == String.class;
        DirectorValue<Object> parsed = parser.get().parse(value);
        return parsed != null && parsed.getConfidence() != DirectorConfidence.INVALID;
    }

    /** Each string expression in the main sources that names {@code /animus} as a command, with run-time values filled in. */
    private static List<String> replies() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        List<Path> sources = new ArrayList<>();
        for (String module : ROOTS) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                files.filter((Path file) -> file.toString().endsWith(".java") && !NOT_CHAT.contains(file.getFileName().toString())).forEach(sources::add);
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) compiler.getTask(null, files, (diagnostic) -> {}, List.of("-proc:none"), null, files.getJavaFileObjectsFromPaths(sources));
        List<CompilationUnitTree> units = new ArrayList<>();
        task.parse().forEach(units::add);
        Map<String, String> constants = constants(units);
        List<String> found = new ArrayList<>();
        for (CompilationUnitTree unit : units) new TreePathScanner<Void, Void>() {
            @Override public Void visitBinary(BinaryTree tree, Void unused) {
                if (tree.getKind() == Tree.Kind.PLUS && !insideConcatenation(getCurrentPath())) add(text(tree, constants));
                return super.visitBinary(tree, unused);
            }
            @Override public Void visitLiteral(LiteralTree tree, Void unused) {
                if (tree.getValue() instanceof String value && !insideConcatenation(getCurrentPath())) add(value);
                return null;
            }
            private void add(String text) { if (COMMAND.matcher(text.replaceAll("&[0-9a-fk-or]", "")).find()) found.add(text); }
        }.scan(unit, null);
        return found.stream().distinct().toList();
    }

    private static boolean insideConcatenation(TreePath path) {
        for (TreePath parent = path.getParentPath(); parent != null; parent = parent.getParentPath()) {
            Tree leaf = parent.getLeaf();
            if (leaf.getKind() == Tree.Kind.PARENTHESIZED) continue;
            return leaf.getKind() == Tree.Kind.PLUS;
        }
        return false;
    }

    /** Static final strings written as text, by name, so a reply built from {@code UNDO + id} reads as it is sent. */
    private static Map<String, String> constants(List<CompilationUnitTree> units) {
        Map<String, String> constants = new HashMap<>(Map.of("BuildIdentity.ID", BuildIdentity.ID));
        Map<String, Integer> seen = new HashMap<>();
        for (CompilationUnitTree unit : units) new TreePathScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree tree, Void unused) {
                Set<Modifier> modifiers = tree.getModifiers().getFlags();
                if (modifiers.contains(Modifier.STATIC) && modifiers.contains(Modifier.FINAL) && tree.getInitializer() instanceof LiteralTree literal
                        && literal.getValue() instanceof String value) {
                    String name = tree.getName().toString();
                    if (seen.merge(name, 1, Integer::sum) == 1) constants.put(name, value); else constants.remove(name);
                }
                return super.visitVariable(tree, unused);
            }
        }.scan(unit, null);
        return constants;
    }

    /** The text an expression makes, with {@value #VALUE} for each value only known at run time. */
    private static String text(ExpressionTree expression, Map<String, String> constants) {
        return switch (expression) {
            case LiteralTree literal -> String.valueOf(literal.getValue());
            case BinaryTree binary when binary.getKind() == Tree.Kind.PLUS -> text(binary.getLeftOperand(), constants) + text(binary.getRightOperand(), constants);
            case ParenthesizedTree parenthesized -> text(parenthesized.getExpression(), constants);
            case ConditionalExpressionTree conditional -> conditional.getTrueExpression() instanceof LiteralTree ? text(conditional.getTrueExpression(), constants) : VALUE;
            case MemberSelectTree select -> constants.getOrDefault(select.getExpression() + "." + select.getIdentifier(), constants.getOrDefault(select.getIdentifier().toString(), VALUE));
            case IdentifierTree identifier -> constants.getOrDefault(identifier.getName().toString(), VALUE);
            case MethodInvocationTree call when call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("formatted") ->
                    text(select.getExpression(), constants).replaceAll("%[sd]", VALUE);
            default -> VALUE;
        };
    }
}
