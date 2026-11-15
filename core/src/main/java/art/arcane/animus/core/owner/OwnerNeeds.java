package art.arcane.animus.core.owner;

import art.arcane.animus.core.behavior.BehaviorNodes;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.integration.MythicBinding;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.schedule.ScheduleSettings;
import art.arcane.animus.core.shop.ShopBinding;
import art.arcane.animus.core.shop.ShopDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What an NPC owned by a player would do beyond its own body and the player who clicked it, read from its definition
 * as it runs (trait groups merged) and the behaviors, menus and shops it reaches. Each need names the node its owner
 * must hold. Reads err toward a need: an argument filled while the behavior runs counts as given.
 */
public final class OwnerNeeds {
    /** The documents a check reads past the definition, as they would load. */
    public interface Sources {
        Optional<JsonElement> behavior(String id);
        /** A chest GUI, menu, hologram or shop. */
        Optional<JsonElement> document(DocumentKind kind, String id);
        Optional<NpcDefinition> npc(UUID uuid);
        Optional<NpcDefinition> npc(String id);
        /** Capability keys the module registry marks privileged; {@code animus:command} counts without being listed. */
        Set<String> privileged();
        /** Module keys plugins registered through the NPC service; the plugin answers for what its module does. */
        default Set<String> pluginModules() { return Set.of(); }
    }

    public static final String COMMAND = "animus:command";
    /** The modules Animus itself reads; any other module comes from another plugin and may do anything. */
    static final Set<String> MODULES = Set.of("animus:combat", "animus:gui", "animus:health", "animus:hire", "animus:home", "animus:look-close", "animus:mirror",
            "animus:modelengine", "animus:mount", "animus:navigation", "animus:riding", "animus:schedule", "animus:seat", "animus:shop", "animus:skin", MythicBinding.MODULE);
    /** What an import kept for review: data nothing runs, so it needs no node. */
    static final Set<String> REVIEWS = Set.of("citizens:review", "shopkeepers:review");
    private static final Set<String> BROADCASTS = Set.of("all", "world", "console");
    /** Conditions whose {@code npc} argument only reads another NPC. */
    private static final Set<String> READS_NPC = Set.of("animus:flag-state");
    private static final Set<String> TARGET_PLAYERS = Set.of("players", "permissions", "groups", "teams", "chat");

    private OwnerNeeds() {}

    /** Every need of an effective definition, in a stable order, each once. */
    public static List<OwnerNeed> definition(JsonObject effective, Sources sources) {
        Set<OwnerNeed> needs = new LinkedHashSet<>();
        Optional<UUID> owner = owner(effective);
        if (effective.get("behaviors") instanceof JsonArray behaviors)
            for (JsonElement reference : behaviors)
                if (reference.isJsonPrimitive() && reference.getAsJsonPrimitive().isString())
                    sources.behavior(reference.getAsString()).ifPresent((JsonElement document) -> needs.addAll(behavior(reference.getAsString(), document, owner, sources)));
        if (effective.get(NpcDefinition.CLICK_REDIRECT) instanceof JsonElement redirect && redirect.isJsonPrimitive()) redirect(redirect.getAsString(), owner, sources, needs);
        if (!(effective.get("modules") instanceof JsonObject modules)) return List.copyOf(needs);
        for (String module : modules.keySet())
            if (!MODULES.contains(module) && !REVIEWS.contains(module) && !sources.pluginModules().contains(module)) needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "carry a module from another plugin", "module " + module));
        if (ScheduleSettings.runsCommands(effective)) needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run commands", "schedule arrivals"));
        if (HealthSettings.drops(effective)) needs.add(new OwnerNeed(PermissionNodes.OWNED_ITEMS, "create items", "death drops"));
        if (modules.has(MythicBinding.MODULE)) needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run MythicMobs skills", "module " + MythicBinding.MODULE));
        if (modules.get(CombatSettings.MODULE) instanceof JsonObject combat && combat.get("targets") instanceof JsonObject targets && targetsPlayers(targets))
            needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "attack players", "combat targets"));
        if (modules.get(ShopBinding.MODULE) instanceof JsonObject shop && shop.get("shop") instanceof JsonElement id && id.isJsonPrimitive())
            needs.addAll(opens(DocumentKind.SHOP, id.getAsString(), owner, "shop " + id.getAsString(), sources));
        if (modules.get(GuiBinding.MODULE) instanceof JsonObject gui) gui(gui, owner, sources, needs);
        if (modules.get(MountDeclaration.MODULE) instanceof JsonObject mount && mount.get("vehicle") instanceof JsonElement vehicle && vehicle.isJsonPrimitive()) {
            Optional<NpcDefinition> ridden = sources.npc(vehicle.getAsString());
            if (ridden.isPresent() && !ridden.get().owner().equals(owner))
                needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "ride an NPC it does not own", "NPC " + ridden.get().id()));
        }
        return List.copyOf(needs);
    }

    /** Every need of one behavior document bound to an NPC that {@code owner} owns. */
    public static List<OwnerNeed> behavior(String id, JsonElement document, Optional<UUID> owner, Sources sources) {
        Set<OwnerNeed> needs = new LinkedHashSet<>();
        String where = "behavior " + id;
        if (document.isJsonObject() && document.getAsJsonObject().get("server") instanceof JsonElement server && server.isJsonPrimitive()
                && server.getAsJsonPrimitive().isBoolean() && server.getAsBoolean())
            needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "run a server behavior", where));
        ArrayDeque<JsonElement> pending = new ArrayDeque<>(List.of(document));
        while (!pending.isEmpty()) {
            JsonElement element = pending.removeFirst();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(pending::add);
            else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (BehaviorNodes.capability(object).isPresent()) needs.addAll(node(object, where, owner, sources));
                if (object.get("type") instanceof JsonElement type && type.isJsonPrimitive() && type.getAsString().equals("call")) {
                    if (object.get("player") instanceof JsonElement player && !player.isJsonNull())
                        needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "run a routine for another player", where));
                    if (object.get("npc") instanceof JsonElement npc && !npc.isJsonNull())
                        needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "run a routine for another NPC", where));
                }
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) pending.add(entry.getValue());
            }
        }
        return List.copyOf(needs);
    }

    /**
     * The needs of one capability call: a behavior node as written, or a call about to run with its arguments filled in
     * (then nothing counts as bound). {@code privileged} is the registry's own mark for the capability.
     */
    public static List<OwnerNeed> call(String capability, JsonObject arguments, boolean privileged, String where, Optional<UUID> owner, Sources sources) {
        JsonObject node = new JsonObject();
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        List<OwnerNeed> needs = new ArrayList<>(node(node, where, owner, sources));
        if (privileged && needs.stream().noneMatch((OwnerNeed need) -> need.node().equals(PermissionNodes.OWNED_COMMANDS)))
            needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run the privileged action " + capability, where));
        return List.copyOf(needs);
    }

    private static List<OwnerNeed> node(JsonObject node, String where, Optional<UUID> owner, Sources sources) {
        String capability = BehaviorNodes.capability(node).orElseThrow();
        List<OwnerNeed> needs = new ArrayList<>();
        if (capability.equals(COMMAND)) {
            needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run commands", where));
            if (!BehaviorNodes.off(node, "asOperator", true)) needs.add(new OwnerNeed(PermissionNodes.OWNED_OPERATOR, "run a command as an operator", where));
            if (BehaviorNodes.bound(node, "permissions")) needs.add(new OwnerNeed(PermissionNodes.OWNED_OPERATOR, "lend permissions chosen while it runs", where));
            else if (BehaviorNodes.literal(node, "permissions").orElse(null) instanceof JsonArray lent)
                for (JsonElement permission : lent)
                    if (permission.isJsonPrimitive()) needs.add(new OwnerNeed(permission.getAsString(), "lend " + permission.getAsString(), where));
        } else if (sources.privileged().contains(capability)) needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run the privileged action " + capability, where));
        switch (capability) {
            case "animus:give-item", "animus:give-book" -> needs.add(new OwnerNeed(PermissionNodes.OWNED_ITEMS, "create items", where));
            case "animus:teleport" -> {
                if (!BehaviorNodes.literal(node, "target").map((JsonElement target) -> target.isJsonPrimitive() && target.getAsString().equals("npc")).orElse(false)
                        || BehaviorNodes.bound(node, "target"))
                    needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "teleport players", where));
            }
            case "animus:build" -> needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "build in the world", where));
            case "animus:explode" -> needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "set off explosions", where));
            case "animus:message" -> {
                boolean broadcast = BehaviorNodes.bound(node, "to") || BehaviorNodes.literal(node, "to").map((JsonElement to) -> !to.isJsonPrimitive() || BROADCASTS.contains(to.getAsString())).orElse(false);
                if (broadcast) needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "message every player or the console", where));
            }
            case "animus:combat-control" -> {
                if (BehaviorNodes.given(node, "target")) needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "attack a chosen target", where));
            }
            case "animus:open-gui" -> needs.addAll(screen(node, where, owner, sources));
            case "animus:open-shop" -> needs.addAll(literalId(node, "shop").map((String id) -> opens(DocumentKind.SHOP, id, owner, where, sources))
                    .orElse(List.of(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "open a shop chosen while it runs", where))));
            default -> {}
        }
        if (BehaviorNodes.paysQuest(node)) needs.add(new OwnerNeed(PermissionNodes.OWNED_ITEMS, "pay a quest's rewards", where));
        if (BehaviorNodes.given(node, "player")) needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "reach a player by name", where));
        if (BehaviorNodes.given(node, "atPlayer")) needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "show effects at another player", where));
        if (BehaviorNodes.given(node, "npc") && !READS_NPC.contains(capability)) needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "act on another NPC", where));
        return needs;
    }

    /** {@code animus:open-gui}: a Gloss menu is another plugin's and may run anything. */
    private static List<OwnerNeed> screen(JsonObject node, String where, Optional<UUID> owner, Sources sources) {
        Optional<String> id = literalId(node, "gui");
        if (id.isEmpty() || BehaviorNodes.bound(node, "kind")) return List.of(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "open a menu chosen while it runs", where));
        String kind = BehaviorNodes.literal(node, "kind").filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).orElse("inventory");
        return switch (kind) {
            case "menu" -> opens(DocumentKind.MENU, id.get(), owner, where, sources);
            case "shop" -> opens(DocumentKind.SHOP, id.get(), owner, where, sources);
            case "gloss" -> List.of(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "open a Gloss menu", where));
            default -> opens(DocumentKind.INVENTORY, id.get(), owner, where, sources);
        };
    }

    private static void gui(JsonObject gui, Optional<UUID> owner, Sources sources, Set<OwnerNeed> needs) {
        if (gui.get("open") instanceof JsonObject open && open.get("id") instanceof JsonElement id && id.isJsonPrimitive()) {
            String kind = BehaviorNodes.text(open, "kind").orElse("inventory");
            String where = "menu " + id.getAsString();
            switch (kind) {
                case "menu" -> needs.addAll(opens(DocumentKind.MENU, id.getAsString(), owner, where, sources));
                case "shop" -> needs.addAll(opens(DocumentKind.SHOP, id.getAsString(), owner, where, sources));
                case "gloss" -> needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "open a Gloss menu", where));
                default -> needs.addAll(opens(DocumentKind.INVENTORY, id.getAsString(), owner, where, sources));
            }
        }
        if (gui.get("hologram") instanceof JsonObject hologram && hologram.get("id") instanceof JsonElement id && id.isJsonPrimitive())
            needs.addAll(opens(DocumentKind.HOLOGRAM, id.getAsString(), owner, "hologram " + id.getAsString(), sources));
    }

    /** What a menu, hologram or shop does when a player uses it: commands, free goods, money, or another player's stock. */
    private static List<OwnerNeed> opens(DocumentKind kind, String id, Optional<UUID> owner, String where, Sources sources) {
        Optional<JsonElement> document = sources.document(kind, id);
        if (document.isEmpty()) return List.of();
        List<OwnerNeed> needs = new ArrayList<>();
        if (kind == DocumentKind.SHOP) {
            if (!document.get().isJsonObject()) return List.of();
            Optional<ShopDocument> shop = ShopDocument.parse(id, document.get().getAsJsonObject()).shop();
            if (shop.isEmpty()) return List.of();
            for (ShopDocument.Entry entry : shop.get().entries()) {
                if (entry.runsCommands()) needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run commands", where));
                if (entry.free()) needs.add(new OwnerNeed(PermissionNodes.OWNED_ITEMS, "hand out goods for nothing", where));
            }
            shop.get().owner().filter((ShopDocument.Owner seller) -> owner.map((UUID npcOwner) -> !npcOwner.equals(seller.uuid())).orElse(true))
                    .ifPresent((ShopDocument.Owner seller) -> needs.add(new OwnerNeed(PermissionNodes.OWNED_PLAYERS, "sell another player's stock", where)));
            return List.copyOf(new LinkedHashSet<>(needs));
        }
        if (BehaviorNodes.any(document.get(), (JsonObject action) -> BehaviorNodes.text(action, "type").map("command"::equals).orElse(false)))
            needs.add(new OwnerNeed(PermissionNodes.OWNED_COMMANDS, "run commands", where));
        if (BehaviorNodes.any(document.get(), (JsonObject action) -> BehaviorNodes.text(action, "type").map((String type) -> type.equals("give") || type.equals("economy")).orElse(false)))
            needs.add(new OwnerNeed(PermissionNodes.OWNED_ITEMS, "create items or money", where));
        return needs;
    }

    private static void redirect(String target, Optional<UUID> owner, Sources sources, Set<OwnerNeed> needs) {
        UUID uuid;
        try { uuid = UUID.fromString(target); }
        catch (IllegalArgumentException invalid) { return; }
        Optional<NpcDefinition> other = sources.npc(uuid);
        if (other.isPresent() && owner.isPresent() && other.get().owner().equals(owner)) return;
        needs.add(new OwnerNeed(PermissionNodes.OWNED_SERVER, "pass its clicks to another NPC", other.map((NpcDefinition npc) -> "NPC " + npc.id()).orElse("NPC " + target)));
    }

    /** A combat target rule that can pick out players: the players category, names, permissions, groups, teams or chat. */
    private static boolean targetsPlayers(JsonObject targets) {
        return BehaviorNodes.any(targets, (JsonObject rules) -> {
            if (rules.get("categories") instanceof JsonArray categories)
                for (JsonElement category : categories) if (category.isJsonPrimitive() && category.getAsString().equals("players")) return true;
            for (String key : TARGET_PLAYERS) {
                JsonElement value = rules.get(key);
                if (value != null && !(value.isJsonArray() && value.getAsJsonArray().isEmpty())) return true;
            }
            return false;
        });
    }

    private static Optional<String> literalId(JsonObject node, String argument) {
        if (BehaviorNodes.bound(node, argument)) return Optional.empty();
        return BehaviorNodes.literal(node, argument).filter((JsonElement value) -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()).map(JsonElement::getAsString);
    }

    /** The owner a definition names; a malformed owner reads as none, which definition validation refuses anyway. */
    static Optional<UUID> owner(JsonObject definition) {
        if (!(definition.get("owner") instanceof JsonElement owner) || !owner.isJsonPrimitive()) return Optional.empty();
        try { return Optional.of(UUID.fromString(owner.getAsString())); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
