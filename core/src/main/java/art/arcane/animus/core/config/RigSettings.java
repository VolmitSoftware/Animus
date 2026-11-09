package art.arcane.animus.core.config;

/**
 * The {@code [rigs]} table of {@code config.toml}. {@code maximumBones} and {@code updatesPerTick} bound each viewer:
 * display bones shown at once and bone transform packets per tick. {@code sampleTicks} is the shortest animation
 * segment; {@code plansPerTick} bounds how many NPCs plan a new segment in one tick on each owner. A hotload applies
 * from the owner's next tick; switching {@code enabled} binds or drops each rig and re-presents its body, and bones
 * already shown stay when {@code maximumBones} is lowered.
 */
public record RigSettings(boolean enabled, int maximumBones, int updatesPerTick, int sampleTicks, int plansPerTick) {
    public static final RigSettings DEFAULT = new RigSettings(true, 512, 64, 2, 64);

    public RigSettings {
        require("maximumBones", maximumBones, 1, 4096);
        require("updatesPerTick", updatesPerTick, 1, 512);
        require("sampleTicks", sampleTicks, 1, 20);
        require("plansPerTick", plansPerTick, 1, 1024);
    }

    private static void require(String field, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum + ", not " + value);
    }
}
