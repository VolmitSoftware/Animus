package art.arcane.animus.bukkit.integration.notquests;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import art.arcane.animus.core.text.RichText;
import com.notquests.Main;
import com.notquests.core.NotQuestsPlugin;
import com.notquests.core.npc.NQNPCID;
import com.notquests.core.npc.NpcAttachments;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Hands right clicks on Animus NPCs to NotQuests as its Citizens and FancyNpcs hooks do, so quest givers, attached
 * conversations, Talk to NPC objectives and the NPC selection tool work. Every NPC is {@code animus:<id>} to NotQuests,
 * which is what the selection tool attaches. An NPC imported from Citizens also answers as {@code citizens:<id>} first,
 * so NotQuests data written for Citizens keeps working unchanged. NotQuests publishes no NPC API; this calls the same core
 * entry point its own hooks call. Load only when NotQuests is installed.
 */
public final class NotQuestsBridge implements Listener {
    static final String CITIZENS = "citizens", ANIMUS = "animus";
    private final Plugin animus;
    private final QuestNpcHost host;
    private final NamespacedKey selector;

    /** Listens for Animus NPC clicks and deaths on NotQuests's behalf. */
    public static void register(Plugin animus, QuestNpcHost host) {
        animus.getServer().getPluginManager().registerEvents(new NotQuestsBridge(animus, host), animus);
        animus.getLogger().info("NotQuests: Animus NPCs answer NotQuests clicks and NPC death triggers");
    }

    private NotQuestsBridge(Plugin animus, QuestNpcHost host) {
        this.animus = animus;
        this.host = host;
        this.selector = new NamespacedKey(Main.getInstance(), "notquests-nqnpc-selector-with-action");
    }

    /** NotQuests's NPC type and id for an Animus NPC. */
    record Identity(String type, NQNPCID id) {}

    /** The identities a click tries in order: the Citizens one of an imported NPC, then the Animus one. */
    static List<Identity> identities(NpcInfo npc, OptionalInt citizensId) {
        Identity animus = new Identity(ANIMUS, NQNPCID.fromString(npc.key()));
        return citizensId.isPresent() ? List.of(new Identity(CITIZENS, NQNPCID.fromInteger(citizensId.getAsInt())), animus) : List.of(animus);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void clicked(VirtualNpcInteractionEvent event) {
        if (event.isPreview() || event.getAction() != VirtualNpcInteractionEvent.Action.INTERACT) return;
        Optional<NpcInfo> npc = host.npcs().byUuid(event.getNpcId());
        if (npc.isEmpty()) return;
        Player player = event.getViewer();
        String name = RichText.plain(npc.get().name());
        int selection = selection(player);
        List<Identity> identities = identities(npc.get(), host.citizensId(npc.get().uuid()));
        if (selection >= 0) identities = List.of(identities.getLast());
        for (Identity identity : identities) {
            NpcAttachments.ClickEffects effects = core().nativeNpcClicked(player.getUniqueId().toString(), identity.type(), identity.id(), name, selection, null, null, null);
            if (effects.selectionHandled() || effects.previewShown() || effects.conversationStarted()) { event.setCancelled(true); return; }
            if (effects.handled() || effects.objectiveHandled()) return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(VirtualNpcDeathEvent event) {
        OptionalInt citizensId = host.citizensId(event.getNpcId());
        if (citizensId.isEmpty()) return;
        String id = String.valueOf(citizensId.getAsInt());
        Runnable died = () -> core().npcDied(id, event.getPosition().loadedWorld().map(World::getName).orElse(""));
        if (Bukkit.isPrimaryThread()) died.run(); else Bukkit.getScheduler().runTask(animus, died);
    }

    private int selection(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta meta = held.getItemMeta();
        if (meta == null) return -1;
        Integer id = meta.getPersistentDataContainer().get(selector, PersistentDataType.INTEGER);
        return id == null ? -1 : id;
    }

    private static NotQuestsPlugin core() { return Main.getInstance().getNotQuests().getCorePlugin(); }
}
