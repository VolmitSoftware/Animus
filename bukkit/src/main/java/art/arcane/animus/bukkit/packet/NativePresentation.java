package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Packet-only presentation details: pose metadata, armor stand parts, a text display for names and hologram lines,
 * and a hidden seat for sitting players. Nothing here creates a server entity. Bound separately so a missing
 * signature only turns presentation off.
 */
final class NativePresentation {
    /** What one body shows. {@code text} renders in a text display riding the body. */
    record Look(String profileName, Optional<String> text, boolean hideProfileName, boolean aboveNativeNameplate,
                NpcPose pose, Optional<ArmorStandLook> armorStand) {
        int extraIds() { return (text.isPresent() ? 1 : 0) + (seated() ? 1 : 0); }
        boolean seated() { return pose == NpcPose.SITTING; }
    }
    static final int SEAT_FLAGS = 0x10 | 0x08;
    static final byte BILLBOARD_CENTER = 3;
    static final int LINE_WIDTH = 4096;
    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern FORMATTED = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    static Look look(UUID uuid, String typeKey, String name, NpcAppearance appearance) {
        boolean player = typeKey.equals("minecraft:player");
        boolean nativeName = player && appearance.nameplateVisible() && fitsProfile(name) && !FORMATTED.matcher(name).find();
        List<String> lines = new ArrayList<>();
        for (String line : appearance.hologram()) lines.add(legacy(line));
        if (appearance.nameplateVisible() && !nativeName) lines.add(legacy(name));
        String profile = player && fitsProfile(name) ? name : uuid.toString().replace("-", "").substring(0, 16);
        Optional<String> text = lines.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", lines));
        return new Look(profile, text, player && !nativeName, nativeName && text.isPresent(), appearance.pose(), appearance.armorStand());
    }

    static Look named(String profileName) {
        return new Look(profileName, Optional.empty(), false, false, NpcPose.STANDING, Optional.empty());
    }

    private static boolean fitsProfile(String name) {
        try { NativePacketAdapter.validateProfileName(name); return true; }
        catch (IllegalArgumentException tooLong) { return false; }
    }

    static String legacy(String text) { return CODE.matcher(text).replaceAll("§$1"); }

    static byte sharedFlags(NpcPose pose, boolean invisible) {
        int flags = switch (pose) { case SNEAKING -> 0x02; case SWIMMING -> 0x10; default -> 0; };
        return (byte) (flags | (invisible ? 0x20 : 0));
    }

    static String nativePose(NpcPose pose) {
        return switch (pose) { case SNEAKING -> "CROUCHING"; case SLEEPING -> "SLEEPING"; case SWIMMING -> "SWIMMING"; default -> "STANDING"; };
    }

    static byte armorStandFlags(ArmorStandLook look) {
        return (byte) ((look.small() ? 0x01 : 0) | (look.arms() ? 0x04 : 0) | (look.basePlate() ? 0 : 0x08));
    }

    /** Height above the passenger point at which the text's bottom line sits. */
    static double lift(double bodyHeight, double seatY, boolean aboveNativeNameplate) {
        return bodyHeight - seatY + (aboveNativeNameplate ? 0.55 : 0.3);
    }

    private final Method dataValue;
    private final Object sharedFlags, pose, standFlags, translation, billboard, text, lineWidth;
    private final Map<ArmorStandLook.Part, Object> standPoses = new EnumMap<>(ArmorStandLook.Part.class);
    private final Class<? extends Enum> poses;
    private final Constructor<?> rotations, vector, scoreboard, team;
    private final Method literal, teamVisibility, teamCollision, addMember, addTeam, removeTeam;
    private final Object never, noCollision;

    @SuppressWarnings("unchecked")
    NativePresentation(Method dataValue) throws ReflectiveOperationException {
        this.dataValue = dataValue;
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
        Class<?> stand = Class.forName("net.minecraft.world.entity.decoration.ArmorStand");
        Class<?> display = Class.forName("net.minecraft.world.entity.Display");
        Class<?> textDisplay = Class.forName("net.minecraft.world.entity.Display$TextDisplay");
        sharedFlags = accessor(entity, "DATA_SHARED_FLAGS_ID"); pose = accessor(entity, "DATA_POSE");
        standFlags = accessor(stand, "DATA_CLIENT_FLAGS");
        Map<ArmorStandLook.Part, String> names = Map.of(ArmorStandLook.Part.HEAD, "DATA_HEAD_POSE", ArmorStandLook.Part.BODY, "DATA_BODY_POSE",
                ArmorStandLook.Part.LEFT_ARM, "DATA_LEFT_ARM_POSE", ArmorStandLook.Part.RIGHT_ARM, "DATA_RIGHT_ARM_POSE",
                ArmorStandLook.Part.LEFT_LEG, "DATA_LEFT_LEG_POSE", ArmorStandLook.Part.RIGHT_LEG, "DATA_RIGHT_LEG_POSE");
        for (Map.Entry<ArmorStandLook.Part, String> part : names.entrySet()) standPoses.put(part.getKey(), accessor(stand, part.getValue()));
        translation = accessor(display, "DATA_TRANSLATION_ID"); billboard = accessor(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID");
        text = accessor(textDisplay, "DATA_TEXT_ID"); lineWidth = accessor(textDisplay, "DATA_LINE_WIDTH_ID");
        poses = (Class<? extends Enum>) Class.forName("net.minecraft.world.entity.Pose");
        for (NpcPose candidate : NpcPose.values()) Enum.valueOf((Class) poses, nativePose(candidate));
        rotations = Class.forName("net.minecraft.core.Rotations").getConstructor(float.class, float.class, float.class);
        vector = Class.forName("org.joml.Vector3f").getConstructor(float.class, float.class, float.class);
        literal = Class.forName("net.minecraft.network.chat.Component").getMethod("literal", String.class);
        Class<?> board = Class.forName("net.minecraft.world.scores.Scoreboard");
        Class<?> playerTeam = Class.forName("net.minecraft.world.scores.PlayerTeam");
        Class<? extends Enum> visibility = (Class<? extends Enum>) Class.forName("net.minecraft.world.scores.Team$Visibility");
        Class<? extends Enum> collision = (Class<? extends Enum>) Class.forName("net.minecraft.world.scores.Team$CollisionRule");
        scoreboard = board.getConstructor(); team = playerTeam.getConstructor(board, String.class);
        teamVisibility = playerTeam.getMethod("setNameTagVisibility", visibility);
        teamCollision = playerTeam.getMethod("setCollisionRule", collision);
        never = Enum.valueOf((Class) visibility, "NEVER"); noCollision = Enum.valueOf((Class) collision, "NEVER");
        addMember = board.getMethod("addPlayerToTeam", String.class, playerTeam);
        Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
        addTeam = packet.getMethod("createAddOrModifyPacket", playerTeam, boolean.class);
        removeTeam = packet.getMethod("createRemovePacket", playerTeam);
    }

    private static Object accessor(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner.getName() + "." + name);
        return field.get(null);
    }

    /** Metadata the body itself needs beyond the defaults; empty when it shows its plain standing look. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Object> bodyValues(Look look) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        byte flags = sharedFlags(look.pose(), invisible);
        if (flags != 0) values.add(dataValue.invoke(null, sharedFlags, flags));
        if (look.pose() != NpcPose.STANDING && look.pose() != NpcPose.SITTING)
            values.add(dataValue.invoke(null, pose, Enum.valueOf((Class) poses, nativePose(look.pose()))));
        if (look.armorStand().isPresent()) {
            ArmorStandLook stand = look.armorStand().get();
            values.add(dataValue.invoke(null, standFlags, armorStandFlags(stand)));
            for (Map.Entry<ArmorStandLook.Part, ArmorStandLook.Angles> angle : stand.angles().entrySet())
                values.add(dataValue.invoke(null, standPoses.get(angle.getKey()), rotations.newInstance(angle.getValue().x(), angle.getValue().y(), angle.getValue().z())));
        }
        return values;
    }

    List<Object> textValues(String content, double lift) throws ReflectiveOperationException {
        return List.of(dataValue.invoke(null, text, literal.invoke(null, content)),
                dataValue.invoke(null, billboard, BILLBOARD_CENTER),
                dataValue.invoke(null, lineWidth, LINE_WIDTH),
                dataValue.invoke(null, translation, vector.newInstance(0f, (float) lift, 0f)));
    }

    List<Object> seatValues() throws ReflectiveOperationException {
        return List.of(dataValue.invoke(null, sharedFlags, (byte) 0x20), dataValue.invoke(null, standFlags, (byte) SEAT_FLAGS));
    }

    /** A team that hides one profile name tag; its own name never collides with a real team. */
    Object[] hiddenNameTeam(int entityId, String profileName) throws ReflectiveOperationException {
        Object board = scoreboard.newInstance();
        Object hidden = team.newInstance(board, "animus-" + Integer.toHexString(entityId));
        teamVisibility.invoke(hidden, never);
        teamCollision.invoke(hidden, noCollision);
        addMember.invoke(board, profileName, hidden);
        return new Object[]{addTeam.invoke(null, hidden, true), removeTeam.invoke(null, hidden)};
    }
}
