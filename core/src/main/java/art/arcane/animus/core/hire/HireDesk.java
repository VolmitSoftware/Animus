package art.arcane.animus.core.hire;

import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.Screen;
import art.arcane.animus.core.gui.ScreenFrame;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.InventoryTransaction;
import art.arcane.animus.core.inventory.MatchRule;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.RichText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hiring window and what its buttons do: pay, record the contract, and hand the NPC to the guard bridge. Payment
 * is all-or-nothing: items are taken in one inventory transaction and money after them, and a refusal from the guard
 * bridge puts both back. Only the hirer can renew or dismiss.
 */
public final class HireDesk<P, S> {
    /** Turns guarding on and off. A refusal throws with a message for the player. */
    public interface Guarding {
        void hire(UUID npc, UUID player);
        void dismiss(UUID npc);
        /** Whether the NPC is guarding this player right now. */
        boolean guards(UUID npc, UUID player);
    }
    public static final int GUARD = 4, COST = 11, TIME = 13, PERKS = 15, HIRE = 20, DISMISS = 22, CANCEL = 24;
    private static final String FILLER = "minecraft:gray_stained_glass_pane";

    private final HireContracts contracts;
    private final Guarding guarding;
    private final Supplier<Instant> clock;

    public HireDesk(HireContracts contracts, Guarding guarding, Supplier<Instant> clock) {
        this.contracts = Objects.requireNonNull(contracts);
        this.guarding = Objects.requireNonNull(guarding);
        this.clock = Objects.requireNonNull(clock);
    }

    public HireContracts contracts() { return contracts; }

    public Screen screen(ScreenManager<P, S>.Viewer viewer, UUID npc, String npcName, HireTerms terms) {
        return new Window(viewer, npc, npcName, terms);
    }

    /** Ends an expired or orphaned contract and releases the NPC; true when it ended now. */
    public boolean expire(HireContracts.Contract contract) {
        if (!contracts.end(contract)) return false;
        try { guarding.dismiss(contract.npc()); }
        catch (RuntimeException ignored) { /* the NPC is gone or no longer fights; nothing to release */ }
        return true;
    }

    /** Puts the guard back on its hirer when combat lost it, after a restart, respawn or rejoin. */
    public void resume(HireContracts.Contract contract) {
        if (!guarding.guards(contract.npc(), contract.player())) guarding.hire(contract.npc(), contract.player());
    }

    /** Hires or renews for the viewer. Returns the message to show. */
    String hire(ScreenManager<P, S>.Viewer viewer, UUID npc, String npcName, HireTerms terms) {
        ScreenHost<P, S> host = viewer.host();
        P player = viewer.player();
        UUID id = host.id(player);
        Instant now = clock.get();
        if (terms.permission().isPresent() && !host.permitted(player, terms.permission().get())) return "&cYou cannot hire " + npcName;
        Optional<HireContracts.Contract> current = contracts.of(npc).filter((HireContracts.Contract contract) -> contract.active(now));
        if (current.isPresent() && !current.get().player().equals(id)) return "&c" + RichText.plain(npcName) + " already works for " + current.get().playerName();
        Economy economy = host.economy();
        boolean money = terms.price().signum() > 0;
        if (money && economy.unavailable().isPresent()) return "&cHiring for money is unavailable: " + economy.unavailable().get();
        InventoryTransaction<S> payment = InventoryTransaction.begin(host.inventory(player), host.items());
        for (NpcItem item : terms.items()) {
            Optional<S> wanted = host.stack(new NpcItem(item.id(), 1, item.components(), Optional.empty()), Optional.empty(), List.of(), new ArrayList<>());
            if (wanted.isEmpty() || !payment.remove(wanted.get(), item.count(), MatchRule.STRICT)) {
                payment.rollback();
                return "&cYou need " + price(terms, economy) + "&c to hire " + RichText.plain(npcName);
            }
        }
        if (money && !economy.withdraw(id, terms.price())) {
            payment.rollback();
            return "&cYou cannot afford " + economy.format(terms.price());
        }
        boolean renewal = current.isPresent();
        if (!renewal) {
            try { guarding.hire(npc, id); }
            catch (RuntimeException refused) {
                payment.rollback();
                if (money) economy.deposit(id, terms.price());
                return "&c" + RichText.plain(npcName) + " cannot work right now: " + Objects.requireNonNullElse(refused.getMessage(), "refused");
            }
        }
        payment.commit();
        Instant from = renewal ? current.get().expires() : now;
        HireContracts.Contract contract = new HireContracts.Contract(npc, id, host.name(player), from.plus(terms.duration()));
        contracts.put(contract);
        return (renewal ? "&aRenewed " : "&aHired ") + npcName + "&a for " + duration(terms.duration()) + "&7, &f" + duration(Duration.between(now, contract.expires())) + " &7left";
    }

    /** Dismisses for the viewer, who must be the hirer. Returns the message to show. */
    String dismiss(ScreenManager<P, S>.Viewer viewer, UUID npc, String npcName) {
        UUID id = viewer.host().id(viewer.player());
        Optional<HireContracts.Contract> current = contracts.of(npc).filter((HireContracts.Contract contract) -> contract.active(clock.get()));
        if (current.isEmpty()) return "&7" + RichText.plain(npcName) + " is not hired";
        if (!current.get().player().equals(id)) return "&cOnly " + current.get().playerName() + " can dismiss " + RichText.plain(npcName);
        expire(current.get());
        return Palette.PINK_LIGHT + RichText.plain(npcName) + " is dismissed. &7Stay safe out there.";
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400, hours = seconds % 86_400 / 3600, minutes = seconds % 3600 / 60, rest = seconds % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return rest == 0 ? minutes + (minutes == 1 ? " minute" : " minutes") : minutes + "m " + rest + "s";
        return rest + "s";
    }

    static String price(HireTerms terms, Economy economy) {
        List<String> parts = new ArrayList<>();
        if (terms.price().signum() > 0) parts.add(Palette.PINK_LIGHT + economy.format(terms.price()));
        for (NpcItem item : terms.items()) parts.add("&f" + item.count() + "x " + itemName(item.id()));
        return parts.isEmpty() ? "&anothing" : String.join("&7, ", parts);
    }

    static String itemName(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(path.length());
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private final class Window implements Screen {
        private final ScreenManager<P, S>.Viewer viewer;
        private final UUID npc;
        private final String npcName;
        private final HireTerms terms;
        private final Map<Integer, Runnable> buttons = new HashMap<>();
        private long lastSecond = -1;

        Window(ScreenManager<P, S>.Viewer viewer, UUID npc, String npcName, HireTerms terms) {
            this.viewer = viewer; this.npc = npc; this.npcName = npcName; this.terms = terms;
        }

        @Override public ScreenFrame render() {
            Instant now = clock.get();
            UUID me = viewer.host().id(viewer.player());
            Optional<HireContracts.Contract> current = contracts.of(npc).filter((HireContracts.Contract contract) -> contract.active(now));
            boolean mine = current.isPresent() && current.get().player().equals(me);
            boolean taken = current.isPresent() && !mine;
            Economy economy = viewer.host().economy();
            Map<Integer, ScreenFrame.Item> items = new LinkedHashMap<>();
            buttons.clear();
            String status = mine ? "&eWorking for you" : taken ? "&cWorking for " + current.get().playerName() : "&aAvailable for hire";
            items.put(GUARD, item("minecraft:player_head", Palette.PINK + npcName, List.of("&7Status: " + status, "&7Bodyguard for hire")));
            List<String> cost = new ArrayList<>();
            cost.add("&7Price per hire:");
            if (terms.free()) cost.add(" &aFree");
            if (terms.price().signum() > 0) cost.add(" " + Palette.PINK_LIGHT + economy.format(terms.price()) + (economy.unavailable().isPresent() ? " &c(no economy)" : ""));
            for (NpcItem item : terms.items()) cost.add(" &f" + item.count() + "x " + itemName(item.id()));
            items.put(COST, item(terms.price().signum() > 0 ? "minecraft:gold_ingot" : terms.items().isEmpty() ? "minecraft:paper" : terms.items().getFirst().id(), Palette.PINK + "Cost", cost));
            List<String> time = new ArrayList<>(List.of("&7Each hire lasts &f" + duration(terms.duration())));
            if (mine) time.add("&7Time left: &f" + duration(Duration.between(now, current.get().expires())));
            items.put(TIME, item("minecraft:clock", Palette.PINK + "Duration", time));
            items.put(PERKS, item("minecraft:shield", "&aWhat you get", List.of("&7Follows you wherever you go", "&7Fights whatever attacks you", "&7Only you can dismiss it")));
            if (taken) items.put(HIRE, item("minecraft:gray_concrete", "&7Unavailable", List.of("&7Already hired by " + current.get().playerName())));
            else button(items, HIRE, "minecraft:lime_concrete", mine ? "&a&lRenew" : "&a&lHire", List.of("&7Pay " + price(terms, economy), mine ? "&7Adds " + duration(terms.duration()) : "&7Hire for " + duration(terms.duration())),
                    () -> finish(hire(viewer, npc, npcName, terms)));
            if (mine) button(items, DISMISS, "minecraft:red_concrete", "&c&lDismiss", List.of("&7End the contract now", "&7No refund"), () -> finish(dismiss(viewer, npc, npcName)));
            else items.put(DISMISS, item("minecraft:gray_dye", "&7Dismiss", List.of("&7Only the hirer can dismiss")));
            button(items, CANCEL, "minecraft:barrier", "&cCancel", List.of("&7Close this window"), viewer::close);
            for (int slot = 0; slot < 27; slot++) items.putIfAbsent(slot, new ScreenFrame.Item(new NpcItem(FILLER, 1, Optional.empty(), Optional.empty()), Optional.of(" "), List.of(), Optional.empty()));
            return new ScreenFrame(RichText.colorize("&8Hire " + RichText.plain(npcName)), "9x3", 9, 3, items);
        }

        @Override public void click(int slot, GuiModel.Click click) {
            Runnable action = buttons.get(slot);
            if (action != null) action.run();
        }

        @Override public boolean tick(long tick) {
            long second = clock.get().getEpochSecond();
            if (second == lastSecond) return false;
            lastSecond = second;
            return true;
        }

        private void finish(String message) {
            viewer.host().message(viewer.player(), RichText.colorize(message));
            viewer.close();
        }

        private void button(Map<Integer, ScreenFrame.Item> items, int slot, String icon, String name, List<String> lore, Runnable action) {
            items.put(slot, item(icon, name, lore));
            buttons.put(slot, action);
        }

        private ScreenFrame.Item item(String icon, String name, List<String> lore) {
            List<String> coloured = new ArrayList<>(lore.size());
            for (String line : lore) coloured.add(RichText.colorize(line));
            return new ScreenFrame.Item(new NpcItem(icon, 1, Optional.empty(), Optional.empty()), Optional.of(RichText.colorize(name)), coloured, Optional.empty());
        }
    }

}
