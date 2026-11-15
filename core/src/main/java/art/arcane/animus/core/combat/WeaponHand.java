package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.NpcItem;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The item an NPC fights with now: the main-hand item its definition gives it, or, with {@code weapons.autoswitch}, the
 * best melee or ranged weapon it carries. Damage, the ranged weapon it fires and a potion's effect follow that item after
 * its redirect. Ammunition is counted per kind of ranged weapon. Owned by one brain on the engine's thread.
 */
final class WeaponHand {
    private final CombatSettings settings;
    private final CombatLoadout loadout;
    private final Optional<NpcItem> own;
    /** Each carried item with the weapon it fights as, read once. */
    private final List<Carried> carried;
    private record Carried(NpcItem item, VanillaMelee.Weapon weapon) {}
    private final Map<RangedWeapon, Integer> spent = new EnumMap<>(RangedWeapon.class);
    private Optional<NpcItem> held;
    private VanillaMelee.Weapon weapon;
    private double melee, ranged;

    WeaponHand(CombatSettings settings, CombatLoadout loadout) {
        this.settings = Objects.requireNonNull(settings);
        this.loadout = Objects.requireNonNull(loadout);
        own = loadout.carried().isEmpty() || !loadout.carried().getFirst().id().equals(loadout.item("main_hand")) ? Optional.empty() : Optional.of(loadout.carried().getFirst());
        carried = settings.weapons().autoswitch()
                ? loadout.carried().stream().map((NpcItem item) -> new Carried(item, effective(Optional.of(item)))).toList() : List.of();
        take(own);
    }

    /** What the NPC shows in its main hand now; empty for its definition's own item. */
    Optional<NpcItem> shown() { return held.equals(own) ? Optional.empty() : held; }
    VanillaMelee.Weapon weapon() { return weapon; }
    double meleeDamage() { return melee; }
    /** The shot damage before a bow's critical bonus. */
    double rangedDamage() { return ranged; }
    /** Whether melee damage comes from the weapon, so its enchantment damage is added. */
    boolean meleeFromWeapon() { return EquipmentStats.meleeFromWeapon(settings.melee(), weapon.item()); }
    /** Whether a bow or crossbow is drawn now, so its shots are full-draw critical arrows. */
    boolean drawnBow() { return RangedWeapons.drawn(weapon.item()); }

    /** What the NPC fires now: the held item's projectile when it switches weapons, else its ranged settings' weapon. */
    RangedWeapon rangedWeapon() {
        if (settings.weapons().autoswitch()) return RangedWeapons.fired(weapon.item()).orElse(settings.ranged().weapon());
        return settings.ranged().weapon();
    }
    /** Whether the held item is what it fires: always without switching, only for a ranged item with it. */
    boolean holdsRanged() {
        if (!settings.weapons().autoswitch()) return true;
        return RangedWeapons.fired(weapon.item()).isPresent() && (rangedWeapon() != RangedWeapon.POTION || effect().isPresent());
    }
    /** The potion effect it throws: the held potion's own when it switches to one, else its ranged settings'. */
    Optional<CombatSettings.Effect> effect() {
        if (rangedWeapon() != RangedWeapon.POTION) return Optional.empty();
        if (settings.weapons().autoswitch() && RangedWeapons.fired(weapon.item()).isPresent())
            return PotionEffects.of(held.flatMap(NpcItem::components)).or(() -> settings.ranged().effect());
        return settings.ranged().effect();
    }
    boolean lingering() { return settings.ranged().lingering() || held.map((NpcItem item) -> RangedWeapons.lingering(settings.weapons().effective(item.id()))).orElse(false); }

    /** Shots left of what it fires now when ammunition is counted, or -1 when it is not. */
    int ammunition() {
        RangedWeapon kind = rangedWeapon();
        if (!settings.ranged().useAmmo() || kind.ammunition().isEmpty()) return -1;
        return Math.max(0, loadout.count(kind.ammunition()) - spent.getOrDefault(kind, 0));
    }
    void fired() { if (ammunition() > 0) spent.merge(rangedWeapon(), 1, Integer::sum); }
    void refill() { spent.clear(); }

    /**
     * With autoswitch, takes the carried item that deals the most damage of the wanted kind; true when the shown item
     * changed. A carried item of the wanted kind already in hand stays.
     */
    boolean switchTo(boolean wantRanged) {
        if (!settings.weapons().autoswitch()) return false;
        if (wantRanged ? isRanged(weapon) : isMelee(weapon)) return false;
        Optional<NpcItem> best = Optional.empty();
        double bestDamage = -1;
        for (Carried each : carried) {
            VanillaMelee.Weapon candidate = each.weapon();
            if (!(wantRanged ? isRanged(candidate) : isMelee(candidate))) continue;
            double damage = wantRanged ? EquipmentStats.ranged(settings.ranged(), settings.melee(), candidate) : EquipmentStats.melee(settings.melee(), candidate.item())
                    + VanillaMelee.bonus(candidate, Set.of(VanillaMelee.Group.OTHER));
            if (damage > bestDamage) { bestDamage = damage; best = Optional.of(each.item()); }
        }
        if (best.isEmpty() || best.equals(held)) return false;
        take(best);
        return true;
    }
    /** Back to the definition's own item after a fight; true when the shown item changed. */
    boolean restore() {
        if (held.equals(own)) return false;
        take(own);
        return true;
    }

    private boolean isRanged(VanillaMelee.Weapon candidate) {
        Optional<RangedWeapon> fired = RangedWeapons.fired(candidate.item());
        if (fired.isEmpty()) return false;
        if (settings.ranged().useAmmo() && !fired.get().ammunition().isEmpty())
            return loadout.count(fired.get().ammunition()) - spent.getOrDefault(fired.get(), 0) > 0;
        return true;
    }
    private static boolean isMelee(VanillaMelee.Weapon candidate) { return RangedWeapons.melee(candidate.item()); }

    private VanillaMelee.Weapon effective(Optional<NpcItem> item) {
        if (item.isEmpty()) return VanillaMelee.Weapon.EMPTY;
        return new VanillaMelee.Weapon(settings.weapons().effective(item.get().id()), EnchantmentLevels.of(item.get().components()));
    }
    private void take(Optional<NpcItem> item) {
        held = item;
        weapon = effective(item);
        melee = EquipmentStats.melee(settings.melee(), weapon.item());
        ranged = EquipmentStats.ranged(settings.ranged(), settings.melee(), weapon);
    }
}
