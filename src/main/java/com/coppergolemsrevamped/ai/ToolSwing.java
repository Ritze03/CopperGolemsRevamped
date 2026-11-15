package com.coppergolemsrevamped.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The visible "golem uses its tool" motion, shared by Lumber and Farmer. One swing = {@link #PERIOD} ticks: the golem is
 * put in the {@link #RAISED} state for {@link #RAISED_TICKS} ticks (the vanilla client plays its chest-reach animation:
 * arm up about 90 degrees, tool overhead), then in IDLE for the rest, which snaps the arm and tool down like a chop.
 * Hit sound and block crack progress come on that snap. Server side only; vanilla clients just see entity data.
 * Why this and not a swing: the vanilla copper golem model ignores swing progress, see
 * superdoc/architecture/vanilla-copper-golem.md "Animations the client can show".
 *
 * The caller owns the tick counter and calls {@link #tick} once per tick of work, then {@link #end} when the work is
 * done. A caller that just stops calling (profession change, unload, give-up) is cleaned up after {@link #STALE}
 * ticks by an end-of-tick hook, so the state can never stay stuck.
 */
public final class ToolSwing {
	private ToolSwing() {}

	/** Ticks per swing. Shorter and the arm never reaches the raised pose (the reach animation needs ~9 ticks). */
	public static final int PERIOD = 12;
	/** Ticks of a swing spent in {@link #RAISED}; the remaining {@code PERIOD - RAISED_TICKS} are the idle snap. */
	public static final int RAISED_TICKS = 10;
	/** The vanilla state whose animation lifts the arms; any non-IDLE state restarts it from the beginning. */
	public static final CopperGolemState RAISED = CopperGolemState.GETTING_NO_ITEM;
	/**
	 * The state {@link #hold} keeps on for the whole work. GETTING_ITEM, not GETTING_NO_ITEM: in its 3 s loop both arms
	 * stay overhead for ~2.9 s (the other three lower them for 0.8 to 1.3 s), with a working-like sway in between.
	 */
	public static final CopperGolemState HELD = CopperGolemState.GETTING_ITEM;
	/** A swing not refreshed for this many ticks is ended by the hook. */
	static final int STALE = 3;

	private static final class Active {
		long last;
		BlockPos crack;
		boolean hold;
	}

	private static final Map<CopperGolem, Active> ACTIVE = new HashMap<>();
	private static boolean hooked;

	/** Ticks a break of {@code base} ticks takes with swinging: whole swings, at least {@code minSwings}. */
	public static int swingTicks(int base, int minSwings) {
		return Math.max(minSwings, (base + PERIOD - 1) / PERIOD) * PERIOD;
	}

	/**
	 * One tick of tool use on {@code pos}; {@code elapsed} counts from 0 and the work is {@code total} ticks long (a
	 * multiple of {@link #PERIOD} is best: the work then finishes right after a snap). With {@code crack} the block shows
	 * break progress that grows with every swing.
	 */
	public static void tick(ServerLevel level, CopperGolem golem, BlockPos pos, int elapsed, int total, boolean crack) {
		work(level, golem, pos, elapsed, total, crack, false);
	}

	/**
	 * Like {@link #tick} but the golem is held in {@link #HELD} for as long as this is called, with no IDLE pulses: the
	 * vanilla client plays its looping chest-interaction animation uninterrupted. Hit sound and crack stay on the same
	 * cadence (once per {@link #PERIOD} ticks, at phase {@link #RAISED_TICKS}). Between two blocks call {@link #clearCrack}
	 * instead of {@link #end}: the state then carries over, and a walk longer than {@link #STALE} ticks ends it by the hook.
	 */
	public static void hold(ServerLevel level, CopperGolem golem, BlockPos pos, int elapsed, int total, boolean crack) {
		work(level, golem, pos, elapsed, total, crack, true);
	}

	private static void work(ServerLevel level, CopperGolem golem, BlockPos pos, int elapsed, int total, boolean crack, boolean hold) {
		hook();
		Active a = ACTIVE.computeIfAbsent(golem, g -> new Active());
		a.last = level.getGameTime();
		a.hold = hold;
		int phase = elapsed % PERIOD;
		if (hold) {
			if (golem.getState() != HELD) golem.setState(HELD);
		} else if (phase < RAISED_TICKS) {
			if (golem.getState() != RAISED) golem.setState(RAISED);
			return;
		}
		if (phase != RAISED_TICKS) return;
		// the chop: the pulse mode snaps arm and tool down here, the hold mode only makes the sound and crack
		if (!hold) golem.setState(CopperGolemState.IDLE);
		BlockState st = level.getBlockState(pos);
		level.playSound(null, pos, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6F, 0.8F);
		if (crack) {
			int stage = Math.min(9, (elapsed + 1) * 10 / Math.max(1, total));
			level.destroyBlockProgress(golem.getId(), pos, stage);
			a.crack = pos.immutable();
		}
	}

	/** Clears the crack overlay only; the held state and the active entry stay (see {@link #hold}). */
	public static void clearCrack(ServerLevel level, CopperGolem golem) {
		Active a = ACTIVE.get(golem);
		if (a == null || a.crack == null) return;
		level.destroyBlockProgress(golem.getId(), a.crack, -1);
		a.crack = null;
	}

	/** Ends the tool use: clears the crack and returns the golem to IDLE (only if this class raised or held it). Idempotent. */
	public static void end(ServerLevel level, CopperGolem golem) {
		Active a = ACTIVE.remove(golem);
		if (a != null && a.crack != null) level.destroyBlockProgress(golem.getId(), a.crack, -1);
		CopperGolemState mine = a != null && a.hold ? HELD : RAISED;
		if (golem.getState() == mine && !Interact.isOpen(golem)) golem.setState(CopperGolemState.IDLE);
	}

	/** Test hook: is the golem in the middle of a tool use. */
	public static boolean active(CopperGolem golem) {
		return ACTIVE.containsKey(golem);
	}

	private static void hook() {
		if (hooked) return;
		hooked = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ACTIVE.isEmpty()) return;
			for (CopperGolem g : new ArrayList<>(ACTIVE.keySet())) {
				if (g.isRemoved() || !(g.level() instanceof ServerLevel level)) {
					ACTIVE.remove(g);
				} else if (level.getGameTime() - ACTIVE.get(g).last >= STALE) {
					end(level, g);
				}
			}
		});
	}
}
