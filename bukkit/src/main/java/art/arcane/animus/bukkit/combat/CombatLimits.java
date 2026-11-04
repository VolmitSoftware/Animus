package art.arcane.animus.bukkit.combat;

public record CombatLimits(int maximumCombatants, int maximumControlledMobs, int mobUpdatesPerTick,
                           int pathPlansPerTick, int visitedNodesPerPath, long workNanosPerTick) {
    public static final CombatLimits DEFAULT = new CombatLimits(64, 32, 4, 1, 256, 2_000_000);
    public CombatLimits {
        if (maximumCombatants < 1 || maximumCombatants > 128 || maximumControlledMobs < 1 || maximumControlledMobs > 32
                || mobUpdatesPerTick < 1 || mobUpdatesPerTick > 4 || pathPlansPerTick < 1 || pathPlansPerTick > 1
                || visitedNodesPerPath < 16 || visitedNodesPerPath > 512 || workNanosPerTick < 100_000 || workNanosPerTick > 5_000_000)
            throw new IllegalArgumentException("Combat limits exceed supported bounded work");
    }
}
