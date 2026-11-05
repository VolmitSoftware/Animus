package art.arcane.animus.core.inventory;

/**
 * Platform item stack operations. Implementations never mutate a stack they are given; {@link #withCount} returns
 * a new stack. A null stack is an empty slot.
 *
 * @param <S> the platform's item stack
 */
public interface ItemOps<S> {
    boolean empty(S stack);
    int count(S stack);
    S withCount(S stack, int count);
    int maxStackSize(S stack);
    /** Whether a held stack counts as the wanted stack under the rule. Counts are ignored. */
    boolean matches(S held, S wanted, MatchRule rule);
    /** A short description such as {@code 4x Bread}, for messages and logs. */
    String describe(S stack, int count);
}
