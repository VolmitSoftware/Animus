package art.arcane.animus.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.CommandSound;
import art.arcane.animus.core.gui.ScreenHost;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What a mod window item carries: an owner's head, upright text, and menu sounds this version knows. */
class ModScreensTest {
    @BeforeAll static void bootstrap() { AnimusMenuTest.bootstrap(); }

    final GameProfile alex = new GameProfile(UUID.nameUUIDFromBytes("Alex".getBytes(StandardCharsets.UTF_8)), "Alex");

    Optional<GameProfile> online(String name) { return name.equalsIgnoreCase("Alex") ? Optional.of(alex) : Optional.empty(); }

    static ItemStack head() { return AnimusMenuTest.stack(Items.PLAYER_HEAD); }

    static boolean showsProfile(ItemStack stack) { return stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).shows(DataComponents.PROFILE); }

    @Test void anOnlinePlayersHeadWearsTheirOwnProfile() {
        ItemStack stack = head();
        ModScreens.owned(stack, "alex", this::online);
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        assertInstanceOf(ResolvableProfile.Static.class, profile);
        assertEquals(alex, profile.partialProfile());
        assertFalse(showsProfile(stack));
    }

    @Test void anyOtherAccountNameIsResolvedByTheClientWithoutATooltipLine() {
        ItemStack stack = head();
        ModScreens.owned(stack, "Notch", this::online);
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        assertInstanceOf(ResolvableProfile.Dynamic.class, profile);
        assertEquals(Optional.of("Notch"), profile.name());
        assertFalse(showsProfile(stack), "no grey Dynamic line under the name");
    }

    @Test void namesNoAccountCanHaveOtherItemsAndSkinHeadsStayAsTheyAre() {
        for (String name : List.of("", "%viewer%", "two words", "a_name_far_too_long")) {
            ItemStack stack = head();
            ModScreens.owned(stack, name, this::online);
            assertFalse(stack.has(DataComponents.PROFILE), name);
            assertTrue(showsProfile(stack), name);
        }
        ItemStack stone = AnimusMenuTest.stack(Items.STONE);
        ModScreens.owned(stone, "Notch", this::online);
        assertFalse(stone.has(DataComponents.PROFILE));
        ItemStack skin = head();
        ResolvableProfile worn = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Skin"));
        skin.set(DataComponents.PROFILE, worn);
        ModScreens.owned(skin, "Notch", this::online);
        assertEquals(worn, skin.get(DataComponents.PROFILE));
    }

    @Test void aWandThatFindsNoFreeSlotIsDroppedNeverDestroyed() {
        ItemStack wand = AnimusMenuTest.stack(Items.STICK);
        List<ItemStack> dropped = new ArrayList<>();
        // Vanilla add answers true for a creative player with no room, after setting the stack's count to nothing.
        java.util.function.Predicate<ItemStack> creative = (ItemStack stack) -> { stack.setCount(0); return true; };
        assertEquals(ScreenHost.WandGift.DROPPED, ModScreens.give(wand, false, creative, dropped::add));
        assertEquals(List.of(wand), dropped);
        assertEquals(1, wand.getCount(), "the dropped wand is whole");
        assertEquals(ScreenHost.WandGift.GIVEN, ModScreens.give(AnimusMenuTest.stack(Items.STICK), true, (ItemStack stack) -> true, dropped::add));
        assertEquals(1, dropped.size());
    }

    @Test void namesAndLoreAreUpright() {
        ItemStack stack = AnimusMenuTest.stack(Items.PAPER);
        ModScreens.dress(stack, Optional.of("&dWand"), List.of("&7Grey line", "plain"));
        upright(stack.get(DataComponents.CUSTOM_NAME));
        ItemLore lore = stack.get(DataComponents.LORE);
        assertEquals(2, lore.lines().size());
        for (Component line : lore.lines()) upright(line);
    }

    /** Every run says italic false outright; a run that leaves it unset is drawn italic in a tooltip. */
    static void upright(Component line) {
        List<Component> runs = new ArrayList<>(line.getSiblings());
        assertFalse(runs.isEmpty(), line.getString());
        for (Component run : runs) assertEquals(run.getStyle().withItalic(false), run.getStyle(), "'" + run.getString() + "' is upright");
    }

    @Test void theMenuClickAndRefusalSoundsExistInThisVersion() {
        List<String> sounds = new ArrayList<>(List.of("minecraft:ui.button.click"));
        for (CommandSound.Tone tone : CommandSound.FAILURE.tones(new Random(1))) sounds.add(tone.sound());
        for (String sound : sounds) assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse(sound)), sound);
    }
}
