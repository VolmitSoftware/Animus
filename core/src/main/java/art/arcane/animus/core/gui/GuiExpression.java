package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.RichText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Gloss expression grammar, evaluated natively: numbers, quoted strings, colour literals, booleans, dotted
 * variables, array literals, calls, unary {@code !} and {@code -}, arithmetic, comparisons, {@code &&}, {@code ||}
 * and {@code a ? b : c}. Numbers are doubles. Expressions are capped at 512 characters and 256 levels of nesting.
 */
public final class GuiExpression {
    public static final int MAXIMUM_LENGTH = 512, MAXIMUM_DEPTH = 256;
    /** Answers variables during evaluation; returns null for a name it does not know. */
    @FunctionalInterface public interface Scope { Object variable(String name); }
    /** A caller-supplied function. It receives evaluated arguments and the scope of the current evaluation. */
    @FunctionalInterface public interface Function { Object call(List<Object> arguments, Scope scope); }
    /** A problem in an expression, with the character position when the parser knows it. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) { super(message); }
    }
    private static final Set<String> FUNCTIONS = Set.of("clamp", "lerp", "min", "max", "floor", "ceil", "round", "abs", "mod", "pow",
            "smoothstep", "sin", "cos", "rgb", "argb", "alpha", "mix", "palette", "select", "number", "bar", "hex", "str", "fixed",
            "plain", "readable", "align", "len", "contains", "lower", "upper");
    /** Every built-in function name, text animations included, sorted. */
    public static final List<String> STANDARD_FUNCTIONS = standardFunctions();

    private sealed interface Node permits Literal, Variable, ArrayLiteral, Call, Unary, Binary, Ternary {}
    private record Literal(Object value) implements Node {}
    private record Variable(String name, int position) implements Node {}
    private record ArrayLiteral(List<Node> items) implements Node {}
    private record Call(String name, List<Node> arguments, int position) implements Node {}
    private record Unary(char operator, Node operand) implements Node {}
    private record Binary(String operator, Node left, Node right) implements Node {}
    private record Ternary(Node condition, Node then, Node otherwise) implements Node {}

    private final String source;
    private final Node root;
    private final Set<String> roots;
    private final Map<String, Function> functions;

    private GuiExpression(String source, Node root, Set<String> roots, Map<String, Function> functions) { this.source = source; this.root = root; this.roots = Set.copyOf(roots); this.functions = functions; }

    /** Parses and checks that every variable starts with one of the allowed roots. */
    public static GuiExpression parse(String source, Set<String> allowedRoots) { return parse(source, allowedRoots, MAXIMUM_LENGTH, Map.of()); }

    /** Parses with another length cap and extra functions, which take precedence over built-in ones of the same name. */
    public static GuiExpression parse(String source, Set<String> allowedRoots, int maximumLength, Map<String, Function> extraFunctions) {
        Objects.requireNonNull(source);
        Map<String, Function> functions = Map.copyOf(extraFunctions);
        if (source.length() > maximumLength) throw new Invalid("exceeds " + maximumLength + " characters");
        if (source.isBlank()) throw new Invalid("must be a non-blank expression");
        Parser parser = new Parser(source, functions.keySet());
        Node root = parser.ternary();
        parser.skip();
        if (parser.index < source.length()) throw new Invalid("unexpected '" + source.charAt(parser.index) + "' at position " + parser.index);
        List<String> found = new ArrayList<>();
        collect(root, found);
        for (String name : found) {
            String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (!allowedRoots.contains(head)) throw new Invalid("unknown variable '" + name + "'");
        }
        return new GuiExpression(source, root, allowedRoots, functions);
    }

    public String source() { return source; }
    public Set<String> roots() { return roots; }
    /** Every variable name the expression reads, dotted names whole, in source order. */
    public List<String> variables() { List<String> found = new ArrayList<>(); collect(root, found); return List.copyOf(found); }

    public Object evaluate(Scope scope) { return eval(root, new Run(scope, Integer.MAX_VALUE, Integer.MAX_VALUE)); }

    /** Evaluates with at most {@code maximumVisits} node visits and {@code maximumText} characters per intermediate string. */
    public Object evaluate(Scope scope, int maximumVisits, int maximumText) { return eval(root, new Run(scope, maximumVisits, maximumText)); }

    private static final class Run {
        final Scope scope; final int maximumVisits, maximumText; int visits;
        Run(Scope scope, int maximumVisits, int maximumText) { this.scope = scope; this.maximumVisits = maximumVisits; this.maximumText = maximumText; }
    }

    public boolean test(Scope scope) {
        Object value = evaluate(scope);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) return Boolean.parseBoolean(text);
        throw new Invalid("must evaluate to true or false, got " + typeName(value));
    }

    public double number(Scope scope) {
        Object value = evaluate(scope);
        if (value instanceof Double number) return number;
        if (value instanceof String text) return parseNumber(text);
        throw new Invalid("must evaluate to a number, got " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(Scope scope) {
        Object value = evaluate(scope);
        if (value instanceof List<?> items) return (List<Object>) items;
        throw new Invalid("must evaluate to a list, got " + typeName(value));
    }

    public static String text(Object value) {
        if (value instanceof Double number) {
            double unboxed = number;
            if (unboxed == Math.rint(unboxed) && !Double.isInfinite(unboxed) && Math.abs(unboxed) < 1e15) return Long.toString((long) unboxed);
            return Double.toString(unboxed);
        }
        if (value instanceof List<?> items) {
            List<String> parts = new ArrayList<>();
            for (Object item : items) parts.add(text(item));
            return "[" + String.join(", ", parts) + "]";
        }
        if (value instanceof Map<?, ?> entries) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) parts.add(entry.getKey() + ": " + text(entry.getValue()));
            return "{" + String.join(", ", parts) + "}";
        }
        return String.valueOf(value);
    }

    private static void collect(Node node, List<String> names) {
        switch (node) {
            case Literal ignored -> {}
            case Variable variable -> names.add(variable.name());
            case ArrayLiteral array -> array.items().forEach((Node item) -> collect(item, names));
            case Call call -> call.arguments().forEach((Node argument) -> collect(argument, names));
            case Unary unary -> collect(unary.operand(), names);
            case Binary binary -> { collect(binary.left(), names); collect(binary.right(), names); }
            case Ternary ternary -> { collect(ternary.condition(), names); collect(ternary.then(), names); collect(ternary.otherwise(), names); }
        }
    }

    private Object eval(Node node, Run run) {
        if (++run.visits > run.maximumVisits) throw new Invalid("exceeds " + run.maximumVisits + " evaluation steps");
        Object result = switch (node) {
            case Literal literal -> literal.value();
            case Variable variable -> {
                Object value = run.scope.variable(variable.name());
                if (value == null) throw new Invalid("unknown variable '" + variable.name() + "' at position " + variable.position());
                yield normalize(value);
            }
            case ArrayLiteral array -> {
                List<Object> values = new ArrayList<>();
                for (Node item : array.items()) values.add(eval(item, run));
                yield List.copyOf(values);
            }
            case Call call -> call(call, run);
            case Unary unary -> {
                Object value = eval(unary.operand(), run);
                if (unary.operator() == '!') yield !bool(value);
                yield -num(value);
            }
            case Binary binary -> binary(binary, run);
            case Ternary ternary -> bool(eval(ternary.condition(), run)) ? eval(ternary.then(), run) : eval(ternary.otherwise(), run);
        };
        if (result instanceof String text && text.length() > run.maximumText) throw new Invalid("text exceeds " + run.maximumText + " characters");
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean || value instanceof String || value instanceof List<?> || value instanceof Map<?, ?>) return value;
        return value.toString();
    }

    private Object binary(Binary binary, Run run) {
        String operator = binary.operator();
        if (operator.equals("&&")) return bool(eval(binary.left(), run)) && bool(eval(binary.right(), run));
        if (operator.equals("||")) return bool(eval(binary.left(), run)) || bool(eval(binary.right(), run));
        Object left = eval(binary.left(), run), right = eval(binary.right(), run);
        return switch (operator) {
            case "+" -> {
                if (!(left instanceof String) && !(right instanceof String)) yield num(left) + num(right);
                String first = text(left), second = text(right);
                if ((long) first.length() + second.length() > run.maximumText) throw new Invalid("text exceeds " + run.maximumText + " characters");
                yield first + second;
            }
            case "-" -> num(left) - num(right);
            case "*" -> num(left) * num(right);
            case "/" -> {
                double divisor = num(right);
                if (divisor == 0) throw new Invalid("division by zero");
                yield num(left) / divisor;
            }
            case "%" -> {
                double divisor = num(right);
                if (divisor == 0) throw new Invalid("division by zero");
                yield num(left) % divisor;
            }
            case "<" -> num(left) < num(right);
            case "<=" -> num(left) <= num(right);
            case ">" -> num(left) > num(right);
            case ">=" -> num(left) >= num(right);
            case "==" -> same(left, right);
            case "!=" -> !same(left, right);
            default -> throw new Invalid("unknown operator " + operator);
        };
    }

    private static boolean same(Object left, Object right) {
        if (left instanceof Double a && right instanceof Double b) return a.doubleValue() == b.doubleValue();
        if (left instanceof String a && right instanceof String b) return a.equals(b);
        if (left instanceof Boolean a && right instanceof Boolean b) return a.booleanValue() == b.booleanValue();
        throw new Invalid("== compares two numbers, two strings or two booleans, got " + typeName(left) + " and " + typeName(right));
    }

    private Object call(Call call, Run run) {
        List<Object> values = new ArrayList<>();
        for (Node argument : call.arguments()) values.add(eval(argument, run));
        String name = call.name();
        Function extra = functions.get(name);
        if (extra != null) return extra.call(List.copyOf(values), run.scope);
        return switch (name) {
            case "clamp" -> { arity(call, values, 3); yield Math.clamp(num(values.get(0)), num(values.get(1)), Math.max(num(values.get(1)), num(values.get(2)))); }
            case "lerp" -> { arity(call, values, 3); double a = num(values.get(0)); yield a + (num(values.get(1)) - a) * num(values.get(2)); }
            case "min" -> { arity(call, values, 2); yield Math.min(num(values.get(0)), num(values.get(1))); }
            case "max" -> { arity(call, values, 2); yield Math.max(num(values.get(0)), num(values.get(1))); }
            case "floor" -> { arity(call, values, 1); yield Math.floor(num(values.get(0))); }
            case "ceil" -> { arity(call, values, 1); yield Math.ceil(num(values.get(0))); }
            case "round" -> { arity(call, values, 1); yield (double) Math.round(num(values.get(0))); }
            case "abs" -> { arity(call, values, 1); yield Math.abs(num(values.get(0))); }
            case "mod" -> {
                arity(call, values, 2);
                double divisor = num(values.get(1));
                if (divisor == 0) throw new Invalid("mod by zero");
                double a = num(values.get(0));
                yield a - divisor * Math.floor(a / divisor);
            }
            case "pow" -> {
                arity(call, values, 2);
                double result = Math.pow(num(values.get(0)), num(values.get(1)));
                if (!Double.isFinite(result)) throw new Invalid("pow result is not finite");
                yield result;
            }
            case "smoothstep" -> {
                arity(call, values, 3);
                double edge0 = num(values.get(0)), edge1 = num(values.get(1));
                if (edge0 == edge1) throw new Invalid("smoothstep edges are equal");
                double t = Math.clamp((num(values.get(2)) - edge0) / (edge1 - edge0), 0, 1);
                yield t * t * (3 - 2 * t);
            }
            case "sin" -> { arity(call, values, 1); yield Math.sin(num(values.get(0))); }
            case "cos" -> { arity(call, values, 1); yield Math.cos(num(values.get(0))); }
            case "rgb" -> { arity(call, values, 3); yield argb(255, num(values.get(0)), num(values.get(1)), num(values.get(2))); }
            case "argb" -> { arity(call, values, 4); yield argb(num(values.get(0)), num(values.get(1)), num(values.get(2)), num(values.get(3))); }
            case "alpha" -> {
                arity(call, values, 2);
                long color = (long) num(values.get(0)) & 0xFFFFFFL;
                yield (double) ((((long) Math.clamp(num(values.get(1)), 0, 255)) << 24) | color);
            }
            case "mix" -> {
                arity(call, values, 3);
                long a = (long) num(values.get(0)), b = (long) num(values.get(1));
                double t = Math.clamp(num(values.get(2)), 0, 1);
                long result = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    long channel = Math.round(((a >> shift) & 0xFF) + (((b >> shift) & 0xFF) - ((a >> shift) & 0xFF)) * t);
                    result |= (channel & 0xFF) << shift;
                }
                yield (double) result;
            }
            case "palette" -> { arity(call, values, 2); yield num(pick(values)); }
            case "select" -> { arity(call, values, 2); yield pick(values); }
            case "number" -> { arity(call, values, 1); yield parseNumber(text(values.get(0))); }
            case "bar" -> {
                arity(call, values, 5);
                double maximum = num(values.get(1));
                int width = (int) Math.clamp(num(values.get(2)), 0, 256);
                int filled = maximum <= 0 ? 0 : (int) Math.round(Math.clamp(num(values.get(0)) / maximum, 0, 1) * width);
                if ((long) filled * text(values.get(3)).length() + (long) (width - filled) * text(values.get(4)).length() > run.maximumText) throw new Invalid("text exceeds " + run.maximumText + " characters");
                yield text(values.get(3)).repeat(filled) + text(values.get(4)).repeat(width - filled);
            }
            case "hex" -> { arity(call, values, 1); yield String.format(Locale.ROOT, "[%06X]", ((long) num(values.get(0))) & 0xFFFFFFL); }
            case "str" -> { arity(call, values, 1); yield text(values.get(0)); }
            case "fixed" -> {
                arity(call, values, 2);
                double digits = num(values.get(1));
                if (digits != Math.rint(digits) || digits < 0 || digits > 20) throw new Invalid("fixed digits must be a whole number 0-20");
                yield String.format(Locale.ROOT, "%." + (int) digits + "f", num(values.get(0)));
            }
            case "plain" -> { arity(call, values, 1); yield RichText.plain(text(values.get(0))); }
            case "readable" -> { arity(call, values, 1); yield readable(text(values.get(0))); }
            case "align" -> {
                arity(call, values, 3);
                String value = text(values.get(0));
                int width = (int) num(values.get(1));
                int pad = Math.max(0, width - RichText.plain(value).length());
                String mode = text(values.get(2));
                yield switch (mode) {
                    case "right" -> " ".repeat(pad) + value;
                    case "center", "middle" -> " ".repeat(pad / 2) + value + " ".repeat(pad - pad / 2);
                    default -> value + " ".repeat(pad);
                };
            }
            case "len" -> { arity(call, values, 1); yield values.get(0) instanceof List<?> items ? (double) items.size() : (double) text(values.get(0)).length(); }
            case "contains" -> { arity(call, values, 2); yield values.get(0) instanceof List<?> items ? items.contains(values.get(1)) : text(values.get(0)).contains(text(values.get(1))); }
            case "lower" -> { arity(call, values, 1); yield text(values.get(0)).toLowerCase(Locale.ROOT); }
            case "upper" -> { arity(call, values, 1); yield text(values.get(0)).toUpperCase(Locale.ROOT); }
            default -> {
                if (!GuiTextFunctions.NAMES.contains(name)) throw new Invalid("unknown function '" + name + "' at position " + call.position());
                yield GuiTextFunctions.call(name, values);
            }
        };
    }

    private static Object pick(List<Object> values) {
        if (!(values.get(0) instanceof List<?> items) || items.isEmpty()) throw new Invalid("expected a non-empty array literal");
        int size = items.size();
        int index = (int) Math.floor(num(values.get(1)));
        return items.get(((index % size) + size) % size);
    }

    private static double argb(double a, double r, double g, double b) {
        long result = ((long) Math.clamp(a, 0, 255) << 24) | ((long) Math.clamp(r, 0, 255) << 16) | ((long) Math.clamp(g, 0, 255) << 8) | (long) Math.clamp(b, 0, 255);
        return (double) result;
    }

    private static String readable(String value) {
        String[] words = value.replace('-', '_').toLowerCase(Locale.ROOT).split("_");
        List<String> parts = new ArrayList<>();
        for (String word : words) if (!word.isEmpty()) parts.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        return String.join(" ", parts);
    }

    private static double parseNumber(String text) {
        String plain = RichText.plain(text).replace(",", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(plain);
        if (!matcher.find()) throw new Invalid("'" + text + "' contains no number");
        return Double.parseDouble(matcher.group());
    }

    private static void arity(Call call, List<Object> values, int expected) {
        if (values.size() != expected) throw new Invalid(call.name() + " takes " + expected + " arguments, got " + values.size());
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        throw new Invalid("expected true or false, got " + typeName(value));
    }

    private static double num(Object value) {
        if (value instanceof Double number) return number;
        throw new Invalid("expected a number, got " + typeName(value));
    }

    private static String typeName(Object value) {
        return switch (value) {
            case Double ignored -> "number";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case List<?> ignored -> "list";
            case Map<?, ?> ignored -> "map";
            case null -> "nothing";
            default -> value.getClass().getSimpleName();
        };
    }

    private static final class Parser {
        private final String text;
        private int index, depth;
        private final Set<String> extra;
        Parser(String text, Set<String> extra) { this.text = text; this.extra = extra; }

        void skip() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
        boolean take(String token) {
            skip();
            if (text.startsWith(token, index)) { index += token.length(); return true; }
            return false;
        }
        void enter() { if (++depth > MAXIMUM_DEPTH) throw new Invalid("nesting exceeds " + MAXIMUM_DEPTH + " levels"); }

        Node ternary() {
            enter();
            Node condition = or();
            if (take("?")) {
                Node then = ternary();
                if (!take(":")) throw new Invalid("expected ':' at position " + index);
                Node otherwise = ternary();
                depth--;
                return new Ternary(condition, then, otherwise);
            }
            depth--;
            return condition;
        }
        Node or() { Node left = and(); while (take("||")) left = new Binary("||", left, and()); return left; }
        Node and() { Node left = equality(); while (take("&&")) left = new Binary("&&", left, equality()); return left; }
        Node equality() {
            Node left = comparison();
            while (true) {
                if (take("==")) left = new Binary("==", left, comparison());
                else if (take("!=")) left = new Binary("!=", left, comparison());
                else return left;
            }
        }
        Node comparison() {
            Node left = additive();
            while (true) {
                if (take("<=")) left = new Binary("<=", left, additive());
                else if (take(">=")) left = new Binary(">=", left, additive());
                else if (take("<")) left = new Binary("<", left, additive());
                else if (take(">")) left = new Binary(">", left, additive());
                else return left;
            }
        }
        Node additive() {
            Node left = multiplicative();
            while (true) {
                if (take("+")) left = new Binary("+", left, multiplicative());
                else if (take("-")) left = new Binary("-", left, multiplicative());
                else return left;
            }
        }
        Node multiplicative() {
            Node left = unary();
            while (true) {
                if (take("*")) left = new Binary("*", left, unary());
                else if (take("/")) left = new Binary("/", left, unary());
                else if (take("%")) left = new Binary("%", left, unary());
                else return left;
            }
        }
        Node unary() {
            skip();
            if (index < text.length() && text.charAt(index) == '!' && !text.startsWith("!=", index)) { index++; enter(); Node operand = unary(); depth--; return new Unary('!', operand); }
            if (take("-")) { enter(); Node operand = unary(); depth--; return new Unary('-', operand); }
            return primary();
        }
        Node primary() {
            skip();
            if (index >= text.length()) throw new Invalid("unexpected end at position " + index);
            char glyph = text.charAt(index);
            int start = index;
            if (glyph == '(') {
                index++;
                Node inner = ternary();
                if (!take(")")) throw new Invalid("unclosed paren at position " + start);
                return inner;
            }
            if (glyph == '[') {
                index++;
                List<Node> items = new ArrayList<>();
                if (!take("]")) {
                    do items.add(ternary()); while (take(","));
                    if (!take("]")) throw new Invalid("unclosed array at position " + start);
                }
                return new ArrayLiteral(List.copyOf(items));
            }
            if (glyph == '\'' || glyph == '"') return new Literal(string(glyph));
            if (glyph == '#') return new Literal(color());
            if (Character.isDigit(glyph) || (glyph == '.' && index + 1 < text.length() && Character.isDigit(text.charAt(index + 1)))) {
                while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) index++;
                try { return new Literal(Double.parseDouble(text.substring(start, index))); }
                catch (NumberFormatException invalid) { throw new Invalid("bad number at position " + start); }
            }
            if (Character.isLetter(glyph) || glyph == '_') {
                while (index < text.length() && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_' || text.charAt(index) == '.')) index++;
                String name = text.substring(start, index);
                if (name.equals("true")) return new Literal(true);
                if (name.equals("false")) return new Literal(false);
                if (take("(")) {
                    if (!FUNCTIONS.contains(name) && !GuiTextFunctions.NAMES.contains(name) && !extra.contains(name)) throw new Invalid("unknown function '" + name + "' at position " + start);
                    List<Node> arguments = new ArrayList<>();
                    if (!take(")")) {
                        do arguments.add(ternary()); while (take(","));
                        if (!take(")")) throw new Invalid("unclosed paren at position " + start);
                    }
                    return new Call(name, List.copyOf(arguments), start);
                }
                if (name.endsWith(".")) throw new Invalid("bad variable name at position " + start);
                return new Variable(name, start);
            }
            throw new Invalid("unexpected '" + glyph + "' at position " + start);
        }
        String string(char quote) {
            int start = index++;
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char glyph = text.charAt(index++);
                if (glyph == quote) return out.toString();
                if (glyph == '\\' && index < text.length()) {
                    char escaped = text.charAt(index++);
                    out.append(escaped == 'n' ? '\n' : escaped);
                } else out.append(glyph);
            }
            throw new Invalid("unclosed string at position " + start);
        }
        Double color() {
            int start = index++;
            while (index < text.length() && Character.digit(text.charAt(index), 16) >= 0) index++;
            String digits = text.substring(start + 1, index);
            return switch (digits.length()) {
                case 3 -> (double) (0xFF000000L | Long.parseLong("" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1) + digits.charAt(2) + digits.charAt(2), 16));
                case 6 -> (double) (0xFF000000L | Long.parseLong(digits, 16));
                case 8 -> (double) Long.parseLong(digits, 16);
                default -> throw new Invalid("colour literal must be #RGB, #RRGGBB or #AARRGGBB at position " + start);
            };
        }
    }

    private static List<String> standardFunctions() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(FUNCTIONS);
        names.addAll(GuiTextFunctions.NAMES);
        return List.copyOf(names);
    }
}
