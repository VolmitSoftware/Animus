package art.arcane.animus.bukkit.integration.mythic;

import art.arcane.animus.bukkit.recording.RecorderLook;
import art.arcane.animus.core.integration.NpcTemplates;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/** Wires MythicMobs support once the plugin is known to be enabled. The only caller is {@link MythicSupport}. */
final class MythicLink {
    private MythicLink() {}

    static AutoCloseable start(MythicSupport.Parts parts) {
        MythicBridge mythic = new MythicApiBridge();
        MythicHost host = new AnimusMythicHost(parts.plugin().getServer(), parts.runtime(), parts.states(), parts.owners());
        Function<String, Optional<NpcItem>> items = (String name) -> mythic.item(name).flatMap((ItemStack stack) -> RecorderLook.item(stack));
        MythicModule module = new MythicModule(mythic, host);
        MobSkills skills = new MobSkills(mythic, host, parts.log());
        List<Listener> listeners = List.of(skills, new MythicExtensions(host));
        return opened((List<AutoCloseable> handles) -> {
            handles.add(parts.runtime().templates().register(NpcTemplates.MYTHIC, new NpcTemplates.Source() {
                @Override public NpcTemplates.Template template(String name) {
                    MobSource mob = mythic.mob(name).orElseThrow(() -> new IllegalArgumentException("MythicMobs has no mob named " + name));
                    return MobLook.template(mob, items, parts.looks(), parts.runtime().bodyTypes());
                }
                @Override public List<String> names() { return mythic.mobTypes(); }
            }));
            handles.add(() -> listeners.forEach(HandlerList::unregisterAll));
            for (Listener listener : listeners) parts.plugin().getServer().getPluginManager().registerEvents(listener, parts.plugin());
            handles.add(parts.ticks().apply(skills::tick));
            // Last, as an integration stays bound once registered.
            parts.extensions().integration(module, module);
        });
    }

    /**
     * Runs {@code steps}, which add each handle they open. When a step fails, as a Mythic class that does not link
     * does, the handles already open close before the failure goes on, so no half of the support stays installed.
     */
    static AutoCloseable opened(Consumer<List<AutoCloseable>> steps) {
        List<AutoCloseable> handles = new ArrayList<>();
        AutoCloseable all = () -> { for (AutoCloseable handle : handles.reversed()) handle.close(); };
        try { steps.accept(handles); }
        catch (RuntimeException | LinkageError failure) {
            try { all.close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return all;
    }
}
