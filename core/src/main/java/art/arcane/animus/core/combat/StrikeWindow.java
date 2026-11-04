package art.arcane.animus.core.combat;

/** Per-combatant tick budget. A reservation also blocks reentrant event/protection callbacks. */
public final class StrikeWindow {
    private long nextTick = Long.MIN_VALUE;
    private long latestTick = Long.MIN_VALUE;
    private boolean busy, closed, exhausted;

    public boolean reserve(long tick, int cooldownTicks) {
        if (tick < 0 || cooldownTicks < 1 || cooldownTicks > 1200) throw new IllegalArgumentException("Invalid combat clock/cooldown");
        if (tick < latestTick) throw new IllegalArgumentException("Combat clock moved backwards");
        latestTick = tick;
        if (closed || exhausted || busy || tick < nextTick) return false;
        busy = true;
        exhausted = tick > Long.MAX_VALUE - cooldownTicks;
        nextTick = tick > Long.MAX_VALUE - cooldownTicks ? Long.MAX_VALUE : tick + cooldownTicks;
        return true;
    }
    public void release() { busy = false; }
    public void close() { closed = true; }
    public boolean closed() { return closed; }
}
