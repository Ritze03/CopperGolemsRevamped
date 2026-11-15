package com.coppergolemsrevamped.profession.lumber;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lumber work loop. One assigned tree spot T (the clicked block: sapling or bottom trunk). The golem detects the whole
 * tree (every connected log of the same kind, 2x2 trunks and branches included), walks to a ground spot next to the
 * trunk ({@link Interact#approach}) and fells it from the ground: it never climbs and never moves into the trunk. Then it
 * collects the drops, replants (1 or 2x2 saplings) and bone-meals the sapling when it has bone meal. Every break goes
 * through {@link #breakBlock}. See superdoc/features/lumber.md.
 */
public final class LumberLogic {
	private LumberLogic() {}

	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	static final int AFTER_ACTION_TICKS = 5;
	static final int REPATH_TICKS = 20;
	static final double SPEED = 1.0;
	/** Walking to one target this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up tree, chest or item is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;
	/** The sapling slot is topped up from the input chest when it holds fewer than this. */
	static final int SAPLING_LOW = 16;
	/** Bone meal is fetched when the golem holds fewer than this, up to {@link #BONE_TARGET}. */
	static final int BONE_LOW = 4;
	static final int BONE_TARGET = 16;
	/** Ticks between two bone meal uses. */
	static final int BONE_DELAY = 10;

	/** Tree detection bounds: horizontal radius around T, height above T, block count. */
	static final int TREE_RADIUS = 8;
	public static final int TREE_HEIGHT = 32;
	static final int TREE_MAX_LOGS = 256;
	/** The golem may break a log whose horizontal distance to it is at most this (it reaches up, not sideways). */
	public static final double REACH_H = 5.5;
	/** Logs within this horizontal distance of T are worked on from T's stand spot, farther ones from a spot nearer to them. */
	static final double NEAR_TREE = 3.5;
	/** Ground-drop area around T: horizontal radius, blocks below T, blocks above T. */
	static final int ITEM_RADIUS = 6;
	static final int ITEM_BELOW = 1;
	static final int ITEM_ABOVE = 4;
	static final double PICKUP_RANGE_SQR = 1.5 * 1.5;

	public enum Result { BROKEN, FULL, DENIED }

	private static final class State {
		/** The tree spot this state was built for; a different spot (reassigned tree) starts a fresh state. */
		@Nullable BlockPos tree;
		int cooldown;
		final Interact.Approach walk = new Interact.Approach();
		/** Walk progress tracking (the target being walked to, best squared distance, ticks without progress). */
		@Nullable Object goalKey;
		double bestDist;
		int stuck;
		@Nullable BlockPos breakPos;
		int breakLeft;
		/** The next drop did not fit into the carry slots: deliver before felling on. */
		boolean mustDeliver;
		/** The golem has walked to its stand spot for the current felling round. */
		boolean anchored;
		/** Logs seen at the last detection: seeds for the next one (T itself may already be gone). */
		Set<BlockPos> known = new HashSet<>();
		@Nullable Set<BlockPos> logs;
		/** Bottom layer of the felled tree (4 cells for a 2x2 trunk), else empty: where to replant. */
		List<BlockPos> footprint = List.of();
		/** Logs this golem could not reach from a spot it tried (tried again once the tree is gone and a new one grows). */
		final Set<BlockPos> unreach = new HashSet<>();
		int boneCooldown;
		/** Given-up targets (tree spot, chest or item UUID) -> game time until which they are ignored. */
		final Map<Object, Long> skip = new HashMap<>();
		/** Item chase tracking. */
		@Nullable ItemEntity chasing;
		int chaseRepath;
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	// ---- pure rules (public for tests) ----------------------------------------------------------------------------

	/**
	 * THE break rule: a golem at ({@code gx}, {@code gz}) may break {@code pos} only when it is a log of the detected
	 * {@code tree} and at most {@link #REACH_H} away horizontally. Height is free: the golem reaches up the trunk.
	 */
	public static boolean breakAllowed(double gx, double gz, Set<BlockPos> tree, BlockPos pos) {
		if (!tree.contains(pos)) return false;
		double dx = gx - (pos.getX() + 0.5), dz = gz - (pos.getZ() + 0.5);
		return dx * dx + dz * dz <= REACH_H * REACH_H;
	}

	/**
	 * Ticks to break a block with a tool: the vanilla formula {@code ceil(hardness * 30 / speed)} (30 = correct tool; no
	 * enchantments, no on-ground/in-water modifiers), at least 1.
	 */
	public static int breakTicks(float hardness, float speed) {
		return Math.max(1, (int) Math.ceil(hardness * 30.0F / Math.max(speed, 0.01F)));
	}

	public static boolean isSapling(ItemStack s) {
		return s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem;
	}

	static boolean isAxe(ItemStack s) {
		return s.is(ItemTags.AXES);
	}

	static boolean isBone(ItemStack s) {
		return s.is(Items.BONE_MEAL);
	}

	/** Saplings that only grow into a tree as a 2x2 group of four. */
	static boolean needs2x2(ItemStack s) {
		return s.is(Items.DARK_OAK_SAPLING) || s.is(Items.PALE_OAK_SAPLING);
	}

	/** Saplings that can grow a 2x2 mega tree (spruce, jungle) or must (dark oak, pale oak). */
	static boolean canBe2x2(ItemStack s) {
		return needs2x2(s) || s.is(Items.SPRUCE_SAPLING) || s.is(Items.JUNGLE_SAPLING);
	}

	/**
	 * The tree around T: every log of one kind connected (26-neighbourhood) to a seed (T if it is a log, plus the
	 * {@code known} logs of the previous scan that still stand). Never below T's Y, horizontally at most
	 * {@link #TREE_RADIUS} from T, at most {@link #TREE_HEIGHT} above it, at most {@link #TREE_MAX_LOGS} blocks. Logs of
	 * another wood kind are not part of the tree, so a neighbouring tree or a house of other wood is left alone.
	 */
	public static Set<BlockPos> detectTree(Level level, BlockPos t, Collection<BlockPos> known) {
		Set<BlockPos> found = new LinkedHashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> seeds = new ArrayList<>();
		if (level.getBlockState(t).is(BlockTags.LOGS)) seeds.add(t);
		for (BlockPos k : known) if (!k.equals(t) && level.getBlockState(k).is(BlockTags.LOGS)) seeds.add(k);
		if (seeds.isEmpty()) return found;
		Block wood = level.getBlockState(seeds.getFirst()).getBlock();
		for (BlockPos sd : seeds) {
			if (level.getBlockState(sd).getBlock() == wood && found.add(sd)) queue.add(sd);
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty() && found.size() < TREE_MAX_LOGS) {
			BlockPos p = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dy == 0 && dz == 0) continue;
				m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
				if (m.getY() < t.getY() || m.getY() > t.getY() + TREE_HEIGHT) continue;
				if (Math.abs(m.getX() - t.getX()) > TREE_RADIUS || Math.abs(m.getZ() - t.getZ()) > TREE_RADIUS) continue;
				if (level.getBlockState(m).getBlock() != wood) continue;
				BlockPos q = m.immutable();
				if (found.add(q)) queue.add(q);
				if (found.size() >= TREE_MAX_LOGS) return found;
			}
		}
		return found;
	}

	/** The 2x2 square of {@code logs} at T's level that contains T (its 4 cells), or just T. */
	static List<BlockPos> bottomSquare(Set<BlockPos> logs, BlockPos t) {
		for (int a = -1; a <= 0; a++) for (int b = -1; b <= 0; b++) {
			List<BlockPos> sq = square(t.getX() + a, t.getY(), t.getZ() + b);
			if (logs.containsAll(sq)) return sq;
		}
		return List.of(t);
	}

	private static List<BlockPos> square(int x, int y, int z) {
		return List.of(new BlockPos(x, y, z), new BlockPos(x + 1, y, z), new BlockPos(x, y, z + 1), new BlockPos(x + 1, y, z + 1));
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		BlockPos t = data.assignments.get(LumberProfession.TREE);
		BlockPos outPos = data.assignments.get(LumberProfession.OUTPUT);
		Container out = outPos == null ? null : TransporterLogic.containerAt(level, outPos);
		if (t == null || out == null) {
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}
		if (!t.equals(s.tree)) { // tree reassigned (or first tick): drop stale progress, skips and delivery flag
			s = new State();
			s.tree = t;
			STATES.put(golem, s);
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		boolean treeSkipped = s.skip.containsKey(t);

		Set<BlockPos> logs;
		if (treeSkipped) {
			logs = Set.of();
		} else if (s.breakPos != null && s.logs != null && level.getBlockState(s.breakPos).is(BlockTags.LOGS)) {
			logs = s.logs; // mid break: the tree does not change under us, no need to flood again
		} else {
			logs = detectTree(level, t, s.known);
			if (s.known.isEmpty() && !logs.isEmpty()) { // a tree appears: remember its footprint for replanting
				s.footprint = bottomSquare(logs, t);
				s.unreach.clear();
				s.anchored = false;
			}
			s.known = new HashSet<>(logs);
			s.logs = logs;
		}
		boolean felling = !logs.isEmpty();
		boolean growing = !felling && !treeSkipped && isGrowing(level, t, s);
		BlockPos inPos = data.assignments.get(LumberProfession.INPUT);
		Container in = inPos == null || s.skip.containsKey(inPos) ? null : TransporterLogic.containerAt(level, inPos);
		boolean wantSap = felling || (!treeSkipped && (!growing || partial(level, s)));

		// 1. input chest: axe, saplings, bone meal
		if (in != null && wantsFetch(data, in, felling, wantSap, growing)) {
			if (!reach(s, level, golem, inPos, inPos, now)) return;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
			boolean took = false;
			if (felling) took |= fetchAxe(in, data);
			if (wantSap) took |= fetchSupply(in, data, LumberLogic::isSapling, 64);
			if (growing) took |= fetchSupply(in, data, LumberLogic::isBone, BONE_TARGET);
			if (took) Interact.openChest(level, golem, inPos, Interact.Action.TAKE);
			s.anchored = false;
			s.walk.reset();
			done(s, golem, data, took ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
			return;
		}

		// 2. fell
		if (felling) {
			if (axeSlot(data) < 0) { // no axe, no work
				idle(s, golem);
				return;
			}
			if (s.mustDeliver) {
				deliver(s, level, golem, data, outPos, out, now);
				return;
			}
			fell(s, level, golem, data, t, logs, now);
			return;
		}
		s.breakPos = null;
		s.anchored = false;

		// 3. replant
		if (!treeSkipped && plant(s, level, golem, data, t, now)) return;
		// 4. drops on the ground
		if (collect(s, level, golem, data, t, now)) return;
		// 5. bone meal on the planted sapling(s)
		if (growing && boneMeal(s, level, golem, data, t, now)) return;
		// 6. deliver
		if (!carryEmpty(data) && !s.skip.containsKey(outPos)) {
			deliver(s, level, golem, data, outPos, out, now);
			return;
		}
		idle(s, golem);
	}

	private static void idle(State s, CopperGolem golem) {
		golem.getNavigation().stop();
		s.cooldown = SCAN_TICKS;
	}

	private static void done(State s, CopperGolem golem, GolemData data, int cooldown) {
		golem.getNavigation().stop();
		s.cooldown = cooldown;
		GolemData.save(golem, data);
	}

	private static void deliver(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos outPos, Container out, long now) {
		if (s.skip.containsKey(outPos)) {
			idle(s, golem);
			return;
		}
		if (!reach(s, level, golem, outPos, outPos, now)) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(outPos));
		NonNullList<ItemStack> carry = carryView(data);
		boolean moved = TransporterLogic.insertAll(carry, out);
		writeCarry(data, carry);
		if (moved) Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
		s.mustDeliver = false;
		s.anchored = false;
		s.walk.reset();
		done(s, golem, data, carryEmpty(data) ? (moved ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS) : RETRY_TICKS);
	}

	// ---- felling --------------------------------------------------------------------------------------------------

	private static double hdist(CopperGolem golem, BlockPos p) {
		double dx = golem.getX() - (p.getX() + 0.5), dz = golem.getZ() - (p.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double hdist(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Next log to break: the lowest one the golem can reach from where it stands, else the lowest one overall. */
	private static @Nullable BlockPos pickNext(State s, CopperGolem golem, Set<BlockPos> logs) {
		Comparator<BlockPos> byHeight = Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> hdist(golem, p));
		BlockPos best = null;
		if (s.anchored) {
			for (BlockPos p : logs)
				if (!s.unreach.contains(p) && hdist(golem, p) <= REACH_H && (best == null || byHeight.compare(p, best) < 0)) best = p;
			if (best != null) return best;
		}
		for (BlockPos p : logs)
			if (!s.unreach.contains(p) && (best == null || byHeight.compare(p, best) < 0)) best = p;
		return best;
	}

	private static void fell(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, Set<BlockPos> logs, long now) {
		BlockPos target = s.breakPos != null && logs.contains(s.breakPos) ? s.breakPos : null;
		if (target == null) {
			s.breakPos = null;
			target = pickNext(s, golem, logs);
			if (target == null) { // every remaining log was out of reach from every spot tried
				s.skip.put(t, now + SKIP_TICKS);
				s.unreach.clear();
				s.anchored = false;
				return;
			}
		}
		if (!s.anchored || hdist(golem, target) > REACH_H) {
			s.breakPos = null;
			BlockPos spot = hdist(target, t) <= NEAR_TREE ? t : standCell(level, golem, target, t);
			if (spot == null) {
				s.unreach.add(target);
				return;
			}
			if (!reach(s, level, golem, spot, t, now)) return;
			s.walk.reset();
			s.anchored = true;
			if (hdist(golem, target) > REACH_H) {
				s.unreach.add(target);
				s.anchored = false;
				return;
			}
		}
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (!target.equals(s.breakPos)) {
			s.breakPos = target;
			BlockState st = level.getBlockState(target);
			s.breakLeft = breakTicks(st.getDestroySpeed(level, target), data.items.get(axeSlot(data)).getDestroySpeed(st));
		}
		if (--s.breakLeft > 0) return;
		s.breakPos = null;
		switch (breakBlock(level, golem, data, logs, target)) {
			case BROKEN -> GolemData.save(golem, data);
			case FULL -> s.mustDeliver = true;
			case DENIED -> s.unreach.add(target);
		}
	}

	/** A ground spot within about 3 blocks of {@code log}'s column to work on it from, nearest to the column; null if none. */
	private static @Nullable BlockPos standCell(ServerLevel level, CopperGolem golem, BlockPos log, BlockPos t) {
		BlockPos best = null;
		int bestD = Integer.MAX_VALUE;
		int[] ys = { 0, 1, -1, -2 };
		for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
			int d = dx * dx + dz * dz;
			if (d >= bestD) continue;
			for (int dy : ys) {
				BlockPos p = new BlockPos(log.getX() + dx, t.getY() + dy, log.getZ() + dz);
				if (Interact.canStand(level, golem, p)) {
					best = p;
					bestD = d;
					break;
				}
			}
		}
		return best;
	}

	/**
	 * The ONLY place that breaks a block. Refuses unless {@link #breakAllowed} and the block is a log; refuses (FULL)
	 * when the drops would not fit, so nothing is ever dropped on the ground or lost. Leaves are never broken.
	 */
	public static Result breakBlock(ServerLevel level, CopperGolem golem, GolemData data, Set<BlockPos> tree, BlockPos pos) {
		if (!breakAllowed(golem.getX(), golem.getZ(), tree, pos)) return Result.DENIED;
		BlockState st = level.getBlockState(pos);
		if (!st.is(BlockTags.LOGS) || axeSlot(data) < 0) return Result.DENIED;
		ItemStack tool = data.items.get(axeSlot(data));
		List<ItemStack> drops = Block.getDrops(st, level, pos, null, null, tool);
		if (!fitsAll(data, drops)) return Result.FULL;
		level.destroyBlock(pos, false, golem);
		storeAll(data, drops);
		// breaking empties the stack (vanilla behaviour); the item is gone, never duplicated
		tool.hurtAndBreak(1, level, null, broken -> {});
		if (tool.isEmpty()) data.items.set(axeSlot(data), ItemStack.EMPTY);
		return Result.BROKEN;
	}

	// ---- walking with give-up -------------------------------------------------------------------------------------

	/**
	 * True when the golem is in reach of {@code target} ({@link Interact#approach}). Otherwise keeps walking; with no
	 * progress for {@link #GIVE_UP_TICKS} the {@code skipKey} (tree spot or chest) is ignored for {@link #SKIP_TICKS}.
	 * A null path or airborne wait is no progress, so the timeout bounds it.
	 */
	private static boolean reach(State s, ServerLevel level, CopperGolem golem, BlockPos target, BlockPos skipKey, long now) {
		if (Interact.approach(level, golem, target, s.walk, SPEED)) {
			s.goalKey = null;
			s.stuck = 0;
			return true;
		}
		double dist = golem.distanceToSqr(Vec3.atCenterOf(target));
		if (!target.equals(s.goalKey) || dist < s.bestDist - PROGRESS_EPS) {
			s.goalKey = target;
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(skipKey, now + SKIP_TICKS);
			s.goalKey = null;
			s.stuck = 0;
			s.breakPos = null;
			s.walk.reset();
			golem.getNavigation().stop();
		}
		return false;
	}

	/** Test hook: is {@code pos} currently ignored by this golem. */
	public static boolean isSkipped(CopperGolem golem, BlockPos pos, long now) {
		State st = STATES.get(golem);
		Long until = st == null ? null : st.skip.get(pos);
		return until != null && until > now;
	}

	// ---- planting -------------------------------------------------------------------------------------------------

	private static boolean isSaplingBlock(Level level, BlockPos p) {
		return level.getBlockState(p).is(BlockTags.SAPLINGS);
	}

	/** A sapling stands on T or on a cell of the remembered footprint. */
	private static boolean isGrowing(Level level, BlockPos t, State s) {
		if (isSaplingBlock(level, t)) return true;
		for (BlockPos p : s.footprint) if (isSaplingBlock(level, p)) return true;
		return false;
	}

	/** A 2x2 footprint that is only partly planted (some cell is still free). */
	private static boolean partial(Level level, State s) {
		if (s.footprint.size() != 4) return false;
		boolean sapling = false, free = false;
		for (BlockPos p : s.footprint) {
			if (isSaplingBlock(level, p)) sapling = true;
			else if (level.getBlockState(p).isAir()) free = true;
		}
		return sapling && free;
	}

	private static boolean plantable(Level level, BlockPos p, BlockState sap) {
		BlockState cur = level.getBlockState(p);
		return (cur.isAir() || (cur.canBeReplaced() && cur.getFluidState().isEmpty())) && sap.canSurvive(level, p);
	}

	private static boolean cellOk(Level level, BlockPos p, BlockState sap) {
		return level.getBlockState(p).is(sap.getBlock()) || plantable(level, p, sap);
	}

	/**
	 * The cells to plant {@code sap} on: one at T, or four (a 2x2 square) for saplings that need it (dark oak, pale oak)
	 * or when a spruce/jungle 2x2 trunk was just felled and enough saplings are at hand. Empty = cannot plant now.
	 */
	static List<BlockPos> plantCells(Level level, BlockPos t, State s, ItemStack sap) {
		BlockState sapState = ((BlockItem) sap.getItem()).getBlock().defaultBlockState();
		int missingFootprint = 0;
		if (s.footprint.size() == 4) for (BlockPos p : s.footprint) if (!level.getBlockState(p).is(sapState.getBlock())) missingFootprint++;
		boolean four = needs2x2(sap) || (canBe2x2(sap) && s.footprint.size() == 4 && sap.getCount() >= missingFootprint);
		if (!four) return cellOk(level, t, sapState) ? List.of(t) : List.of();
		List<List<BlockPos>> candidates = new ArrayList<>();
		if (s.footprint.size() == 4) candidates.add(s.footprint);
		for (int a = -1; a <= 0; a++) for (int b = -1; b <= 0; b++) candidates.add(square(t.getX() + a, t.getY(), t.getZ() + b));
		for (List<BlockPos> sq : candidates) {
			boolean ok = true;
			for (BlockPos p : sq) if (!cellOk(level, p, sapState)) ok = false;
			if (ok) return sq;
		}
		return List.of();
	}

	/** One replant step: walk next to T, plant one sapling per call. True when this tick was used. */
	private static boolean plant(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		int slot = supplySlot(data, LumberLogic::isSapling);
		if (slot < 0) return false;
		ItemStack sap = data.items.get(slot);
		List<BlockPos> cells = plantCells(level, t, s, sap);
		List<BlockPos> missing = new ArrayList<>();
		BlockState sapState = ((BlockItem) sap.getItem()).getBlock().defaultBlockState();
		for (BlockPos p : cells) if (!level.getBlockState(p).is(sapState.getBlock())) missing.add(p);
		if (missing.isEmpty() || sap.getCount() < missing.size()) return false;
		if (!reach(s, level, golem, t, t, now)) return true;
		BlockPos cell = missing.getFirst();
		golem.getLookControl().setLookAt(Vec3.atCenterOf(cell));
		level.setBlock(cell, sapState, 3);
		level.playSound(null, cell, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		sap.shrink(1);
		if (sap.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		s.footprint = cells.size() == 4 ? cells : List.of(); // a 1x1 sapling is found at T
		if (missing.size() == 1) s.walk.reset();
		done(s, golem, data, AFTER_ACTION_TICKS);
		return true;
	}

	/** Bone meal on a growing sapling, one use every {@link #BONE_DELAY} ticks. True when this tick was used. */
	private static boolean boneMeal(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		int slot = supplySlot(data, LumberLogic::isBone);
		if (slot < 0) return false;
		BlockPos target = null;
		if (isSaplingBlock(level, t)) target = t;
		else for (BlockPos p : s.footprint) if (isSaplingBlock(level, p)) target = p;
		if (target == null) return false;
		if (!reach(s, level, golem, t, t, now)) return true;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		ItemStack bone = data.items.get(slot);
		if (BoneMealItem.growCrop(bone, level, target)) level.levelEvent(1505, target, 15);
		if (bone.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		done(s, golem, data, BONE_DELAY);
		return true;
	}

	// ---- ground drops ---------------------------------------------------------------------------------------------

	/** Walk to a drop lying around T and pick it up (the Cleaner's chase and give-up pattern). True when this tick was used. */
	private static boolean collect(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		AABB box = new AABB(t.getX() - ITEM_RADIUS, t.getY() - ITEM_BELOW, t.getZ() - ITEM_RADIUS,
			t.getX() + ITEM_RADIUS + 1, t.getY() + ITEM_ABOVE, t.getZ() + ITEM_RADIUS + 1);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
			e -> e.isAlive() && !e.hasPickUpDelay() && !s.skip.containsKey(e.getUUID()) && fitsSome(data, e.getItem()));
		if (items.isEmpty()) {
			s.chasing = null;
			return false;
		}
		ItemEntity target = items.stream().min(Comparator.comparingDouble(golem::distanceToSqr)).get();
		if (golem.distanceToSqr(target) <= PICKUP_RANGE_SQR) {
			golem.getNavigation().stop();
			s.chasing = null;
			List<ItemStack> rest = new ArrayList<>(List.of(target.getItem().copy()));
			storeInto(data, rest);
			if (rest.isEmpty()) target.discard(); else target.setItem(rest.getFirst());
			done(s, golem, data, AFTER_ACTION_TICKS);
			return true;
		}
		if (!target.equals(s.chasing)) {
			s.chasing = target;
			s.goalKey = target.getUUID();
			s.bestDist = golem.distanceToSqr(target);
			s.stuck = 0;
			s.chaseRepath = 0;
		} else if (golem.distanceToSqr(target) < s.bestDist - PROGRESS_EPS) {
			s.bestDist = golem.distanceToSqr(target);
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(target.getUUID(), now + SKIP_TICKS);
			s.chasing = null;
			golem.getNavigation().stop();
			return true;
		}
		if (--s.chaseRepath <= 0 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(target, 0);
			// a null path is transient (spawn tick, mid-air): no progress, so the timeout decides
			if (path != null) golem.getNavigation().moveTo(path, SPEED);
			s.chaseRepath = REPATH_TICKS;
		}
		return true;
	}

	// ---- chest fetching -------------------------------------------------------------------------------------------

	static int axeSlot(GolemData data) {
		return isAxe(data.items.get(LumberProfession.AXE_SLOT)) ? LumberProfession.AXE_SLOT : -1;
	}

	/** Chest slot of an axe that may be taken: fills the empty axe slot or replaces a worn one (about to break); -1 if none. */
	static int fetchableAxe(Container chest, GolemData data) {
		ItemStack cur = data.items.get(LumberProfession.AXE_SLOT);
		if (!cur.isEmpty() && !cur.nextDamageWillBreak()) return -1;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (isAxe(st) && st.getCount() == 1 && (cur.isEmpty() || !st.nextDamageWillBreak())) return i;
		}
		return -1;
	}

	/** Empty: take one. Worn (breaks on its next use): swap, the old one goes into the chest slot (nothing lost). */
	static boolean fetchAxe(Container chest, GolemData data) {
		int i = fetchableAxe(chest, data);
		if (i < 0) return false;
		ItemStack fresh = chest.removeItem(i, 1);
		ItemStack old = data.items.get(LumberProfession.AXE_SLOT);
		if (!old.isEmpty()) chest.setItem(i, old); // fetchableAxe only offers single-item slots, so slot i is empty now
		data.items.set(LumberProfession.AXE_SLOT, fresh);
		chest.setChanged();
		return true;
	}

	/** Anything to fetch right now? Mirrors the fetch methods' preconditions exactly (no endless walking to the chest). */
	private static boolean wantsFetch(GolemData data, Container in, boolean felling, boolean wantSap, boolean growing) {
		if (felling && fetchableAxe(in, data) >= 0) return true;
		if (wantSap && countOf(data, LumberLogic::isSapling) < SAPLING_LOW && supplySource(in, data, LumberLogic::isSapling) >= 0) return true;
		return growing && countOf(data, LumberLogic::isBone) < BONE_LOW && supplySource(in, data, LumberLogic::isBone) >= 0;
	}

	static int supplySlot(GolemData data, Predicate<ItemStack> is) {
		for (int i = LumberProfession.SUPPLY_FROM; i < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; i++)
			if (is.test(data.items.get(i))) return i;
		return -1;
	}

	private static int emptySupplySlot(GolemData data) {
		for (int i = LumberProfession.SUPPLY_FROM; i < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; i++)
			if (data.items.get(i).isEmpty()) return i;
		return -1;
	}

	private static int countOf(GolemData data, Predicate<ItemStack> is) {
		int slot = supplySlot(data, is);
		return slot < 0 ? 0 : data.items.get(slot).getCount();
	}

	/**
	 * Slot in {@code chest} to take supplies from: the first slot with the item already in the golem's slot, or (no such
	 * slot yet, a free supply slot) the first matching item. -1 when nothing can be taken.
	 */
	static int supplySource(Container chest, GolemData data, Predicate<ItemStack> is) {
		int slot = supplySlot(data, is);
		if (slot < 0 && emptySupplySlot(data) < 0) return -1;
		ItemStack cur = slot < 0 ? ItemStack.EMPTY : data.items.get(slot);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || !is.test(st)) continue;
			if (cur.isEmpty() || ItemStack.isSameItemSameComponents(cur, st)) return i;
		}
		return -1;
	}

	/** Takes supplies of one kind up to {@code target} items in the golem's slot; true when something moved. */
	static boolean fetchSupply(Container chest, GolemData data, Predicate<ItemStack> is, int target) {
		int first = supplySource(chest, data, is);
		if (first < 0) return false;
		int slot = supplySlot(data, is);
		if (slot < 0) slot = emptySupplySlot(data);
		ItemStack cur = data.items.get(slot);
		ItemStack kind = chest.getItem(first).copyWithCount(1);
		int have = cur.getCount();
		int max = Math.min(target, Math.min(64, kind.getMaxStackSize()));
		int took = 0;
		for (int i = first; i < chest.getContainerSize() && have + took < max; i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || !ItemStack.isSameItemSameComponents(kind, st)) continue;
			int n = Math.min(max - have - took, st.getCount());
			took += n;
			st.shrink(n);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		if (took == 0) return false;
		data.items.set(slot, kind.copyWithCount(have + took));
		chest.setChanged();
		return true;
	}

	// ---- carry helpers --------------------------------------------------------------------------------------------

	static NonNullList<ItemStack> carryView(GolemData data) {
		NonNullList<ItemStack> carry = NonNullList.withSize(LumberProfession.CARRY_SLOTS, ItemStack.EMPTY);
		for (int i = 0; i < carry.size(); i++) carry.set(i, data.items.get(LumberProfession.CARRY_FROM + i));
		return carry;
	}

	static void writeCarry(GolemData data, NonNullList<ItemStack> carry) {
		for (int i = 0; i < carry.size(); i++) data.items.set(LumberProfession.CARRY_FROM + i, carry.get(i));
	}

	static boolean carryEmpty(GolemData data) {
		return TransporterLogic.isEmpty(carryView(data));
	}

	/**
	 * Stores items: merge into a supply slot that holds the same item; a sapling also goes into a free supply slot when
	 * the golem holds no sapling yet; the rest into carry. {@code drops} becomes the leftover.
	 */
	private static void storeInto(GolemData data, List<ItemStack> drops) {
		SimpleContainer tmp = new SimpleContainer(Math.max(1, drops.size()));
		for (int i = 0; i < drops.size(); i++) {
			ItemStack d = drops.get(i).copy();
			for (int k = LumberProfession.SUPPLY_FROM; k < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS && !d.isEmpty(); k++) {
				ItemStack sup = data.items.get(k);
				if (!sup.isEmpty() && ItemStack.isSameItemSameComponents(sup, d)) {
					int n = Math.min(d.getCount(), Math.min(64, sup.getMaxStackSize()) - sup.getCount());
					sup.grow(n);
					d.shrink(n);
				}
			}
			if (!d.isEmpty() && isSapling(d) && supplySlot(data, LumberLogic::isSapling) < 0) {
				int free = emptySupplySlot(data);
				if (free >= 0) {
					int n = Math.min(d.getCount(), Math.min(64, d.getMaxStackSize()));
					data.items.set(free, d.copyWithCount(n));
					d.shrink(n);
				}
			}
			tmp.setItem(i, d);
		}
		NonNullList<ItemStack> carry = carryView(data);
		TransporterLogic.takeAll(tmp, carry);
		writeCarry(data, carry);
		drops.clear();
		for (int i = 0; i < tmp.getContainerSize(); i++) if (!tmp.getItem(i).isEmpty()) drops.add(tmp.getItem(i));
	}

	private static GolemData copyOf(GolemData data) {
		GolemData copy = new GolemData();
		copy.items = NonNullList.withSize(data.items.size(), ItemStack.EMPTY);
		for (int i = 0; i < data.items.size(); i++) copy.items.set(i, data.items.get(i).copy());
		return copy;
	}

	/** Would all drops fit (supply slots + carry)? Works on a copy of the inventory. */
	private static boolean fitsAll(GolemData data, List<ItemStack> drops) {
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(copyOf(data), rest);
		return rest.isEmpty();
	}

	/** Some part of {@code stack} would be taken. */
	private static boolean fitsSome(GolemData data, ItemStack stack) {
		List<ItemStack> rest = new ArrayList<>(List.of(stack.copy()));
		storeInto(copyOf(data), rest);
		return rest.isEmpty() || rest.getFirst().getCount() < stack.getCount();
	}

	private static void storeAll(GolemData data, List<ItemStack> drops) {
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(data, rest);
		// cannot happen: fitsAll proved the drops fit and nothing ran in between
		if (!rest.isEmpty()) throw new IllegalStateException("lumber drops did not fit: " + rest);
	}
}
