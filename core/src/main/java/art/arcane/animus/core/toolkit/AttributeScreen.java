package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.VanillaAttributes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The vanilla attributes the NPC's body carries, each with its value or the vanilla default. Numbers step within the
 * attribute's bounds; sneak-left types a value and sneak-right goes back to the vanilla default. A value saved as
 * {@code minecraft:<id>} keeps that spelling.
 */
final class AttributeScreen<P, S> extends ValueScreen<P, S> {
    private final String label;

    AttributeScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String label, Scope scope) {
        super(toolkit, viewer, commands, scope, List.of(NpcAttributes.FIELD));
        this.label = label;
    }

    @Override String title() { return clip(label, 32); }

    @Override void draw() {
        pageCheck();
        JsonObject document = scope.values().get();
        Set<String> body = VanillaAttributes.BODIES.get(PropertyScreen.type(document));
        List<VanillaAttributes.Attribute> shown = VanillaAttributes.ALL.stream().filter((VanillaAttributes.Attribute attribute) -> body == null || body.contains(attribute.id())).toList();
        JsonElement here = current();
        JsonObject values = here != null && here.isJsonObject() ? here.getAsJsonObject() : new JsonObject();
        JsonObject schema = PropertyScreen.SCHEMA.getAsJsonObject("properties").getAsJsonObject(NpcAttributes.FIELD).getAsJsonObject("properties");
        list(shown, "This body has no attributes", (Integer slot, VanillaAttributes.Attribute attribute) -> {
            String spelled = NpcAttributes.NAMESPACE + attribute.id(), key = values.has(spelled) && !values.has(attribute.id()) ? spelled : attribute.id();
            JsonElement value = values.get(key);
            JsonObject node = schema.has(attribute.id()) ? schema.getAsJsonObject(attribute.id()) : new JsonObject();
            double now = value != null && value.isJsonPrimitive() ? value.getAsDouble() : attribute.defaultValue(), step = stepOf(node);
            List<String> lines = new ArrayList<>(describe(node));
            lines.add(value == null ? "&7Now &fvanilla default " + plain(attribute.defaultValue()) : "&7Now &f" + plain(now));
            List<String> target = append(path, key);
            field(slot, FieldScreen.NUMBER, title(attribute.id()), lines, hints("+" + plain(step), "-" + plain(step), "left types a value, right resets"), target, (GuiModel.Click click) -> {
                if (arm(slot, target)) return;
                if (click == GuiModel.Click.SHIFT_RIGHT) apply((JsonObject root) -> with(with(root, append(path, attribute.id()), Optional.empty()), append(path, spelled), Optional.empty()));
                else if (click == GuiModel.Click.SHIFT_LEFT) prompt(attribute.id(), plain(now), (String typed) -> typed(typed, node).ifPresent((JsonPrimitive number) -> set(target, Optional.of(number))));
                else set(target, Optional.of(new JsonPrimitive(step(click, now, step, attribute.minimum(), attribute.maximum()))));
            });
        });
        back();
    }

    /** Every attribute field of the schema that names a vanilla attribute, bare or with its namespace. */
    static Set<String> covered(JsonObject schema) {
        Set<String> out = new HashSet<>();
        JsonObject attributes = schema.getAsJsonObject("properties").getAsJsonObject(NpcAttributes.FIELD).getAsJsonObject("properties");
        for (String key : attributes.keySet()) if (NpcAttributes.attribute(key).isPresent()) out.add(NpcAttributes.FIELD + "." + key);
        return out;
    }
}
