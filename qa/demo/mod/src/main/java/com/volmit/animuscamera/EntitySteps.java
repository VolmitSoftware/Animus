package com.volmit.animuscamera;

import com.volmit.animuscamera.ScriptRunner.ScriptContext;
import com.volmit.animuscamera.mixin.MinecraftAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Steps that find, face, inspect and act on entities, plus world readiness and pose logging. */
public final class EntitySteps {
	private static final double FALLBACK_REACH = 4.5;
	private static final int WORLD_SETTLE_TICKS = 20;

	private EntitySteps() {
	}

	public static void waitForWorld(ScriptContext context, long timeout) {
		Minecraft minecraft = context.minecraft();
		String ready = ScriptSteps.waitFor(timeout, () -> {
			LocalPlayer player = minecraft.player;
			if (player == null || minecraft.level == null || minecraft.gui.screen() != null || player.tickCount < WORLD_SETTLE_TICKS) {
				return null;
			}

			return minecraft.level.isLoaded(player.blockPosition()) ? "ready" : null;
		}, true);
		if (ready == null) {
			String state = ClientThread.call(() -> String.format(
				Locale.ROOT, "player=%s level=%s screen=%s ticks=%d",
				minecraft.player != null, minecraft.level != null,
				minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName(),
				minecraft.player == null ? 0 : minecraft.player.tickCount
			));
			throw new StepFailure("World not ready within " + timeout + " ms (" + state + ")");
		}

		logPose(context);
	}

	public static void turn(ScriptContext context, float yaw, float pitch, long millis, int entityId) {
		Minecraft minecraft = context.minecraft();
		ScriptSteps.requirePlayer(minecraft);
		ClientThread.run(() -> Overlay.turnTo(minecraft.player, yaw, pitch, millis));
		ScriptSteps.sleep(millis + 50L);
		ClientThread.run(() -> ((MinecraftAccessor) minecraft).animuscamera$pick(1.0F));
		context.setLastEntityId(entityId);
		logPose(context);
	}

	public static void lookAt(ScriptContext context, Vec3 target, long millis) {
		ScriptSteps.requirePlayer(context.minecraft());
		float[] angles = ClientThread.call(() -> anglesTo(context.minecraft().player, target));
		turn(context, angles[0], angles[1], millis, -1);
	}

	public static void lookAtEntity(ScriptContext context, String name, long millis) {
		Minecraft minecraft = context.minecraft();
		ScriptSteps.requirePlayer(minecraft);
		String needle = name.toLowerCase(Locale.ROOT);
		Target target = ClientThread.call(() -> {
			LocalPlayer player = minecraft.player;
			Entity best = null;
			double bestDistance = Double.MAX_VALUE;
			for (Entity entity : minecraft.level.entitiesForRendering()) {
				if (entity == player || !matchesName(entity, needle)) {
					continue;
				}

				double distance = entity.distanceToSqr(player);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = entity;
				}
			}

			return best == null ? null : Target.of(player, best);
		});
		if (target == null) {
			throw new StepFailure("lookAtEntity found no entity named like '" + name + "'");
		}

		context.log(target.describe("lookAtEntity matched"));
		turn(context, target.yaw, target.pitch, millis, target.id);
	}

	/** Uses the entity from the last lookAtEntity when the crosshair is not on an entity. Returns true when it acted. */
	public static boolean actOnLastEntity(ScriptContext context, boolean attack) {
		Minecraft minecraft = context.minecraft();
		ScriptSteps.requirePlayer(minecraft);
		String outcome = ClientThread.call(() -> {
			((MinecraftAccessor) minecraft).animuscamera$pick(1.0F);
			if (minecraft.hitResult instanceof EntityHitResult) {
				return null;
			}

			LocalPlayer player = minecraft.player;
			Entity entity = context.lastEntityId() < 0 ? null : minecraft.level.getEntity(context.lastEntityId());
			if (entity == null || entity.distanceTo(player) > FALLBACK_REACH + entity.getBbWidth()) {
				return attack ? "fail" : null;
			}

			if (attack) {
				minecraft.gameMode.attack(player, entity);
			} else {
				EntityHitResult hit = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
				minecraft.gameMode.interact(player, entity, hit, InteractionHand.MAIN_HAND);
			}

			player.swing(InteractionHand.MAIN_HAND);
			return String.format(Locale.ROOT, "%s fallback used on %s id=%d (crosshair hit %s)",
				attack ? "attackTarget" : "useItem", plain(entity.getDisplayName()), entity.getId(), describeHit(minecraft.hitResult));
		});
		if ("fail".equals(outcome)) {
			throw new StepFailure("No entity under the crosshair and no recent lookAtEntity target in reach");
		}

		if (outcome != null) {
			context.log(outcome);
			return true;
		}

		return false;
	}

	public static void logPose(ScriptContext context) {
		Minecraft minecraft = context.minecraft();
		String pose = ClientThread.call(() -> {
			LocalPlayer player = minecraft.player;
			if (player == null) {
				return null;
			}

			return String.format(Locale.ROOT, "Pose: pos %.2f %.2f %.2f yaw %.1f pitch %.1f, crosshair %s",
				player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), describeHit(minecraft.hitResult));
		});
		if (pose != null) {
			context.log(pose);
		}
	}

	private static String describeHit(HitResult hit) {
		if (hit instanceof EntityHitResult entityHit) {
			return "entity " + entityHit.getEntity().getId() + " " + plain(entityHit.getEntity().getDisplayName());
		}

		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			return "block " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		}

		return "nothing";
	}

	private static boolean matchesName(Entity entity, String needle) {
		List<String> names = new ArrayList<>();
		names.add(plain(entity.getDisplayName()));
		names.add(plain(entity.getName()));
		if (entity.getCustomName() != null) {
			names.add(plain(entity.getCustomName()));
		}

		if (entity instanceof Player player) {
			names.add(player.getGameProfile().name());
		}

		for (String name : names) {
			if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) {
				return true;
			}
		}

		return false;
	}

	private static String plain(Component component) {
		String stripped = ChatFormatting.stripFormatting(component.getString());
		return stripped == null ? "" : stripped;
	}

	private static float[] anglesTo(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new float[]{yaw, pitch};
	}

	private record Target(int id, String type, String name, Vec3 aim, Vec3 position, double distance, float yaw, float pitch) {
		static Target of(LocalPlayer player, Entity entity) {
			Vec3 aim = entity instanceof LivingEntity ? entity.getEyePosition() : entity.getBoundingBox().getCenter();
			float[] angles = anglesTo(player, aim);
			return new Target(
				entity.getId(), String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())), plain(entity.getDisplayName()),
				aim, entity.position(), entity.distanceTo(player), angles[0], angles[1]
			);
		}

		String describe(String prefix) {
			return String.format(Locale.ROOT, "%s %s %s (id %d) at %.2f %.2f %.2f, %.1f blocks", prefix, this.type, this.name, this.id,
				this.position.x, this.position.y, this.position.z, this.distance);
		}
	}
}
