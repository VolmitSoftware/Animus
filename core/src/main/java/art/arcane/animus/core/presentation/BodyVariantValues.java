package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.BodyVariant;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Metadata for the settings only one body type has: a horse's coat, a sheep's wool, a villager's clothes, a slime's
 * size, a painting's art and the rest, read from the server's own classes by name. Minecraft 26 ships unobfuscated, so
 * the same names hold on Paper, Spigot, Folia, Fabric, NeoForge and Forge. Each value is bound on its own, so a server
 * that keeps one of them elsewhere only shows that setting with the client's default; {@link #missing()} names those.
 */
public final class BodyVariantValues {
    private static final String ENTITY = "net.minecraft.world.entity.";

    /** What only the platform knows how to make: data values, item stacks, block states and text components. */
    public interface Natives {
        /** A {@code SynchedEntityData.DataValue} of {@code accessor}. */
        Object value(Object accessor, Object value) throws ReflectiveOperationException;
        /** The server item stack of an item, or null when the server does not know it. */
        Object item(art.arcane.animus.core.npc.NpcItem item) throws ReflectiveOperationException;
        /** The server block state a block state text names, or null when the server does not know it. */
        Object blockState(String state) throws ReflectiveOperationException;
        /** A chat component of display text with & codes. */
        Object component(String text) throws ReflectiveOperationException;
    }

    private final Natives natives;
    private final Object registries;
    private final Method lookup, holder, resourceKey, identifier, blockId;
    private final Map<String, Object> accessors = new HashMap<>(), holders = new HashMap<>();
    private final Set<String> missing = new TreeSet<>();
    private Constructor<?> villagerData;

    /** @param registries the server's {@code HolderLookup.Provider} */
    public BodyVariantValues(Natives natives, Object registries) throws ReflectiveOperationException {
        this.natives = natives;
        this.registries = registries;
        blockId = Class.forName("net.minecraft.world.level.block.Block").getMethod("getId", Class.forName("net.minecraft.world.level.block.state.BlockState"));
        Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey");
        lookup = Class.forName("net.minecraft.core.HolderLookup$Provider").getMethod("lookupOrThrow", keys);
        holder = Class.forName("net.minecraft.core.HolderGetter").getMethod("getOrThrow", keys);
        Class<?> identifiers = Class.forName("net.minecraft.resources.Identifier");
        resourceKey = keys.getMethod("create", keys, identifiers);
        identifier = identifiers.getMethod("parse", String.class);
        try {
            villagerData = Class.forName(ENTITY + "npc.villager.VillagerData").getConstructor(Class.forName("net.minecraft.core.Holder"),
                    Class.forName("net.minecraft.core.Holder"), int.class);
        } catch (ReflectiveOperationException absent) { missing.add("villager data"); }
    }

    /** The settings this server cannot show, by owner and field. */
    public synchronized Set<String> missing() { return Set.copyOf(missing); }

    /** The data accessor {@code name} of the first of {@code owners} that has it, or null when none does. */
    private Object accessor(String name, String... owners) {
        String key = owners[0] + "." + name;
        if (accessors.containsKey(key)) return accessors.get(key);
        Object found = null;
        for (String owner : owners) {
            try {
                Field field = Class.forName(ENTITY + owner).getDeclaredField(name);
                if (field.trySetAccessible()) { found = field.get(null); break; }
            } catch (ReflectiveOperationException | LinkageError absent) { /* Try the next name this class has had. */ }
        }
        if (found == null) missing.add(key);
        accessors.put(key, found);
        return found;
    }

    /** The registry entry {@code name}, {@code minecraft:} unless it names a namespace, of the registry {@code registry} names in {@code Registries}, or null. */
    private Object holder(String registry, String name) {
        String key = registry + "/" + name;
        if (holders.containsKey(key)) return holders.get(key);
        Object found = null;
        try {
            Object registryKey = Class.forName("net.minecraft.core.registries.Registries").getField(registry).get(null);
            Object entries = lookup.invoke(registries, registryKey);
            found = holder.invoke(entries, resourceKey.invoke(null, registryKey, identifier.invoke(null, name)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { missing.add(key); }
        holders.put(key, found);
        return found;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(String type, String name) {
        try { return Enum.valueOf((Class) Class.forName(ENTITY + type), name.toUpperCase(Locale.ROOT)); }
        catch (ReflectiveOperationException | IllegalArgumentException | LinkageError absent) { return null; }
    }

    /** The values a body of {@code typeKey} needs for its settings; empty when it keeps the client's defaults. */
    public synchronized List<Object> values(String typeKey, BodyVariant variant) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        if (variant.equals(BodyVariant.NONE)) return values;
        Values out = new Values(values);
        switch (typeKey) {
            case "minecraft:horse" -> { if (!variant.choices().isEmpty()) out.put(accessor("DATA_ID_TYPE_VARIANT", "animal.equine.Horse"), BodyVariant.horseVariant(variant)); }
            case "minecraft:llama" -> {
                if (variant.index("variant") >= 0) out.put(accessor("DATA_VARIANT_ID", "animal.equine.Llama"), variant.index("variant"));
                if (variant.flag("chest")) out.put(chest(), Boolean.TRUE);
            }
            case "minecraft:donkey", "minecraft:mule" -> { if (variant.flag("chest")) out.put(chest(), Boolean.TRUE); }
            case "minecraft:frog" -> registry(out, accessor("DATA_VARIANT_ID", "animal.frog.Frog"), "FROG_VARIANT", variant, "variant");
            case "minecraft:armadillo" -> {
                if (variant.choices().containsKey("state"))
                    out.put(accessor("ARMADILLO_STATE", "animal.armadillo.Armadillo"), constant("animal.armadillo.Armadillo$ArmadilloState", variant.choices().get("state")));
            }
            case "minecraft:sheep" -> out.put(accessor("DATA_WOOL_ID", "animal.sheep.Sheep"), VariantWire.wool(variant), Byte.class);
            case "minecraft:wolf" -> {
                registry(out, accessor("DATA_VARIANT_ID", "animal.wolf.Wolf"), "WOLF_VARIANT", variant, "variant");
                tame(out, variant, accessor("DATA_COLLAR_COLOR", "animal.wolf.Wolf"));
                if (variant.flag("angry")) out.put(accessor("DATA_ANGER_END_TIME", "animal.wolf.Wolf"), Long.MAX_VALUE);
                if (variant.flag("interested")) out.put(accessor("DATA_INTERESTED_ID", "animal.wolf.Wolf"), Boolean.TRUE);
            }
            case "minecraft:cat" -> {
                registry(out, accessor("DATA_VARIANT_ID", "animal.feline.Cat"), "CAT_VARIANT", variant, "variant");
                tame(out, variant, accessor("DATA_COLLAR_COLOR", "animal.feline.Cat"));
                if (variant.flag("lying")) out.put(accessor("IS_LYING", "animal.feline.Cat"), Boolean.TRUE);
            }
            case "minecraft:parrot" -> {
                index(out, accessor("DATA_VARIANT_ID", "animal.parrot.Parrot"), variant, "variant");
                tame(out, variant, null);
            }
            case "minecraft:fox" -> {
                index(out, accessor("DATA_TYPE_ID", "animal.fox.Fox"), variant, "type");
                out.put(accessor("DATA_FLAGS_ID", "animal.fox.Fox"), VariantWire.foxFlags(variant), Byte.class);
            }
            case "minecraft:axolotl" -> {
                index(out, accessor("DATA_VARIANT", "animal.axolotl.Axolotl"), variant, "variant");
                if (variant.flag("playingDead")) out.put(accessor("DATA_PLAYING_DEAD", "animal.axolotl.Axolotl"), Boolean.TRUE);
            }
            case "minecraft:rabbit" -> out.put(accessor("DATA_TYPE_ID", "animal.rabbit.Rabbit"), VariantWire.rabbit(variant), Integer.class);
            case "minecraft:villager" -> villager(out, accessor("DATA_VILLAGER_DATA", "npc.villager.Villager"), variant);
            case "minecraft:zombie_villager" -> villager(out, accessor("DATA_VILLAGER_DATA", "monster.zombie.ZombieVillager"), variant);
            case "minecraft:creeper" -> { if (variant.flag("powered")) out.put(accessor("DATA_IS_POWERED", "monster.Creeper"), Boolean.TRUE); }
            case "minecraft:slime", "minecraft:magma_cube" -> {
                if (variant.choices().containsKey("size")) out.put(accessor("ID_SIZE", "monster.cubemob.AbstractCubeMob", "monster.Slime"), variant.number("size", 1));
            }
            case "minecraft:phantom" -> { if (variant.choices().containsKey("size")) out.put(accessor("ID_SIZE", "monster.Phantom"), variant.number("size", 0)); }
            case "minecraft:mooshroom" -> index(out, accessor("DATA_TYPE", "animal.cow.MushroomCow"), variant, "variant");
            case "minecraft:panda" -> {
                if (variant.index("mainGene") >= 0) out.put(accessor("MAIN_GENE_ID", "animal.panda.Panda"), (byte) variant.index("mainGene"));
                if (variant.index("hiddenGene") >= 0) out.put(accessor("HIDDEN_GENE_ID", "animal.panda.Panda"), (byte) variant.index("hiddenGene"));
                out.put(accessor("DATA_ID_FLAGS", "animal.panda.Panda"), VariantWire.pandaFlags(variant), Byte.class);
                if (variant.flag("eating")) out.put(accessor("EAT_COUNTER", "animal.panda.Panda"), 1);
            }
            case "minecraft:goat" -> {
                if (variant.index("leftHorn") >= 0) out.put(accessor("DATA_HAS_LEFT_HORN", "animal.goat.Goat"), variant.index("leftHorn") == 0);
                if (variant.index("rightHorn") >= 0) out.put(accessor("DATA_HAS_RIGHT_HORN", "animal.goat.Goat"), variant.index("rightHorn") == 0);
                if (variant.flag("screaming")) out.put(accessor("DATA_IS_SCREAMING_GOAT", "animal.goat.Goat"), Boolean.TRUE);
            }
            case "minecraft:pig" -> registry(out, accessor("DATA_VARIANT_ID", "animal.pig.Pig"), "PIG_VARIANT", variant, "variant");
            case "minecraft:cow" -> registry(out, accessor("DATA_VARIANT_ID", "animal.cow.Cow"), "COW_VARIANT", variant, "variant");
            case "minecraft:chicken" -> registry(out, accessor("DATA_VARIANT_ID", "animal.chicken.Chicken"), "CHICKEN_VARIANT", variant, "variant");
            case "minecraft:shulker" -> {
                out.put(accessor("DATA_COLOR_ID", "monster.Shulker"), VariantWire.shulkerColor(variant), Byte.class);
                if (variant.choices().containsKey("peek")) out.put(accessor("DATA_PEEK_ID", "monster.Shulker"), (byte) variant.number("peek", 0));
            }
            case "minecraft:pufferfish" -> index(out, accessor("PUFF_STATE", "animal.fish.Pufferfish"), variant, "puff");
            case "minecraft:tropical_fish" -> out.put(accessor("DATA_ID_TYPE_VARIANT", "animal.fish.TropicalFish"), VariantWire.tropicalFish(variant), Integer.class);
            case "minecraft:snow_golem" -> out.put(accessor("DATA_PUMPKIN_ID", "animal.golem.SnowGolem"), VariantWire.pumpkin(variant), Byte.class);
            case "minecraft:polar_bear" -> { if (variant.flag("standing")) out.put(accessor("DATA_STANDING_ID", "animal.polarbear.PolarBear"), Boolean.TRUE); }
            case "minecraft:piglin" -> { if (variant.flag("dancing")) out.put(accessor("DATA_IS_DANCING", "monster.piglin.Piglin"), Boolean.TRUE); }
            case "minecraft:allay" -> { if (variant.flag("dancing")) out.put(accessor("DATA_DANCING", "animal.allay.Allay"), Boolean.TRUE); }
            case "minecraft:bee" -> {
                out.put(accessor("DATA_FLAGS_ID", "animal.bee.Bee"), VariantWire.beeFlags(variant), Byte.class);
                if (variant.flag("angry")) out.put(accessor("DATA_ANGER_END_TIME", "animal.bee.Bee"), Long.MAX_VALUE);
            }
            case "minecraft:vex" -> { if (variant.flag("charging")) out.put(accessor("DATA_FLAGS_ID", "monster.Vex"), (byte) 0x01); }
            case "minecraft:evoker" -> {
                if (variant.index("spell") >= 0) out.put(accessor("DATA_SPELL_CASTING_ID", "monster.illager.SpellcasterIllager"), (byte) variant.index("spell"));
            }
            case "minecraft:sniffer" -> {
                if (variant.choices().containsKey("state")) out.put(accessor("DATA_STATE", "animal.sniffer.Sniffer"), constant("animal.sniffer.Sniffer$State", variant.choices().get("state")));
            }
            case "minecraft:bat" -> { if (variant.flag("resting")) out.put(accessor("DATA_ID_FLAGS", "ambient.Bat"), (byte) 0x01); }
            case "minecraft:copper_golem" -> {
                if (variant.choices().containsKey("weather")) {
                    Object state = null;
                    try { state = Enum.valueOf(weatherStates(), variant.choices().get("weather").toUpperCase(Locale.ROOT)); }
                    catch (ReflectiveOperationException | IllegalArgumentException absent) { missing.add("WeatheringCopper$WeatherState"); }
                    out.put(accessor("DATA_WEATHER_STATE", "animal.golem.CopperGolem"), state);
                }
            }
            case "minecraft:enderman" -> { if (variant.flag("angry")) out.put(accessor("DATA_CREEPY", "monster.Enderman", "monster.EnderMan"), Boolean.TRUE); }
            case "minecraft:wither" -> {
                if (variant.choices().containsKey("invulnerableTicks")) out.put(accessor("DATA_ID_INV", "boss.wither.WitherBoss"), variant.number("invulnerableTicks", 0));
                if (variant.flag("armored")) out.put(accessor("DATA_HEALTH_ID", "LivingEntity"), 1f);
            }
            case "minecraft:camel" -> {
                if (variant.flag("sitting")) {
                    out.put(accessor("DATA_POSE", "Entity"), constant("Pose", "sitting"));
                    out.put(accessor("LAST_POSE_CHANGE_TICK", "animal.camel.Camel"), -1L);
                }
                if (variant.flag("dashing")) out.put(accessor("DASH", "animal.camel.Camel"), Boolean.TRUE);
            }
            case "minecraft:painting" -> {
                Object holder = variant.id("variant").map((String id) -> holder("PAINTING_VARIANT", id)).orElse(null);
                out.put(accessor("DATA_PAINTING_VARIANT_ID", "decoration.painting.Painting"), holder);
            }
            case "minecraft:item_frame", "minecraft:glow_item_frame" -> {
                out.put(accessor("DATA_ITEM", "decoration.ItemFrame"), item(variant, "item"));
                if (variant.choices().containsKey("rotation")) out.put(accessor("DATA_ROTATION", "decoration.ItemFrame"), variant.number("rotation", 0));
            }
            case "minecraft:end_crystal" -> { if (variant.index("base") == 1) out.put(accessor("DATA_SHOW_BOTTOM", "boss.enderdragon.EndCrystal"), Boolean.FALSE); }
            case "minecraft:block_display" -> {
                out.put(accessor("DATA_BLOCK_STATE_ID", "Display$BlockDisplay"), state(variant));
                billboard(out, variant);
            }
            case "minecraft:item_display" -> {
                out.put(accessor("DATA_ITEM_STACK_ID", "Display$ItemDisplay"), item(variant, "item"));
                if (variant.index("transform") >= 0) out.put(accessor("DATA_ITEM_DISPLAY_ID", "Display$ItemDisplay"), (byte) variant.index("transform"));
                billboard(out, variant);
            }
            case "minecraft:text_display" -> {
                String shown = variant.choices().get("text");
                if (shown != null) out.put(accessor("DATA_TEXT_ID", "Display$TextDisplay"), natives.component(shown));
                out.put(accessor("DATA_STYLE_FLAGS_ID", "Display$TextDisplay"), VariantWire.textStyle(variant), Byte.class);
                if (variant.choices().containsKey("lineWidth")) out.put(accessor("DATA_LINE_WIDTH_ID", "Display$TextDisplay"), variant.number("lineWidth", 200));
                billboard(out, variant);
            }
            case "minecraft:item" -> out.put(accessor("DATA_ITEM", "item.ItemEntity"), item(variant, "item"));
            default -> { }
        }
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<Enum> weatherStates() throws ClassNotFoundException {
        return (Class<Enum>) Class.forName("net.minecraft.world.level.block.WeatheringCopper$WeatherState");
    }

    /** The add-entity data a body spawns with: a hanging body's facing, or a falling block's state id. */
    public synchronized int spawnData(String typeKey, BodyVariant variant) throws ReflectiveOperationException {
        if (typeKey.equals("minecraft:falling_block")) {
            Object state = state(variant);
            return state == null ? 0 : (int) blockId.invoke(null, state);
        }
        return VariantWire.facing(typeKey, variant);
    }

    private void billboard(Values out, BodyVariant variant) throws ReflectiveOperationException {
        if (variant.index("billboard") >= 0) out.put(accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID", "Display"), (byte) variant.index("billboard"));
    }

    /** The server item stack of an item setting, or null when it is left out or the server does not know the item. */
    private Object item(BodyVariant variant, String key) throws ReflectiveOperationException {
        java.util.Optional<art.arcane.animus.core.npc.NpcItem> item = variant.item(key);
        return item.isEmpty() ? null : natives.item(item.get());
    }

    /** The server block state a block setting names, or null when it is left out or not a block this server knows. */
    private Object state(BodyVariant variant) throws ReflectiveOperationException {
        java.util.Optional<String> block = variant.id("block");
        return block.isEmpty() ? null : natives.blockState(block.get());
    }

    private Object chest() { return accessor("DATA_ID_CHEST", "animal.equine.AbstractChestedHorse"); }

    private void index(Values out, Object accessor, BodyVariant variant, String key) throws ReflectiveOperationException {
        if (variant.index(key) >= 0) out.put(accessor, variant.index(key));
    }

    private void registry(Values out, Object accessor, String registry, BodyVariant variant, String key) throws ReflectiveOperationException {
        String name = variant.choices().get(key);
        if (name != null) out.put(accessor, holder(registry, name));
    }

    /** A sitting flag and a collar, which shows only on a tamed body. */
    private void tame(Values out, BodyVariant variant, Object collar) throws ReflectiveOperationException {
        out.put(accessor("DATA_FLAGS_ID", "TamableAnimal"), VariantWire.tameFlags(variant), Byte.class);
        if (collar != null && variant.index("collar") >= 0) out.put(collar, variant.index("collar"));
    }

    private void villager(Values out, Object accessor, BodyVariant variant) throws ReflectiveOperationException {
        if (variant.choices().isEmpty() || villagerData == null) return;
        Object type = holder("VILLAGER_TYPE", variant.choices().getOrDefault("type", "plains"));
        Object profession = holder("VILLAGER_PROFESSION", variant.choices().getOrDefault("profession", "none"));
        if (type != null && profession != null) out.put(accessor, villagerData.newInstance(type, profession, VariantWire.villagerLevel(variant)));
    }

    /** Collects data values, leaving out a value whose accessor or content this server lacks, and a packed value of -1. */
    private final class Values {
        private final List<Object> values;
        Values(List<Object> values) { this.values = values; }

        void put(Object accessor, Object value) throws ReflectiveOperationException {
            if (accessor == null || value == null) return;
            values.add(natives.value(accessor, value));
        }

        void put(Object accessor, int packed, Class<?> type) throws ReflectiveOperationException {
            if (packed < 0) return;
            put(accessor, type == Byte.class ? (Object) (byte) packed : (Object) packed);
        }
    }

    /** Settings the server could not bind, logged once at startup. */
    public synchronized void report(Consumer<String> warn) {
        for (BodyVariant.Field field : BodyVariant.FIELDS.values()) {
            try { values(field.type(), probe(field)); }
            catch (ReflectiveOperationException | RuntimeException failure) { missing.add(field.field() + ": " + failure.getClass().getSimpleName()); }
        }
        if (!missing.isEmpty()) warn.accept("NPC body settings unavailable on this server, shown with the client's defaults: " + String.join(", ", missing));
    }

    /** Every setting of {@code field} chosen, so binding each one is tried. */
    private static BodyVariant probe(BodyVariant.Field field) {
        Map<String, String> choices = new HashMap<>();
        Set<String> flags = new TreeSet<>();
        for (BodyVariant.Setting setting : field.settings()) {
            switch (setting.kind()) {
                case FLAG -> flags.add(setting.key());
                case NUMBER -> choices.put(setting.key(), Integer.toString(setting.maximum()));
                case CHOICE -> choices.put(setting.key(), setting.values().getLast());
                case ITEM -> choices.put(setting.key(), "{\"id\":\"minecraft:stone\"}");
                case BLOCK -> choices.put(setting.key(), "minecraft:stone");
                case ID -> choices.put(setting.key(), "minecraft:kebab");
                case TEXT -> choices.put(setting.key(), "Animus");
            }
        }
        return new BodyVariant(field.field(), choices, flags);
    }
}
