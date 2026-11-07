package art.arcane.animus.mod;

import art.arcane.animus.core.npc.BodyVariant;
import art.arcane.animus.mod.mixin.ArmadilloDataAccess;
import art.arcane.animus.mod.mixin.ChestedHorseDataAccess;
import art.arcane.animus.mod.mixin.FrogDataAccess;
import art.arcane.animus.mod.mixin.HorseDataAccess;
import art.arcane.animus.mod.mixin.LlamaDataAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.animal.armadillo.Armadillo;

/** Metadata for the settings only one body type has: a horse's coat, a llama's variant and chest, a frog's variant and an armadillo's state. */
final class ModBodyVariants {
    private ModBodyVariants() {}

    /** The values a body of {@code typeKey} needs for its settings; empty when it keeps the client's defaults. */
    static List<SynchedEntityData.DataValue<?>> values(String typeKey, BodyVariant variant, HolderLookup.Provider registries) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        if (variant.equals(BodyVariant.NONE)) return values;
        switch (typeKey) {
            case "minecraft:horse" -> {
                if (!variant.choices().isEmpty()) values.add(SynchedEntityData.DataValue.create(HorseDataAccess.animus$variant(), BodyVariant.horseVariant(variant)));
            }
            case "minecraft:llama" -> {
                if (variant.index("variant") >= 0) values.add(SynchedEntityData.DataValue.create(LlamaDataAccess.animus$variant(), variant.index("variant")));
                if (variant.flag("chest")) values.add(SynchedEntityData.DataValue.create(ChestedHorseDataAccess.animus$chest(), Boolean.TRUE));
            }
            case "minecraft:frog" -> {
                String name = variant.choices().get("variant");
                if (name != null) registries.lookupOrThrow(Registries.FROG_VARIANT)
                        .get(ResourceKey.create(Registries.FROG_VARIANT, Identifier.withDefaultNamespace(name)))
                        .ifPresent(holder -> values.add(SynchedEntityData.DataValue.create(FrogDataAccess.animus$variant(), holder)));
            }
            case "minecraft:armadillo" -> {
                String state = variant.choices().get("state");
                if (state != null) values.add(SynchedEntityData.DataValue.create(ArmadilloDataAccess.animus$state(), Armadillo.ArmadilloState.valueOf(state.toUpperCase(Locale.ROOT))));
            }
            default -> { }
        }
        return values;
    }
}
