package art.arcane.animus.bukkit.packet;

import art.arcane.animus.core.presentation.BoneDisplay;
import art.arcane.animus.core.rig.DisplayTransform;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * Metadata for packet-only rig bones: item, block and text displays with their transforms and interpolation. Bound
 * separately so a missing signature turns rigs off without touching bodies. Nothing here creates a server entity.
 */
final class NativeBones {
    private final Method dataValue, nativeStack, literal;
    private final Object translation, scale, leftRotation, rightRotation, interpolationStart, interpolationDuration, billboard;
    private final Object itemStack, itemContext, blockState, text, lineWidth, background, styleFlags;
    private final Constructor<?> vector, quaternion;
    private final Object itemDisplayType, blockDisplayType, textDisplayType;
    /** Styles text the way holograms are styled; null leaves plain text. */
    private final NativePresentation presentation;

    NativeBones(Method dataValue, String craftPackage, Object itemDisplayType, Object blockDisplayType, Object textDisplayType, NativePresentation presentation) throws ReflectiveOperationException {
        this.dataValue = dataValue;
        this.presentation = presentation;
        this.itemDisplayType = itemDisplayType; this.blockDisplayType = blockDisplayType; this.textDisplayType = textDisplayType;
        Class<?> display = Class.forName("net.minecraft.world.entity.Display");
        Class<?> item = Class.forName("net.minecraft.world.entity.Display$ItemDisplay");
        Class<?> block = Class.forName("net.minecraft.world.entity.Display$BlockDisplay");
        Class<?> textDisplay = Class.forName("net.minecraft.world.entity.Display$TextDisplay");
        translation = accessor(display, "DATA_TRANSLATION_ID"); scale = accessor(display, "DATA_SCALE_ID");
        leftRotation = accessor(display, "DATA_LEFT_ROTATION_ID"); rightRotation = accessor(display, "DATA_RIGHT_ROTATION_ID");
        interpolationStart = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID");
        interpolationDuration = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID");
        billboard = accessor(display, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID");
        itemStack = accessor(item, "DATA_ITEM_STACK_ID"); itemContext = accessor(item, "DATA_ITEM_DISPLAY_ID");
        blockState = accessor(block, "DATA_BLOCK_STATE_ID");
        text = accessor(textDisplay, "DATA_TEXT_ID"); lineWidth = accessor(textDisplay, "DATA_LINE_WIDTH_ID");
        background = accessor(textDisplay, "DATA_BACKGROUND_COLOR_ID"); styleFlags = accessor(textDisplay, "DATA_STYLE_FLAGS_ID");
        vector = Class.forName("org.joml.Vector3f").getConstructor(float.class, float.class, float.class);
        quaternion = Class.forName("org.joml.Quaternionf").getConstructor(float.class, float.class, float.class, float.class);
        literal = Class.forName("net.minecraft.network.chat.Component").getMethod("literal", String.class);
        nativeStack = Class.forName(craftPackage + ".inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
    }

    private static Object accessor(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new NoSuchFieldException("Cannot access " + owner.getName() + "." + name);
        return field.get(null);
    }

    Object type(BoneDisplay<ItemStack> bone) {
        return switch (bone.kind()) { case ITEM -> itemDisplayType; case BLOCK -> blockDisplayType; case TEXT -> textDisplayType; };
    }

    /** The bone's fixed content. Block states are parsed here, so an invalid one is refused before anything is sent. */
    List<Object> content(BoneDisplay<ItemStack> bone) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        switch (bone.kind()) {
            case ITEM -> {
                values.add(dataValue.invoke(null, itemStack, nativeStack.invoke(null, bone.item())));
                values.add(dataValue.invoke(null, itemContext, bone.itemContext()));
            }
            case BLOCK -> {
                BlockData data;
                try { data = Bukkit.createBlockData(bone.block()); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown block state for a rig bone: " + bone.block()); }
                values.add(dataValue.invoke(null, blockState, data.getClass().getMethod("getState").invoke(data)));
            }
            case TEXT -> {
                values.add(dataValue.invoke(null, text, presentation != null ? presentation.component(bone.text()) : literal.invoke(null, bone.text())));
                values.add(dataValue.invoke(null, billboard, bone.billboard()));
                values.add(dataValue.invoke(null, lineWidth, bone.lineWidth()));
                values.add(dataValue.invoke(null, background, bone.background()));
                if (bone.shadow()) values.add(dataValue.invoke(null, styleFlags, (byte) 0x01));
            }
        }
        return values;
    }

    /** Transform values interpolated over {@code duration} ticks, starting on the client's next tick. */
    List<Object> transform(DisplayTransform transform, int duration) throws ReflectiveOperationException {
        return List.of(dataValue.invoke(null, interpolationStart, 0), dataValue.invoke(null, interpolationDuration, duration),
                dataValue.invoke(null, translation, vector.newInstance(transform.tx(), transform.ty(), transform.tz())),
                dataValue.invoke(null, leftRotation, quaternion.newInstance(transform.lx(), transform.ly(), transform.lz(), transform.lw())),
                dataValue.invoke(null, scale, vector.newInstance(transform.sx(), transform.sy(), transform.sz())),
                dataValue.invoke(null, rightRotation, quaternion.newInstance(transform.rx(), transform.ry(), transform.rz(), transform.rw())));
    }
}
