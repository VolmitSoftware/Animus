package art.arcane.animus.core.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Text with {@code {{ expr }}} holes, parsed once at load. A hole that fails at run time renders as {@code ?}. Where
 * the roots include {@value #ANIMATION}, a {@code |animation.<id>|} token shows the current frame of a text
 * animation, read from the scope as {@code animation.<id>}.
 */
public final class GuiTemplate {
    /** The pseudo-root that turns on {@code |animation.<id>|} tokens. */
    public static final String ANIMATION = "animation";
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("\\|animation\\.([a-z0-9_:/-]{1,256})\\|");
    private record Animation(String id) {}
    private final String source;
    private final List<Object> parts;

    private GuiTemplate(String source, List<Object> parts) { this.source = source; this.parts = List.copyOf(parts); }

    public static GuiTemplate parse(String source, Set<String> roots) {
        if (!source.contains("|animation.")) return holes(source, roots);
        if (!roots.contains(ANIMATION)) throw new GuiExpression.Invalid("|animation.<id>| text animations work in holograms and holographic menus, not here");
        List<Object> parts = new ArrayList<>();
        java.util.regex.Matcher token = TOKEN.matcher(source);
        int index = 0;
        while (token.find()) {
            if (token.start() > index) parts.addAll(holes(source.substring(index, token.start()), roots).parts);
            parts.add(new Animation(token.group(1)));
            index = token.end();
        }
        if (index < source.length()) parts.addAll(holes(source.substring(index), roots).parts);
        if (parts.stream().anyMatch((Object part) -> part instanceof String text && text.contains("|animation.")))
            throw new GuiExpression.Invalid("malformed |animation.<id>| token; ids use lowercase letters, digits, _, -, / and :");
        return new GuiTemplate(source, parts);
    }

    /** The animation ids this text shows. */
    public List<String> animations() {
        return parts.stream().filter((Object part) -> part instanceof Animation).map((Object part) -> ((Animation) part).id()).toList();
    }

    private static GuiTemplate holes(String source, Set<String> roots) {
        List<Object> parts = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf("{{", index);
            if (open < 0) { parts.add(source.substring(index)); break; }
            int close = source.indexOf("}}", open + 2);
            if (close < 0) throw new GuiExpression.Invalid("unclosed {{ at position " + open);
            if (open > index) parts.add(source.substring(index, open));
            String expression = source.substring(open + 2, close).trim();
            try { parts.add(GuiExpression.parse(expression, roots)); }
            catch (GuiExpression.Invalid invalid) { throw new GuiExpression.Invalid("{{ at position " + open + "}}: " + invalid.getMessage()); }
            index = close + 2;
        }
        return new GuiTemplate(source, parts);
    }

    public static GuiTemplate literal(String text) { return new GuiTemplate(text, List.of(text)); }

    public String source() { return source; }
    public boolean constant() { return parts.stream().allMatch((Object part) -> part instanceof String); }

    public String render(GuiExpression.Scope scope) {
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof GuiExpression expression) {
                try { out.append(GuiExpression.text(expression.evaluate(scope))); }
                catch (GuiExpression.Invalid failed) { out.append('?'); }
            } else if (part instanceof Animation animation) {
                Object frame = scope.variable(ANIMATION + "." + animation.id());
                if (frame != null) out.append(GuiExpression.text(frame));
            } else out.append((String) part);
        }
        return out.toString();
    }
}
