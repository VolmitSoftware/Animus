package art.arcane.animus.core.presentation;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.BossBarSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The boss bars of active NPCs that declare a {@code bossBar}, the same on every platform: each is shown to the players
 * in the NPC's world within its range who may see the NPC and hold its permission, and follows the NPC's health when
 * asked to. The platform draws the bars through {@link Host}. Call {@link #update} on the runtime's owner.
 *
 * @param <P> the platform's player
 */
public final class NpcBossBars<P> {
    /** Ticks between updates the platforms use. */
    public static final int INTERVAL = 10;

    /** Where an active NPC stands and its health. */
    public record Active(String world, Vec3 point, double health) {}

    /** One drawn bar. */
    public interface Bar<P> {
        void title(String text);
        void progress(double fill);
        void show(P player);
        void hide(P player);
        Set<P> viewers();
        void close();
    }

    /** The platform: makes bars and answers who stands where. */
    public interface Host<P> {
        Bar<P> create(BossBarSettings settings);
        List<P> players(String world);
        UUID id(P player);
        Vec3 position(P player);
        boolean permitted(P player, String node);
        /** The node is set for the player and true, not held only as an operator's default. */
        boolean granted(P player, String node);
    }

    private record Shown<P>(Bar<P> bar, BossBarSettings settings) {}

    private final Host<P> host;
    private final Map<UUID, Shown<P>> bars = new HashMap<>();

    public NpcBossBars(Host<P> host) { this.host = Objects.requireNonNull(host); }

    /** Shows, updates and removes bars for {@code definitions}; {@code active} answers each NPC's place while it is active. */
    public void update(Collection<NpcDefinition> definitions, Function<UUID, Optional<Active>> active) {
        Set<UUID> seen = new HashSet<>();
        for (NpcDefinition definition : definitions) {
            Optional<BossBarSettings> declared = definition.bossBar();
            if (declared.isEmpty()) continue;
            Optional<Active> where = active.apply(definition.uuid());
            if (where.isEmpty()) continue;
            seen.add(definition.uuid());
            update(definition, declared.get(), where.get());
        }
        for (Iterator<Map.Entry<UUID, Shown<P>>> shown = bars.entrySet().iterator(); shown.hasNext(); ) {
            Map.Entry<UUID, Shown<P>> entry = shown.next();
            if (seen.contains(entry.getKey())) continue;
            entry.getValue().bar().close();
            shown.remove();
        }
    }

    private void update(NpcDefinition definition, BossBarSettings settings, Active where) {
        Shown<P> shown = bars.get(definition.uuid());
        if (shown == null || !shown.settings().equals(settings)) {
            if (shown != null) shown.bar().close();
            shown = new Shown<>(host.create(settings), settings);
            bars.put(definition.uuid(), shown);
        }
        Bar<P> bar = shown.bar();
        bar.title(RichText.colorize(settings.title().isEmpty() ? definition.name() : settings.title()));
        bar.progress(settings.fill(OptionalDouble.of(where.health()), HealthSettings.maximum(definition.json())));
        Set<P> wanted = new HashSet<>();
        for (P player : host.players(where.world())) {
            double distance = host.position(player).distance(where.point());
            if (distance > settings.range()) continue;
            if (settings.permission().isPresent() && !host.permitted(player, settings.permission().get())) continue;
            if (definition.viewers().hides(host.id(player), distance, (String node) -> host.permitted(player, node), (String node) -> host.granted(player, node))) continue;
            wanted.add(player);
        }
        for (P player : bar.viewers()) if (!wanted.contains(player)) bar.hide(player);
        Set<P> viewers = bar.viewers();
        for (P player : wanted) if (!viewers.contains(player)) bar.show(player);
    }

    /** Removes every bar from every player. */
    public void close() {
        for (Shown<P> shown : bars.values()) shown.bar().close();
        bars.clear();
    }
}
