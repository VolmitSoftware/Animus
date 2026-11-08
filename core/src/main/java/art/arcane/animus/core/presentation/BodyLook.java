package art.arcane.animus.core.presentation;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.HologramStyle;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What one body shows. Its text renders in text displays riding the body: one display for every line, or one per
 * row when the hologram sets a line height or margins. A sitting body rides a hidden seat. Every platform presents
 * the same look from the same definition.
 */
public record BodyLook(String profileName, List<TextRow> rows, TextStyle style, boolean hideProfileName, boolean aboveNativeNameplate,
                       NpcPose pose, Optional<ArmorStandLook> armorStand, BodyTraits traits, int motion) {
    public static final int SEAT_FLAGS = 0x10 | 0x08;
    /**
     * Shared-flag bits of {@link #motion}: a sneaking body of any type, which dims its name; a sprinting body; and one
     * gliding on elytra, which also shows the fall-flying pose.
     */
    public static final int SNEAKING = 0x02, SPRINTING = 0x08, GLIDING = 0x80;
    /** The shared-flag bit of a burning body, which every client draws in flames. */
    public static final int ON_FIRE = 0x01;
    /** A {@link #motion} bit outside the shared flags: the body holds its main-hand item in use, as a drawn bow. */
    public static final int USING_ITEM = 0x100;
    public static final byte BILLBOARD_CENTER = 3;
    public static final int LINE_WIDTH = 4096;
    /** Vanilla's text display view range is a multiple of this many blocks. */
    public static final float VIEW_RANGE_BLOCKS = 64f;

    /** One text display: its text and its height above the lowest row, in blocks. */
    public record TextRow(String text, double offset) {
        public TextRow { Objects.requireNonNull(text); }
    }

    /** The display settings every row shares: billboard id, style flags (shadow 0x01, see-through 0x02), view range and interpolation ticks. */
    public record TextStyle(byte billboard, byte flags, float viewRange, int interpolationDelay, int interpolationDuration) {
        public static final TextStyle DEFAULT = new TextStyle(BILLBOARD_CENTER, (byte) 0, 1f, 0, 0);

        static TextStyle of(HologramStyle style) {
            byte billboard = switch (style.billboard()) { case "fixed" -> 0; case "vertical" -> 1; case "horizontal" -> 2; default -> BILLBOARD_CENTER; };
            byte flags = (byte) ((style.shadowed() ? 0x01 : 0) | (style.seeThrough() ? 0x02 : 0));
            float range = style.viewRange().isPresent() ? (float) (style.viewRange().getAsDouble() / VIEW_RANGE_BLOCKS) : 1f;
            return new TextStyle(billboard, flags, range, style.interpolationDelay(), style.interpolationDuration());
        }
    }

    public BodyLook {
        rows = List.copyOf(rows);
        Objects.requireNonNull(style); Objects.requireNonNull(pose); Objects.requireNonNull(armorStand); Objects.requireNonNull(traits);
        if ((motion & ~(ON_FIRE | SNEAKING | SPRINTING | GLIDING | USING_ITEM)) != 0) throw new IllegalArgumentException("motion holds only the burning, sneaking, sprinting, gliding and using-item bits");
    }

    /** This look in another pose and motion, as a recording playback shows it. */
    public BodyLook moving(NpcPose nextPose, int nextMotion) {
        return new BodyLook(profileName, rows, style, hideProfileName, aboveNativeNameplate, nextPose, armorStand, traits, nextMotion);
    }

    /** This look glowing, as a selected NPC shows to the player who selected it. */
    public BodyLook glowing() {
        BodyTraits glow = new BodyTraits(true, traits.teamColor(), traits.baby(), traits.usingItem(), traits.listed(), traits.gravity(), traits.attributes(), traits.variant());
        return new BodyLook(profileName, rows, style, hideProfileName, aboveNativeNameplate, pose, armorStand, glow, motion);
    }

    /** The shared flags this look sends: its pose, invisibility, glow, motion and burning. */
    public byte sharedFlags(boolean invisible) { return (byte) (sharedFlags(pose, invisible, traits.glowing()) | (motion & (ON_FIRE | SNEAKING | SPRINTING | GLIDING))); }

    /** Whether the body holds its main-hand item in use: the definition's {@code usingItem}, or a bow drawn in a fight. */
    public boolean usingItem() { return traits.usingItem() || (motion & USING_ITEM) != 0; }

    /** The vanilla {@code Pose} constant this look shows; gliding shows {@code FALL_FLYING}. */
    public String nativePose() { return (motion & GLIDING) != 0 ? "FALL_FLYING" : nativePose(pose); }

    /** Every row's text, top row first, one row per line. */
    public Optional<String> text() {
        if (rows.isEmpty()) return Optional.empty();
        List<String> lines = new ArrayList<>();
        for (TextRow row : rows) lines.add(row.text());
        return Optional.of(String.join("\n", lines));
    }
    public int extraIds() { return rows.size() + (seated() ? 1 : 0); }
    public boolean seated() { return pose == NpcPose.SITTING; }
    /** Whether the body needs its own scoreboard team: to hide its profile name, or to colour its name and glow. */
    public boolean team() { return hideProfileName || traits.glowing() || traits.teamColor().isPresent(); }

    public static BodyLook of(UUID uuid, String typeKey, String name, NpcAppearance appearance) {
        boolean player = typeKey.equals(BodyRules.PLAYER);
        boolean nativeName = player && appearance.nameplateVisible() && fitsProfile(name) && !RichText.formatted(name);
        HologramStyle hologram = appearance.hologramStyle();
        List<String> lines = new ArrayList<>();
        for (String line : appearance.hologram()) lines.add(line(lines, line));
        boolean nameRow = appearance.nameplateVisible() && !nativeName;
        if (nameRow) {
            String coloured = appearance.traits().colorCode().map((Character code) -> "&" + code + name).orElse(name);
            lines.add(line(lines, coloured));
        }
        List<TextRow> rows = new ArrayList<>();
        if (lines.isEmpty()) { /* nothing to show */ }
        else if (!hologram.separateRows()) rows.add(new TextRow(String.join("\n", lines), 0));
        else rows.addAll(stacked(lines, hologram, nameRow));
        String profile = player && fitsProfile(name) ? name : uuid.toString().replace("-", "").substring(0, 16);
        return new BodyLook(profile, rows, TextStyle.of(hologram), player && !nativeName, nativeName && !rows.isEmpty(), appearance.pose(),
                appearance.armorStand(), appearance.traits(), 0);
    }

    /**
     * Rows stacked from the bottom as Citizens stacks hologram lines: each line sits its bottom margin plus the line height
     * above the one below and adds its top margin; without a name row the lowest line sits where the name would.
     */
    private static List<TextRow> stacked(List<String> lines, HologramStyle hologram, boolean nameRow) {
        double height = hologram.lineHeight().orElse(HologramStyle.DEFAULT_LINE_HEIGHT);
        int hologramLines = hologram.margins().size();
        TextRow[] rows = new TextRow[lines.size()];
        double base = nameRow ? 0 : -height;
        if (nameRow) rows[lines.size() - 1] = new TextRow(lines.get(lines.size() - 1), 0);
        for (int index = hologramLines - 1; index >= 0; index--) {
            HologramStyle.Margin margin = hologram.margins().get(index);
            base += margin.bottom() + height;
            rows[index] = new TextRow(lines.get(index), base);
            base += margin.top();
        }
        return List.of(rows);
    }

    /** A plain body: the profile name is its nameplate and nothing else is presented. */
    public static BodyLook named(String profileName) {
        return new BodyLook(profileName, List.of(), TextStyle.DEFAULT, false, false, NpcPose.STANDING, Optional.empty(), BodyTraits.DEFAULT, 0);
    }

    private static boolean fitsProfile(String name) {
        try { BodyRules.validateProfileName(name); return true; }
        catch (IllegalArgumentException tooLong) { return false; }
    }

    /** A colorized line that starts unstyled when an earlier line set a style, so one line never tints the next. */
    private static String line(List<String> earlier, String text) {
        String coloured = RichText.colorize(text);
        for (String before : earlier) if (before.indexOf(RichText.SECTION) >= 0) return "§r" + coloured;
        return coloured;
    }

    public static byte sharedFlags(NpcPose pose, boolean invisible) { return sharedFlags(pose, invisible, false); }

    public static byte sharedFlags(NpcPose pose, boolean invisible, boolean glowing) {
        int flags = switch (pose) { case SNEAKING -> 0x02; case SWIMMING -> 0x10; default -> 0; };
        return (byte) (flags | (invisible ? 0x20 : 0) | (glowing ? 0x40 : 0));
    }

    /** The living-entity flags of a body that raises its main-hand item, as a player drawing a bow or blocking does. */
    public static byte livingFlags(boolean usingItem) { return (byte) (usingItem ? 0x01 : 0); }

    /** The vanilla {@code Pose} constant name for this pose. */
    public static String nativePose(NpcPose pose) {
        return switch (pose) { case SNEAKING -> "CROUCHING"; case SLEEPING -> "SLEEPING"; case SWIMMING -> "SWIMMING"; default -> "STANDING"; };
    }

    public static byte armorStandFlags(ArmorStandLook look) {
        return (byte) ((look.small() ? 0x01 : 0) | (look.arms() ? 0x04 : 0) | (look.basePlate() ? 0 : 0x08));
    }

    /** Height above the passenger point at which the lowest text row's bottom sits; the client scales the body and its passenger point by {@code scale}. */
    public static double lift(double bodyHeight, double seatY, boolean aboveNativeNameplate, double scale) {
        return scale * (bodyHeight - seatY) + (aboveNativeNameplate ? 0.55 : 0.3);
    }
}
