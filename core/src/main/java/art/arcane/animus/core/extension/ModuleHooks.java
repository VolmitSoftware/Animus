package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.building.BuildProtection;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.inventory.Economy;
import art.arcane.animus.core.virtual.WorldPosition;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Engine-wide registry of module integration hooks and import converters. Platform adapters wrap their own
 * protection and economy with it at startup, before modules enable; each call reads the current registration.
 * A provider whose module became unavailable fails closed: protection denies and money is refused.
 */
public final class ModuleHooks {
    /** The registering module, as its host sees it now. */
    public interface Owner {
        String id();
        boolean available();
        String reason();
        long deadlineNanos();
        /** A synchronous hook call ran past the module deadline. */
        void overran(String hook);
    }
    private record Hook<T>(Owner owner, T hook) {}
    /** Converter calls already bound to the module's bounded invocation path. */
    public record Converter(ImportConverters.Descriptor descriptor, Version moduleVersion, Owner owner,
                            Function<ImportConverters.Source, CompletionStage<Outcome<ImportConverters.Detection>>> detect,
                            Function<ImportConverters.Source, CompletionStage<Outcome<ImportConverters.Conversion>>> convert) {}
    /** Everything one module staged during enable. */
    public record Staged(Optional<Integrations.Protection> protection, Optional<Integrations.Economy> economy, List<Converter> converters) {
        public Staged { converters = List.copyOf(converters); }
        public static final Staged NONE = new Staged(Optional.empty(), Optional.empty(), List.of());
    }

    private volatile Hook<Integrations.Protection> protection;
    private volatile Hook<Integrations.Economy> economy;
    private final Map<String, Converter> converters = new LinkedHashMap<>();
    private final AtomicLong failures = new AtomicLong();

    /** All or nothing: one provider per point and one converter per key. */
    public synchronized void commit(Owner owner, Staged staged) {
        Objects.requireNonNull(owner);
        if (staged.protection().isPresent() && protection != null) throw new IllegalArgumentException("Protection hook already provided by " + protection.owner().id());
        if (staged.economy().isPresent() && economy != null) throw new IllegalArgumentException("Economy hook already provided by " + economy.owner().id());
        if (converters.size() + staged.converters().size() > 256) throw new IllegalArgumentException("Converter capacity reached");
        for (Converter converter : staged.converters())
            if (converters.containsKey(converter.descriptor().key().toString())) throw new IllegalArgumentException("Converter collision");
        staged.protection().ifPresent((Integrations.Protection hook) -> protection = new Hook<>(owner, hook));
        staged.economy().ifPresent((Integrations.Economy hook) -> economy = new Hook<>(owner, hook));
        for (Converter converter : staged.converters()) converters.put(converter.descriptor().key().toString(), converter);
    }

    public synchronized List<Converter> converters() { return List.copyOf(converters.values()); }
    public synchronized Optional<Converter> converter(String key) { return Optional.ofNullable(converters.get(key)); }
    public long failures() { return failures.get(); }

    /** One line per bound point for status output. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        Hook<Integrations.Protection> guard = protection;
        Hook<Integrations.Economy> money = economy;
        if (guard != null) lines.add("protection hook: " + guard.owner().id() + (guard.owner().available() ? "" : " (unavailable: " + guard.owner().reason() + ")"));
        if (money != null) lines.add("economy hook: " + money.owner().id() + (money.owner().available() ? "" : " (unavailable: " + money.owner().reason() + ")"));
        for (Converter converter : converters()) lines.add("import converter: " + converter.descriptor().key() + " for " + converter.descriptor().sourcePlugin());
        if (failures.get() > 0) lines.add("hook failures: " + failures.get());
        return lines;
    }

    /** Platform combat protection AND the module's, when one is registered. */
    public ProtectionAdapter protection(ProtectionAdapter platform) {
        Objects.requireNonNull(platform);
        return new ProtectionAdapter() {
            @Override public Decision decide(Request request) {
                if (platform.decide(request) != Decision.ALLOW) return Decision.DENY;
                return module(new Integrations.ProtectionRequest(action(request.action()), party(request.attacker()), position(request.attackerPosition()),
                        Optional.of(party(request.target())), position(request.targetPosition()), categories(request.targetCategories()), Optional.empty()))
                        ? Decision.ALLOW : Decision.DENY;
            }
            @Override public Optional<Boolean> inRegion(String region, WorldPosition position) { return platform.inRegion(region, position); }
        };
    }

    /** Platform build protection AND the module's, when one is registered. */
    public BuildProtection buildProtection(BuildProtection platform) {
        Objects.requireNonNull(platform);
        return (BuildProtection.Request request) -> {
            if (platform.decide(request) != BuildProtection.Decision.ALLOW) return BuildProtection.Decision.DENY;
            Integrations.Position at = new Integrations.Position(request.world(), request.at().x(), request.at().y(), request.at().z());
            Integrations.ProtectionAction action = switch (request.action()) {
                case PLACE -> Integrations.ProtectionAction.BLOCK_PLACE;
                case BREAK -> Integrations.ProtectionAction.BLOCK_BREAK;
                case USE -> Integrations.ProtectionAction.BLOCK_USE;
            };
            boolean allowed = module(new Integrations.ProtectionRequest(action, new Integrations.Party(Integrations.PartyKind.NPC, request.npc()), at, Optional.empty(), at, Set.of(), request.startedBy()));
            return allowed ? BuildProtection.Decision.ALLOW : BuildProtection.Decision.DENY;
        };
    }

    private boolean module(Integrations.ProtectionRequest request) {
        Hook<Integrations.Protection> hook = protection;
        if (hook == null) return true;
        if (!hook.owner().available()) return false;
        return call(hook.owner(), "protection", () -> hook.hook().decide(request), Integrations.Decision.DENY) == Integrations.Decision.ALLOW;
    }

    /** The platform's money provider while it has one; otherwise the module's. */
    public Economy economy(Economy platform) {
        Objects.requireNonNull(platform);
        return new Economy() {
            private Hook<Integrations.Economy> hook() { return platform.unavailable().isPresent() ? economy : null; }
            @Override public Optional<String> unavailable() {
                Hook<Integrations.Economy> hook = hook();
                if (hook == null) return platform.unavailable();
                return hook.owner().available() ? Optional.empty() : Optional.of("module economy " + hook.owner().id() + " unavailable: " + hook.owner().reason());
            }
            @Override public boolean has(UUID player, BigDecimal amount) {
                Hook<Integrations.Economy> hook = hook();
                if (hook == null) return platform.has(player, amount);
                return hook.owner().available() && call(hook.owner(), "economy", () -> hook.hook().has(player, amount), false);
            }
            @Override public boolean withdraw(UUID player, BigDecimal amount) {
                Hook<Integrations.Economy> hook = hook();
                if (hook == null) return platform.withdraw(player, amount);
                return hook.owner().available() && call(hook.owner(), "economy", () -> hook.hook().withdraw(player, amount), false);
            }
            @Override public boolean deposit(UUID player, BigDecimal amount) {
                Hook<Integrations.Economy> hook = hook();
                if (hook == null) return platform.deposit(player, amount);
                return hook.owner().available() && call(hook.owner(), "economy", () -> hook.hook().deposit(player, amount), false);
            }
            @Override public String format(BigDecimal amount) {
                Hook<Integrations.Economy> hook = hook();
                if (hook == null) return platform.format(amount);
                if (!hook.owner().available()) return amount.toPlainString();
                String text = call(hook.owner(), "economy", () -> hook.hook().format(amount), amount.toPlainString());
                return text.length() > 128 ? amount.toPlainString() : text;
            }
        };
    }

    /** Trusted code on the owner thread: a throw or null is the failed answer; an overrun suspends the module. */
    private <T> T call(Owner owner, String name, Callable<T> work, T failed) {
        long started = System.nanoTime();
        try {
            T value = work.call();
            if (value != null) return value;
            failures.incrementAndGet();
            return failed;
        } catch (Exception | LinkageError | AssertionError failure) {
            failures.incrementAndGet();
            return failed;
        } finally {
            if (System.nanoTime() - started > owner.deadlineNanos()) owner.overran(name);
        }
    }

    private static Integrations.ProtectionAction action(ProtectionAdapter.Action action) {
        return switch (action) {
            case NPC_ATTACK -> Integrations.ProtectionAction.NPC_ATTACK;
            case PLAYER_ATTACK -> Integrations.ProtectionAction.PLAYER_ATTACK;
            case MOB_ATTACK -> Integrations.ProtectionAction.MOB_ATTACK;
            case MOB_CONTROL -> Integrations.ProtectionAction.MOB_CONTROL;
        };
    }
    private static Integrations.Party party(TargetRef ref) {
        Integrations.PartyKind kind = switch (ref.kind()) {
            case PLAYER -> Integrations.PartyKind.PLAYER;
            case MOB -> Integrations.PartyKind.MOB;
            case NPC -> Integrations.PartyKind.NPC;
        };
        return new Integrations.Party(kind, ref.id());
    }
    private static Integrations.Position position(WorldPosition position) {
        return new Integrations.Position(position.world(), position.point().x(), position.point().y(), position.point().z());
    }
    private static Set<String> categories(Set<TargetCategory> categories) {
        Set<String> names = new HashSet<>();
        for (TargetCategory category : categories) names.add(category.name().toLowerCase(Locale.ROOT));
        return names;
    }
}
