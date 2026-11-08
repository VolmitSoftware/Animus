package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
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
    private final Object sharedFlags, pose, standFlags, translation, billboard, text, lineWidth, styleFlags, viewRange, interpolationStart,
            interpolationDuration, livingFlags;
    private final Map<BodyRules.BabyFlag, Object> babies = new EnumMap<>(BodyRules.BabyFlag.class);
    /** The team colour setter, and the enum its argument names: ChatFormatting on 26.1, TeamColor inside an Optional later. */
    private final Method setColor;
    private final Class<? extends Enum> colors;
    private final boolean optionalColor;
    private final Map<ArmorStandLook.Part, Object> standPoses = new EnumMap<>(ArmorStandLook.Part.class);
    private final Class<? extends Enum> poses;
    private final Constructor<?> rotations, vector, scoreboard, team;
    private final Method literal, empty, append, setStyle, withColor, fromRgb, withBold, withItalic, withUnderlined, withStrikethrough, withObfuscated;
    private final Object plainStyle;
    private final Method teamVisibility, teamCollision, addMember, addTeam, removeTeam;
    private final Object never, always, noCollision;

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
        styleFlags = accessor(textDisplay, "DATA_STYLE_FLAGS_ID"); viewRange = accessor(display, "DATA_VIEW_RANGE_ID");
        interpolationStart = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID");
        interpolationDuration = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID");
        livingFlags = accessor(Class.forName("net.minecraft.world.entity.LivingEntity"), "DATA_LIVING_ENTITY_FLAGS");
        // A baby flag this server keeps elsewhere only leaves those bodies grown; the rest of the presentation still works.
        Map<BodyRules.BabyFlag, String> owners = Map.of(BodyRules.BabyFlag.AGEABLE, "net.minecraft.world.entity.AgeableMob",
                BodyRules.BabyFlag.ZOMBIE, "net.minecraft.world.entity.monster.zombie.Zombie", BodyRules.BabyFlag.PIGLIN, "net.minecraft.world.entity.monster.piglin.Piglin",
                BodyRules.BabyFlag.ZOGLIN, "net.minecraft.world.entity.monster.Zoglin");
        for (Map.Entry<BodyRules.BabyFlag, String> owner : owners.entrySet()) {
            try { babies.put(owner.getKey(), accessor(Class.forName(owner.getValue()), "DATA_BABY_ID")); }
            catch (ReflectiveOperationException missing) { /* stays grown */ }
        }
        poses = (Class<? extends Enum>) Class.forName("net.minecraft.world.entity.Pose");
        for (NpcPose candidate : NpcPose.values()) Enum.valueOf((Class) poses, BodyLook.nativePose(candidate));
        Enum.valueOf((Class) poses, "FALL_FLYING");
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
        never = Enum.valueOf((Class) visibility, "NEVER"); always = Enum.valueOf((Class) visibility, "ALWAYS"); noCollision = Enum.valueOf((Class) collision, "NEVER");
        Method colorSetter = null;
        for (Method method : playerTeam.getMethods())
            if (method.getName().equals("setColor") && method.getParameterCount() == 1
                    && (method.getParameterTypes()[0].isEnum() || method.getParameterTypes()[0] == Optional.class)) colorSetter = method;
        if (colorSetter == null) throw new NoSuchMethodException("PlayerTeam.setColor");
        setColor = colorSetter;
        optionalColor = colorSetter.getParameterTypes()[0] == Optional.class;
        colors = optionalColor ? (Class<? extends Enum>) Class.forName("net.minecraft.world.scores.TeamColor") : (Class<? extends Enum>) colorSetter.getParameterTypes()[0];
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
    List<Object> bodyValues(BodyLook look, String typeKey) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        byte flags = look.sharedFlags(invisible);
        if (flags != 0) values.add(dataValue.invoke(null, sharedFlags, flags));
        if (look.usingItem()) values.add(dataValue.invoke(null, livingFlags, BodyLook.livingFlags(true)));
        Optional<BodyRules.BabyFlag> baby = look.traits().baby() ? BodyRules.babyFlag(typeKey) : Optional.empty();
        if (baby.isPresent() && babies.containsKey(baby.get())) values.add(dataValue.invoke(null, babies.get(baby.get()), Boolean.TRUE));
        if ((look.pose() != NpcPose.STANDING && look.pose() != NpcPose.SITTING) || (look.motion() & BodyLook.GLIDING) != 0)
            values.add(dataValue.invoke(null, pose, Enum.valueOf((Class) poses, look.nativePose())));
        if (look.armorStand().isPresent()) {
            ArmorStandLook stand = look.armorStand().get();
            values.add(dataValue.invoke(null, standFlags, BodyLook.armorStandFlags(stand)));
            for (Map.Entry<ArmorStandLook.Part, ArmorStandLook.Angles> angle : stand.angles().entrySet())
                values.add(dataValue.invoke(null, standPoses.get(angle.getKey()), rotations.newInstance(angle.getValue().x(), angle.getValue().y(), angle.getValue().z())));
        }
        return values;
    }

    List<Object> textValues(String content, double lift, BodyLook.TextStyle style) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>(List.of(dataValue.invoke(null, text, component(content)),
                dataValue.invoke(null, billboard, style.billboard()),
                dataValue.invoke(null, lineWidth, BodyLook.LINE_WIDTH),
                dataValue.invoke(null, translation, vector.newInstance(0f, (float) lift, 0f))));
        if (style.flags() != 0) values.add(dataValue.invoke(null, styleFlags, style.flags()));
        if (style.viewRange() != 1f) values.add(dataValue.invoke(null, viewRange, style.viewRange()));
        if (style.interpolationDelay() != 0) values.add(dataValue.invoke(null, interpolationStart, style.interpolationDelay()));
        if (style.interpolationDuration() != 0) values.add(dataValue.invoke(null, interpolationDuration, style.interpolationDuration()));
        return values;
    }

    /** Only the text of a text display row, as a placeholder refresh changes it. */
    Object textValue(String content) throws ReflectiveOperationException { return dataValue.invoke(null, text, component(content)); }

    /** The body's shared flags, invisible when a rig hides it; its pose flags are kept. */
    Object visibility(BodyLook look, boolean hidden) throws ReflectiveOperationException {
        boolean invisible = hidden || look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        return dataValue.invoke(null, sharedFlags, look.sharedFlags(invisible));
    }

    /** The shared flags, pose and item use of a body whose look changed in place; all are always sent, so standing again clears a crouch. */
    List<Object> lookValues(BodyLook look) throws ReflectiveOperationException {
        boolean invisible = look.armorStand().map(ArmorStandLook::invisible).orElse(false);
        return List.of(dataValue.invoke(null, sharedFlags, look.sharedFlags(invisible)),
                dataValue.invoke(null, pose, Enum.valueOf((Class) poses, look.pose() == NpcPose.SITTING ? "STANDING" : look.nativePose())),
                dataValue.invoke(null, livingFlags, BodyLook.livingFlags(look.usingItem())));
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

    /**
     * A team of one body that hides its profile name tag when the look asks, and colours its name and glow with the
     * team colour. Its own name never collides with a real team.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object[] bodyTeam(int entityId, String member, BodyLook look) throws ReflectiveOperationException {
        Object board = scoreboard.newInstance();
        Object own = team.newInstance(board, "animus-" + Integer.toHexString(entityId));
        teamVisibility.invoke(own, look.hideProfileName() ? never : always);
        teamCollision.invoke(own, noCollision);
        if (look.traits().teamColor().isPresent()) {
            Object color = Enum.valueOf((Class) colors, look.traits().teamColor().get().toUpperCase(Locale.ROOT));
            setColor.invoke(own, optionalColor ? Optional.of(color) : color);
        }
        addMember.invoke(board, member, own);
        return new Object[]{addTeam.invoke(null, own, true), removeTeam.invoke(null, own)};
    }
}
