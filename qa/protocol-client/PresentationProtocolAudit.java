package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcPose;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Rotations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.scores.Team;
import org.joml.Vector3f;

/**
 * Local audit: the plugin's own presentation code builds entity data and team packets, and the exact server jar's
 * codecs encode and decode them. Runs from the plugin's package so it uses the shipped code, not a copy. No server.
 */
public final class PresentationProtocolAudit {
    private static final MessageDigest DIGEST;
    static { try { DIGEST = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static int packets;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        if (args.length != 1 || !SharedConstants.getCurrentVersion().name().equals(args[0])) throw new IllegalArgumentException("Exact jar version required");
        Bootstrap.bootStrap();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Method create = SynchedEntityData.DataValue.class.getMethod("create", EntityDataAccessor.class, Object.class);
        NativePresentation presentation = new NativePresentation(create);

        for (NpcPose pose : List.of(NpcPose.SNEAKING, NpcPose.SLEEPING, NpcPose.SWIMMING)) {
            List<SynchedEntityData.DataValue<?>> values = roundTrip(registries, presentation.bodyValues(look(pose, Optional.empty())));
            Pose expected = switch (pose) { case SNEAKING -> Pose.CROUCHING; case SLEEPING -> Pose.SLEEPING; default -> Pose.SWIMMING; };
            check(values.stream().anyMatch(value -> value.value() == expected), "pose " + pose);
            if (pose != NpcPose.SLEEPING) check(values.stream().anyMatch(value -> value.value() instanceof Byte flags && flags == NativePresentation.sharedFlags(pose, false)), "flags " + pose);
        }
        check(presentation.bodyValues(look(NpcPose.STANDING, Optional.empty())).isEmpty(), "standing body sends no extra data");
        check(presentation.bodyValues(look(NpcPose.SITTING, Optional.empty())).isEmpty(), "sitting body rides its seat");
        ArmorStandLook stand = new ArmorStandLook(true, true, false, true, Map.of(ArmorStandLook.Part.HEAD, new ArmorStandLook.Angles(10, -20, 0),
                ArmorStandLook.Part.RIGHT_ARM, new ArmorStandLook.Angles(-90, 0, 15.5f)));
        List<SynchedEntityData.DataValue<?>> standValues = roundTrip(registries, presentation.bodyValues(look(NpcPose.STANDING, Optional.of(stand))));
        check(standValues.stream().anyMatch(value -> value.value() instanceof Byte flags && flags == 0x0d), "armor stand flags");
        check(standValues.stream().anyMatch(value -> value.value() instanceof Byte flags && flags == 0x20), "armor stand invisible");
        check(standValues.stream().anyMatch(value -> new Rotations(10, -20, 0).equals(value.value())), "head angles");
        check(standValues.stream().anyMatch(value -> new Rotations(-90, 0, 15.5f).equals(value.value())), "right arm angles");

        List<SynchedEntityData.DataValue<?>> text = roundTrip(registries, presentation.textValues("§6Quest\nCaptain of the Northern Watch", 0.3));
        check(text.stream().anyMatch(value -> Component.literal("§6Quest\nCaptain of the Northern Watch").equals(value.value())), "text");
        check(Display.BillboardConstraints.BY_ID.apply(NativePresentation.BILLBOARD_CENTER) == Display.BillboardConstraints.CENTER, "billboard id");
        check(text.stream().anyMatch(value -> Byte.valueOf(NativePresentation.BILLBOARD_CENTER).equals(value.value())), "billboard");
        check(text.stream().anyMatch(value -> Integer.valueOf(NativePresentation.LINE_WIDTH).equals(value.value())), "line width");
        check(text.stream().anyMatch(value -> new Vector3f(0, 0.3f, 0).equals(value.value())), "lift");
        List<SynchedEntityData.DataValue<?>> seat = roundTrip(registries, presentation.seatValues());
        check(seat.stream().anyMatch(value -> Byte.valueOf((byte) NativePresentation.SEAT_FLAGS).equals(value.value())), "seat marker");

        Object[] team = presentation.hiddenNameTeam(42, "0a1b2c3d00004000");
        ClientboundSetPlayerTeamPacket added = teamRoundTrip(registries, (ClientboundSetPlayerTeamPacket) team[0]);
        check(added.getName().equals("animus-2a") && added.getPlayers().contains("0a1b2c3d00004000"), "team members");
        Object parameters = added.getParameters().orElseThrow();
        check(accessor(parameters, "nameTagVisibility", "getNametagVisibility") == Team.Visibility.NEVER, "hidden name tag");
        check(accessor(parameters, "collisionRule", "getCollisionRule") == Team.CollisionRule.NEVER, "no pushing");
        ClientboundSetPlayerTeamPacket removed = teamRoundTrip(registries, (ClientboundSetPlayerTeamPacket) team[1]);
        check(removed.getName().equals("animus-2a") && removed.getParameters().isEmpty(), "team removal");

        Bootstrap.realStdoutPrintln("{\"version\":\"" + args[0] + "\",\"protocol\":" + SharedConstants.getProtocolVersion() + ",\"packets\":" + packets
                + ",\"payloadSequenceSha256\":\"" + HexFormat.of().formatHex(DIGEST.digest()) + "\"}");
    }

    private static NativePresentation.Look look(NpcPose pose, Optional<ArmorStandLook> stand) {
        return new NativePresentation.Look("Guard", Optional.empty(), false, false, pose, stand);
    }

    @SuppressWarnings("unchecked")
    private static List<SynchedEntityData.DataValue<?>> roundTrip(RegistryAccess registries, List<Object> values) {
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(7, (List<SynchedEntityData.DataValue<?>>) (List<?>) values);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, packet);
            digest(buffer);
            ClientboundSetEntityDataPacket decoded = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
            check(decoded.equals(packet) && !buffer.isReadable(), "entity data round trip");
            packets++;
            return decoded.packedItems();
        } finally { buffer.release(); }
    }

    private static ClientboundSetPlayerTeamPacket teamRoundTrip(RegistryAccess registries, ClientboundSetPlayerTeamPacket packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ClientboundSetPlayerTeamPacket.STREAM_CODEC.encode(buffer, packet);
            digest(buffer);
            ClientboundSetPlayerTeamPacket decoded = ClientboundSetPlayerTeamPacket.STREAM_CODEC.decode(buffer);
            check(!buffer.isReadable(), "team round trip");
            packets++;
            return decoded;
        } finally { buffer.release(); }
    }

    /** 26.3 made team parameters a record; earlier versions use getters. */
    private static Object accessor(Object owner, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            try { return owner.getClass().getMethod(name).invoke(owner); }
            catch (NoSuchMethodException absent) { /* try the next exact-version name */ }
        }
        throw new NoSuchMethodException(String.join(" or ", names));
    }

    private static void digest(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        DIGEST.update(bytes);
    }

    private static void check(boolean condition, String what) { if (!condition) throw new AssertionError("Presentation audit failed: " + what); }
}
