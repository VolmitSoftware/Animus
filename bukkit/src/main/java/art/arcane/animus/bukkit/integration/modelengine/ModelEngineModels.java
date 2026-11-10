package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.text.RichText;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.ModelState;
import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
import com.ticxo.modelengine.api.entity.Dummy;
import com.ticxo.modelengine.api.events.BaseEntityInteractEvent;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.bone.type.NameTag;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Model Engine 4 models on packet NPCs. Each NPC gets a {@link Dummy} base, Model Engine's base for models without a
 * server entity, that sees no player on its own: every viewer is paired by hand as the NPC's body is shown to them.
 * Loaded only when the ModelEngine plugin is enabled.
 */
final class ModelEngineModels implements ModelDriver<Player>, Listener {
    private static final List<ModelState> DEFAULT_STATES = List.of(ModelState.IDLE, ModelState.WALK, ModelState.DEATH);
    private record Drawn(Dummy<UUID> base, ModeledEntity entity, ActiveModel model, ModelEngineModule module) {}

    private final Plugin plugin;
    private final Runnable closed;
    private final Map<UUID, Drawn> drawn = new HashMap<>();
    /** Model Engine base UUID to NPC UUID, read from the click event's thread. */
    private final Map<UUID, UUID> bases = new ConcurrentHashMap<>();
    private final Set<String> missing = new HashSet<>();
    private volatile Clicks<Player> clicks = (UUID npc, Player player, boolean attack, String hand, boolean secondary) -> {};

    ModelEngineModels(Plugin plugin, Runnable closed) {
        this.plugin = Objects.requireNonNull(plugin);
        this.closed = Objects.requireNonNull(closed);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override public boolean show(UUID npc, ModelEngineModule module, String name, BodyPose pose) {
        remove(npc);
        if (ModelEngineAPI.getBlueprint(module.model()) == null) {
            if (missing.add(module.model())) plugin.getLogger().warning("Model Engine has no model '" + module.model() + "'; its NPCs show their own body");
            return false;
        }
        Location at = location(pose);
        if (at == null) return false;
        Dummy<UUID> base = new Dummy<>(npc);
        base.setDetectingPlayers(false);
        base.syncLocation(at);
        ModeledEntity entity = ModelEngineAPI.createModeledEntity(base);
        try { draw(npc, base, entity, module, name); }
        catch (RuntimeException failed) {
            entity.destroy();
            base.setRemoved(true);
            ModelEngineAPI.removeModeledEntity(base.getUUID());
            if (missing.add(module.model())) plugin.getLogger().log(Level.WARNING, "Model Engine could not draw '" + module.model() + "'; its NPCs show their own body", failed);
            return false;
        }
        return true;
    }

    private void draw(UUID npc, Dummy<UUID> base, ModeledEntity entity, ModelEngineModule module, String name) {
        ActiveModel model = ModelEngineAPI.createActiveModel(module.model());
        model.setScale(module.scale());
        model.setHitboxScale(module.scale());
        AnimationHandler animations = model.getAnimationHandler();
        for (ModelState state : DEFAULT_STATES) {
            String animation = module.states().get(ModelEngineModule.State.valueOf(state.name()));
            if (animation == null) continue;
            AnimationHandler.DefaultProperty current = animations.getDefaultProperty(state);
            animations.setDefaultProperty(new AnimationHandler.DefaultProperty(state, animation, current.getLerpIn(), current.getLerpOut(), current.getSpeed()));
        }
        entity.addModel(model, module.hitbox());
        // Model Engine names a nametag bone without the tag_ prefix it has in Blockbench; either spelling finds it.
        module.nametagBone().flatMap((String bone) -> model.getBone(bone).or(() -> model.getBone(bone.startsWith("tag_") ? bone.substring(4) : bone))).flatMap((ModelBone bone) -> bone.getBoneBehavior(BoneBehaviorTypes.NAMETAG)).ifPresent((NameTag tag) -> {
            tag.setString(RichText.colorize(name));
            tag.setVisible(true);
        });
        drawn.put(npc, new Drawn(base, entity, model, module));
        bases.put(base.getUUID(), npc);
    }

    @Override public void view(UUID npc, Player viewer, boolean visible) {
        Drawn shown = drawn.get(npc);
        if (shown != null) shown.base().setForceViewing(viewer, visible);
    }

    @Override public void move(UUID npc, BodyPose pose) {
        Drawn shown = drawn.get(npc);
        Location at = shown == null ? null : location(pose);
        if (at != null) shown.base().syncLocation(at);
    }

    @Override public void walking(UUID npc, boolean walking) {
        Drawn shown = drawn.get(npc);
        if (shown != null) shown.base().setWalking(walking);
    }

    @Override public void play(UUID npc, ModelEngineModule.State state) {
        Drawn shown = drawn.get(npc);
        if (shown == null) return;
        if (state == ModelEngineModule.State.HURT) shown.entity().markHurt();
        String animation = shown.module().animation(state);
        if (animation != null) shown.model().getAnimationHandler().playAnimation(animation, 0, 0, 1, true);
    }

    @Override public void glowing(UUID npc, boolean glowing) {
        Drawn shown = drawn.get(npc);
        if (shown != null) shown.model().setGlowing(glowing);
    }

    @Override public Optional<Size> hitbox(UUID npc) {
        Drawn shown = drawn.get(npc);
        if (shown == null) return Optional.empty();
        com.ticxo.modelengine.api.entity.Hitbox box = shown.model().getBlueprint().getMainHitbox();
        return Optional.of(new Size(box.getMaxWidth() * shown.module().scale(), box.getHeight() * shown.module().scale()));
    }

    @Override public void remove(UUID npc) {
        Drawn shown = drawn.remove(npc);
        if (shown == null) return;
        bases.remove(shown.base().getUUID());
        shown.entity().destroy();
        shown.base().setRemoved(true);
        ModelEngineAPI.removeModeledEntity(shown.base().getUUID());
    }

    @Override public void clicks(Clicks<Player> listener) { clicks = Objects.requireNonNull(listener); }

    /** Model Engine reports clicks on a model's hitbox; one on an NPC model reaches the runtime on the server thread. */
    @EventHandler public void clicked(BaseEntityInteractEvent event) {
        if (event.getAction() == BaseEntityInteractEvent.Action.INTERACT_ON) return;
        UUID npc = bases.get(event.getBaseEntity().getUUID());
        if (npc == null) return;
        Player player = event.getPlayer();
        boolean attack = event.getAction() == BaseEntityInteractEvent.Action.ATTACK;
        String hand = event.getSlot() == EquipmentSlot.OFF_HAND ? "OFF_HAND" : "MAIN_HAND";
        boolean secondary = event.isSecondary();
        Runnable click = () -> { if (plugin.isEnabled()) clicks.clicked(npc, player, attack, hand, secondary); };
        if (Bukkit.isPrimaryThread()) click.run();
        else plugin.getServer().getScheduler().runTask(plugin, click);
    }

    @Override public void close() {
        for (UUID npc : List.copyOf(drawn.keySet())) remove(npc);
        HandlerList.unregisterAll(this);
        closed.run();
    }

    private static Location location(BodyPose pose) {
        World world;
        try { world = Bukkit.getWorld(UUID.fromString(pose.world())); }
        catch (IllegalArgumentException notUuid) { world = Bukkit.getWorld(pose.world()); }
        return world == null ? null : new Location(world, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
    }
}
