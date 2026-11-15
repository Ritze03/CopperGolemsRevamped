package com.coppergolemsrevamped.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Transient walk-to override: a queue of stand positions the golem walks to in order, run by GolemTicker before
 * everything else. Used as visual confirmation after an assignment (walk to the block) and by the menu "Show" button.
 * Each waypoint has its own timeout and is skipped when there is no path, so an unreachable one never blocks.
 * Not persisted: a restart simply drops the walk.
 */
public final class WalkTo {
	private WalkTo() {}

	public static final int TIMEOUT_TICKS = 200;
	static final double SPEED = 1.0;
	static final int REPATH_TICKS = 10;
	/** Failed path attempts before a waypoint is skipped (a golem in mid-air cannot path yet, so one failure is not final). */
	static final int MAX_FAILS = 3;
	/** Horizontal distance (blocks) to a stand position that counts as arrived. */
	static final double REACHED = 1.0;

	private static final class State {
		final Deque<BlockPos> queue = new ArrayDeque<>();
		int waited;
		int fails;
		boolean started;
		/** Block walk: driven by {@link Interact#approach} (the professions' reach rule); null for an outline walk. */
		@Nullable BlockPos block;
		final Interact.Approach approach = new Interact.Approach();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Total ticks a block walk may take before it gives up (Interact's own fallback ends it sooner when stuck). */
	public static final int BLOCK_TIMEOUT_TICKS = 600;

	/**
	 * Walk until the golem is in reach of {@code block} exactly as a profession would be before opening it
	 * ({@link Interact#approach}: 1-block stand position, 2-block fallback).
	 */
	public static void toBlock(ServerLevel level, CopperGolem golem, BlockPos block) {
		set(golem, List.of(block));
		STATES.get(golem).block = block.immutable();
	}

	/** Walk the rectangle spanned by the two corners once: its 4 corners in order, then back to the first. */
	public static void outline(ServerLevel level, CopperGolem golem, BlockPos a, BlockPos b) {
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		int top = Math.max(a.getY(), b.getY()) + 2, bottom = Math.min(a.getY(), b.getY()) - 2;
		int[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}, {x0, z0}};
		List<BlockPos> stands = new ArrayList<>();
		for (int[] c : corners) stands.add(groundAt(level, c[0], c[1], top, bottom, a.getY() + 1));
		set(golem, stands);
	}

	public static void cancel(CopperGolem golem) {
		if (STATES.remove(golem) != null) golem.getNavigation().stop();
	}

	public static boolean active(CopperGolem golem) {
		return STATES.containsKey(golem);
	}

	private static void set(CopperGolem golem, List<BlockPos> stands) {
		State s = new State();
		s.queue.addAll(stands);
		STATES.put(golem, s);
	}

	/** Advances the walk; true while a walk is active (the caller then skips the golem's normal behaviour this tick). */
	public static boolean tick(ServerLevel level, CopperGolem golem) {
		State s = STATES.get(golem);
		if (s == null) return false;
		BlockPos target = s.queue.peek();
		if (target == null) {
			cancel(golem);
			return false;
		}
		if (s.block != null) {
			if (Interact.approach(level, golem, s.block, s.approach, SPEED) || ++s.waited > BLOCK_TIMEOUT_TICKS) {
				return next(golem, s);
			}
			return true;
		}
		if (reached(golem, target)) return next(golem, s);
		if (++s.waited > TIMEOUT_TICKS) return next(golem, s);
		boolean due = s.started ? s.waited % REPATH_TICKS == 0 && golem.getNavigation().isDone() : s.waited % REPATH_TICKS == 1;
		if (due) {
			boolean ok = golem.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
			if (ok) {
				s.started = true;
			} else if (++s.fails >= MAX_FAILS) {
				return next(golem, s); // no path: skip this waypoint
			}
		}
		return true;
	}

	private static boolean next(CopperGolem golem, State s) {
		s.queue.poll();
		s.waited = 0;
		s.fails = 0;
		s.started = false;
		golem.getNavigation().stop();
		if (s.queue.isEmpty()) {
			STATES.remove(golem);
			return false;
		}
		return true;
	}

	private static boolean reached(CopperGolem golem, BlockPos stand) {
		double dx = golem.getX() - (stand.getX() + 0.5), dz = golem.getZ() - (stand.getZ() + 0.5);
		return dx * dx + dz * dz <= REACHED * REACHED && Math.abs(golem.getY() - stand.getY()) < 2.0;
	}

	// ---- stand-position search ----

	/** A position the golem can stand in: free body space and head space, solid floor. */
	static boolean standable(ServerLevel level, BlockPos p) {
		BlockState feet = level.getBlockState(p);
		BlockState head = level.getBlockState(p.above());
		BlockState floor = level.getBlockState(p.below());
		return feet.getCollisionShape(level, p).isEmpty() && head.getCollisionShape(level, p.above()).isEmpty()
			&& floor.isFaceSturdy(level, p.below(), Direction.UP);
	}

	/** Ground in the column: highest standable position between {@code top} and {@code bottom}, else {@code fallbackY}. */
	static BlockPos groundAt(ServerLevel level, int x, int z, int top, int bottom, int fallbackY) {
		for (int y = top; y >= bottom; y--) {
			BlockPos p = new BlockPos(x, y, z);
			if (standable(level, p)) return p;
		}
		return new BlockPos(x, fallbackY, z);
	}
}
