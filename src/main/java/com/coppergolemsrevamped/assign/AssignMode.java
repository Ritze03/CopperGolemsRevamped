package com.coppergolemsrevamped.assign;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
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
			Pending p = PENDING.get(sp.getUUID());
			if (p == null) return InteractionResult.PASS;
			click(sp, p, hit.getBlockPos());
			return InteractionResult.SUCCESS;
		});
		ServerTickEvents.END_SERVER_TICK.register(AssignMode::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
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
		msg(player, "Right-click " + prompt(spec.kind(), false) + " to set " + spec.label().getString()
			+ " (sneak-right-click to cancel)");
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
			if (!areaFits(p.first, pos, spec.maxSize())) {
				msg(player, "Area too large: " + (Math.abs(p.first.getX() - pos.getX()) + 1) + "x"
					+ (Math.abs(p.first.getZ() - pos.getZ()) + 1) + ", max " + spec.maxSize() + "x" + spec.maxSize());
				return;
			}
			data.assignments.put(spec.key() + "_a", p.first);
			data.assignments.put(spec.key() + "_b", pos.immutable());
			msg(player, label + " set to " + fmt(p.first) + " to " + fmt(pos));
		} else {
			data.assignments.put(spec.key(), pos.immutable());
			msg(player, label + " set to " + fmt(pos));
		}
		GolemData.save(p.golem, data);
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

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	private static void msg(ServerPlayer player, String s) {
		player.sendSystemMessage(Component.literal(s));
	}
}
