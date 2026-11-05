package art.arcane.animus.core.gui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiExpressionTest {
    private static final Set<String> ROOTS = Set.of("viewer", "entry");
    private static final GuiExpression.Scope SCOPE = (String name) -> Map.<String, Object>of("viewer.level", 7, "viewer.name", "Alex", "entry", "stone").get(name);

    private static Object eval(String source) { return GuiExpression.parse(source, ROOTS).evaluate(SCOPE); }

    @Test void arithmeticFollowsPrecedence() {
        assertEquals(14.0, eval("2 + 3 * 4"));
        assertEquals(20.0, eval("(2 + 3) * 4"));
        assertEquals(-1.0, eval("-1 % 3"));
        assertEquals(2.0, eval("mod(-1, 3)"));
    }

    @Test void variablesComparisonsAndTernaries() {
        assertEquals(true, eval("viewer.level >= 5 && viewer.name == 'Alex'"));
        assertEquals("high", eval("viewer.level > 5 ? 'high' : 'low'"));
        assertEquals(false, eval("!(viewer.level == 7)"));
    }

    @Test void plusConcatenatesWhenEitherSideIsText() {
        assertEquals("Level 7", eval("'Level ' + viewer.level"));
        assertEquals("Level 7", eval("'Level ' + str(viewer.level)"));
    }

    @Test void theStandardLibraryMatchesGloss() {
        assertEquals(5.0, eval("clamp(9, 0, 5)"));
        assertEquals(-2.0, eval("round(-2.5)"));
        assertEquals("b", eval("select(['a','b','c'], 4)"));
        assertEquals("Iron Ore", eval("readable('IRON_ORE')"));
        assertEquals("##--", eval("bar(5, 10, 4, '#', '-')"));
        assertEquals("1.50", eval("fixed(1.5, 2)"));
        assertEquals("[FF0000]", eval("hex(#F00)"));
        assertEquals(1234.0, eval("number('&a1,234 coins')"));
        assertEquals("ab  ", eval("align('ab', 4, 'left')"));
    }

    @Test void arraysAreLists() {
        assertEquals(List.of("stone", "dirt"), eval("['stone', 'dirt']"));
    }

    @Test void unknownVariablesAreRefusedAtParse() {
        GuiExpression.Invalid invalid = assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("wobble + 1", ROOTS));
        assertEquals("unknown variable 'wobble'", invalid.getMessage());
    }

    @Test void syntaxErrorsNameThePosition() {
        assertEquals("unclosed paren at position 0", assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("(1 + 2", ROOTS)).getMessage());
        assertEquals("unknown function 'wiggle' at position 0", assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("wiggle(1)", ROOTS)).getMessage());
        assertTrue(assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("1 +", ROOTS)).getMessage().contains("unexpected end"));
    }

    @Test void longAndDeepExpressionsAreRefused() {
        assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("1+".repeat(300) + "1", ROOTS));
        assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("(".repeat(300) + "1" + ")".repeat(300), Set.of()));
    }

    @Test void comparingDifferentTypesIsAnError() {
        assertThrows(GuiExpression.Invalid.class, () -> eval("viewer.level == 'seven'"));
        assertThrows(GuiExpression.Invalid.class, () -> eval("1 / 0"));
    }

    @Test void shortCircuitSkipsTheRightSide() {
        assertEquals(false, eval("false && (1 / 0 > 1)"));
        assertEquals(true, eval("true || (1 / 0 > 1)"));
    }

    @Test void templatesFillHolesAndShowAQuestionMarkForFailures() {
        GuiTemplate template = GuiTemplate.parse("&7You picked &f{{ entry }}&7 at {{ viewer.level * 2 }} {{ 1 / 0 }}", ROOTS);
        assertEquals("&7You picked &fstone&7 at 14 ?", template.render(SCOPE));
        assertThrows(GuiExpression.Invalid.class, () -> GuiTemplate.parse("{{ viewer.level", ROOTS));
    }

    @Test void colourCodesAndMiniMessageBecomeSectionCodes() {
        assertEquals("§aGreen", GuiText.colorize("&aGreen"));
        assertEquals("§x§f§f§0§0§0§0Red", GuiText.colorize("&#FF0000Red"));
        assertEquals("§cRed§rPlain", GuiText.colorize("<red>Red</red>Plain"));
        assertEquals("<notatag> stays", GuiText.colorize("<notatag> stays"));
        assertEquals("Red", GuiText.plain("<red>&lRed"));
    }
}
