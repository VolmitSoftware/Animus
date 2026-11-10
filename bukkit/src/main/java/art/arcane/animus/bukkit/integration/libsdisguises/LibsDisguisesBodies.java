package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.npc.ArmorStandLook;
import art.arcane.animus.core.npc.NpcDisguise;
import art.arcane.animus.core.presentation.BodySkin;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import me.libraryaddict.disguise.DisguiseConfig;
import me.libraryaddict.disguise.disguisetypes.Disguise;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.FlagWatcher;
import me.libraryaddict.disguise.disguisetypes.MetaIndex;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.AbstractHorseWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.AgeableWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ArmadilloWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ArmorStandWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.BlockDisplayWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ChestedHorseWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.DroppedItemWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.EnderDragonWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.FallingBlockWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.FrogWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.HorseWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ItemDisplayWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.LivingWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.LlamaWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.PiglinWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.TextDisplayWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ZoglinWatcher;
import me.libraryaddict.disguise.disguisetypes.watchers.ZombieWatcher;
import me.libraryaddict.disguise.utilities.DisguiseUtilities;
import me.libraryaddict.disguise.utilities.parser.DisguiseParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

/**
 * Reads LibsDisguises disguise text with LibsDisguises' own parser, so custom disguises from its disguises.yml and every
 * option /disguise takes read the same way, and turns the parsed disguise into Animus body fields. Loaded only while
 * LibsDisguises is installed.
 */
final class LibsDisguisesBodies implements NpcDisguise.Resolver {
    /** Bodies whose look is a block, item or text carried in spawn data, which an Animus body does not send. */
    /** Entities with nothing an Animus body shows. */
    private static final Set<String> CARRIED = Set.of("minecraft:area_effect_cloud", "minecraft:marker", "minecraft:interaction");
    /** Options this class reads into fields or that change nothing an NPC shows; any other option is reported as not shown. */
    private static final Set<String> READ = Set.of("setbaby", "setadult", "setglowing", "setglowcolor", "setcustomname", "setcustomnamevisible", "setnogravity",
            "setsneaking", "setswimming", "setsleeping", "setentitypose", "setarmor", "sethelmet", "setchestplate", "setleggings", "setboots", "setiteminmainhand",
            "setiteminoffhand", "setcolor", "setstyle", "setcarryingchest", "setcarpet", "sethorsearmor", "setsaddled", "setvariant", "setstate", "setphase",
            "setsmall", "setshowarms", "setnobaseplate", "setinvisible", "sethead", "setbody", "setleftarm", "setrightarm", "setleftleg", "setrightleg", "setskin",
            "setname", "setnamevisible", "setdisplayedintab", "setscale", "setmainhandraised", "setrightclicking",
            // How the disguise behaves for the disguised player or the server, which an NPC has no use for.
            "setdisguisename", "setdynamicname", "setviewselfdisguise", "sethidearmorfromself", "sethidehelditemfromself", "setreplacesounds",
            "sethearselfdisguise", "setvelocitysent", "setmodifyboundingbox", "setkeepdisguiseonplayerdeath", "setnotifybar", "setexpires",
            "setmobsignoredisguise", "setsilent");
    private static final Map<EquipmentSlot, String> SLOTS = Map.of(EquipmentSlot.HAND, "main_hand", EquipmentSlot.OFF_HAND, "off_hand", EquipmentSlot.HEAD, "head",
            EquipmentSlot.CHEST, "chest", EquipmentSlot.LEGS, "legs", EquipmentSlot.FEET, "feet", EquipmentSlot.BODY, "body", EquipmentSlot.SADDLE, "saddle");

    /** How long a reload off the server thread waits for LibsDisguises to read one disguise there. */
    private static final long ANSWER_SECONDS = 5;
    private final Runnable skinArrived;
    private final Supplier<Executor> serverThread;
    private final Map<String, NpcDisguise.Body> read = new ConcurrentHashMap<>();

    /**
     * @param skinArrived  runs once LibsDisguises has fetched a player skin a disguise named without textures
     * @param serverThread runs work on the server thread, where LibsDisguises reads disguises
     */
    LibsDisguisesBodies(Runnable skinArrived, Supplier<Executor> serverThread) { this.skinArrived = skinArrived; this.serverThread = serverThread; }

    @Override public NpcDisguise.Body resolve(String text) {
        String key = text + "\n" + custom(text);
        NpcDisguise.Body known = read.get(key);
        if (known != null) return known;
        Read body = Bukkit.isPrimaryThread() ? parse(text) : onServerThread(text);
        if (!body.pending()) read.put(key, body.body());
        return body.body();
    }

    /** LibsDisguises refuses to parse off the server thread, so a reload on the file lane waits for the server thread to read it. */
    private Read onServerThread(String text) {
        CompletableFuture<Read> answer = new CompletableFuture<>();
        try {
            serverThread.get().execute(() -> {
                try { answer.complete(parse(text)); }
                catch (RuntimeException failure) { answer.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException stopped) { throw new IllegalArgumentException("the server is not running tasks, so LibsDisguises cannot read '" + text + "'"); }
        try { return answer.get(ANSWER_SECONDS, TimeUnit.SECONDS); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof IllegalArgumentException refused) throw refused;
            throw new IllegalArgumentException("LibsDisguises cannot read '" + text + "': " + plain(failed.getCause()));
        } catch (TimeoutException slow) {
            throw new IllegalArgumentException("LibsDisguises did not read '" + text + "' within " + ANSWER_SECONDS + " seconds; /animus reload tries again");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("interrupted while LibsDisguises read '" + text + "'");
        }
    }

    private Read parse(String text) {
        Disguise disguise;
        try { disguise = DisguiseParser.parseDisguise(text); }
        catch (Throwable unreadable) { throw new IllegalArgumentException("LibsDisguises cannot read '" + text + "': " + plain(unreadable)); }
        if (disguise == null) throw new IllegalArgumentException("LibsDisguises cannot read '" + text + "'");
        return body(disguise);
    }

    /** The disguise text the first word names in disguises.yml, so an edited custom disguise is read again. */
    private static String custom(String text) {
        String first = text.strip().split("\\s+", 2)[0];
        Map.Entry<?, String> raw = DisguiseConfig.getRawCustomDisguise(first);
        return raw == null ? "" : raw.getValue();
    }

    private record Read(NpcDisguise.Body body, boolean pending) {}

    private Read body(Disguise disguise) {
        DisguiseType kind = disguise.getType();
        EntityType entity = kind.getEntityType();
        if (kind.isCustom() || kind.isUnknown() || entity == null || entity == EntityType.UNKNOWN)
            throw new IllegalArgumentException(kind.toReadable() + " is not a vanilla entity, so an Animus body cannot show it");
        String type = entity.getKey().toString();
        if (CARRIED.contains(type)) throw new IllegalArgumentException(kind.toReadable() + " disguises show nothing an Animus body can draw");
        DisguiseFields fields = new DisguiseFields(type);
        FlagWatcher watcher = disguise.getWatcher();
        boolean pending = false;
        if (disguise instanceof PlayerDisguise player) pending = player(player, fields);
        else if (watcher.hasCustomName()) {
            fields.name(watcher.getCustomName());
            fields.nameplate(watcher.isCustomNameVisible());
        }
        flags(watcher, fields, type);
        equipment(watcher, fields);
        traits(watcher, fields);
        carried(watcher, fields);
        notShown(disguise, fields);
        return new Read(fields.body(), pending);
    }

    /** Name, nameplate, tab entry and skin; true while the skin is still being fetched. */
    private boolean player(PlayerDisguise player, DisguiseFields fields) {
        fields.name(player.getName());
        if (!player.isNameVisible()) fields.nameplate(false);
        if (player.isDisplayedInTab()) fields.listed();
        if (player.isDeadmau5Ears()) fields.unsupported("setDeadmau5Ears");
        Optional<BodySkin> skin = textures(player.getWatcher().getSkin());
        if (skin.isPresent()) { fields.skin(skin.get()); return false; }
        String name = Optional.ofNullable(player.getWatcher().getSkinName()).orElse(player.getName());
        skin = textures(DisguiseUtilities.getProfileFromMojang(name, skinArrived));
        skin.ifPresent(fields::skin);
        return skin.isEmpty();
    }

    private static Optional<BodySkin> textures(UserProfile profile) {
        if (profile == null || profile.getTextureProperties() == null) return Optional.empty();
        for (TextureProperty property : profile.getTextureProperties()) {
            if (!property.getName().equals("textures") || property.getValue() == null) continue;
            try { return Optional.of(new BodySkin(property.getValue(), property.getSignature())); }
            catch (IllegalArgumentException malformed) { return Optional.empty(); }
        }
        return Optional.empty();
    }

    private static void flags(FlagWatcher watcher, DisguiseFields fields, String type) {
        if (watcher.isGlowing()) fields.glowing(Optional.ofNullable(watcher.getGlowColor()).map(Enum::name));
        if (watcher.isNoGravity()) fields.floating();
        if (watcher.hasValue(MetaIndex.ENTITY_POSE) && watcher.getEntityPose() != null) fields.pose(watcher.getEntityPose().name());
        if (watcher.isSneaking()) fields.pose("CROUCHING");
        if (watcher.isSwimming()) fields.pose("SWIMMING");
        if (watcher.isSleeping()) fields.pose("SLEEPING");
        if (watcher.isRightClicking()) fields.usingItem();
        if (watcher.isInvisible() && !type.equals("minecraft:armor_stand")) fields.unsupported("setInvisible");
    }

    private static void equipment(FlagWatcher watcher, DisguiseFields fields) {
        for (Map.Entry<EquipmentSlot, String> slot : SLOTS.entrySet()) {
            ItemStack stack;
            try { stack = watcher.getItemStack(slot.getKey()); }
            catch (RuntimeException unsupportedSlot) { continue; }
            if (stack == null || stack.getType().isAir()) continue;
            Optional<String> components = stack.hasItemMeta() ? Optional.ofNullable(stack.getItemMeta().getAsComponentString()) : Optional.empty();
            fields.equipment(slot.getValue(), stack.getType().getKey().toString(), stack.getAmount(), components);
        }
    }

    private static void traits(FlagWatcher watcher, DisguiseFields fields) {
        boolean baby = switch (watcher) {
            case AgeableWatcher ageable -> ageable.isBaby();
            case ZombieWatcher zombie -> zombie.isBaby();
            case PiglinWatcher piglin -> piglin.isBaby();
            case ZoglinWatcher zoglin -> zoglin.isBaby();
            default -> false;
        };
        if (baby) fields.baby();
        if (watcher instanceof LivingWatcher living) {
            if (living.isMainHandRaised()) fields.usingItem();
            Double scale = living.getScale();
            if (scale != null && scale != 1) fields.scale(scale);
        }
        if (watcher instanceof HorseWatcher horse && watcher.hasValue(MetaIndex.HORSE_COLOR_STYLE)) fields.horse(horse.getColor().name(), horse.getStyle().name());
        if (watcher instanceof LlamaWatcher llama && (watcher.hasValue(MetaIndex.LLAMA_COLOR) || llama.isCarryingChest()))
            fields.llama(llama.getColor().name(), llama.isCarryingChest());
        else if (watcher instanceof ChestedHorseWatcher chested && chested.isCarryingChest()) fields.unsupported("setCarryingChest");
        if (watcher instanceof LlamaWatcher llama && llama.getCarpet() != null)
            fields.equipment("body", "minecraft:" + llama.getCarpet().name().toLowerCase(Locale.ROOT) + "_carpet", 1, Optional.empty());
        if (watcher instanceof AbstractHorseWatcher horse && horse.isSaddled() && !(watcher instanceof HorseWatcher)) fields.unsupported("setSaddled");
        if (watcher instanceof FrogWatcher frog && watcher.hasValue(MetaIndex.FROG_VARIANT)) fields.frog(frog.getVariant().getKey().toString());
        if (watcher instanceof ArmadilloWatcher armadillo && watcher.hasValue(MetaIndex.ARMADILLO_STATE)) fields.armadillo(armadillo.getState().name());
        if (watcher instanceof EnderDragonWatcher dragon && watcher.hasValue(MetaIndex.ENDER_DRAGON_PHASE)) fields.dragon(dragon.getPhase());
        if (watcher instanceof ArmorStandWatcher stand) {
            Map<ArmorStandLook.Part, double[]> angles = new EnumMap<>(ArmorStandLook.Part.class);
            angle(stand, MetaIndex.ARMORSTAND_HEAD, ArmorStandLook.Part.HEAD, stand.getHead(), angles);
            angle(stand, MetaIndex.ARMORSTAND_BODY, ArmorStandLook.Part.BODY, stand.getBody(), angles);
            angle(stand, MetaIndex.ARMORSTAND_LEFT_ARM, ArmorStandLook.Part.LEFT_ARM, stand.getLeftArm(), angles);
            angle(stand, MetaIndex.ARMORSTAND_RIGHT_ARM, ArmorStandLook.Part.RIGHT_ARM, stand.getRightArm(), angles);
            angle(stand, MetaIndex.ARMORSTAND_LEFT_LEG, ArmorStandLook.Part.LEFT_LEG, stand.getLeftLeg(), angles);
            angle(stand, MetaIndex.ARMORSTAND_RIGHT_LEG, ArmorStandLook.Part.RIGHT_LEG, stand.getRightLeg(), angles);
            fields.armorStand(stand.isSmall(), stand.isShowArms(), !stand.isNoBasePlate(), stand.isInvisible(), angles);
            if (stand.isMarker()) fields.unsupported("setMarker");
        }
    }

    /** The block, item or text a falling block, dropped item or display disguise shows, into its body field. */
    private static void carried(FlagWatcher watcher, DisguiseFields fields) {
        switch (watcher) {
            case FallingBlockWatcher falling when falling.getBlockData() != null -> fields.block("fallingBlock", falling.getBlockData().getAsString());
            case BlockDisplayWatcher display when display.getBlock() != null -> fields.block("blockDisplay", display.getBlock().getAsString());
            case DroppedItemWatcher dropped -> carriedItem(dropped.getItemStack(), "droppedItem", fields);
            case ItemDisplayWatcher display -> carriedItem(display.getItemStack(), "itemDisplay", fields);
            case TextDisplayWatcher display when display.getText() != null -> fields.text(display.getText());
            default -> {}
        }
    }

    private static void carriedItem(ItemStack stack, String field, DisguiseFields fields) {
        if (stack == null || stack.getType().isAir()) return;
        Optional<String> components = stack.hasItemMeta() ? Optional.ofNullable(stack.getItemMeta().getAsComponentString()) : Optional.empty();
        fields.item(field, stack.getType().getKey().toString(), stack.getAmount(), components);
    }

    private static void angle(FlagWatcher watcher, MetaIndex<?> index, ArmorStandLook.Part part, EulerAngle angle, Map<ArmorStandLook.Part, double[]> angles) {
        if (watcher.hasValue(index) && angle != null) angles.put(part, new double[]{angle.getX(), angle.getY(), angle.getZ()});
    }

    /** Every option in LibsDisguises' own text of the disguise that was not read into a field. */
    private static void notShown(Disguise disguise, DisguiseFields fields) {
        String canonical;
        try { canonical = DisguiseParser.parseToString(disguise, false); }
        catch (RuntimeException unprintable) { return; }
        boolean quoted = false;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= canonical.length(); i++) {
            char c = i < canonical.length() ? canonical.charAt(i) : ' ';
            if (c == '"') quoted = !quoted;
            if (c == ' ' && !quoted) {
                String option = word.toString();
                if (option.length() > 3 && option.startsWith("set") && Character.isUpperCase(option.charAt(3)) && !READ.contains(option.toLowerCase(Locale.ROOT)))
                    fields.unsupported(option);
                word.setLength(0);
            } else word.append(c);
        }
    }

    /** A failure's message without colour codes. */
    private static String plain(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.replaceAll("§.", "").strip();
    }
}
