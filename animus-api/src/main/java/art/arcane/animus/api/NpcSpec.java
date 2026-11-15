package art.arcane.animus.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parts of an NPC definition a plugin sets, built with {@link #builder()}. {@link NpcService#create} and
 * {@link NpcService#createTemporary} start from Animus's defaults and apply these parts; {@link NpcService#edit} applies
 * only the parts that are set to the NPC's current definition. Raw definition fields from {@link Builder#json} are laid
 * down first and the typed parts over them, so a typed part wins.
 * <p>
 * Building never fails. Animus checks the result as the web editor does, and the service answers
 * {@link Outcome.Status#INVALID} with one diagnostic per problem.
 *
 * @since 1.3
 */
public final class NpcSpec {
    /** A visible equipment slot. A slot the body has no use for is refused. */
    public enum Slot { MAIN_HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET, BODY, SADDLE }

    /**
     * A body pose. Every body stands, living bodies other than the ender dragon also sit, and the other poses need a
     * {@code minecraft:player} body.
     */
    public enum Pose { STANDING, SNEAKING, SLEEPING, SWIMMING, SITTING, CRAWLING, BELLYFLOP, SPINNING }

    private final Optional<String> id, group, type, name, json;
    private final Optional<NpcLocation> location;
    private final Optional<Boolean> spawned;
    private final Optional<SkinSource> skin;
    private final Optional<Pose> pose;
    private final Map<Slot, Optional<ItemSpec>> equipment;
    private final Map<String, Optional<String>> modules;

    private NpcSpec(Builder builder) {
        id = builder.id; group = builder.group; type = builder.type; name = builder.name; json = builder.json;
        location = builder.location; spawned = builder.spawned; skin = builder.skin; pose = builder.pose;
        equipment = Collections.unmodifiableMap(new LinkedHashMap<>(builder.equipment));
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(builder.modules));
    }

    /**
     * A builder with nothing set.
     *
     * @return the builder
     */
    public static Builder builder() { return new Builder(); }

    /** @return the NPC id, such as {@code guard}: 1-64 lowercase letters, digits, {@code _} or {@code -} */
    public Optional<String> id() { return id; }
    /** @return the folder below {@code npcs/}, such as {@code town}; empty text for {@code npcs/} itself */
    public Optional<String> group() { return group; }
    /** @return the body type, such as {@code minecraft:player} */
    public Optional<String> type() { return type; }
    /** @return the display name, with {@code &} colour codes */
    public Optional<String> name() { return name; }
    /** @return where the NPC stands */
    public Optional<NpcLocation> location() { return location; }
    /** @return whether the NPC is active in the world */
    public Optional<Boolean> spawned() { return spawned; }
    /** @return the skin to wear */
    public Optional<SkinSource> skin() { return skin; }
    /** @return the body pose */
    public Optional<Pose> pose() { return pose; }
    /** @return equipment by slot; an empty item clears the slot */
    public Map<Slot, Optional<ItemSpec>> equipment() { return equipment; }
    /** @return module data by module key, as JSON objects; an empty value removes the module */
    public Map<String, Optional<String>> modules() { return modules; }
    /** @return raw definition fields as one JSON object */
    public Optional<String> json() { return json; }

    /** Collects the parts of a spec. Every setter returns this builder; no setter checks its value. */
    public static final class Builder {
        private Optional<String> id = Optional.empty(), group = Optional.empty(), type = Optional.empty(), name = Optional.empty(), json = Optional.empty();
        private Optional<NpcLocation> location = Optional.empty();
        private Optional<Boolean> spawned = Optional.empty();
        private Optional<SkinSource> skin = Optional.empty();
        private Optional<Pose> pose = Optional.empty();
        private final Map<Slot, Optional<ItemSpec>> equipment = new LinkedHashMap<>();
        private final Map<String, Optional<String>> modules = new LinkedHashMap<>();

        private Builder() {}

        /**
         * The NPC id. A persistent NPC needs one; a temporary NPC without one gets a generated id.
         *
         * @param id NPC id
         * @return this builder
         */
        public Builder id(String id) { this.id = Optional.of(Objects.requireNonNull(id)); return this; }
        /**
         * The folder below {@code npcs/} the file is saved in. Temporary NPCs keep it only as a label.
         *
         * @param group folder such as {@code town/market}, or empty text
         * @return this builder
         */
        public Builder group(String group) { this.group = Optional.of(Objects.requireNonNull(group)); return this; }
        /**
         * The body type. New NPCs default to {@code minecraft:player}.
         *
         * @param type entity type id
         * @return this builder
         */
        public Builder type(String type) { this.type = Optional.of(Objects.requireNonNull(type)); return this; }
        /**
         * The display name, at most 256 characters. New NPCs default to their id.
         *
         * @param name display name with {@code &} colour codes
         * @return this builder
         */
        public Builder name(String name) { this.name = Optional.of(Objects.requireNonNull(name)); return this; }
        /**
         * Where the NPC stands. New NPCs need one.
         *
         * @param location the place
         * @return this builder
         */
        public Builder location(NpcLocation location) { this.location = Optional.of(Objects.requireNonNull(location)); return this; }
        /**
         * Whether the NPC is active. New NPCs from the service default to spawned.
         *
         * @param spawned true to show it
         * @return this builder
         */
        public Builder spawned(boolean spawned) { this.spawned = Optional.of(spawned); return this; }
        /**
         * The skin to wear.
         *
         * @param skin where the skin comes from
         * @return this builder
         */
        public Builder skin(SkinSource skin) { this.skin = Optional.of(Objects.requireNonNull(skin)); return this; }
        /**
         * The body pose.
         *
         * @param pose the pose
         * @return this builder
         */
        public Builder pose(Pose pose) { this.pose = Optional.of(Objects.requireNonNull(pose)); return this; }
        /**
         * Puts an item in a slot.
         *
         * @param slot equipment slot
         * @param item the item
         * @return this builder
         */
        public Builder equip(Slot slot, ItemSpec item) { equipment.put(Objects.requireNonNull(slot), Optional.of(Objects.requireNonNull(item))); return this; }
        /**
         * Clears a slot.
         *
         * @param slot equipment slot
         * @return this builder
         */
        public Builder unequip(Slot slot) { equipment.put(Objects.requireNonNull(slot), Optional.empty()); return this; }
        /**
         * Sets one module's data, replacing what it held. Built-in modules use the {@code animus} namespace, such as
         * {@code animus:seat}; a plugin's own module uses the key it registered with {@link NpcModules.Registrar}.
         *
         * @param key module key, {@code namespace:name}
         * @param json the module's data as a JSON object
         * @return this builder
         */
        public Builder module(String key, String json) { modules.put(Objects.requireNonNull(key), Optional.of(Objects.requireNonNull(json))); return this; }
        /**
         * Removes one module's data.
         *
         * @param key module key
         * @return this builder
         */
        public Builder removeModule(String key) { modules.put(Objects.requireNonNull(key), Optional.empty()); return this; }
        /**
         * Raw definition fields as one JSON object, such as {@code {"hologram":{"lines":["Open daily"]}}}, for anything without a
         * typed setter. A field set to {@code null} is removed. The fields Animus owns ({@code uuid}, {@code revision},
         * {@code schemaVersion}) are refused here.
         *
         * @param fields a JSON object
         * @return this builder
         */
        public Builder json(String fields) { this.json = Optional.of(Objects.requireNonNull(fields)); return this; }
        /**
         * The spec. Building never fails; the service checks it.
         *
         * @return the spec
         */
        public NpcSpec build() { return new NpcSpec(this); }
    }
}
