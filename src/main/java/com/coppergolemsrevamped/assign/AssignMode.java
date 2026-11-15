package com.coppergolemsrevamped.assign;

import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.ProfessionHelp;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Armed next-click target assignment. A golem GUI "Assign" button calls {@link #arm}; the player's next
 * main-hand block right-click(s) are consumed server-side and written to {@link GolemData#assignments}.
 * Server-side only; see superdoc/features/assign-mode.md.
 */
public final class AssignMode {
	/** Vanilla copper golem search radius. */
	public static final double MAX_RANGE = 32;
	public static final int TIMEOUT_TICKS = 600;

	private AssignMode() {}

	private static final class Pending {
		final CopperGolem golem;
		final Identifier profession;
		final AssignSpec spec;
		int armedAt;
		@Nullable BlockPos first;

		Pending(CopperGolem golem, Identifier profession, AssignSpec spec, int armedAt) {
			this.golem = golem;
			this.profession = profession;
			this.spec = spec;
			this.armedAt = armedAt;
		}
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			return onClick(sp, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(AssignMode::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> PENDING.clear());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
	}

	/** Main-hand block click of an armed player; false when the player has nothing armed. Also the test entry point. */
	public static boolean onClick(ServerPlayer player, BlockPos pos) {
		Pending p = PENDING.get(player.getUUID());
		if (p == null) return false;
		click(player, p, pos);
		return true;
	}

	public static void arm(ServerPlayer player, CopperGolem golem, String assignKey) {
		Profession prof = GolemService.profession(golem);
		AssignSpec spec = null;
		if (prof != null) {
			for (AssignSpec s : prof.assigns()) if (s.key().equals(assignKey)) spec = s;
		}
		if (spec == null) {
			msg(player, "This golem cannot assign '" + assignKey + "'.");
			return;
		}
		PENDING.put(player.getUUID(), new Pending(golem, prof.id(), spec, player.level().getServer().getTickCount()));
		msg(player, spec.label().getString() + ": " + capitalize(ProfessionHelp.prompt(spec))
			+ ". Sneak-right-click to cancel.");
	}

	private static void click(ServerPlayer player, Pending p, BlockPos pos) {
		UUID id = player.getUUID();
		int now = player.level().getServer().getTickCount();
		if (player.isShiftKeyDown()) {
			PENDING.remove(id);
			msg(player, "Assignment cancelled.");
			return;
		}
		if (now - p.armedAt > TIMEOUT_TICKS) {
			PENDING.remove(id);
			msg(player, "Assignment timed out.");
			return;
		}
		if (p.golem.isRemoved() || !p.golem.isAlive() || p.golem.level() != player.level()) {
			PENDING.remove(id);
			msg(player, "Assignment cancelled: the golem is gone.");
			return;
		}
		GolemData data = GolemData.get(p.golem);
		if (!p.profession.equals(data.profession)) {
			PENDING.remove(id);
			msg(player, "Assignment cancelled: the golem's profession changed.");
			return;
		}
		AssignSpec spec = p.spec;
		BlockState state = player.level().getBlockState(pos);
		if (!matches(spec.kind(), state)) {
			msg(player, "That is not " + prompt(spec.kind(), p.first != null) + ".");
			return;
		}
		if (!inRange(p.golem.position(), pos)) {
			msg(player, "Too far from the golem (max " + (int) MAX_RANGE + " blocks).");
			return;
		}
		String label = spec.label().getString();
		if (spec.kind() == Kind.AREA) {
			if (p.first == null) {
				p.first = pos.immutable();
				p.armedAt = now;
				msg(player, "First corner set to " + fmt(pos) + ". Right-click the opposite corner.");
				return;
			}
			BlockPos second = clampCorner(p.first, pos, spec.maxSize());
			data.assignments.put(spec.key() + "_a", p.first);
			data.assignments.put(spec.key() + "_b", second);
			msg(player, label + " set to " + fmt(p.first) + " to " + fmt(second));
			if (!second.equals(pos)) {
				msg(player, "Area capped to " + spec.maxSize() + "x" + spec.maxSize() + " (max)");
			}
			pos = second;
		} else {
			data.assignments.put(spec.key(), pos.immutable());
			msg(player, label + " set to " + fmt(pos));
		}
		GolemData.save(p.golem, data);
		// visual confirmation: walk next to the block (areas get none, user request)
		if (spec.kind() != Kind.AREA && p.golem.level() instanceof ServerLevel level) {
			WalkTo.toBlock(level, p.golem, pos);
		}
		PENDING.remove(id);
	}

	private static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) return;
		int now = server.getTickCount();
		for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Pending> e = it.next();
			if (now - e.getValue().armedAt > TIMEOUT_TICKS) {
				it.remove();
				ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
				if (sp != null) msg(sp, "Assignment timed out.");
			}
		}
	}

	// ---- pure helpers (testable) ----

	/** Footprint |ax-bx|+1 and |az-bz|+1 must each be at most {@code max}. */
	public static boolean areaFits(BlockPos a, BlockPos b, int max) {
		return Math.abs(a.getX() - b.getX()) + 1 <= max && Math.abs(a.getZ() - b.getZ()) + 1 <= max;
	}

	/**
	 * The second corner pulled toward the first until the footprint is at most {@code max} on each axis
	 * (first + sign(d) * (max - 1)); Y is kept. Why: a too large request is capped, not refused (user decision).
	 */
	public static BlockPos clampCorner(BlockPos first, BlockPos second, int max) {
		int dx = second.getX() - first.getX(), dz = second.getZ() - first.getZ();
		int cx = Math.max(-(max - 1), Math.min(max - 1, dx));
		int cz = Math.max(-(max - 1), Math.min(max - 1, dz));
		return new BlockPos(first.getX() + cx, second.getY(), first.getZ() + cz);
	}

	/** Distance from the golem to the block centre is at most {@link #MAX_RANGE}. */
	public static boolean inRange(Vec3 golemPos, BlockPos pos) {
		return golemPos.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_RANGE * MAX_RANGE;
	}

	public static boolean matches(Kind kind, BlockState state) {
		return switch (kind) {
			case CHEST -> state.getBlock() instanceof ChestBlock;
			case CRAFTING_TABLE -> state.is(Blocks.CRAFTING_TABLE);
			case BLOCK, AREA -> !state.isAir();
		};
	}

	private static String prompt(Kind kind, boolean secondCorner) {
		return switch (kind) {
			case CHEST -> "a chest";
			case CRAFTING_TABLE -> "a crafting table";
			case BLOCK -> "a block";
			case AREA -> secondCorner ? "a block (opposite corner)" : "a block (first corner)";
		};
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	private static void msg(ServerPlayer player, String s) {
		player.sendSystemMessage(Component.literal(s));
	}
}
