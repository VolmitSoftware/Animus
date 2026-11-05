package art.arcane.animus.core.hire;

import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.gui.MockScreenHost;
import art.arcane.animus.core.gui.MockScreenHost.Player;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.inventory.MockInventory.Stack;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HireDeskTest {
    static final UUID GUARD = UUID.fromString("3fae0bdf-8635-501b-bff2-ea2001d65a89");
    @TempDir Path folder;
    Instant now = Instant.parse("2026-11-04T12:00:00Z");
    final MockScreenHost host = new MockScreenHost();
    final ScreenManager<Player, Stack> manager = new ScreenManager<>(host, (UUID npc) -> true, (String warning) -> { throw new AssertionError(warning); });
    final Player alex = new Player("Alex"), sam = new Player("Sam");
    final List<String> calls = new ArrayList<>();
    final Map<UUID, UUID> guarded = new HashMap<>();
    boolean refuse;
    final HireDesk.Guarding guarding = new HireDesk.Guarding() {
        @Override public void hire(UUID npc, UUID player) {
            if (refuse) throw new IllegalStateException("combat is off");
            calls.add("hire " + player); guarded.put(npc, player);
        }
        @Override public void dismiss(UUID npc) { calls.add("dismiss"); guarded.remove(npc); }
        @Override public boolean guards(UUID npc, UUID player) { return player.equals(guarded.get(npc)); }
    };

    HireContracts contracts() { return new HireContracts(folder.resolve("contracts.json"), Runnable::run, (String line) -> fail(line)); }
    HireDesk<Player, Stack> desk(HireContracts contracts) { return new HireDesk<>(contracts, guarding, () -> now); }
    static HireTerms terms(String module) {
        return HireTerms.parse(JsonParser.parseString("{\"modules\":{\"animus:hire\":" + module + "}}").getAsJsonObject()).orElseThrow();
    }
    void open(HireDesk<Player, Stack> desk, Player player, HireTerms terms) {
        assertTrue(manager.open(player, Optional.of(GUARD), (ScreenManager<Player, Stack>.Viewer viewer) -> Optional.of(desk.screen(viewer, GUARD, "&6Bodyguard", terms))));
    }
    void click(Player player, int slot) { manager.click(player, player.window, slot, Click.LEFT); }

    @Test void termsParseAndRejectBadFields() {
        HireTerms parsed = terms("{\"price\": 25, \"items\": [{\"id\": \"minecraft:emerald\", \"count\": 5}], \"durationMinutes\": 30, \"permission\": \"animus.hire\"}");
        assertEquals(new BigDecimal("25"), parsed.price());
        assertEquals(Duration.ofMinutes(30), parsed.duration());
        assertEquals("minecraft:emerald", parsed.items().getFirst().id());
        assertEquals(Duration.ofMinutes(60), terms("{}").duration());
        assertTrue(terms("{}").free());
        assertTrue(HireTerms.parse(JsonParser.parseString("{\"modules\":{\"animus:hire\":{\"enabled\":false}}}").getAsJsonObject()).isEmpty());
        for (String bad : List.of("{\"price\": -1}", "{\"durationMinutes\": 0}", "{\"items\": [{\"id\": \"BAD\"}]}", "{\"cost\": 1}", "{\"permission\": \"Bad Node\"}"))
            assertThrows(IllegalArgumentException.class, () -> terms(bad), bad);
    }

    @Test void hiringTakesTheItemsRecordsTheContractAndGuardsTheHirer() {
        HireContracts contracts = contracts();
        HireDesk<Player, Stack> desk = desk(contracts);
        HireTerms hire = terms("{\"items\": [{\"id\": \"minecraft:emerald\", \"count\": 5}], \"durationMinutes\": 30}");
        alex.inventory.with(0, Stack.of("minecraft:emerald", 3));
        open(desk, alex, hire);
        assertEquals("Hire Bodyguard", RichText.plain(alex.frame.title()));
        assertEquals("Hire", RichText.plain(alex.frame.items().get(HireDesk.HIRE).name().orElseThrow()));
        click(alex, HireDesk.HIRE);
        assertTrue(alex.messages.getLast().startsWith("You need"), alex.messages.toString());
        assertEquals(3, alex.inventory.total("minecraft:emerald"), "nothing taken when short");
        assertTrue(contracts.of(GUARD).isEmpty());

        alex.inventory.with(1, Stack.of("minecraft:emerald", 4));
        open(desk, alex, hire);
        click(alex, HireDesk.HIRE);
        assertEquals(2, alex.inventory.total("minecraft:emerald"));
        assertEquals(List.of("hire " + alex.id), calls);
        assertEquals(now.plus(Duration.ofMinutes(30)), contracts.of(GUARD).orElseThrow().expires());
        assertTrue(alex.messages.getLast().startsWith("Hired Bodyguard for 30 minutes"), alex.messages.getLast());

        HireContracts reloaded = contracts();
        assertEquals(alex.id, reloaded.of(GUARD).orElseThrow().player(), "the contract survives a restart");
    }

    @Test void onlyTheHirerRenewsOrDismissesAndOthersAreTurnedAway() {
        HireContracts contracts = contracts();
        HireDesk<Player, Stack> desk = desk(contracts);
        HireTerms hire = terms("{\"durationMinutes\": 10}");
        open(desk, alex, hire);
        click(alex, HireDesk.HIRE);
        open(desk, sam, hire);
        assertEquals("Unavailable", RichText.plain(sam.frame.items().get(HireDesk.HIRE).name().orElseThrow()));
        assertEquals("minecraft:gray_dye", sam.frame.items().get(HireDesk.DISMISS).item().id());
        click(sam, HireDesk.DISMISS);
        assertEquals(alex.id, contracts.of(GUARD).orElseThrow().player());

        now = now.plus(Duration.ofMinutes(4));
        open(desk, alex, hire);
        assertEquals("Renew", RichText.plain(alex.frame.items().get(HireDesk.HIRE).name().orElseThrow()));
        click(alex, HireDesk.HIRE);
        assertEquals(Instant.parse("2026-11-04T12:20:00Z"), contracts.of(GUARD).orElseThrow().expires(), "a renewal extends the current end");
        assertEquals(List.of("hire " + alex.id), calls, "a renewal does not re-hire");

        open(desk, alex, hire);
        click(alex, HireDesk.DISMISS);
        assertTrue(contracts.of(GUARD).isEmpty());
        assertEquals("dismiss", calls.getLast());
        open(desk, sam, hire);
        click(sam, HireDesk.HIRE);
        assertEquals(sam.id, contracts.of(GUARD).orElseThrow().player(), "free again after the dismissal");
    }

    @Test void moneyFailsClosedWithoutAnEconomyAndARefusedGuardRefundsEverything() {
        HireContracts contracts = contracts();
        HireDesk<Player, Stack> desk = desk(contracts);
        HireTerms paid = terms("{\"price\": 50, \"items\": [{\"id\": \"minecraft:emerald\", \"count\": 1}]}");
        alex.inventory.with(0, Stack.of("minecraft:emerald", 1));
        open(desk, alex, paid);
        click(alex, HireDesk.HIRE);
        assertTrue(alex.messages.getLast().startsWith("Hiring for money is unavailable"), alex.messages.getLast());
        assertEquals(1, alex.inventory.total("minecraft:emerald"));

        Map<UUID, BigDecimal> balances = new HashMap<>(Map.of(alex.id, new BigDecimal("80")));
        host.economy = new Economy() {
            @Override public Optional<String> unavailable() { return Optional.empty(); }
            @Override public boolean has(UUID player, BigDecimal amount) { return balances.getOrDefault(player, BigDecimal.ZERO).compareTo(amount) >= 0; }
            @Override public boolean withdraw(UUID player, BigDecimal amount) {
                if (!has(player, amount)) return false;
                balances.put(player, balances.get(player).subtract(amount));
                return true;
            }
            @Override public boolean deposit(UUID player, BigDecimal amount) { balances.merge(player, amount, BigDecimal::add); return true; }
            @Override public String format(BigDecimal amount) { return "$" + amount.toPlainString(); }
        };
        refuse = true;
        open(desk, alex, paid);
        click(alex, HireDesk.HIRE);
        assertTrue(alex.messages.getLast().contains("cannot work right now"), alex.messages.getLast());
        assertEquals(0, new BigDecimal("80").compareTo(balances.get(alex.id)));
        assertEquals(1, alex.inventory.total("minecraft:emerald"));
        assertTrue(contracts.of(GUARD).isEmpty());

        refuse = false;
        open(desk, alex, paid);
        click(alex, HireDesk.HIRE);
        assertEquals(0, new BigDecimal("30").compareTo(balances.get(alex.id)));
        assertEquals(0, alex.inventory.total("minecraft:emerald"));
    }

    @Test void expiryEndsTheContractAndResumeRestoresALostGuard() throws Exception {
        HireContracts contracts = contracts();
        HireDesk<Player, Stack> desk = desk(contracts);
        open(desk, alex, terms("{\"durationMinutes\": 1}"));
        click(alex, HireDesk.HIRE);
        HireContracts.Contract contract = contracts.of(GUARD).orElseThrow();
        guarded.clear();
        desk.resume(contract);
        assertEquals(alex.id, guarded.get(GUARD));
        desk.resume(contract);
        assertEquals(2, calls.stream().filter((String call) -> call.startsWith("hire")).count(), "resume skips a guard that still guards");
        now = now.plus(Duration.ofMinutes(2));
        assertFalse(contract.active(now));
        assertTrue(desk.expire(contract));
        assertFalse(desk.expire(contract));
        assertTrue(Files.readString(folder.resolve("contracts.json")).contains("\"contracts\": []"));
    }
}
