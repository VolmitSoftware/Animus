package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Interaction subscriptions, integration hooks and converter registration through the module host. */
class ModuleIntegrationTest {
    private static final DefinitionGateway DEFINITIONS = new DefinitionGateway() {
        public DefinitionService scoped(java.util.function.BooleanSupplier available) { return this; }
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc) { return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.NOT_FOUND, "missing", "Missing")); }
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit) { return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE, "offline", "Offline")); }
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit) { return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE, "offline", "Offline")); }
    };
    private static final NpcHandle NPC = new NpcHandle(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final Integrations.Descriptor HOOK = new Integrations.Descriptor("Example hook", Set.of(Platform.PAPER));

    private static TrustedModule module(String id, Consumer<ModuleContext> enable) {
        ModuleManifest manifest = new ModuleManifest(id, new Version(1, 0, 0), new ApiRange(1, 1), Set.of(Platform.PAPER), List.of());
        return new TrustedModule() {
            public ModuleManifest manifest() { return manifest; }
            public void enable(ModuleContext context) { enable.accept(context); }
            public void disable() {}
        };
    }
    private static ModuleHost host(int slots, Duration deadline) { return new ModuleHost(Platform.PAPER, DEFINITIONS, slots, deadline); }
    private static void eventually(java.util.function.BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(check.getAsBoolean());
    }
    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }

    @Test void subscribersReceiveAcceptedInteractionsWithHandleViewerActionAndPreview() throws Exception {
        List<InteractionNotice> received = new CopyOnWriteArrayList<>();
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            assertEquals(Outcome.Status.OK, host.install(module("listener", (ModuleContext c) -> c.onInteraction((InteractionNotice notice, Capabilities.Cancellation cancel) -> received.add(notice)))).status());
            host.interacted(NPC, PLAYER, InteractionNotice.Action.ATTACK, 2.5, false);
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1.0, true);
            eventually(() -> received.size() == 2);
            received.sort(Comparator.comparingLong(InteractionNotice::sequence));
            assertEquals(new InteractionNotice(NPC, PLAYER, InteractionNotice.Action.ATTACK, 2.5, false, 0), received.get(0));
            assertEquals(new InteractionNotice(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1.0, true, 1), received.get(1));
        }
    }

    @Test void aThrowingListenerIsIsolatedFromOtherModules() throws Exception {
        AtomicInteger healthy = new AtomicInteger();
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.install(module("broken", (ModuleContext c) -> c.onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> { throw new IllegalStateException("broken"); })));
            host.install(module("healthy", (ModuleContext c) -> c.onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> healthy.incrementAndGet())));
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            eventually(() -> healthy.get() == 2 && host.outstandingInvocations() == 0);
            assertTrue(host.modules().stream().allMatch((ModuleHost.ModuleStatus status) -> status.state() == ModuleHost.State.ACTIVE));
        }
    }

    @Test void aListenerPastTheDeadlineSuspendsItsModuleAndStopsDelivery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (ModuleHost host = host(8, Duration.ofMillis(50))) {
            host.install(module("slow", (ModuleContext c) -> c.onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> { calls.incrementAndGet(); release.await(3, TimeUnit.SECONDS); })));
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            eventually(() -> host.modules().getFirst().state() == ModuleHost.State.SUSPENDED);
            release.countDown();
            eventually(() -> host.outstandingInvocations() == 0);
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            Thread.sleep(50);
            assertEquals(1, calls.get());
        }
    }

    @Test void noticesBeyondTheInvocationBoundAreDroppedAndCounted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ModuleHost host = host(1, Duration.ofSeconds(2))) {
            host.install(module("busy", (ModuleContext c) -> c.onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); })));
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            await(entered);
            host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
            assertEquals(1, host.droppedNotices());
            release.countDown();
        }
    }

    @Test void closingTheHostCancelsRunningListeners() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean sawCancellation = new AtomicBoolean();
        ModuleHost host = host(8, Duration.ofSeconds(2));
        host.install(module("cancel", (ModuleContext c) -> c.onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> {
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!t.cancelled() && System.nanoTime() < until) Thread.sleep(2);
            sawCancellation.set(t.cancelled());
        })));
        host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
        await(entered);
        host.close();
        eventually(sawCancellation::get);
        host.interacted(NPC, PLAYER, InteractionNotice.Action.INTERACT, 1, false);
        assertEquals(0, host.droppedNotices());
    }

    @Test void subscriptionsAndHooksCloseWhenEnableReturns() throws Exception {
        AtomicReference<ModuleContext> context = new AtomicReference<>();
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.install(module("late", context::set));
            assertThrows(IllegalStateException.class, () -> context.get().onInteraction((InteractionNotice n, Capabilities.Cancellation t) -> {}));
            assertThrows(IllegalStateException.class, () -> context.get().integrations().protection(HOOK, (Integrations.ProtectionRequest r) -> Integrations.Decision.ALLOW));
        }
    }

    private static final ProtectionAdapter.Request ATTACK = new ProtectionAdapter.Request(ProtectionAdapter.Action.NPC_ATTACK,
            new TargetRef(TargetKind.NPC, NPC.uuid()), new WorldPosition("world", new Vec3(0, 64, 0)),
            new TargetRef(TargetKind.PLAYER, PLAYER), new WorldPosition("world", new Vec3(1, 64, 0)), Set.of(TargetCategory.ANIMALS));
    private static final BuildProtection.Request BREAK = new BuildProtection.Request(NPC.uuid(), Optional.of(PLAYER), "world", new BlockPos(3, 70, -2), BuildProtection.Action.BREAK);

    @Test void protectionHooksCombineWithThePlatformAndSeeTheRequest() throws Exception {
        List<Integrations.ProtectionRequest> seen = new CopyOnWriteArrayList<>();
        ModuleHooks hooks = new ModuleHooks();
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.connectHooks(hooks);
            ProtectionAdapter combat = hooks.protection(ProtectionAdapter.NONE);
            BuildProtection builds = hooks.buildProtection(BuildProtection.NONE);
            assertEquals(ProtectionAdapter.Decision.ALLOW, combat.decide(ATTACK), "No hook leaves the platform answer");
            host.install(module("guard", (ModuleContext c) -> c.integrations().protection(HOOK, (Integrations.ProtectionRequest request) -> {
                seen.add(request);
                return request.action() == Integrations.ProtectionAction.BLOCK_BREAK ? Integrations.Decision.DENY : Integrations.Decision.ALLOW;
            })));
            assertEquals(ProtectionAdapter.Decision.ALLOW, combat.decide(ATTACK));
            assertEquals(BuildProtection.Decision.DENY, builds.decide(BREAK));
            assertEquals(ProtectionAdapter.Decision.DENY, hooks.protection((ProtectionAdapter.Request r) -> ProtectionAdapter.Decision.DENY).decide(ATTACK));
            Integrations.ProtectionRequest attack = seen.get(0), block = seen.get(1);
            assertEquals(new Integrations.Party(Integrations.PartyKind.PLAYER, PLAYER), attack.target().orElseThrow());
            assertEquals(new Integrations.Position("world", 1, 64, 0), attack.targetPosition());
            assertEquals(Set.of("animals"), attack.targetCategories());
            assertEquals(new Integrations.Position("world", 3, 70, -2), block.targetPosition());
            assertEquals(Optional.of(PLAYER), block.player());
            assertTrue(block.target().isEmpty());
        }
    }

    @Test void protectionFailsClosedOnThrowsNullAndUnavailableModules() throws Exception {
        AtomicReference<Integrations.Decision> answer = new AtomicReference<>(Integrations.Decision.ALLOW);
        AtomicBoolean fail = new AtomicBoolean();
        ModuleHooks hooks = new ModuleHooks();
        ProtectionAdapter combat = hooks.protection(ProtectionAdapter.NONE);
        ModuleHost host = host(8, Duration.ofSeconds(2));
        host.connectHooks(hooks);
        host.install(module("guard", (ModuleContext c) -> c.integrations().protection(HOOK, (Integrations.ProtectionRequest r) -> {
            if (fail.get()) throw new IllegalStateException("protection offline");
            return answer.get();
        })));
        assertEquals(ProtectionAdapter.Decision.ALLOW, combat.decide(ATTACK));
        fail.set(true);
        assertEquals(ProtectionAdapter.Decision.DENY, combat.decide(ATTACK));
        fail.set(false); answer.set(null);
        assertEquals(ProtectionAdapter.Decision.DENY, combat.decide(ATTACK));
        assertEquals(2, hooks.failures());
        answer.set(Integrations.Decision.ALLOW);
        host.close();
        assertEquals(ProtectionAdapter.Decision.DENY, combat.decide(ATTACK), "A provider whose module stopped denies");
    }

    @Test void oneSlowHookCallDoesNotSuspendItsModule() throws Exception {
        ModuleHooks hooks = new ModuleHooks();
        try (ModuleHost host = host(8, Duration.ofMillis(5))) {
            host.connectHooks(hooks);
            AtomicBoolean slow = new AtomicBoolean(true);
            host.install(module("sluggish", (ModuleContext c) -> c.integrations().protection(HOOK, (Integrations.ProtectionRequest r) -> {
                if (slow.getAndSet(false)) Thread.sleep(20);
                return Integrations.Decision.ALLOW;
            })));
            assertEquals(ProtectionAdapter.Decision.ALLOW, hooks.protection(ProtectionAdapter.NONE).decide(ATTACK));
            assertEquals(ModuleHost.State.ACTIVE, host.modules().getFirst().state(), "A single lag spike is not a broken module");
            assertEquals(ProtectionAdapter.Decision.ALLOW, hooks.protection(ProtectionAdapter.NONE).decide(ATTACK));
        }
    }

    @Test void repeatedSlowHookCallsSuspendUntilAnAdministratorResumes() throws Exception {
        ModuleHooks hooks = new ModuleHooks();
        List<String> reports = new CopyOnWriteArrayList<>();
        try (ModuleHost host = host(8, Duration.ofMillis(5))) {
            host.connectHooks(hooks);
            host.connectReports(reports::add);
            AtomicBoolean slow = new AtomicBoolean(true);
            host.install(module("sluggish", (ModuleContext c) -> c.integrations().protection(HOOK, (Integrations.ProtectionRequest r) -> {
                if (slow.get()) Thread.sleep(20);
                return Integrations.Decision.ALLOW;
            })));
            ProtectionAdapter combat = hooks.protection(ProtectionAdapter.NONE);
            for (int call = 1; call < OverrunPolicy.LIMIT; call++) combat.decide(ATTACK);
            assertEquals(ModuleHost.State.ACTIVE, host.modules().getFirst().state());
            combat.decide(ATTACK);
            ModuleHost.ModuleStatus suspended = host.modules().getFirst();
            assertEquals(ModuleHost.State.SUSPENDED, suspended.state());
            assertTrue(suspended.reason().startsWith("integration-deadline-exceeded: protection"), suspended.reason());
            assertEquals(ProtectionAdapter.Decision.DENY, combat.decide(ATTACK), "A suspended provider still fails closed");
            assertEquals(1, reports.stream().filter((String line) -> line.contains("sluggish") && line.contains("suspended") && line.contains("/animus modules resume sluggish")).count(), reports.toString());
            slow.set(false);
            assertEquals(Outcome.Status.OK, host.resume("sluggish").status());
            assertEquals(ModuleHost.State.ACTIVE, host.modules().getFirst().state());
            assertEquals(ProtectionAdapter.Decision.ALLOW, combat.decide(ATTACK));
            assertTrue(reports.stream().anyMatch((String line) -> line.contains("sluggish") && line.contains("resumed")), reports.toString());
            assertEquals(Outcome.Status.INVALID, host.resume("sluggish").status(), "Only a suspended module resumes");
            assertEquals(Outcome.Status.NOT_FOUND, host.resume("missing").status());
        }
    }

    @Test void aThrowOnDoorUseAllowsAsBeforeTheActionExistedAndWarnsOnce() throws Exception {
        List<String> warnings = new CopyOnWriteArrayList<>();
        ModuleHooks hooks = new ModuleHooks(warnings::add);
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.connectHooks(hooks);
            // A module built against 1.1 with an exhaustive switch and no default branch.
            host.install(module("legacy", (ModuleContext c) -> c.integrations().protection(HOOK, (Integrations.ProtectionRequest request) -> {
                if (request.action() == Integrations.ProtectionAction.BLOCK_USE) throw new MatchException(null, null);
                if (request.action() == Integrations.ProtectionAction.BLOCK_BREAK) throw new IllegalStateException("region lookup failed");
                return Integrations.Decision.ALLOW;
            })));
            BuildProtection builds = hooks.buildProtection(BuildProtection.NONE);
            BuildProtection.Request door = new BuildProtection.Request(NPC.uuid(), Optional.empty(), "world", new BlockPos(1, 64, 1), BuildProtection.Action.USE);
            assertEquals(BuildProtection.Decision.ALLOW, builds.decide(door));
            assertEquals(BuildProtection.Decision.ALLOW, builds.decide(door));
            assertEquals(BuildProtection.Decision.DENY, builds.decide(BREAK), "Actions the module knows still fail closed");
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("legacy") && warnings.getFirst().contains("BLOCK_USE"), warnings.toString());
            assertEquals(BuildProtection.Decision.DENY, hooks.buildProtection((BuildProtection.Request r) -> BuildProtection.Decision.DENY).decide(door), "The platform still decides first");
        }
    }

    private static final class Wallet implements Integrations.Economy {
        final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
        public boolean has(UUID player, BigDecimal amount) { return balances.getOrDefault(player, BigDecimal.ZERO).compareTo(amount) >= 0; }
        public boolean withdraw(UUID player, BigDecimal amount) { if (!has(player, amount)) return false; balances.merge(player, amount.negate(), BigDecimal::add); return true; }
        public boolean deposit(UUID player, BigDecimal amount) { balances.merge(player, amount, BigDecimal::add); return true; }
        public String format(BigDecimal amount) { return amount.toPlainString() + " shells"; }
    }

    @Test void moduleEconomyServesOnlyWhereThePlatformHasNone() throws Exception {
        Wallet wallet = new Wallet();
        wallet.balances.put(PLAYER, new BigDecimal("10"));
        ModuleHooks hooks = new ModuleHooks();
        Economy absent = hooks.economy(Economy.none("no provider"));
        Economy platform = hooks.economy(new Economy() {
            public Optional<String> unavailable() { return Optional.empty(); }
            public boolean has(UUID player, BigDecimal amount) { return false; }
            public boolean withdraw(UUID player, BigDecimal amount) { return false; }
            public boolean deposit(UUID player, BigDecimal amount) { return false; }
            public String format(BigDecimal amount) { return "platform"; }
        });
        assertEquals(Optional.of("no provider"), absent.unavailable());
        ModuleHost host = host(8, Duration.ofSeconds(2));
        host.connectHooks(hooks);
        host.install(module("bank", (ModuleContext c) -> c.integrations().economy(HOOK, wallet)));
        assertEquals(Optional.empty(), absent.unavailable());
        assertTrue(absent.withdraw(PLAYER, new BigDecimal("4")));
        assertFalse(absent.has(PLAYER, new BigDecimal("7")));
        assertEquals("3 shells", absent.format(new BigDecimal("3")));
        assertEquals("platform", platform.format(BigDecimal.ONE));
        host.close();
        assertTrue(absent.unavailable().orElseThrow().startsWith("module economy bank unavailable"));
        assertFalse(absent.has(PLAYER, BigDecimal.ONE));
    }

    @Test void onlyOneModuleMayProvideAPoint() throws Exception {
        ModuleHooks hooks = new ModuleHooks();
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.connectHooks(hooks);
            assertEquals(Outcome.Status.OK, host.install(module("first", (ModuleContext c) -> c.integrations().economy(HOOK, new Wallet()))).status());
            assertEquals(Outcome.Status.FAILED, host.install(module("second", (ModuleContext c) -> c.integrations().economy(HOOK, new Wallet()))).status());
            assertTrue(hooks.status().contains("economy hook: first"));
        }
    }

    @Test void convertersRegisterUnderTheirModuleAndRunAsBoundedWork() throws Exception {
        ModuleHooks hooks = new ModuleHooks();
        AtomicReference<String> thread = new AtomicReference<>();
        ImportConverters.Converter converter = new ImportConverters.Converter() {
            public ImportConverters.Detection detect(ImportConverters.Source source, Capabilities.Cancellation cancellation) {
                thread.set(Thread.currentThread().getName());
                return new ImportConverters.Detection(source.text().startsWith("npcs:"), "expects npcs:");
            }
            public ImportConverters.Conversion convert(ImportConverters.Source source, Capabilities.Cancellation cancellation) { throw new IllegalStateException("not used"); }
        };
        try (ModuleHost host = host(8, Duration.ofSeconds(2))) {
            host.connectHooks(hooks);
            assertEquals(Outcome.Status.FAILED, host.install(module("foreign", (ModuleContext c) -> c.imports().converter(
                    new ImportConverters.Descriptor(new Capabilities.Key("other", "npcs"), "Other", "Other", Set.of(Platform.PAPER)), converter))).status());
            assertEquals(Outcome.Status.OK, host.install(module("legacy", (ModuleContext c) -> c.imports().converter(
                    new ImportConverters.Descriptor(new Capabilities.Key("legacy", "npcs"), "Legacy NPCs", "LegacyNpcs", Set.of(Platform.PAPER)), converter))).status());
            ModuleHooks.Converter bound = hooks.converter("legacy:npcs").orElseThrow();
            Outcome<ImportConverters.Detection> detected = bound.detect().apply(new ImportConverters.Source("saves.txt", "npcs: 1")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(detected.value().orElseThrow().recognized());
            assertEquals("Animus-module", thread.get());
            Outcome<ImportConverters.Conversion> failed = bound.convert().apply(new ImportConverters.Source("saves.txt", "npcs: 1")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Outcome.Status.FAILED, failed.status());
            assertEquals(ModuleHost.State.ACTIVE, host.modules().get(1).state());
        }
    }
}
