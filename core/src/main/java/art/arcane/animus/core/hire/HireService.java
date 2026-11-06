package art.arcane.animus.core.hire;

import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.ShopCatalog;
import art.arcane.animus.core.text.RichText;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hireable NPCs on one server: a click on an NPC whose definition declares {@code animus:hire} opens the hiring window,
 * and {@link #tick()} ends expired contracts and puts guards back on hirers who came online or after a restart.
 * Clicks and ticks run on the owner that drives combat.
 */
public final class HireService<P, S> {
    private static final long CHECK_TICKS = 20;
    private final AnimusRuntime runtime;
    private final ScreenManager<P, S> screens;
    private final NpcRuntime<P, ?> living;
    private final HireDesk<P, S> desk;
    private final Consumer<String> log;
    private final Supplier<Instant> clock;
    private volatile Map<String, NpcDefinition> source;
    private volatile Map<UUID, HireTerms> terms = Map.of();
    private long ticks;

    public HireService(AnimusRuntime runtime, ScreenManager<P, S> screens, NpcRuntime<P, ?> living, Executor files, HireDesk.Guarding bridge, Consumer<String> log) throws IOException {
        this.runtime = Objects.requireNonNull(runtime);
        this.screens = Objects.requireNonNull(screens);
        this.living = Objects.requireNonNull(living);
        this.log = Objects.requireNonNull(log);
        this.clock = Instant::now;
        Path file = runtime.documents().resolve(ShopCatalog.SHOP_FOLDER).getParent().resolve("state").resolve("hire").resolve("contracts.json");
        desk = new HireDesk<>(new HireContracts(file, files, log), bridge, clock);
    }

    public String status() {
        return "Hire | hireable=" + bindings().size() + " | contracts=" + desk.contracts().all().size();
    }

    public List<String> contracts() { return desk.contracts().describe(clock.get()); }

    /** A validated click on the player's owner. True when it opened the hiring window. */
    public boolean interacted(P player, UUID npc, boolean attack, boolean sneaking) {
        if (attack) return false;
        HireTerms hire = bindings().get(npc);
        if (hire == null) return false;
        if (sneaking && screens.host().permitted(player, "animus.admin")) return false;
        Optional<NpcRuntime.ActiveNpc> active = living.active(npc);
        if (active.isEmpty()) return false;
        String name = active.get().name();
        return screens.open(player, Optional.of(npc), (ScreenManager<P, S>.Viewer viewer) -> Optional.of(desk.screen(viewer, npc, name, hire)));
    }

    /** Every owner tick; does its work once a second. */
    public void tick() {
        if (++ticks % CHECK_TICKS != 0) return;
        Instant now = clock.get();
        Map<UUID, HireTerms> available = bindings();
        for (HireContracts.Contract contract : desk.contracts().all()) {
            boolean hireable = available.containsKey(contract.npc());
            if (!hireable || !contract.active(now)) {
                if (!desk.expire(contract)) continue;
                if (!hireable) log.accept("Hire of " + contract.npc() + " by " + contract.playerName() + " ended: the NPC is no longer hireable");
                else notify(contract.player(), Palette.PINK_LIGHT + living.active(contract.npc()).map(NpcRuntime.ActiveNpc::name).map(RichText::plain).orElse("Your bodyguard")
                        + "'s contract has ended. &7Right-click it to hire again.");
                continue;
            }
            if (!living.players().online(contract.player()) || living.active(contract.npc()).isEmpty()) continue;
            try { desk.resume(contract); }
            catch (RuntimeException refused) { /* combat is not ready for this NPC yet; try again next second */ }
        }
    }

    private void notify(UUID player, String text) {
        living.players().deliver(player, (P online) -> screens.host().message(online, RichText.colorize(text)));
    }

    private Map<UUID, HireTerms> bindings() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != source) {
            Map<UUID, HireTerms> next = new HashMap<>();
            for (NpcDefinition definition : snapshot.values()) {
                try { HireTerms.of(definition).ifPresent((HireTerms value) -> next.put(definition.uuid(), value)); }
                catch (IllegalArgumentException invalid) { /* Definitions are validated on load; an invalid module binds nothing. */ }
            }
            terms = Map.copyOf(next);
            source = snapshot;
        }
        return terms;
    }
}
