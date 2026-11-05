package art.arcane.animus.core.config;

/**
 * {@code [hotload]} in {@code config.toml}: hand edits to the data folder apply live and ping operators. With
 * {@code enabled = false} only {@code config.toml} itself is still watched, so hotload can be turned back on.
 *
 * @param sound plays the note block ding before each hotload message
 * @param maximumFiles watched files at most; more are left until a restart or {@code /animus reload}
 * @param readsPerPoll changed files read in one 500 ms poll; the rest wait for the next poll
 * @param maximumFileBytes larger files are refused instead of read
 */
public record HotloadSettings(boolean enabled, boolean sound, int maximumFiles, int readsPerPoll, int maximumFileBytes) {
    public static final HotloadSettings DEFAULT = new HotloadSettings(true, true, 20_000, 256, 4 * 1024 * 1024);

    public HotloadSettings {
        if (maximumFiles < 1 || maximumFiles > 1_000_000) throw new IllegalArgumentException("maximumFiles must be within 1..1000000");
        if (readsPerPoll < 1 || readsPerPoll > 65_536) throw new IllegalArgumentException("readsPerPoll must be within 1..65536");
        if (maximumFileBytes < 1024 || maximumFileBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("maximumFileBytes must be within 1024..16777216");
    }
}
