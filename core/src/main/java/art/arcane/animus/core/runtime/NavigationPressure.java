package art.arcane.animus.core.runtime;

import java.util.Optional;

/**
 * Whether routes are waiting for the navigation budget. Pressure starts after routes were left waiting for
 * {@link #START_TICKS} ticks in a row and ends after {@link #END_TICKS} ticks without any, so a short burst logs nothing
 * and a lasting shortage logs once when it starts and once when it ends. Single thread.
 */
public final class NavigationPressure {
    public static final int START_TICKS = 20, END_TICKS = 100;
    private int busy, clear, lastDeferred, peak;
    private boolean pressured;
    private long since;

    /** One tick's count of ready routes left for a later tick. Returns the line to log when pressure starts or ends. */
    public Optional<String> observe(long tick, int deferred, String budgets) {
        lastDeferred = Math.max(0, deferred);
        if (lastDeferred > 0) { busy++; clear = 0; } else { clear++; busy = 0; }
        if (!pressured && busy >= START_TICKS) {
            pressured = true; since = tick; peak = lastDeferred;
            return Optional.of("Navigation budget is short: " + lastDeferred + " routes are waiting for a turn each tick"
                    + (budgets.isEmpty() ? "" : " (" + budgets + ")") + "; patrols near players move slower until it clears");
        }
        if (pressured) peak = Math.max(peak, lastDeferred);
        if (pressured && clear >= END_TICKS) {
            pressured = false;
            return Optional.of("Navigation budget pressure ended after " + Math.max(0, tick - since - END_TICKS + 1) + " ticks; at most " + peak + " routes waited");
        }
        return Optional.empty();
    }
    public boolean pressured() { return pressured; }
    public int lastDeferred() { return lastDeferred; }
    /** For status: "none", or the waiting routes and how long it has lasted. */
    public String describe() {
        if (!pressured && lastDeferred == 0) return "none";
        return lastDeferred + " routes waiting" + (pressured ? " (budget short)" : "");
    }
}
