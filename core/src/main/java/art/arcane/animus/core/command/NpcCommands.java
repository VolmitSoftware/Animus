package art.arcane.animus.core.command;

import art.arcane.animus.core.control.LookClose;
import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.runtime.HealthRecords;
import art.arcane.animus.core.npc.NpcAnchors;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.npc.VanillaAttributes;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.SkinFiles;
import art.arcane.animus.core.toolkit.SkinLookup;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/** {@code /animus npc}: focused edits of one NPC's definition file. */
@Director(name = "npc", aliases = "npcs", description = "Change one setting of an NPC's definition")
@AnimusPermission(PermissionNodes.NPC_EDIT)
final class NpcCommands extends CommandGroup {
    static final String RESET = "reset";

    NpcCommands(CommandServices services) { super(services); }

    @Director(description = "Give an NPC the skin of a Minecraft account by its name, or reset it")
    @AnimusPermission(PermissionNodes.NPC_SKIN)
    void skin(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "player", description = "A Minecraft account name, or reset to remove the skin") String player) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> skin(this, actor, id, player, SkinLookup.shared()));
    }

    /**
     * Looks the name up off the server thread, then saves the signed skin labelled with the account's spelling and puts it
     * on the NPC on the file lane, as the toolkit's "From a player name" does. Works from the console.
     */
    static void skin(CommandGroup group, CommandActor actor, String id, String player, SkinLookup lookup) {
        Optional<String> refused = skinProblem(group.runtime().npcs(), id, player);
        if (refused.isPresent()) { actor.tell(Palette.error(refused.get())); return; }
        SkinFiles skins = new SkinFiles(group.runtime().npcs(), group.runtime().documents());
        if (player.equalsIgnoreCase(RESET)) {
            group.files(actor, () -> { String line = skins.remove(List.of(id)); group.living().refresh(); return List.of(Palette.success(line)); });
            return;
        }
        actor.tell("Looking up " + player + " on Mojang");
        lookup.find(player).thenAccept((SkinLookup.Found found) -> actor.reply(() -> {
            if (group.stopping()) return;
            if (found.skin().isEmpty()) { actor.tell(Palette.error(found.problem())); return; }
            group.files(actor, () -> {
                String line = skins.wear(found.skin().get(), found.name().orElse(player), List.of(id));
                group.living().refresh();
                return List.of(Palette.success(line));
            });
        }));
    }

    /** Why the skin cannot be set: no such NPC, a body that shows no skin, or a word that is not a Minecraft name. */
    static Optional<String> skinProblem(NpcRepository repository, String id, String player) {
        Optional<NpcDefinition> definition = repository.find(id);
        if (definition.isEmpty()) return Optional.of(unknownNpc(id));
        JsonElement type = definition.get().json().get("type");
        String body = type == null ? "" : type.getAsString();
        if (!body.equals(DEFAULT_PLAYER)) return Optional.of(id + " is a " + body + "; skins show on " + DEFAULT_PLAYER + " bodies only");
        if (!player.equalsIgnoreCase(RESET) && !player.matches(SkinLookup.NAME)) return Optional.of("'" + player + "' is not a Minecraft name (1-16 letters, digits or _)");
        return Optional.empty();
    }

    private static final String DEFAULT_PLAYER = AnimusCommandTree.DEFAULT_TYPE;

    @Director(description = "Move an NPC to where you stand, facing your way", origin = DirectorOrigin.PLAYER, aliases = "tphere")
    @AnimusPermission(PermissionNodes.NPC_MOVE)
    void move(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        Optional<WorldPosition> standing = actor.position();
        if (standing.isEmpty()) { actor.tell(Palette.error("Your position cannot be read right now; try again")); return; }
        float yaw = actor.facing().map(Coordinates.Facing::yaw).orElse(0f);
        WorldPosition at = new WorldPosition(standing.get().world(), Coordinates.point("", "", "", Coordinates.origin(actor)));
        npc(actor, npc).ifPresent((String id) -> {
            Toolkit.of(services.shops().screens()).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.relocate(id, at));
            files(actor, () -> { String line = new Administration(runtime()).move(id, at, yaw); living().refresh(); return List.of(Palette.success(line)); });
        });
    }

    @Director(description = "Teleport to an NPC, beside it and facing it", origin = DirectorOrigin.PLAYER)
    @AnimusPermission(PermissionNodes.NPC_MOVE)
    void tp(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> {
            Optional<Toolkit<?, ?>> toolkit = Toolkit.of(services.shops().screens());
            if (toolkit.isEmpty()) actor.tell(Palette.error("This platform cannot teleport players"));
            else toolkit.get().visit(actor.player().orElseThrow(), id);
        });
    }

    @Director(description = "Change an NPC's display name; & colour codes work")
    void rename(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "name", description = "The new display name; quote it to use spaces") String name) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> { String line = new Administration(runtime()).rename(id, name); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Show, set or reset one vanilla attribute of an NPC, such as scale or movement_speed")
    @Positional("value")
    void attribute(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                   @Param(name = "attribute", description = "A vanilla attribute id such as scale or minecraft:gravity") String attribute,
                   @Param(name = "value", defaultValue = "", description = "A number in the attribute's range, reset, or nothing to show it") String value) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            if (value.isEmpty()) return show(repository, id, attribute);
            String line = attribute(repository, id, attribute, value);
            living().refresh();
            return List.of(Palette.success(line));
        }));
    }

    /** The NPC's effective value of an attribute, where it comes from, and its vanilla default and range. */
    static List<String> show(NpcRepository repository, String id, String attribute) throws IOException {
        VanillaAttributes.Attribute known = known(attribute);
        NpcDefinition effective = repository.find(id).orElseThrow(() -> new IOException(CommandGroup.unknownNpc(id)));
        OptionalDouble current = NpcAttributes.of(effective.json()).get(known.id());
        String range = "vanilla default " + NpcAttributes.format(known.defaultValue()) + ", range " + NpcAttributes.format(known.minimum()) + ".." + NpcAttributes.format(known.maximum());
        List<String> lines = new ArrayList<>();
        if (current.isEmpty()) lines.add(id + ": " + known.id() + " is not set; " + range);
        else {
            String source = repository.provenance(id).getOrDefault(NpcAttributes.FIELD + "." + known.id(), GroupMerge.NPC);
            lines.add(id + ": " + known.id() + " = " + NpcAttributes.format(current.getAsDouble()) + " (" + source + "); " + range);
        }
        lines.add(NpcAttributes.note(known.id()));
        return lines;
    }

    /** Sets or resets one attribute in the NPC's own file as a new revision; returns the line to answer. */
    static String attribute(NpcRepository repository, String id, String attribute, String value) throws IOException {
        VanillaAttributes.Attribute known = known(attribute);
        boolean reset = value.equalsIgnoreCase(RESET);
        double number = 0;
        if (!reset) {
            try { number = Double.parseDouble(value); }
            catch (NumberFormatException notNumber) { throw new IllegalArgumentException("value must be a number or reset"); }
            String problem = NpcAttributes.problem(known.id(), number);
            if (problem != null) throw new IllegalArgumentException(problem);
        }
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonElement existing = document.get(NpcAttributes.FIELD);
        JsonObject attributes = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
        for (String key : List.copyOf(attributes.keySet())) if (NpcAttributes.id(key).filter(known.id()::equals).isPresent()) attributes.remove(key);
        if (!reset) attributes.addProperty(known.id(), number);
        if (attributes.isEmpty()) document.remove(NpcAttributes.FIELD);
        else document.add(NpcAttributes.FIELD, attributes);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + ": " + known.id() + (reset ? " reset" : " = " + NpcAttributes.format(number)) + "; tx " + transaction;
    }

    @Director(description = "Show, set or reset an NPC's LibsDisguises disguise, such as \"zombie setBaby true\" or a custom disguise name")
    @NeedsPlugin("LibsDisguises")
    @Positional("disguise")
    void disguise(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                  @Param(name = "disguise", defaultValue = "", description = "Disguise text as /disguise takes it, quoted when it has spaces; reset to remove it; nothing to show it") String disguise) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            if (disguise.isEmpty()) return DisguiseSetting.show(repository, id);
            List<String> lines = DisguiseSetting.set(repository, id, disguise);
            living().refresh();
            return lines;
        }));
    }

    @Director(description = "Bring a dead NPC back: clears a permadeath, or respawns it now")
    void revive(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> revive(this, actor, id));
    }

    /** A permadeath is cleared in the definition; any other NPC is brought back by its running health on its owner. */
    static void revive(CommandGroup group, CommandActor actor, String id) {
        boolean[] running = {false};
        group.files(actor, () -> {
            java.util.Optional<HealthRecords.Recorded> cleared = HealthRecords.revive(group.runtime().npcs(), id);
            if (cleared.isEmpty()) { running[0] = true; return List.of(); }
            group.living().refresh();
            return List.of(Palette.success(id + " revived; it spawns at its own position; tx " + cleared.get().transaction()));
        }, () -> { if (running[0]) WorldCommands.combat(actor, () -> group.services.combat().npc(id, CombatControl.Operation.REVIVE, java.util.Optional.empty())); });
    }

    @Director(description = "Show or set one health setting of an NPC: killable, maximum, regenerate, after-death, delay or show-health")
    @Positional({"setting", "value"})
    void health(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "setting", defaultValue = "", description = "killable, maximum, regenerate, after-death, delay or show-health; nothing shows them all") String setting,
                @Param(name = "value", defaultValue = "", description = "The new value, or reset to remove it") String value) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            if (setting.isEmpty() || value.isEmpty()) return List.of(HealthSetting.show(repository, id, setting));
            String line = HealthSetting.set(repository, id, setting, value);
            living().refresh();
            return List.of(Palette.success(line));
        }));
    }

    @Director(description = "Show or set the Model Engine model an NPC shows: model, scale, show-body, hitbox, nametag-bone or a state's animation")
    @NeedsPlugin("ModelEngine")
    @Positional({"part", "to"})
    void model(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
               @Param(name = "part", defaultValue = "", description = "model, scale, show-body, hitbox, nametag-bone, idle, walk, attack, hurt, death or interact; nothing shows them all") String part,
               @Param(name = "to", defaultValue = "", description = "The new value, or reset; resetting model removes the model") String to) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            boolean drawn = living().drawsModels();
            if (part.isEmpty() || to.isEmpty()) return ModelSetting.reply(ModelSetting.show(repository, id), drawn);
            String line = ModelSetting.set(repository, id, part, to);
            living().refresh();
            return ModelSetting.reply(Palette.success(line), drawn);
        }));
    }

    @Director(description = "Turn an NPC's look-close on or off; off keeps its range and other settings")
    void look(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "enabled", description = "on, off or toggle") String enabled) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            String line = look(repository, id, enabled);
            living().refresh();
            return List.of(Palette.success(line));
        }));
    }

    /** Sets {@code enabled} of the NPC's own look-close module, adding the module when it has none. */
    static String look(NpcRepository repository, String id, String enabled) throws IOException {
        boolean on = switch (enabled.toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            case DiagnosticCommands.TOGGLE -> LookClose.parse(repository.find(id).orElseThrow(() -> new IOException(CommandGroup.unknownNpc(id))).json()).isEmpty();
            default -> throw new IllegalArgumentException("enabled must be on, off or toggle");
        };
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        JsonObject look = modules.has(LookClose.MODULE) && modules.get(LookClose.MODULE).isJsonObject() ? modules.getAsJsonObject(LookClose.MODULE) : new JsonObject();
        look.addProperty("enabled", on);
        modules.add(LookClose.MODULE, look);
        document.add("modules", modules);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + ": look-close " + (on ? "on" : "off") + "; tx " + transaction;
    }

    @Director(description = "List an NPC's anchors, add one where you stand or at x y z, or remove one")
    @Positional({"action", "name"})
    void anchor(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "action", defaultValue = "list", description = "list, add or remove") String action,
                @Param(name = "name", defaultValue = "", description = "The anchor's name: 1-32 lowercase letters, digits, _ or -") String name,
                @Param(name = "x", defaultValue = "", description = "X position: a number, ~ or ~N; yours when left out") String x,
                @Param(name = "y", defaultValue = "", description = "Y position: a number, ~ or ~N; yours when left out") String y,
                @Param(name = "z", defaultValue = "", description = "Z position: a number, ~ or ~N; yours when left out") String z,
                @Param(name = "world", defaultValue = "", description = "The anchor's world; yours when left out") String world) {
        CommandActor actor = actor();
        NpcRepository repository = runtime().npcs();
        AnimusCommandTree.Placement placement;
        if (action.equalsIgnoreCase("add")) {
            try { placement = AnimusCommandTree.placement(actor, world, x, y, z); }
            catch (IllegalArgumentException missing) { actor.tell(Palette.error(missing.getMessage())); return; }
        } else placement = null;
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            String line = anchor(repository, id, action, name, placement);
            living().refresh();
            return List.of(Palette.success(line));
        }));
    }

    /** Lists, adds or removes one of the NPC's {@code anchors}; an anchor in the NPC's own world is saved without a world. */
    static String anchor(NpcRepository repository, String id, String action, String name, AnimusCommandTree.Placement placement) throws IOException {
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        NpcAnchors current = NpcAnchors.parse(document);
        String verb = action.toLowerCase(Locale.ROOT);
        if (verb.equals("list")) {
            if (current.anchors().isEmpty()) return id + " has no anchors; /animus npc anchor " + id + " add <name> saves one where you stand";
            List<String> shown = new ArrayList<>();
            current.anchors().forEach((String key, NpcAnchors.Anchor anchor) -> shown.add(key + " " + anchor.world().map((String w) -> w + " ").orElse("")
                    + art.arcane.animus.core.command.Coordinates.shown(anchor.position())));
            return id + " anchors: " + String.join(", ", shown);
        }
        if (!name.matches(NpcAnchors.NAME)) throw new IllegalArgumentException("An anchor name is 1-32 lowercase letters, digits, _ or -");
        JsonObject anchors = document.has(NpcAnchors.FIELD) && document.get(NpcAnchors.FIELD).isJsonObject() ? document.getAsJsonObject(NpcAnchors.FIELD) : new JsonObject();
        switch (verb) {
            case "add" -> {
                if (!anchors.has(name) && anchors.size() >= NpcAnchors.MAXIMUM_ANCHORS) throw new IllegalArgumentException("An NPC keeps at most " + NpcAnchors.MAXIMUM_ANCHORS + " anchors");
                JsonObject anchor = new JsonObject();
                anchor.addProperty("x", placement.point().x()); anchor.addProperty("y", placement.point().y()); anchor.addProperty("z", placement.point().z());
                JsonElement position = document.get("position");
                String own = position != null && position.isJsonObject() && position.getAsJsonObject().has("world") ? position.getAsJsonObject().get("world").getAsString() : "";
                if (!placement.world().equals(own)) anchor.addProperty("world", placement.world());
                anchors.add(name, anchor);
            }
            case "remove" -> { if (anchors.remove(name) == null) throw new IllegalArgumentException(id + " has no anchor named " + name); }
            default -> throw new IllegalArgumentException("action must be list, add or remove");
        }
        if (anchors.isEmpty()) document.remove(NpcAnchors.FIELD);
        else document.add(NpcAnchors.FIELD, anchors);
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + ": " + (verb.equals("add") ? "saved" : "removed") + " anchor " + name + "; tx " + transaction;
    }

    /** Attribute ids for completion, bare. */
    static List<String> ids() { return NpcAttributes.table().stream().map(VanillaAttributes.Attribute::id).toList(); }

    private static VanillaAttributes.Attribute known(String attribute) {
        return NpcAttributes.attribute(attribute).orElseThrow(() -> new IllegalArgumentException(NpcAttributes.problem(attribute, 0)));
    }
}
