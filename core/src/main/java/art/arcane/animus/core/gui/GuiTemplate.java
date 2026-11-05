package art.arcane.animus.core.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Text with {@code {{ expr }}} holes, parsed once at load. A hole that fails at run time renders as {@code ?}. */
public final class GuiTemplate {
    private final String source;
    private final List<Object> parts;

    private GuiTemplate(String source, List<Object> parts) { this.source = source; this.parts = List.copyOf(parts); }

    public static GuiTemplate parse(String source, Set<String> roots) {
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
            } else out.append((String) part);
        }
        return out.toString();
    }
}
