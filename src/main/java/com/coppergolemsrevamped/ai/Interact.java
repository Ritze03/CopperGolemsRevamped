package com.coppergolemsrevamped.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared "get close, then interact" helpers for every profession: where a golem stands to touch a block
 * ({@link #approach}, {@link #standPos}, {@link #inReach}) and the visible chest lid ({@link #openChest}).
 * API and rationale: superdoc/architecture/interaction.md.
 */
public final class Interact {
	private Interact() {}

	/** How long a lid stays open after the last {@link #openChest} call. */
	public static final int OPEN_TICKS = 20;
	/** Lid closes early once the golem is farther than this from the chest centre. */
	static final double CLOSE_DIST = 3.0;
	static final int REPATH_TICKS = 20;
	/** Repaths without any reachable 1-block spot before the 2-block fallback kicks in (3 x 20 ticks). */
	static final int NO_SPOT_LIMIT = 3;
	/** Ticks without getting closer to the stand spot before it is abandoned (3 s). */
	static final int STUCK_TICKS = 60;
	static final double PROGRESS_EPS = 0.05;
	/** Legacy (2-block) walk without getting closer to the target for this long: drop the fallback and search spots again (10 s). */
	static final int LEGACY_STUCK_TICKS = 200;
	/** How far ahead a walk towards an unloaded target aims (inside the golem's own loaded chunks). */
	static final int TOWARDS_STEP = 16;
	/** Fallback reach: horizontal distance (to the block centre) and vertical window, the pre-1-block rule. */
	static final double NEAR_HORIZONTAL = 2.0;
	static final int NEAR_DOWN = 2;
	static final int NEAR_UP = 1;

	/** What the golem does at the chest; picks its animation state. */
	public enum Action {
		TAKE(CopperGolemState.GETTING_ITEM),
		INSERT(CopperGolemState.DROPPING_ITEM);

		final CopperGolemState state;

		Action(CopperGolemState state) {
			this.state = state;
		}
	}

	// ---- reach ----

	/** Offsets (dx, dy, dz) of golem feet relative to the target, in preference order. */
	private static final int[][] OFFSETS = buildOffsets();
	/** Index of the first offset that ranks lower (diagonally-down spots and standing on top). */
	private static final int LOW_RANK_FROM = 8;

	private static int[][] buildOffsets() {
		int[][] horiz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		List<int[]> l = new ArrayList<>();
		for (int[] h : horiz) l.add(new int[] {h[0], 0, h[1]});   // same level
		for (int[] h : horiz) l.add(new int[] {h[0], -1, h[1]});  // target diagonally one block up
		for (int[] h : horiz) l.add(new int[] {h[0], 1, h[1]});   // target diagonally one block down
		l.add(new int[] {0, 1, 0});                               // standing on top, target below
		return l.toArray(new int[0][]);
	}

	/** Golem feet are on one of the 1-block stand positions of {@code target} (see interaction.md). */
	public static boolean inReach(CopperGolem golem, BlockPos target) {
		BlockPos f = golem.blockPosition();
		int ox = f.getX() - target.getX();
		int oy = f.getY() - target.getY();
		int oz = f.getZ() - target.getZ();
		if (ox == 0 && oz == 0) return oy == 1;
		return Math.abs(ox) + Math.abs(oz) == 1 && oy >= -1 && oy <= 1;
	}

	/** The relaxed 2-block reach (the pre-existing rule): horizontal <= 2.0 to the centre, target 2 below to 1 above the feet. */
	public static boolean nearReach(CopperGolem golem, BlockPos target) {
		double dx = golem.getX() - (target.getX() + 0.5);
		double dz = golem.getZ() - (target.getZ() + 0.5);
		if (dx * dx + dz * dz > NEAR_HORIZONTAL * NEAR_HORIZONTAL) return false;
		int dy = target.getY() - golem.blockPosition().getY();
		return dy >= -NEAR_DOWN && dy <= NEAR_UP;
	}

	/**
	 * The chunk at {@code pos} is loaded right now. Every profession reads its assigned positions only through this
	 * guard (or a helper that uses it, such as {@code TransporterLogic:containerAt}): a block read in an unloaded chunk
	 * would load it synchronously, every tick or retry. An unloaded target is "not available right now". Why: see
	 * superdoc/features/chunk-loading.md, "Unloaded targets".
	 */
	public static boolean loaded(Level level, BlockPos pos) {
		return level.hasChunkAt(pos);
	}

	/** Feet block {@code pos} is somewhere the golem fits: body space free and a collidable block below. */
	public static boolean canStand(ServerLevel level, CopperGolem golem, BlockPos pos) {
		if (!loaded(level, pos) || !loaded(level, pos.below())) return false;
		double w = golem.getBbWidth() / 2.0;
		AABB box = new AABB(pos.getX() + 0.5 - w, pos.getY(), pos.getZ() + 0.5 - w,
			pos.getX() + 0.5 + w, pos.getY() + golem.getBbHeight(), pos.getZ() + 0.5 + w);
		if (!level.noCollision(golem, box)) return false;
		BlockPos below = pos.below();
		return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
	}

	/**
	 * The best 1-block stand position for {@code target}: standable and with a path the golem can finish, nearest to
	 * the golem first (diagonally-down spots and standing on top rank slightly lower). Null when none.
	 * Runs one path search per candidate until one works, so call it at repath rate, not every tick.
	 */
	public static @Nullable BlockPos standPos(ServerLevel level, CopperGolem golem, BlockPos target) {
		return standPos(level, golem, target, Set.of());
	}

	static @Nullable BlockPos standPos(ServerLevel level, CopperGolem golem, BlockPos target, Set<BlockPos> exclude) {
		BlockPos feet = golem.blockPosition();
		if (inReach(golem, target) && !exclude.contains(feet) && canStand(level, golem, feet)) return feet;
		record Cand(BlockPos pos, double score) {}
		List<Cand> cands = new ArrayList<>();
		for (int i = 0; i < OFFSETS.length; i++) {
			int[] o = OFFSETS[i];
			BlockPos p = target.offset(o[0], o[1], o[2]);
			if (exclude.contains(p) || !canStand(level, golem, p)) continue;
			double penalty = i >= LOW_RANK_FROM ? 2.0 : 0.0;
			cands.add(new Cand(p, golem.distanceToSqr(Vec3.atBottomCenterOf(p)) + penalty));
		}
		cands.sort((a, b) -> Double.compare(a.score, b.score));
		for (int i = 0; i < cands.size(); i++) {
			Path path = golem.getNavigation().createPath(cands.get(i).pos, 0);
			if (path != null && path.canReach()) return cands.get(i).pos;
			if (i == 0 && cands.size() > 2 && !anyReachable(golem, cands.subList(1, cands.size()).stream().map(Cand::pos).toList())) return null;
		}
		return null;
	}

	/**
	 * One multi-target path search: can the golem finish a path to at least one of {@code spots}? Why: when the best spot
	 * fails, the rest usually fail too (target walled off, or out of path range), and that used to cost one full search
	 * per spot, up to 13 per repath. The ordered per-spot loop still decides which spot is taken, so the choice is
	 * unchanged; this only skips it when nothing can be reached.
	 */
	private static boolean anyReachable(CopperGolem golem, List<BlockPos> spots) {
		Path path = golem.getNavigation().createPath(new HashSet<>(spots), 0);
		return path != null && path.canReach();
	}

	/**
	 * Walks towards a target whose chunk is not loaded, without reading it: navigation cannot path into an unloaded
	 * chunk ({@code GroundPathNavigation#createPath} returns null there), so it aims {@link #TOWARDS_STEP} blocks ahead
	 * in the target's direction. The golem's chunk ticket moves with it, so the target loads once it is near.
	 */
	public static void walkTowards(CopperGolem golem, BlockPos target, double speed) {
		Vec3 here = golem.position();
		Vec3 dir = new Vec3(target.getX() + 0.5 - here.x, 0, target.getZ() + 0.5 - here.z);
		double len = dir.length();
		if (len < 1.0E-3) return;
		Vec3 aim = here.add(dir.scale(Math.min(TOWARDS_STEP, len) / len));
		Path path = golem.getNavigation().createPath(BlockPos.containing(aim), 1);
		if (path == null) {
			Vec3 r = LandRandomPos.getPosTowards(golem, TOWARDS_STEP, 7, Vec3.atBottomCenterOf(target));
			if (r != null) path = golem.getNavigation().createPath(BlockPos.containing(r), 1);
		}
		if (path != null) golem.getNavigation().moveTo(path, speed);
	}

	/**
	 * Per-golem walking state for {@link #approach}. Keep one per golem in the profession's transient {@code State}.
	 * Call {@link #reset()} after finishing the work at the target; it also resets itself when the target block changes.
	 */
	public static final class Approach {
		@Nullable BlockPos target;
		@Nullable BlockPos goal;
		boolean legacy;
		/** Legacy walk: closest squared distance to the target so far, and ticks without beating it. */
		double legacyBest = Double.MAX_VALUE;
		int legacyStuck;
		int repath;
		int noSpot;
		int stuck;
		double best;
		final Set<BlockPos> bad = new HashSet<>();

		public void reset() {
			target = null;
			goal = null;
			legacy = false;
			legacyBest = Double.MAX_VALUE;
			legacyStuck = 0;
			repath = 0;
			noSpot = 0;
			stuck = 0;
			bad.clear();
		}

		/** True once the 2-block fallback is active for the current target. */
		public boolean isLegacy() {
			return legacy;
		}

		/** The stand position currently walked to, or null. */
		public @Nullable BlockPos goal() {
			return goal;
		}
	}

	/**
	 * One tick of "walk to the target". Returns true when the golem is within reach and standing (navigation
	 * stopped): do the interaction now. Otherwise it steers navigation and returns false.
	 * Prefers a 1-block stand spot; with none reachable (or none progressing) it falls back to the 2-block reach and
	 * the old walk-to-the-block behaviour; a legacy walk that gets no closer for {@link #LEGACY_STUCK_TICKS} starts over
	 * with the spot search. A target in an unloaded chunk is never read: the golem walks towards it
	 * ({@link #walkTowards}) and the spot search starts once it is loaded. It never sets a look target, callers do that.
	 */
	public static boolean approach(ServerLevel level, CopperGolem golem, BlockPos target, Approach st, double speed) {
		if (!target.equals(st.target)) {
			st.reset();
			st.target = target.immutable();
		}
		if (inReach(golem, target) || (st.legacy && nearReach(golem, target))) {
			golem.getNavigation().stop();
			st.repath = 0;
			return true;
		}
		if (!loaded(level, target)) {
			if (--st.repath <= 0 || golem.getNavigation().isDone()) {
				walkTowards(golem, target, speed);
				st.repath = REPATH_TICKS;
			}
			return false;
		}
		if (st.legacy) {
			double d = golem.distanceToSqr(Vec3.atCenterOf(target));
			if (d < st.legacyBest - PROGRESS_EPS) {
				st.legacyBest = d;
				st.legacyStuck = 0;
			} else if (golem.onGround() && ++st.legacyStuck >= LEGACY_STUCK_TICKS) {
				// walled off for good, or blocked by something that may have moved: forget the fallback and search spots again
				st.reset();
				st.target = target.immutable();
				return false;
			}
			if (--st.repath <= 0 || golem.getNavigation().isDone()) {
				golem.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
				st.repath = REPATH_TICKS;
			}
			return false;
		}
		BlockPos goal = st.goal;
		if (goal != null) {
			double d = golem.distanceToSqr(Vec3.atBottomCenterOf(goal));
			if (d < st.best - PROGRESS_EPS) {
				st.best = d;
				st.stuck = 0;
			} else if (++st.stuck >= STUCK_TICKS) {
				st.bad.add(goal);
				st.goal = null;
				st.stuck = 0;
				st.repath = 0;
				goal = null;
			}
		}
		if (goal == null) {
			if (--st.repath > 0) return false;
			st.repath = REPATH_TICKS;
			goal = standPos(level, golem, target, st.bad);
			if (goal == null) {
				// A null can be transient (spawn tick, mid-air), so only a few in a row mean "walled off".
				if (++st.noSpot >= NO_SPOT_LIMIT) {
					st.legacy = true;
					st.repath = 0;
				}
				return false;
			}
			st.noSpot = 0;
			st.goal = goal;
			st.best = golem.distanceToSqr(Vec3.atBottomCenterOf(goal));
			st.stuck = 0;
			Path path = golem.getNavigation().createPath(goal, 0);
			if (path != null) golem.getNavigation().moveTo(path, speed);
			return false;
		}
		if (--st.repath <= 0 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(goal, 0);
			if (path != null) golem.getNavigation().moveTo(path, speed);
			st.repath = REPATH_TICKS;
		}
		return false;
	}

	// ---- visible chest lid ----

	private static final class Open {
		final ServerLevel level;
		final BlockPos pos;
		long closeAt;

		Open(ServerLevel level, BlockPos pos, long closeAt) {
			this.level = level;
			this.pos = pos;
			this.closeAt = closeAt;
		}
	}

	/** Golem -> the one container it currently holds open. Entries live at most {@link #OPEN_TICKS} ticks. */
	private static final Map<CopperGolem, Open> OPEN = new HashMap<>();
	private static boolean hooked;

	/**
	 * Opens the lid of the container at {@code chest} for the golem (opener count +1, vanilla open sound, animation
	 * state) and schedules the close: {@link #OPEN_TICKS} ticks from now, earlier when the golem walks away, dies or
	 * is removed, or when it opens another container. Calling again for the same chest just extends the timer.
	 * Call it right when the golem takes or inserts items. No-op when {@code chest} is not a container.
	 */
	public static void openChest(ServerLevel level, CopperGolem golem, BlockPos chest, Action action) {
		hook();
		long closeAt = level.getGameTime() + OPEN_TICKS;
		Open cur = OPEN.get(golem);
		if (cur != null) {
			if (cur.pos.equals(chest) && cur.level == level) {
				cur.closeAt = closeAt;
				golem.setState(action.state);
				return;
			}
			closeChest(golem);
		}
		if (!(level.getBlockEntity(chest) instanceof Container c)) return;
		BlockPos pos = chest.immutable();
		golem.setOpenedChestPos(pos);
		c.startOpen(golem);
		golem.setState(action.state);
		OPEN.put(golem, new Open(level, pos, closeAt));
	}

	/** Closes the lid this golem holds open now (idempotent; also what the automatic close calls). */
	public static void closeChest(CopperGolem golem) {
		Open o = OPEN.remove(golem);
		if (o != null) release(golem, o);
	}

	/** True while this golem holds a container open through {@link #openChest}. */
	public static boolean isOpen(CopperGolem golem) {
		return OPEN.containsKey(golem);
	}

	/**
	 * Why the guard: ContainerOpenersCounter#decrementOpeners decrements blindly, but the counter also rechecks
	 * itself every 5 ticks from the entities that report hasContainerOpen, so it may already have dropped this golem
	 * (removed, far away). Only a golem it still counts is stopped; the rest self-heals.
	 */
	private static void release(CopperGolem golem, Open o) {
		BlockEntity be = loaded(o.level, o.pos) ? o.level.getBlockEntity(o.pos) : null;
		if (be instanceof Container c && c.getEntitiesWithContainerOpen().contains(golem)) c.stopOpen(golem);
		golem.clearOpenedChestPos();
		if (golem.getState() != CopperGolemState.IDLE) golem.setState(CopperGolemState.IDLE);
	}

	private static void hook() {
		if (hooked) return;
		hooked = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (OPEN.isEmpty()) return;
			for (Iterator<Map.Entry<CopperGolem, Open>> it = OPEN.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<CopperGolem, Open> e = it.next();
				CopperGolem g = e.getKey();
				Open o = e.getValue();
				boolean far = g.distanceToSqr(Vec3.atCenterOf(o.pos)) > CLOSE_DIST * CLOSE_DIST;
				if (g.isRemoved() || !g.isAlive() || g.level() != o.level || far || o.level.getGameTime() >= o.closeAt) {
					it.remove();
					release(g, o);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Map.Entry<CopperGolem, Open> e : new ArrayList<>(OPEN.entrySet())) release(e.getKey(), e.getValue());
			OPEN.clear();
		});
	}
}
