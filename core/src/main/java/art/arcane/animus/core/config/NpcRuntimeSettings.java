package art.arcane.animus.core.config;

/** Plugin-wide runtime budgets; definitions and state remain separate JSON documents. */
public record NpcRuntimeSettings(boolean enabled, int viewDistance, int maximumVisible,
                                 int maximumViewers, int updatesPerTick) {
    public static final NpcRuntimeSettings DEFAULT = new NpcRuntimeSettings(true, 48, 64, 256, 16);
    public NpcRuntimeSettings {
        if (viewDistance < 8 || viewDistance > 256 || maximumVisible < 1 || maximumVisible > 128
                || maximumViewers < 1 || maximumViewers > 1024 || updatesPerTick < 1 || updatesPerTick > 64)
            throw new IllegalArgumentException("NPC budgets require viewDistance=8..256, maximumVisible=1..128, maximumViewers=1..1024, updatesPerTick=1..64");
    }
}
