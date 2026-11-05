package art.arcane.animus.core.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /** A problem in an expression, with the character position when the parser knows it. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) { super(message); }
    }
    private static final Set<String> FUNCTIONS = Set.of("clamp", "lerp", "min", "max", "floor", "ceil", "round", "abs", "mod", "pow",
            "smoothstep", "sin", "cos", "rgb", "argb", "alpha", "mix", "palette", "select", "number", "bar", "hex", "str", "fixed",
            "plain", "readable", "align", "len", "contains", "lower", "upper");

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

    private GuiExpression(String source, Node root, Set<String> roots) { this.source = source; this.root = root; this.roots = Set.copyOf(roots); }

    /** Parses and checks that every variable starts with one of the allowed roots. */
    public static GuiExpression parse(String source, Set<String> allowedRoots) {
        Objects.requireNonNull(source);
        if (source.length() > MAXIMUM_LENGTH) throw new Invalid("exceeds " + MAXIMUM_LENGTH + " characters");
        if (source.isBlank()) throw new Invalid("must be a non-blank expression");
        Parser parser = new Parser(source);
        Node root = parser.ternary();
        parser.skip();
        if (parser.index < source.length()) throw new Invalid("unexpected '" + source.charAt(parser.index) + "' at position " + parser.index);
        List<String> found = new ArrayList<>();
        collect(root, found);
        for (String name : found) {
            String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (!allowedRoots.contains(head)) throw new Invalid("unknown variable '" + name + "'");
        }
        return new GuiExpression(source, root, allowedRoots);
    }

    public String source() { return source; }
    public Set<String> roots() { return roots; }

    public Object evaluate(Scope scope) { return eval(root, scope); }

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

    private static Object eval(Node node, Scope scope) {
        return switch (node) {
            case Literal literal -> literal.value();
            case Variable variable -> {
                Object value = scope.variable(variable.name());
                if (value == null) throw new Invalid("unknown variable '" + variable.name() + "' at position " + variable.position());
                yield normalize(value);
            }
            case ArrayLiteral array -> {
                List<Object> values = new ArrayList<>();
                for (Node item : array.items()) values.add(eval(item, scope));
                yield List.copyOf(values);
            }
            case Call call -> call(call, scope);
            case Unary unary -> {
                Object value = eval(unary.operand(), scope);
                if (unary.operator() == '!') yield !bool(value);
                yield -num(value);
            }
            case Binary binary -> binary(binary, scope);
            case Ternary ternary -> bool(eval(ternary.condition(), scope)) ? eval(ternary.then(), scope) : eval(ternary.otherwise(), scope);
        };
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean || value instanceof String || value instanceof List<?>) return value;
        return value.toString();
    }

    private static Object binary(Binary binary, Scope scope) {
        String operator = binary.operator();
        if (operator.equals("&&")) return bool(eval(binary.left(), scope)) && bool(eval(binary.right(), scope));
        if (operator.equals("||")) return bool(eval(binary.left(), scope)) || bool(eval(binary.right(), scope));
        Object left = eval(binary.left(), scope), right = eval(binary.right(), scope);
        return switch (operator) {
            case "+" -> left instanceof String || right instanceof String ? text(left) + text(right) : num(left) + num(right);
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

    private static Object call(Call call, Scope scope) {
        List<Object> values = new ArrayList<>();
        for (Node argument : call.arguments()) values.add(eval(argument, scope));
        String name = call.name();
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
            case "plain" -> { arity(call, values, 1); yield GuiText.plain(text(values.get(0))); }
            case "readable" -> { arity(call, values, 1); yield readable(text(values.get(0))); }
            case "align" -> {
                arity(call, values, 3);
                String value = text(values.get(0));
                int width = (int) num(values.get(1));
                int pad = Math.max(0, width - GuiText.plain(value).length());
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
        String plain = GuiText.plain(text).replace(",", "");
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
            case null -> "nothing";
            default -> value.getClass().getSimpleName();
        };
    }

    private static final class Parser {
        private final String text;
        private int index, depth;
        Parser(String text) { this.text = text; }

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
                    if (!FUNCTIONS.contains(name) && !GuiTextFunctions.NAMES.contains(name)) throw new Invalid("unknown function '" + name + "' at position " + start);
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
}
