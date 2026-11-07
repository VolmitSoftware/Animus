package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.npc.BodyVariant;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata for the settings only one body type has: a horse's coat, a llama's variant and chest, a frog's variant and
 * an armadillo's state. Bound separately, so a server that keeps one of them elsewhere only shows those bodies with
 * the client's defaults.
 */
final class NativeBodyVariants {
    private final Method dataValue;
    private final Object horseVariant, llamaVariant, llamaChest, armadilloState, frogVariant;
    private final Class<? extends Enum> armadilloStates;
    /** The frog variant holders by name, from the server's registries. */
    private final Map<String, Object> frogs = new HashMap<>();

    @SuppressWarnings("unchecked")
    NativeBodyVariants(Method dataValue, Object server) throws ReflectiveOperationException {
        this.dataValue = dataValue;
        horseVariant = accessor("net.minecraft.world.entity.animal.equine.Horse", "DATA_ID_TYPE_VARIANT");
        llamaVariant = accessor("net.minecraft.world.entity.animal.equine.Llama", "DATA_VARIANT_ID");
        llamaChest = accessor("net.minecraft.world.entity.animal.equine.AbstractChestedHorse", "DATA_ID_CHEST");
        armadilloState = accessor("net.minecraft.world.entity.animal.armadillo.Armadillo", "ARMADILLO_STATE");
        armadilloStates = (Class<? extends Enum>) Class.forName("net.minecraft.world.entity.animal.armadillo.Armadillo$ArmadilloState");
        frogVariant = accessor("net.minecraft.world.entity.animal.frog.Frog", "DATA_VARIANT_ID");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        Object registries = server.getClass().getMethod("registryAccess").invoke(server);
        Object registryKey = Class.forName("net.minecraft.core.registries.Registries").getField("FROG_VARIANT").get(null);
        Object lookup = Class.forName("net.minecraft.core.HolderLookup$Provider").getMethod("lookupOrThrow", resourceKey).invoke(registries, registryKey);
        Method holder = Class.forName("net.minecraft.core.HolderGetter").getMethod("getOrThrow", resourceKey);
        Class<?> keys = Class.forName("net.minecraft.world.entity.animal.frog.FrogVariants");
        for (String name : BodyVariant.FIELDS.get("frog").setting("variant").orElseThrow().values())
            frogs.put(name, holder.invoke(lookup, keys.getField(name.toUpperCase(Locale.ROOT)).get(null)));
    }

    private static Object accessor(String owner, String name) throws ReflectiveOperationException {
        Field field = Class.forName(owner).getDeclaredField(name);
        if (!field.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner + "." + name);
        return field.get(null);
    }

    /** The values a body of {@code typeKey} needs for its settings; empty when it keeps the client's defaults. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Object> values(String typeKey, BodyVariant variant) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        if (variant.equals(BodyVariant.NONE)) return values;
        switch (typeKey) {
            case "minecraft:horse" -> {
                if (!variant.choices().isEmpty()) values.add(dataValue.invoke(null, horseVariant, BodyVariant.horseVariant(variant)));
            }
            case "minecraft:llama" -> {
                if (variant.index("variant") >= 0) values.add(dataValue.invoke(null, llamaVariant, variant.index("variant")));
                if (variant.flag("chest")) values.add(dataValue.invoke(null, llamaChest, Boolean.TRUE));
            }
            case "minecraft:frog" -> {
                Object holder = frogs.get(variant.choices().get("variant"));
                if (holder != null) values.add(dataValue.invoke(null, frogVariant, holder));
            }
            case "minecraft:armadillo" -> {
                if (variant.choices().containsKey("state"))
                    values.add(dataValue.invoke(null, armadilloState, Enum.valueOf((Class) armadilloStates, variant.choices().get("state").toUpperCase(Locale.ROOT))));
            }
            default -> { }
        }
        return values;
    }
}
