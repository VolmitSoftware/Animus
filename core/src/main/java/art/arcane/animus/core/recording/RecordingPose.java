package art.arcane.animus.core.recording;

/** The absolute pose of a playback at its cursor: world position, yaw wrapped into -180..180, pitch and the held flags. */
public record RecordingPose(double x, double y, double z, double yaw, double pitch, int flags) {
    public boolean onGround() { return RecordingFrame.has(flags, RecordingFrame.ON_GROUND); }
    public boolean sneaking() { return RecordingFrame.has(flags, RecordingFrame.SNEAKING); }
    public boolean sprinting() { return RecordingFrame.has(flags, RecordingFrame.SPRINTING); }
    public boolean swimming() { return RecordingFrame.has(flags, RecordingFrame.SWIMMING); }
    public boolean gliding() { return RecordingFrame.has(flags, RecordingFrame.GLIDING); }
    public boolean jumping() { return RecordingFrame.has(flags, RecordingFrame.JUMPING); }
}
