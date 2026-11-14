package art.arcane.animus.mod.toolkit;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.runtime.PersistedNpcService;
import art.arcane.animus.core.toolkit.Toolkit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

/** The Animus menu on Fabric, NeoForge and Forge: the same screens and wand as Bukkit, ticked on the server thread. */
public final class ModToolkit implements AutoCloseable {
    private final Toolkit<ServerPlayer, ItemStack> toolkit;
    private final AutoCloseable ticks;

    /** {@code every} runs a task every server tick until closed. */
    public ModToolkit(MinecraftServer server, AnimusRuntime runtime, ScreenManager<ServerPlayer, ItemStack> screens, PersistedNpcService<ServerPlayer, ItemStack> living,
                      Executor files, Function<ServerPlayer, Optional<BodySkin>> skins, Function<Runnable, AutoCloseable> every) {
        toolkit = Toolkit.install(new Toolkit.Parts<>(runtime::npcs, runtime::documents, screens, living, files, skins, Optional.of(living::relocate), System::nanoTime));
        ticks = every.apply(() -> {
            for (UUID id : toolkit.watching()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) toolkit.tick(player);
            }
        });
    }

    /** First in the interaction chain. */
    public boolean interacted(ServerPlayer player, UUID npc, boolean attack, boolean sneaking) { return toolkit.interacted(player, npc, attack, sneaking); }

    /**
     * Whether the click on an NPC is the wand's, asked before the NPC's behaviors, modules, quests, shop, GUI, riding and
     * the hit run: a wand click is for editing only, so true means none of them may.
     */
    public boolean claims(ServerPlayer player, UUID npc, boolean attack) { return toolkit.interacted(player, npc, attack, player.isShiftKeyDown()); }

    /**
     * A right-click on air or a block; the wand opens the menu, or while sneaking clears the selection. True when it did,
     * so a click on a block must not use the block too.
     */
    public boolean used(ServerPlayer player) { return toolkit.used(player, player.isShiftKeyDown()); }

    /**
     * An arm swing; one with the wand that reaches no block is the wand missing an NPC. Every swing of every player comes
     * here, so the raycast waits for the wand.
     */
    public void swung(ServerPlayer player) {
        if (ModWand.is(player.getMainHandItem()) && player.pick(player.blockInteractionRange(), 0f, false).getType() == HitResult.Type.MISS) toolkit.swung(player);
    }

    public void joined(ServerPlayer player) { toolkit.joined(player.getUUID()); }

    public void quit(ServerPlayer player) { toolkit.quit(player.getUUID()); }

    @Override public void close() {
        try { ticks.close(); }
        catch (Exception ignored) { /* the tick loop closes with the server */ }
        toolkit.close();
    }
}
