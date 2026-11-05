package art.arcane.animus.core.metrics;

/**
 * What an outgoing Animus packet presents. {@link #typicalBytes()} is a fixed estimate of one packet's uncompressed
 * size; Animus never serializes a packet to measure it, so byte figures are estimates.
 */
public enum PacketKind {
    SPAWN("spawn", 96),
    DESPAWN("despawn", 16),
    MOVE("move", 32),
    METADATA("metadata", 28),
    EQUIPMENT("equipment", 48),
    PASSENGERS("passengers", 16),
    HOLOGRAM("hologram", 72),
    RIG_BONES("rig_bones", 40),
    GUI_WINDOW("gui_window", 256);

    private final String key;
    private final int typicalBytes;

    PacketKind(String key, int typicalBytes) { this.key = key; this.typicalBytes = typicalBytes; }

    public String key() { return key; }
    public int typicalBytes() { return typicalBytes; }
}
