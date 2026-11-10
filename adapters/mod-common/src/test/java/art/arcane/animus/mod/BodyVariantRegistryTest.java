package art.arcane.animus.mod;

import art.arcane.animus.core.npc.BodyVariant;
import art.arcane.animus.core.presentation.BodyVariantValues;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Registry settings name their entries by full identifier: a data pack painting keeps its own namespace. */
class BodyVariantRegistryTest {
    /** Vanilla's registries need its bootstrap; NeoForge's patched classes bootstrap only inside FML, so this runs on Fabric and Forge. */
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (LinkageError outsideLoader) {
            org.junit.jupiter.api.Assumptions.abort("vanilla cannot bootstrap outside this loader: " + outsideLoader.getCause());
        }
    }

    @Test
    void aDataPackPaintingIsLookedUpInItsOwnNamespace() throws ReflectiveOperationException {
        List<String> asked = new ArrayList<>();
        Object entries = Proxy.newProxyInstance(HolderLookup.RegistryLookup.class.getClassLoader(), new Class<?>[] {HolderLookup.RegistryLookup.class},
                (Object proxy, java.lang.reflect.Method method, Object[] args) -> {
                    if (method.getName().equals("getOrThrow")) asked.add(((ResourceKey<?>) args[0]).identifier().toString());
                    return null;
                });
        HolderLookup.Provider registries = (HolderLookup.Provider) Proxy.newProxyInstance(HolderLookup.Provider.class.getClassLoader(), new Class<?>[] {HolderLookup.Provider.class},
                (Object proxy, java.lang.reflect.Method method, Object[] args) -> method.getName().equals("lookupOrThrow") ? entries : null);
        BodyVariantValues values = new BodyVariantValues(new BodyVariantValues.Natives() {
            @Override public Object value(Object accessor, Object value) { return value; }
            @Override public Object item(art.arcane.animus.core.npc.NpcItem item) { return null; }
            @Override public Object blockState(String state) { return null; }
            @Override public Object component(String text) { return text; }
        }, registries);
        values.values("minecraft:painting", new BodyVariant("painting", Map.of("variant", "mypack:sunset"), Set.of()));
        values.values("minecraft:painting", new BodyVariant("painting", Map.of("variant", "kebab"), Set.of()));
        assertEquals(List.of("mypack:sunset", "minecraft:kebab"), asked);
    }
}
