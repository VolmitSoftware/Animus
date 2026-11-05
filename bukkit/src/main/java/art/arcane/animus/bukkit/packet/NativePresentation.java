package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Packet-only presentation details: pose metadata, armor stand parts, a text display for names and hologram lines,
 * and a hidden seat for sitting players. Nothing here creates a server entity. Bound separately so a missing
 * signature only turns presentation off.
 */
final class NativePresentation {
    private final Method dataValue;
    private final Object sharedFlags, pose, standFlags, translation, billboard, text, lineWidth;
    private final Map<ArmorStandLook.Part, Object> standPoses = new EnumMap<>(ArmorStandLook.Part.class);
    private final Class<? extends Enum> poses;
    private final Constructor<?> rotations, vector, scoreboard, team;
    private final Method literal, empty, append, setStyle, withColor, fromRgb, withBold, withItalic, withUnderlined, withStrikethrough, withObfuscated;
    private final Object plainStyle;
    private final Method teamVisibility, teamCollision, addMember, addTeam, removeTeam;
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
        for (NpcPose candidate : NpcPose.values()) Enum.valueOf((Class) poses, BodyLook.nativePose(candidate));
        rotations = Class.forName("net.minecraft.core.Rotations").getConstructor(float.class, float.class, float.class);
        vector = Class.forName("org.joml.Vector3f").getConstructor(float.class, float.class, float.class);
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        Class<?> mutable = Class.forName("net.minecraft.network.chat.MutableComponent");
        Class<?> style = Class.forName("net.minecraft.network.chat.Style");
        Class<?> colour = Class.forName("net.minecraft.network.chat.TextColor");
        literal = component.getMethod("literal", String.class); empty = component.getMethod("empty");
        append = mutable.getMethod("append", component); setStyle = mutable.getMethod("setStyle", style);
        plainStyle = style.getField("EMPTY").get(null);
        withColor = style.getMethod("withColor", colour); fromRgb = colour.getMethod("fromRgb", int.class);
        withBold = style.getMethod("withBold", Boolean.class); withItalic = style.getMethod("withItalic", Boolean.class);
        withUnderlined = style.getMethod("withUnderlined", Boolean.class); withStrikethrough = style.getMethod("withStrikethrough", Boolean.class);
        withObfuscated = style.getMethod("withObfuscated", Boolean.class);
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
    List<Object> bodyValues(BodyLook look) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        byte flags = BodyLook.sharedFlags(look.pose(), invisible);
        if (flags != 0) values.add(dataValue.invoke(null, sharedFlags, flags));
        if (look.pose() != NpcPose.STANDING && look.pose() != NpcPose.SITTING)
            values.add(dataValue.invoke(null, pose, Enum.valueOf((Class) poses, BodyLook.nativePose(look.pose()))));
        if (look.armorStand().isPresent()) {
            ArmorStandLook stand = look.armorStand().get();
            values.add(dataValue.invoke(null, standFlags, BodyLook.armorStandFlags(stand)));
            for (Map.Entry<ArmorStandLook.Part, ArmorStandLook.Angles> angle : stand.angles().entrySet())
                values.add(dataValue.invoke(null, standPoses.get(angle.getKey()), rotations.newInstance(angle.getValue().x(), angle.getValue().y(), angle.getValue().z())));
        }
        return values;
    }

    List<Object> textValues(String content, double lift) throws ReflectiveOperationException {
        return List.of(dataValue.invoke(null, text, component(content)),
                dataValue.invoke(null, billboard, BodyLook.BILLBOARD_CENTER),
                dataValue.invoke(null, lineWidth, BodyLook.LINE_WIDTH),
                dataValue.invoke(null, translation, vector.newInstance(0f, (float) lift, 0f)));
    }

    /** The body's shared flags, invisible when a rig hides it; its pose flags are kept. */
    Object visibility(BodyLook look, boolean hidden) throws ReflectiveOperationException {
        boolean invisible = hidden || look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        return dataValue.invoke(null, sharedFlags, BodyLook.sharedFlags(look.pose(), invisible));
    }

    /** Formatted text as a styled vanilla component, so hex colours render; a plain literal would show them as codes. */
    Object component(String content) throws ReflectiveOperationException {
        Object root = empty.invoke(null);
        for (RichText.Run run : RichText.runs(content)) {
            Object style = plainStyle;
            if (run.rgb().isPresent()) style = withColor.invoke(style, fromRgb.invoke(null, run.rgb().getAsInt()));
            if (run.bold()) style = withBold.invoke(style, Boolean.TRUE);
            if (run.italic()) style = withItalic.invoke(style, Boolean.TRUE);
            if (run.underlined()) style = withUnderlined.invoke(style, Boolean.TRUE);
            if (run.strikethrough()) style = withStrikethrough.invoke(style, Boolean.TRUE);
            if (run.obfuscated()) style = withObfuscated.invoke(style, Boolean.TRUE);
            append.invoke(root, setStyle.invoke(literal.invoke(null, run.text()), style));
        }
        return root;
    }

    List<Object> seatValues() throws ReflectiveOperationException {
        return List.of(dataValue.invoke(null, sharedFlags, (byte) 0x20), dataValue.invoke(null, standFlags, (byte) BodyLook.SEAT_FLAGS));
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
