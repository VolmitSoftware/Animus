package art.arcane.animus.core.skin;

import java.util.Optional;

/** Checks a skin PNG before it is kept or uploaded: a PNG of 64x64 or the old 64x32, at most {@link SkinFile#MAXIMUM_IMAGE} bytes. */
public final class SkinPng {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private SkinPng() {}

    /** Why the bytes are not a skin PNG, or empty when they are. */
    public static Optional<String> problem(byte[] png) {
        if (png.length > SkinFile.MAXIMUM_IMAGE) return Optional.of("The PNG is " + png.length + " bytes; a skin is at most " + SkinFile.MAXIMUM_IMAGE + " bytes");
        if (png.length < 24) return Optional.of("Not a PNG file");
        for (int i = 0; i < SIGNATURE.length; i++) if (png[i] != SIGNATURE[i]) return Optional.of("Not a PNG file");
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return Optional.of("Not a PNG file");
        int width = integer(png, 16), height = integer(png, 20);
        if (width != 64 || height != 64 && height != 32) return Optional.of("The PNG is " + width + "x" + height + "; a skin is 64x64 (or the old 64x32)");
        return Optional.empty();
    }

    private static int integer(byte[] bytes, int at) {
        return (bytes[at] & 0xff) << 24 | (bytes[at + 1] & 0xff) << 16 | (bytes[at + 2] & 0xff) << 8 | bytes[at + 3] & 0xff;
    }
}
