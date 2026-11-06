package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcAttributes;
import com.google.gson.JsonObject;
import java.util.OptionalDouble;

/**
 * Runtime attribute changes, as the behavior action {@code animus:attribute} makes them. A change lasts until the NPC's
 * definition is applied again (an edit, a reload or a restart); a reset returns the attribute to the definition's value.
 */
public final class RuntimeAttributes {
    private RuntimeAttributes() {}

    /**
     * @param definition the attributes the definition sets
     * @param current    the attributes the NPC runs with now
     * @param next       the new value, or empty to reset
     * @throws IllegalArgumentException for an unknown attribute or a value outside its range
     */
    public static NpcAttributes change(NpcAttributes definition, NpcAttributes current, String attribute, OptionalDouble next) {
        String id = NpcAttributes.id(attribute).orElseThrow(() -> new IllegalArgumentException(NpcAttributes.problem(attribute, 0)));
        if (next.isPresent()) return current.with(id, next.getAsDouble());
        OptionalDouble declared = definition.get(id);
        return declared.isPresent() ? current.with(id, declared.getAsDouble()) : current.without(id);
    }

    /** A copy of the definition document running with these attributes, for the settings read from it. */
    public static JsonObject definition(JsonObject document, NpcAttributes attributes) {
        JsonObject copy = document.deepCopy();
        if (attributes.isEmpty()) copy.remove(NpcAttributes.FIELD);
        else copy.add(NpcAttributes.FIELD, attributes.json());
        return copy;
    }
}
