package art.arcane.animus.bukkit.recording;

import art.arcane.animus.bukkit.runtime.DefinitionItems;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.BodySkin;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** Reads what a recording keeps about the recorder's look: the signed skin and the six equipment slots. Owner thread. */
final class RecorderLook {
    private RecorderLook() {}

    /**
     * The player's signed {@code textures} property: Paper's {@code PlayerProfile.getProperties()}, otherwise the
     * CraftBukkit game profile. Empty in offline mode or when neither path binds.
     */
    static Optional<BodySkin> skin(Player player) {
        try {
            Optional<BodySkin> paper = paperSkin(player);
            if (paper.isPresent()) return paper;
            return gameProfileSkin(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Optional.empty(); }
    }

    private static Optional<BodySkin> paperSkin(Player player) throws ReflectiveOperationException {
        Class<?> profileType;
        try { profileType = Class.forName("com.destroystokyo.paper.profile.PlayerProfile"); }
        catch (ClassNotFoundException spigot) { return Optional.empty(); }
        Class<?> propertyType = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
        Object profile = Player.class.getMethod("getPlayerProfile").invoke(player);
        if (!profileType.isInstance(profile)) return Optional.empty();
        Method name = propertyType.getMethod("getName"), value = propertyType.getMethod("getValue"), signature = propertyType.getMethod("getSignature");
        for (Object property : (Collection<?>) profileType.getMethod("getProperties").invoke(profile))
            if ("textures".equals(name.invoke(property))) return skin((String) value.invoke(property), (String) signature.invoke(property));
        return Optional.empty();
    }

    private static Optional<BodySkin> gameProfileSkin(Player player) throws ReflectiveOperationException {
        Object profile = call(player, "getProfile");
        Object properties = call(profile, "properties", "getProperties");
        Object textures = properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
        for (Object property : (Collection<?>) textures) return skin((String) call(property, "value", "getValue"), (String) call(property, "signature", "getSignature"));
        return Optional.empty();
    }

    private static Optional<BodySkin> skin(String value, String signature) {
        if (value == null || value.isBlank() || signature == null || signature.isBlank()) return Optional.empty();
        try { return Optional.of(new BodySkin(value, signature)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /** Calls the first public no-argument method of {@code names} that {@code target} has. */
    private static Object call(Object target, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            Method method;
            try { method = target.getClass().getMethod(name); }
            catch (NoSuchMethodException missing) { continue; }
            if (!method.trySetAccessible()) continue;
            return method.invoke(target);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + String.join("|", names));
    }

    /** The stack in one of the six player slots. */
    static ItemStack stack(PlayerInventory inventory, EquipmentSlot slot) {
        return switch (slot) {
            case MAIN_HAND -> inventory.getItemInMainHand();
            case OFF_HAND -> inventory.getItemInOffHand();
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            default -> null;
        };
    }

    /** A stack in the definition item shape; components the item shape cannot hold are left out. Empty for air. */
    static Optional<NpcItem> item(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() < 1) return Optional.empty();
        String id = stack.getType().getKey().toString();
        if (!NpcItem.validId(id)) return Optional.empty();
        Optional<String> components = Optional.empty();
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            String text = meta == null ? null : meta.getAsComponentString();
            if (text != null && !text.isBlank()) {
                String bracketed = text.startsWith("[") ? text : "[" + text + "]";
                if (!bracketed.equals("[]") && bracketed.endsWith("]") && bracketed.length() <= NpcItem.MAX_COMPONENTS) components = Optional.of(bracketed);
            }
        }
        return Optional.of(new NpcItem(id, Math.min(NpcItem.MAX_COUNT, stack.getAmount()), components, Optional.empty()));
    }

    /** The stack a ghost shows for a recorded item; air clears the slot, and an item this server lacks shows nothing. */
    static ItemStack stack(Optional<NpcItem> item) {
        if (item.isEmpty()) return new ItemStack(Material.AIR);
        ItemStack stack = DefinitionItems.stack(item.get(), "$.item", new ArrayList<>());
        return stack == null ? new ItemStack(Material.AIR) : stack;
    }

    static boolean openable(Block block) { return block != null && block.getBlockData() instanceof Openable; }
}
