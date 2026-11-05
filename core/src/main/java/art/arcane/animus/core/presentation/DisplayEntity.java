package art.arcane.animus.core.presentation;

import art.arcane.animus.core.gui.ScreenFrame;
import java.util.Objects;
import java.util.UUID;

/**
 * One packet-only entity of a hologram or holographic menu: a text, item or block display, a still entity, or an
 * interaction box that receives clicks. It holds no platform objects; a {@link DisplayTransport} turns it into
 * spawn, metadata and move packets for one viewer.
 *
 * @param x feet position; displays render around it, interaction boxes grow up from it
 */
public record DisplayEntity(int id, UUID uuid, Content content, double x, double y, double z, float yaw, float pitch, Style style) {
    public DisplayEntity {
        Objects.requireNonNull(uuid); Objects.requireNonNull(content); Objects.requireNonNull(style);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Display position must be finite");
    }

    public DisplayEntity at(double nextX, double nextY, double nextZ, float nextYaw, float nextPitch) {
        return new DisplayEntity(id, uuid, content, nextX, nextY, nextZ, nextYaw, nextPitch, style);
    }
    public DisplayEntity with(Content next) { return new DisplayEntity(id, uuid, next, x, y, z, yaw, pitch, style); }
    public DisplayEntity with(Style next) { return new DisplayEntity(id, uuid, content, x, y, z, yaw, pitch, next); }

    public enum Billboard {
        FIXED(0), VERTICAL(1), HORIZONTAL(2), CENTER(3);
        private final byte wire;
        Billboard(int wire) { this.wire = (byte) wire; }
        public byte wire() { return wire; }
    }
    public enum Alignment { CENTER, LEFT, RIGHT }

    public sealed interface Content permits Text, Item, Block, Mob, Hitbox {}
    /**
     * Section-coded text, lines split by {@code \n}. {@code background} and {@code glow} colours are ARGB.
     */
    public record Text(String text, int background, int lineWidth, int opacity, boolean shadow, boolean seeThrough, Alignment alignment) implements Content {
        public Text { Objects.requireNonNull(text); Objects.requireNonNull(alignment); }
        public static Text of(String text) { return new Text(text, 0, 16384, 255, false, false, Alignment.CENTER); }
        /** The text display style flags byte. */
        public byte flags() {
            int flags = (shadow ? 0x01 : 0) | (seeThrough ? 0x02 : 0);
            flags |= switch (alignment) { case CENTER -> 0; case LEFT -> 0x08; case RIGHT -> 0x10; };
            return (byte) flags;
        }
    }
    /** {@code context}: 0 none, 6 GUI, 8 head, as the item display transform byte. */
    public record Item(ScreenFrame.Item item, int context) implements Content {
        public Item { Objects.requireNonNull(item); }
    }
    /** A block id such as {@code minecraft:stone}, drawn in its default state. */
    public record Block(String block) implements Content {
        public Block { Objects.requireNonNull(block); }
    }
    /** A still entity of a type such as {@code minecraft:fox}. It has no AI and never exists on the server. */
    public record Mob(String type) implements Content {
        public Mob { Objects.requireNonNull(type); }
    }
    /** An invisible interaction box that reports left and right clicks. */
    public record Hitbox(float width, float height) implements Content {
        public Hitbox {
            if (!(width > 0 && width <= 64 && height > 0 && height <= 64)) throw new IllegalArgumentException("Hitbox size must be in (0, 64]");
        }
    }

    /**
     * Display metadata. {@code brightness} is -1 for none, otherwise {@code block << 4 | sky << 20}. {@code glow} is -1
     * for none, otherwise an RGB colour that also turns the glowing flag on. {@code moveTicks} is the client
     * interpolation for moves and transformation changes.
     */
    public record Style(Billboard billboard, float scaleX, float scaleY, float scaleZ, float translateX, float translateY, float translateZ,
                        int brightness, float viewRange, float shadowRadius, float shadowStrength, int glow, int moveTicks) {
        public static final Style DEFAULT = new Style(Billboard.FIXED, 1, 1, 1, 0, 0, 0, -1, 1, 0, 0, -1, 0);
        public Style { Objects.requireNonNull(billboard); }
        public Style scaled(float x, float y, float z) {
            return new Style(billboard, x, y, z, translateX, translateY, translateZ, brightness, viewRange, shadowRadius, shadowStrength, glow, moveTicks);
        }
        public Style translated(float x, float y, float z) {
            return new Style(billboard, scaleX, scaleY, scaleZ, x, y, z, brightness, viewRange, shadowRadius, shadowStrength, glow, moveTicks);
        }
        public Style billboard(Billboard next) {
            return new Style(next, scaleX, scaleY, scaleZ, translateX, translateY, translateZ, brightness, viewRange, shadowRadius, shadowStrength, glow, moveTicks);
        }
    }
}
