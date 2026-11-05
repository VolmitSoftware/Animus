package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.gui.GuiModel.Action;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.MatchRule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs Gloss action lists for one viewer of a chest window, hologram or holographic menu. The document decides what
 * {@code navigate}, {@code inventory} and {@code call} mean through its {@link Context}; everything else is the same
 * everywhere. A click runs at most {@value #MAXIMUM_ACTIONS_PER_CLICK} actions and {@value #MAXIMUM_CALL_DEPTH}
 * nested calls.
 */
public final class GuiActions<P, S> {
    public static final int MAXIMUM_CALL_DEPTH = 32, MAXIMUM_ACTIONS_PER_CLICK = 1024;

    /** What one document lets its actions do. Every method runs on the viewer's owner. */
    public interface Context<P, S> {
        P player();
        UUID id();
        ScreenHost<P, S> host();
        Map<String, String> session();
        boolean open();
        boolean cooldown(String key, int ticks);
        /** Runs the work after the given ticks while the document stays open for this viewer. */
        boolean schedule(int ticks, Runnable work);
        void close();
        /** Shows the effect of delayed actions. */
        void refresh();
        /** Returns whether the action list continues. */
        boolean navigate(GuiModel.Navigate navigate, String target);
        /** {@code inventory}: opens a chest GUI by id. */
        void inventory(String id, Map<String, String> args);
        /** {@code animus:open-shop}. */
        void shop(String id);
        /** The named action list {@code call} runs, or empty. */
        List<Action> named(String name);
        /** Where {@code particle} with {@code at: location} spawns, as x, y, z; empty for a chest window. */
        default Optional<double[]> location() { return Optional.empty(); }
    }

    private final Context<P, S> context;
    private int budget;

    public GuiActions(Context<P, S> context) { this.context = context; }

    /** Runs one click's actions. */
    public void click(List<Action> actions, Click click, GuiExpression.Scope scope) {
        budget = MAXIMUM_ACTIONS_PER_CLICK;
        run(actions, 0, click, scope, 0);
    }

    /** Runs a click's actions and reports whether the list ran to its end, without a stop, refusal or delay. */
    public boolean completes(List<Action> actions, Click click, GuiExpression.Scope scope) {
        budget = MAXIMUM_ACTIONS_PER_CLICK;
        return run(actions, 0, click, scope, 0);
    }

    /** Whether any action, at any depth, runs a command. Such a document needs the command allow-list. */
    public static boolean commands(List<Action> actions) {
        for (Action action : actions) {
            boolean found = switch (action) {
                case GuiModel.Command ignored -> true;
                case GuiModel.Sequence sequence -> commands(sequence.steps().stream().map(GuiModel.Step::action).toList());
                case GuiModel.If branch -> commands(branch.then()) || commands(branch.otherwise());
                case GuiModel.Switch choice -> choice.cases().values().stream().anyMatch(GuiActions::commands) || commands(choice.fallback());
                case GuiModel.Chance chance -> commands(chance.then()) || commands(chance.otherwise());
                case GuiModel.Cooldown cooldown -> commands(cooldown.then()) || commands(cooldown.otherwise());
                case GuiModel.Parallel parallel -> parallel.branches().stream().anyMatch(GuiActions::commands);
                case GuiModel.Repeat repeat -> commands(repeat.steps());
                default -> false;
            };
            if (found) return true;
        }
        return false;
    }

    /** Runs actions from {@code index}; returns false when a {@code stop}, a refusal or a delay ended this list. */
    private boolean run(List<Action> actions, int index, Click click, GuiExpression.Scope scope, int depth) {
        for (int position = index; position < actions.size(); position++) {
            if (!context.open() || --budget < 0) return false;
            Action action = actions.get(position);
            GuiModel.Common common = action.common();
            if (!common.triggers().isEmpty() && !common.triggers().contains(click)) continue;
            if (common.when().isPresent() && !test(common.when().get(), scope)) continue;
            if (common.cooldownTicks() > 0 && !context.cooldown("action:" + System.identityHashCode(action), common.cooldownTicks())) continue;
            if (action instanceof GuiModel.Delay delay) {
                int next = position + 1;
                context.schedule(delay.ticks(), () -> { budget = MAXIMUM_ACTIONS_PER_CLICK; run(actions, next, click, scope, depth); context.refresh(); });
                return false;
            }
            if (!perform(action, click, scope, depth)) return false;
        }
        return true;
    }

    private boolean perform(Action action, Click click, GuiExpression.Scope scope, int depth) {
        ScreenHost<P, S> host = context.host();
        P player = context.player();
        switch (action) {
            case GuiModel.Command command -> host.command(command.source() == GuiModel.CommandSource.PLAYER ? Optional.of(player) : Optional.empty(),
                    command.command().render(scope));
            case GuiModel.Sound sound -> host.sound(player, sound.sound(), sound.source(), sound.volume(), sound.pitch());
            case GuiModel.Message message -> host.message(player, RichText.colorize(message.message().render(scope)));
            case GuiModel.Title title -> host.title(player, RichText.colorize(title.title().render(scope)),
                    title.subtitle().map((GuiTemplate text) -> RichText.colorize(text.render(scope))).orElse(""), title.fadeInTicks(), title.stayTicks(), title.fadeOutTicks());
            case GuiModel.ActionBar bar -> host.actionBar(player, RichText.colorize(bar.text().render(scope)));
            case GuiModel.Close ignored -> { context.close(); return false; }
            case GuiModel.Navigate navigate -> {
                return context.navigate(navigate, navigate.target().map((GuiTemplate text) -> text.render(scope).trim()).orElse(""));
            }
            case GuiModel.Inventory inventory -> {
                Map<String, String> next = new LinkedHashMap<>();
                inventory.args().forEach((String key, GuiTemplate value) -> next.put(key, value.render(scope)));
                context.inventory(inventory.id().render(scope).trim(), next);
                return false;
            }
            case GuiModel.SetSession set -> context.session().put(set.variable(), set.value().render(scope));
            case GuiModel.Give give -> { return give(give, scope); }
            case GuiModel.Take take -> { return take(take, scope); }
            case GuiModel.Economy economy -> { return economy(economy, scope); }
            case GuiModel.Delay ignored -> { return false; }
            case GuiModel.Sequence sequence -> {
                for (GuiModel.Step step : sequence.steps()) {
                    if (step.atTicks() == 0) { if (!perform(step.action(), click, scope, depth)) return false; }
                    else context.schedule(step.atTicks(), () -> { budget = MAXIMUM_ACTIONS_PER_CLICK; perform(step.action(), click, scope, depth); context.refresh(); });
                }
            }
            case GuiModel.If branch -> { return run(test(branch.condition(), scope) ? branch.then() : branch.otherwise(), 0, click, scope, depth); }
            case GuiModel.Switch choice -> {
                String value;
                try { value = GuiExpression.text(choice.on().evaluate(scope)); }
                catch (GuiExpression.Invalid failed) { value = ""; }
                return run(choice.cases().getOrDefault(value, choice.fallback()), 0, click, scope, depth);
            }
            case GuiModel.Chance chance -> { return run(ThreadLocalRandom.current().nextDouble(100) < chance.percent() ? chance.then() : chance.otherwise(), 0, click, scope, depth); }
            case GuiModel.Cooldown cooldown -> { return run(context.cooldown("key:" + cooldown.key(), cooldown.ticks()) ? cooldown.then() : cooldown.otherwise(), 0, click, scope, depth); }
            case GuiModel.Stop ignored -> { return false; }
            case GuiModel.Call call -> {
                if (depth >= MAXIMUM_CALL_DEPTH) return false;
                return run(context.named(call.action()), 0, click, scope, depth + 1);
            }
            case GuiModel.OpenShop open -> { context.shop(open.shop()); return false; }
            case GuiModel.Teleport teleport -> { return host.teleport(player, teleport.world(), teleport.x(), teleport.y(), teleport.z(), teleport.yaw(), teleport.pitch()); }
            case GuiModel.BossBar bar -> {
                float progress = 1;
                if (bar.progress().isPresent()) {
                    try { progress = (float) Math.clamp(Double.parseDouble(bar.progress().get().render(scope).trim()), 0, 1); }
                    catch (NumberFormatException invalid) { progress = 1; }
                }
                host.bossBar(player, bar.lane(), RichText.colorize(bar.title().render(scope)), progress, bar.color(), bar.style(), bar.ticks());
            }
            case GuiModel.Broadcast broadcast -> host.broadcast(player, RichText.colorize(broadcast.message().render(scope)), broadcast.scope(), broadcast.radius(), broadcast.permission());
            case GuiModel.Effect effect -> { return host.effect(player, effect.effect(), effect.ticks(), effect.amplifier()); }
            case GuiModel.Particle particle -> {
                Optional<double[]> at = particle.atLocation() ? context.location()
                        : host.pose(player).map((ScreenHost.Pose pose) -> new double[]{pose.x(), pose.y() + 1, pose.z()});
                if (at.isEmpty()) return false;
                return host.particle(player, particle.particle(), particle.count(), particle.offset(), at.get()[0], at.get()[1], at.get()[2]);
            }
            case GuiModel.Parallel parallel -> {
                // Each branch keeps its own delays and stops; the list goes on once every branch has started.
                for (List<Action> branch : parallel.branches()) {
                    if (!context.open()) return false;
                    run(branch, 0, click, scope, depth);
                }
            }
            case GuiModel.Repeat repeat -> { return repeat(repeat, 0, click, scope, depth); }
        }
        return true;
    }

    /** Iterations from {@code done}; a stop inside the steps ends the loop and the list. */
    private boolean repeat(GuiModel.Repeat repeat, int done, Click click, GuiExpression.Scope scope, int depth) {
        for (int iteration = done; iteration < repeat.times(); iteration++) {
            if (!context.open() || budget <= 0) return false;
            if (repeat.whileCondition().isPresent() && !test(repeat.whileCondition().get(), scope)) return true;
            if (repeat.everyTicks() > 0 && iteration > done) {
                int next = iteration;
                context.schedule(repeat.everyTicks(), () -> { budget = MAXIMUM_ACTIONS_PER_CLICK; repeat(repeat, next, click, scope, depth); context.refresh(); });
                return false;
            }
            if (!run(repeat.steps(), 0, click, scope, depth)) return false;
        }
        return true;
    }

    private Optional<S> stack(GuiModel.Icon icon, GuiExpression.Scope scope) {
        ScreenFrame.Item item = GuiScreen.item(icon, scope);
        List<String> errors = new ArrayList<>();
        return context.host().stack(item.item(), item.name(), item.lore(), errors);
    }

    private static int amount(Optional<GuiExpression> amount, GuiModel.Icon icon, GuiExpression.Scope scope) {
        if (amount.isEmpty()) return icon.count();
        try { return (int) Math.clamp(Math.floor(amount.get().number(scope)), 0, 2304); }
        catch (GuiExpression.Invalid failed) { return 0; }
    }

    private boolean give(GuiModel.Give give, GuiExpression.Scope scope) {
        Optional<S> stack = stack(give.item(), scope);
        int amount = amount(give.amount(), give.item(), scope);
        if (stack.isEmpty() || amount <= 0) return false;
        InventoryTransaction<S> transaction = InventoryTransaction.begin(context.host().inventory(context.player()), context.host().items());
        if (give.dropIfFull()) {
            List<S> overflow = transaction.addOrOverflow(stack.get(), amount);
            transaction.commit();
            for (S extra : overflow) context.host().drop(context.player(), extra);
            return true;
        }
        if (!transaction.add(stack.get(), amount)) { transaction.rollback(); return false; }
        transaction.commit();
        return true;
    }

    private boolean take(GuiModel.Take take, GuiExpression.Scope scope) {
        Optional<S> stack = stack(take.item(), scope);
        int amount = amount(take.amount(), take.item(), scope);
        MatchRule rule = take.item().name().isEmpty() && take.item().lore().isEmpty() && take.item().customModelValue().isEmpty() ? MatchRule.MATERIAL : MatchRule.STRICT;
        InventoryTransaction<S> transaction = InventoryTransaction.begin(context.host().inventory(context.player()), context.host().items());
        if (stack.isEmpty() || amount <= 0 || !transaction.remove(stack.get(), amount, rule)) {
            transaction.rollback();
            take.denyMessage().ifPresent((GuiTemplate text) -> context.host().message(context.player(), RichText.colorize(text.render(scope))));
            return false;
        }
        transaction.commit();
        return true;
    }

    private boolean economy(GuiModel.Economy action, GuiExpression.Scope scope) {
        Economy economy = context.host().economy();
        BigDecimal amount;
        try { amount = BigDecimal.valueOf(action.amount().map((GuiExpression expression) -> expression.number(scope)).orElse(0.0)).max(BigDecimal.ZERO); }
        catch (GuiExpression.Invalid | NumberFormatException failed) { amount = BigDecimal.ZERO; }
        boolean ok;
        if (economy.unavailable().isPresent()) ok = false;
        else if (amount.signum() == 0) ok = true;
        else ok = switch (action.operation()) {
            case HAS -> economy.has(context.id(), amount);
            case WITHDRAW -> economy.has(context.id(), amount) && economy.withdraw(context.id(), amount);
            case DEPOSIT -> economy.deposit(context.id(), amount);
        };
        if (!ok) action.denyMessage().ifPresent((GuiTemplate text) -> context.host().message(context.player(), RichText.colorize(text.render(scope))));
        return ok;
    }

    static boolean test(GuiExpression expression, GuiExpression.Scope scope) {
        try { return expression.test(scope); }
        catch (GuiExpression.Invalid failed) { return false; }
    }
}
