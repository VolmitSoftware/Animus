package art.arcane.animus.core.presentation;

import art.arcane.animus.core.rig.RigDocument;
import java.util.Objects;

/**
 * What one rig bone's packet-only display entity shows: an item display, block display or text display. Built on the
 * NPC's owner from a rig visual; {@code item} is the platform's stack and only set for item bones.
 *
 * @param <I> the platform's item stack
 */
public record BoneDisplay<I>(Kind kind, I item, byte itemContext, String block, String text, byte billboard, int background, boolean shadow, int lineWidth) {
    public enum Kind { ITEM, BLOCK, TEXT }

    public BoneDisplay {
        Objects.requireNonNull(kind);
        if (kind == Kind.ITEM) Objects.requireNonNull(item);
        if (kind == Kind.BLOCK) Objects.requireNonNull(block);
        if (kind == Kind.TEXT) Objects.requireNonNull(text);
    }

    /** The vanilla {@code ItemDisplayContext} id of a rig's {@code itemDisplay} word. */
    public static byte itemContext(String name) {
        return switch (name) {
            case "thirdperson_lefthand" -> 1;
            case "thirdperson_righthand" -> 2;
            case "firstperson_lefthand" -> 3;
            case "firstperson_righthand" -> 4;
            case "head" -> 5;
            case "gui" -> 6;
            case "ground" -> 7;
            case "fixed" -> 8;
            default -> 0;
        };
    }
    /** The vanilla {@code Display.BillboardConstraints} id. */
    public static byte billboard(String name) {
        return switch (name) {
            case "vertical" -> 1;
            case "horizontal" -> 2;
            case "center" -> 3;
            default -> 0;
        };
    }

    /** A text or block bone; item bones go through {@link #item}. */
    public static <I> BoneDisplay<I> of(RigDocument.Visual visual) {
        return switch (visual.type()) {
            case BLOCK -> new BoneDisplay<>(Kind.BLOCK, null, (byte) 0, visual.block().orElseThrow(), null, (byte) 0, 0, false, 0);
            case TEXT -> new BoneDisplay<>(Kind.TEXT, null, (byte) 0, null, art.arcane.animus.core.text.RichText.colorize(visual.text().orElseThrow()), billboard(visual.billboard()),
                    visual.background(), visual.shadow(), visual.lineWidth());
            default -> throw new IllegalArgumentException("Item and empty bones are not built here");
        };
    }
    public static <I> BoneDisplay<I> item(RigDocument.Visual visual, I stack) {
        return new BoneDisplay<>(Kind.ITEM, stack, itemContext(visual.itemDisplay()), null, null, (byte) 0, 0, false, 0);
    }
}
