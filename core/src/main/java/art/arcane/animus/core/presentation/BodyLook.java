package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What one body shows. {@code text} renders in a text display riding the body; a sitting body rides a hidden seat.
 * Every platform presents the same look from the same definition.
 */
public record BodyLook(String profileName, Optional<String> text, boolean hideProfileName, boolean aboveNativeNameplate,
                       NpcPose pose, Optional<ArmorStandLook> armorStand) {
    public static final int SEAT_FLAGS = 0x10 | 0x08;
    public static final byte BILLBOARD_CENTER = 3;
    public static final int LINE_WIDTH = 4096;
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern FORMATTED = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    public int extraIds() { return (text.isPresent() ? 1 : 0) + (seated() ? 1 : 0); }
    public boolean seated() { return pose == NpcPose.SITTING; }

    public static BodyLook of(UUID uuid, String typeKey, String name, NpcAppearance appearance) {
        boolean player = typeKey.equals(BodyRules.PLAYER);
        boolean nativeName = player && appearance.nameplateVisible() && fitsProfile(name) && !FORMATTED.matcher(name).find();
        List<String> lines = new ArrayList<>();
        for (String line : appearance.hologram()) lines.add(legacy(line));
        if (appearance.nameplateVisible() && !nativeName) lines.add(legacy(name));
        String profile = player && fitsProfile(name) ? name : uuid.toString().replace("-", "").substring(0, 16);
        Optional<String> text = lines.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", lines));
        return new BodyLook(profile, text, player && !nativeName, nativeName && text.isPresent(), appearance.pose(), appearance.armorStand());
    }

    /** A plain body: the profile name is its nameplate and nothing else is presented. */
    public static BodyLook named(String profileName) {
        return new BodyLook(profileName, Optional.empty(), false, false, NpcPose.STANDING, Optional.empty());
    }

    private static boolean fitsProfile(String name) {
        try { BodyRules.validateProfileName(name); return true; }
        catch (IllegalArgumentException tooLong) { return false; }
    }

    public static String legacy(String text) { return CODE.matcher(text).replaceAll("§$1"); }

    public static byte sharedFlags(NpcPose pose, boolean invisible) {
        int flags = switch (pose) { case SNEAKING -> 0x02; case SWIMMING -> 0x10; default -> 0; };
        return (byte) (flags | (invisible ? 0x20 : 0));
    }

    /** The vanilla {@code Pose} constant name for this pose. */
    public static String nativePose(NpcPose pose) {
        return switch (pose) { case SNEAKING -> "CROUCHING"; case SLEEPING -> "SLEEPING"; case SWIMMING -> "SWIMMING"; default -> "STANDING"; };
    }

    public static byte armorStandFlags(ArmorStandLook look) {
        return (byte) ((look.small() ? 0x01 : 0) | (look.arms() ? 0x04 : 0) | (look.basePlate() ? 0 : 0x08));
    }

    /** Height above the passenger point at which the text's bottom line sits. */
    public static double lift(double bodyHeight, double seatY, boolean aboveNativeNameplate) {
        return bodyHeight - seatY + (aboveNativeNameplate ? 0.55 : 0.3);
    }
}
