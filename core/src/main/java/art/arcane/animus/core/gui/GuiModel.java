package art.arcane.animus.core.gui;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The immutable pieces of a chest GUI document, with Gloss's field meanings. */
public final class GuiModel {
    private GuiModel() {}

    /** The four player clicks a window binds. Middle click, number keys, drops and drags never reach a slot. */
    public enum Click {
        LEFT("left_click"), RIGHT("right_click"), SHIFT_LEFT("shift_left_click"), SHIFT_RIGHT("shift_right_click");
        private final String key;
        Click(String key) { this.key = key; }
        public String key() { return key; }
        public boolean shift() { return this == SHIFT_LEFT || this == SHIFT_RIGHT; }
        public boolean right() { return this == RIGHT || this == SHIFT_RIGHT; }
    }

    /** {@code show} and {@code when}: a constant or an expression. */
    public record Condition(Optional<GuiExpression> expression, boolean constant) {
        public static final Condition ALWAYS = new Condition(Optional.empty(), true);
        public static final Condition NEVER = new Condition(Optional.empty(), false);
        public boolean test(GuiExpression.Scope scope) {
            if (expression.isEmpty()) return constant;
            try { return expression.get().test(scope); }
            catch (GuiExpression.Invalid failed) { return false; }
        }
    }

    public enum IconKind { ITEM, PLAYER_HEAD }

    /** {@code item} and {@code playerHead} icons. {@code item} and {@code player} may contain holes. */
    public record Icon(IconKind kind, GuiTemplate item, int count, Optional<Integer> customModelValue, Optional<GuiTemplate> name,
                       List<GuiTemplate> lore) {
        public Icon { Objects.requireNonNull(kind); Objects.requireNonNull(item); lore = List.copyOf(lore); }
    }

    public sealed interface Component permits Button, Decoration, Toggle {
        Condition show();
    }
    public record Button(Icon icon, List<Action> actions, Condition show) implements Component {
        public Button { actions = List.copyOf(actions); }
    }
    public record Decoration(Icon icon, Condition show) implements Component {}
    public record Toggle(GuiExpression condition, String expectedValue, List<Action> trueActions, List<Action> falseActions,
                         Icon trueIcon, Icon falseIcon, Condition show) implements Component {
        public Toggle { trueActions = List.copyOf(trueActions); falseActions = List.copyOf(falseActions); }
    }

    /** Fields every action may carry. An empty trigger set means any click. */
    public record Common(Set<Click> triggers, Optional<GuiExpression> when, int cooldownTicks) {
        public static final Common NONE = new Common(Set.of(), Optional.empty(), 0);
        public Common { triggers = Set.copyOf(triggers); }
    }

    public sealed interface Action permits Command, Sound, Message, Title, ActionBar, Close, Navigate, Inventory, SetSession, Give, Take,
            Economy, Delay, Sequence, If, Switch, Chance, Cooldown, Stop, Call, OpenShop {
        Common common();
    }
    public enum CommandSource { SERVER, PLAYER }
    public record Command(Common common, GuiTemplate command, CommandSource source) implements Action {}
    public record Sound(Common common, String sound, String source, float volume, float pitch) implements Action {}
    public record Message(Common common, GuiTemplate message) implements Action {}
    public record Title(Common common, GuiTemplate title, Optional<GuiTemplate> subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) implements Action {}
    public record ActionBar(Common common, GuiTemplate text) implements Action {}
    public record Close(Common common) implements Action {}
    public enum NavigateMode { PAGE, PUSH, REPLACE, BACK, HOME, CLOSE }
    public record Navigate(Common common, NavigateMode mode, Optional<GuiTemplate> target) implements Action {}
    public record Inventory(Common common, GuiTemplate id, Map<String, GuiTemplate> args) implements Action {
        public Inventory { args = Map.copyOf(args); }
    }
    public record SetSession(Common common, String variable, GuiTemplate value) implements Action {}
    public record Give(Common common, Icon item, Optional<GuiExpression> amount, boolean dropIfFull) implements Action {}
    public record Take(Common common, Icon item, Optional<GuiExpression> amount, Optional<GuiTemplate> denyMessage) implements Action {}
    public enum EconomyOperation { WITHDRAW, DEPOSIT, HAS }
    public record Economy(Common common, EconomyOperation operation, Optional<GuiExpression> amount, Optional<GuiTemplate> denyMessage) implements Action {}
    public record Delay(Common common, int ticks) implements Action {}
    public record Step(int atTicks, Action action) {}
    public record Sequence(Common common, List<Step> steps) implements Action {
        public Sequence { steps = List.copyOf(steps); }
    }
    public record If(Common common, GuiExpression condition, List<Action> then, List<Action> otherwise) implements Action {
        public If { then = List.copyOf(then); otherwise = List.copyOf(otherwise); }
    }
    public record Switch(Common common, GuiExpression on, Map<String, List<Action>> cases, List<Action> fallback) implements Action {
        public Switch { cases = Map.copyOf(cases); fallback = List.copyOf(fallback); }
    }
    public record Chance(Common common, double percent, List<Action> then, List<Action> otherwise) implements Action {
        public Chance { then = List.copyOf(then); otherwise = List.copyOf(otherwise); }
    }
    public record Cooldown(Common common, String key, int ticks, List<Action> then, List<Action> otherwise) implements Action {
        public Cooldown { then = List.copyOf(then); otherwise = List.copyOf(otherwise); }
    }
    public record Stop(Common common) implements Action {}
    public record Call(Common common, String action) implements Action {}
    /** {@code animus:open-shop}: replaces the window with a shop. */
    public record OpenShop(Common common, String shop) implements Action {}

    /** {@code list}: fills one mask character with pages of a template. */
    public record ListSection(char area, String variable, GuiExpression source, Optional<Integer> pageSize, Component template, int refreshTicks) {}
    public record Presentation(Optional<GuiTemplate> title, Optional<List<String>> mask, Optional<Map<String, Component>> keys,
                               Optional<Map<Integer, Component>> slots) {}
    public record Variant(String id, int priority, GuiExpression when, Presentation presentation) {}
    public record Refresh(boolean always, int titleTicks, int slotsTicks) {
        public static final Refresh DEFAULT = new Refresh(false, 20, 20);
    }
}
