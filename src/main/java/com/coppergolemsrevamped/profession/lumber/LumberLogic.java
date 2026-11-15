package com.coppergolemsrevamped.profession.lumber;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Lumber work loop. One assigned tree spot T (the clicked block: sapling or bottom trunk). The golem fells the column
 * T, T+1, ... of logs/leaves: first the block in front of it (T, from a horizontal neighbour), then it steps into the gap
 * and breaks straight up, one block at a time, climbing the shaft it has cleared. Every break goes through
 * {@link #breakBlock}, which enforces {@link #breakAllowed}. See superdoc/features/lumber.md.
 */
public final class LumberLogic {
	private LumberLogic() {}

	static final int REPATH_TICKS = 20;
	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	static final int AFTER_ACTION_TICKS = 5;
	static final double SPEED = 1.0;
	/** Walking to one target this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up tree or chest is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;
	/** The sapling slot is topped up from the input chest when it holds fewer than this. */
	static final int SAPLING_LOW = 16;
	/** Column scan limit (vanilla trees are at most ~32 tall). */
	static final int MAX_HEIGHT = 40;
	/** Path goals count as reached within about a block: closer than this to the stand spot, the golem steps onto it. */
	static final double STAND_SNAP = 1.5;

	public enum Result { BROKEN, FULL, DENIED }

	private static final class State {
		/** The tree spot this state was built for; a different spot (reassigned tree) starts a fresh state. */
		@Nullable BlockPos tree;
		int cooldown;
		int repath;
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
		@Nullable BlockPos breakPos;
		int breakLeft;
		/** The next drop did not fit into the carry slots: deliver before felling on. */
		boolean mustDeliver;
		/** Highest block Y of this tree's column the golem has cleared so far (the air below it still belongs to the tree). */
		int clearedTopY = Integer.MIN_VALUE;
		final Map<BlockPos, Long> skip = new HashMap<>();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	// ---- pure rules (public for tests) ----------------------------------------------------------------------------

	/**
	 * THE break rule: a golem whose feet are in block {@code feet} and that faces {@code facing} may break only the block
	 * directly in front of it ({@code feet + facing}) or directly above it ({@code feet + up}). Nothing else, ever.
	 */
	public static boolean breakAllowed(BlockPos feet, Direction facing, BlockPos pos) {
		if (facing.getAxis().isVertical()) return pos.equals(feet.above());
		return pos.equals(feet.relative(facing)) || pos.equals(feet.above());
	}

	/**
	 * Ticks to break a block with a tool: the vanilla formula {@code ceil(hardness * 30 / speed)} (30 = correct tool; no
	 * enchantments, no on-ground/in-water modifiers), at least 1.
	 */
	public static int breakTicks(float hardness, float speed) {
		return Math.max(1, (int) Math.ceil(hardness * 30.0F / Math.max(speed, 0.01F)));
	}

	public static boolean isTreeBlock(BlockState st) {
		return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES);
	}

	public static boolean isSapling(ItemStack s) {
		return s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem;
	}

	static boolean isAxe(ItemStack s) {
		return s.is(ItemTags.AXES);
	}

	static boolean isShears(ItemStack s) {
		return s.is(Items.SHEARS);
	}

	/**
	 * The lowest log/leaves block of the tree column above {@code t} (inclusive), or null when there is none. The part of
	 * the column the golem has already cleared (air up to {@code airTopY}, at least {@code t} itself) may be air; above it
	 * the column must be contiguous log/leaves.
	 */
	public static @Nullable BlockPos nextBlock(Level level, BlockPos t, int airTopY) {
		for (int k = 0; k < MAX_HEIGHT; k++) {
			BlockPos p = t.above(k);
			BlockState st = level.getBlockState(p);
			if (isTreeBlock(st)) return p;
			if (st.isAir() && p.getY() <= Math.max(t.getY(), airTopY)) continue;
			return null;
		}
		return null;
	}

	/** The golem's feet block; tolerates the small gravity sag while it hangs in the shaft. */
	static BlockPos feet(CopperGolem golem) {
		return BlockPos.containing(golem.getX(), golem.getY() + 0.25, golem.getZ());
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
		BlockPos next = treeSkipped ? null : nextBlock(level, t, s.clearedTopY);
		if (next == null) s.clearedTopY = Integer.MIN_VALUE;
		boolean needPlant = !treeSkipped && next == null && level.getBlockState(t).isAir();
		BlockPos inPos = data.assignments.get(LumberProfession.INPUT);
		Container in = inPos == null || s.skip.containsKey(inPos) ? null : TransporterLogic.containerAt(level, inPos);
		boolean fetch = in != null && wantsFetch(data, in, next != null, needPlant);
		boolean felling = next != null && axeSlot(data) >= 0 && !s.mustDeliver && !fetch;

		// leave the shaft whenever we are not continuing to fell
		if (!felling && inShaft(golem, t)) {
			s.breakPos = null;
			if (exitShaft(level, golem, t)) return;
			// no free neighbour to step out to: ignore the tree for a while and never retry every tick
			s.skip.put(t, now + SKIP_TICKS);
			if (feet(golem).getY() > t.getY()) { // hanging in the cleared column: lower it to T's level, no fall
				if (level.getBlockState(t).getCollisionShape(level, t).isEmpty()) snapTo(golem, t);
				s.cooldown = RETRY_TICKS;
				return;
			}
			// standing on T's level (not in a shaft): carry on as idle/deliver below
		}

		// 1. input chest: axe, shears, saplings
		if (fetch) {
			if (walk(s, golem, inPos, inPos.getX() + 0.5, inPos.getY(), inPos.getZ() + 0.5, TransporterLogic.inReach(golem, inPos), now)) {
				if (next != null) {
					fetchTool(in, data, LumberLogic::isAxe);
					fetchTool(in, data, LumberLogic::isShears);
				}
				fetchSaplings(in, data);
				done(s, golem, data, AFTER_ACTION_TICKS);
			}
			return;
		}

		// 2. fell
		if (next != null) {
			if (axeSlot(data) < 0) { // no axe, no work
				idle(s, golem);
				return;
			}
			if (s.mustDeliver) {
				deliver(s, golem, data, outPos, out, now);
				return;
			}
			fell(s, level, golem, data, t, next, now);
			return;
		}
		// 3. replant
		if (needPlant && isSapling(data.items.get(LumberProfession.SAPLING_SLOT))) {
			if (!atStand(golem, t)) {
				BlockPos n = standPos(level, t, golem);
				if (n == null) {
					s.skip.put(t, now + SKIP_TICKS);
					return;
				}
				walkToStand(s, golem, t, n, now);
				return;
			}
			if (!plant(level, data, t)) s.skip.put(t, now + SKIP_TICKS);
			done(s, golem, data, AFTER_ACTION_TICKS);
			return;
		}
		// 4. deliver
		if (!carryEmpty(data) && !s.skip.containsKey(outPos)) {
			deliver(s, golem, data, outPos, out, now);
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
		s.repath = 0;
		s.target = null;
		s.cooldown = cooldown;
		GolemData.save(golem, data);
	}

	private static void deliver(State s, CopperGolem golem, GolemData data, BlockPos outPos, Container out, long now) {
		if (s.skip.containsKey(outPos)) {
			idle(s, golem);
			return;
		}
		if (!walk(s, golem, outPos, outPos.getX() + 0.5, outPos.getY(), outPos.getZ() + 0.5, TransporterLogic.inReach(golem, outPos), now)) return;
		NonNullList<ItemStack> carry = carryView(data);
		TransporterLogic.insertAll(carry, out);
		writeCarry(data, carry);
		s.mustDeliver = false;
		done(s, golem, data, carryEmpty(data) ? AFTER_ACTION_TICKS : RETRY_TICKS);
	}

	// ---- felling --------------------------------------------------------------------------------------------------

	private static void fell(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, BlockPos next, long now) {
		BlockPos feet = feet(golem);
		boolean first = next.equals(t);
		boolean atFront = first && atStand(golem, t);
		boolean inColumn = !first && feet.equals(next.below());
		if (!atFront && !inColumn) {
			s.breakPos = null;
			if (!first && (inShaft(golem, t) || nearColumn(golem, t))) { // climb into the shaft we cleared
				snapTo(golem, next.below());
				return;
			}
			BlockPos n = standPos(level, t, golem);
			if (n == null) {
				s.skip.put(t, now + SKIP_TICKS);
				return;
			}
			walkToStand(s, golem, t, n, now);
			return;
		}
		Direction facing = Direction.NORTH;
		if (atFront) {
			facing = Direction.getApproximateNearest(t.getX() - feet.getX(), 0, t.getZ() - feet.getZ());
			faceYaw(golem, facing);
		} else {
			hold(golem, next.below());
		}
		BlockState st = level.getBlockState(next);
		if (!next.equals(s.breakPos)) {
			s.breakPos = next;
			s.breakLeft = breakTicks(st.getDestroySpeed(level, next), toolFor(data, st).getDestroySpeed(st));
		}
		if (--s.breakLeft > 0) return;
		s.breakPos = null;
		switch (breakBlock(level, golem, data, facing, next)) {
			case BROKEN -> {
				GolemData.save(golem, data);
				s.clearedTopY = next.getY();
				snapTo(golem, next); // step into / climb up the gap
			}
			case FULL -> s.mustDeliver = true;
			// no cooldown: the golem must be held or climb out next tick, never left hanging unheld in the shaft
			case DENIED -> s.breakPos = null;
		}
	}

	/** Axe for logs; for leaves shears if the golem has some, else the axe. */
	private static ItemStack toolFor(GolemData data, BlockState st) {
		if (!st.is(BlockTags.LOGS)) {
			int sh = toolSlot(data, LumberLogic::isShears);
			if (sh >= 0) return data.items.get(sh);
		}
		return data.items.get(axeSlot(data));
	}

	/**
	 * The ONLY place that breaks a block. Refuses unless {@link #breakAllowed} and the block is a log/leaves; refuses
	 * (FULL) when the drops would not fit, so nothing is ever dropped on the ground or lost.
	 */
	public static Result breakBlock(ServerLevel level, CopperGolem golem, GolemData data, Direction facing, BlockPos pos) {
		if (!breakAllowed(feet(golem), facing, pos)) return Result.DENIED;
		BlockState st = level.getBlockState(pos);
		if (!isTreeBlock(st) || axeSlot(data) < 0) return Result.DENIED;
		ItemStack tool = toolFor(data, st);
		List<ItemStack> drops = Block.getDrops(st, level, pos, null, null, tool);
		if (!fitsAll(data, drops)) return Result.FULL;
		boolean log = st.is(BlockTags.LOGS);
		int wearSlot = log ? axeSlot(data) : isShears(tool) ? toolSlot(data, LumberLogic::isShears) : -1;
		level.destroyBlock(pos, false, golem);
		storeAll(data, drops);
		if (wearSlot >= 0) {
			ItemStack worn = data.items.get(wearSlot);
			// breaking empties the stack (vanilla behaviour); the item is gone, never duplicated
			worn.hurtAndBreak(1, level, null, broken -> {});
			if (worn.isEmpty()) data.items.set(wearSlot, ItemStack.EMPTY);
		}
		return Result.BROKEN;
	}

	// ---- positioning ----------------------------------------------------------------------------------------------

	private static boolean inShaft(CopperGolem golem, BlockPos t) {
		BlockPos f = feet(golem);
		return f.getX() == t.getX() && f.getZ() == t.getZ() && f.getY() >= t.getY();
	}

	private static boolean nearColumn(CopperGolem golem, BlockPos t) {
		double dx = golem.getX() - (t.getX() + 0.5), dz = golem.getZ() - (t.getZ() + 0.5);
		return dx * dx + dz * dz <= 1.6 * 1.6 && Math.abs(feet(golem).getY() - t.getY()) <= 1;
	}

	/** Golem stands in a horizontal neighbour of {@code t}, on T's level. */
	private static boolean atStand(CopperGolem golem, BlockPos t) {
		BlockPos f = feet(golem);
		return f.getY() == t.getY() && Math.abs(f.getX() - t.getX()) + Math.abs(f.getZ() - t.getZ()) == 1;
	}

	/** Free, supported horizontal neighbour of {@code t} nearest to the golem, or null. */
	private static @Nullable BlockPos standPos(ServerLevel level, BlockPos t, CopperGolem golem) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = t.relative(d);
			BlockPos below = n.below();
			if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
			if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
			double dist = golem.distanceToSqr(n.getX() + 0.5, n.getY(), n.getZ() + 0.5);
			if (dist < bestD) {
				bestD = dist;
				best = n;
			}
		}
		return best;
	}

	/** Steps the golem out onto a neighbour of T; false when there is no stand spot. */
	private static boolean exitShaft(ServerLevel level, CopperGolem golem, BlockPos t) {
		BlockPos n = standPos(level, t, golem);
		if (n == null) return false;
		snapTo(golem, n);
		return true;
	}

	private static void snapTo(CopperGolem golem, BlockPos feet) {
		golem.getNavigation().stop();
		golem.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
		golem.setDeltaMovement(Vec3.ZERO);
		golem.resetFallDistance();
	}

	/** Keeps the golem hanging in the shaft (gravity would pull it down between ticks). */
	private static void hold(CopperGolem golem, BlockPos feet) {
		snapTo(golem, feet);
	}

	private static void faceYaw(CopperGolem golem, Direction d) {
		golem.setYRot(d.toYRot());
		golem.setYBodyRot(d.toYRot());
		golem.setYHeadRot(d.toYRot());
	}

	// ---- walking with give-up -------------------------------------------------------------------------------------

	private static void walkToStand(State s, CopperGolem golem, BlockPos t, BlockPos n, long now) {
		double dx = golem.getX() - (n.getX() + 0.5), dz = golem.getZ() - (n.getZ() + 0.5);
		boolean arrived = dx * dx + dz * dz < STAND_SNAP * STAND_SNAP && Math.abs(golem.getY() - n.getY()) < 1.0;
		if (walk(s, golem, t, n.getX() + 0.5, n.getY(), n.getZ() + 0.5, arrived, now)) {
			snapTo(golem, n);
			faceYaw(golem, Direction.getApproximateNearest(t.getX() - n.getX(), 0, t.getZ() - n.getZ()));
		}
	}

	/**
	 * True when {@code arrived}. Otherwise keeps walking to the point; a target ({@code key}) that shows no progress for
	 * {@link #GIVE_UP_TICKS} is skipped for {@link #SKIP_TICKS}.
	 */
	private static boolean walk(State s, CopperGolem golem, BlockPos key, double tx, double ty, double tz, boolean arrived, long now) {
		if (arrived) return true;
		// airborne on the first ticks after spawning: paths are null, wait instead of counting no-progress ticks
		// (bounded: a golem that never lands, e.g. floating in water, gives up like any stuck walk)
		if (!golem.onGround()) {
			if (++s.stuck >= GIVE_UP_TICKS) {
				s.skip.put(key, now + SKIP_TICKS);
				s.target = null;
				s.stuck = 0;
				s.repath = 0;
			}
			return false;
		}
		double dist = golem.distanceToSqr(tx, ty, tz);
		if (!key.equals(s.target) || dist < s.bestDist - PROGRESS_EPS) {
			s.target = key;
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(key, now + SKIP_TICKS);
			s.target = null;
			s.stuck = 0;
			s.repath = 0;
			golem.getNavigation().stop();
			return false;
		}
		if (--s.repath <= 0 || golem.getNavigation().isDone()) {
			golem.getNavigation().moveTo(tx, ty, tz, SPEED);
			s.repath = REPATH_TICKS;
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

	/** Plants one sapling from the slot at {@code t}; the sapling is consumed only when it was placed. */
	private static boolean plant(ServerLevel level, GolemData data, BlockPos t) {
		ItemStack sap = data.items.get(LumberProfession.SAPLING_SLOT);
		if (!isSapling(sap) || !level.getBlockState(t).isAir()) return false;
		BlockState st = ((BlockItem) sap.getItem()).getBlock().defaultBlockState();
		if (!st.canSurvive(level, t)) return false;
		level.setBlock(t, st, 3);
		level.playSound(null, t, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		sap.shrink(1);
		if (sap.isEmpty()) data.items.set(LumberProfession.SAPLING_SLOT, ItemStack.EMPTY);
		return true;
	}

	// ---- chest fetching -------------------------------------------------------------------------------------------

	static int toolSlot(GolemData data, Predicate<ItemStack> is) {
		for (int i = LumberProfession.TOOL_FROM; i < LumberProfession.TOOL_FROM + LumberProfession.TOOL_SLOTS; i++)
			if (is.test(data.items.get(i))) return i;
		return -1;
	}

	static int axeSlot(GolemData data) {
		return toolSlot(data, LumberLogic::isAxe);
	}

	/** Chest slot of a tool that may be taken: replaces a worn one (not itself about to break) or fills an empty tool slot; -1 if none. */
	static int fetchableTool(Container chest, GolemData data, Predicate<ItemStack> is) {
		int cur = toolSlot(data, is);
		if (cur >= 0 && !data.items.get(cur).nextDamageWillBreak()) return -1;
		if (cur < 0 && !hasEmptyToolSlot(data)) return -1;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (is.test(st) && st.getCount() == 1 && (cur < 0 || !st.nextDamageWillBreak())) return i;
		}
		return -1;
	}

	private static boolean hasEmptyToolSlot(GolemData data) {
		for (int i = LumberProfession.TOOL_FROM; i < LumberProfession.TOOL_FROM + LumberProfession.TOOL_SLOTS; i++)
			if (data.items.get(i).isEmpty()) return true;
		return false;
	}

	/** Empty: take one. Worn (breaks on its next use): swap, the old one goes into the chest slot (nothing lost). */
	static void fetchTool(Container chest, GolemData data, Predicate<ItemStack> is) {
		int i = fetchableTool(chest, data, is);
		if (i < 0) return;
		int cur = toolSlot(data, is);
		ItemStack fresh = chest.removeItem(i, 1);
		if (cur < 0) {
			for (int k = LumberProfession.TOOL_FROM; k < LumberProfession.TOOL_FROM + LumberProfession.TOOL_SLOTS; k++) {
				if (data.items.get(k).isEmpty()) {
					data.items.set(k, fresh);
					break;
				}
			}
		} else {
			chest.setItem(i, data.items.get(cur)); // fetchableTool only offers single-item slots, so slot i is empty now
			data.items.set(cur, fresh);
		}
		chest.setChanged();
	}

	/** Anything to fetch right now? Mirrors the fetch methods' preconditions exactly (no endless walking to the chest). */
	private static boolean wantsFetch(GolemData data, Container in, boolean felling, boolean needPlant) {
		if (felling && (fetchableTool(in, data, LumberLogic::isAxe) >= 0 || fetchableTool(in, data, LumberLogic::isShears) >= 0))
			return true;
		ItemStack sap = data.items.get(LumberProfession.SAPLING_SLOT);
		return (felling || needPlant) && sap.getCount() < SAPLING_LOW && saplingSlot(in, sap) >= 0;
	}

	/** Slot in {@code chest} to take saplings from: the first slot with the sapling slot's item, or (empty slot) the first sapling. */
	static int saplingSlot(Container chest, ItemStack cur) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty()) continue;
			if (cur.isEmpty() ? isSapling(st) : ItemStack.isSameItemSameComponents(cur, st)) return i;
		}
		return -1;
	}

	/** Takes up to a full stack of the first sapling (or tops up the current sapling item). */
	static void fetchSaplings(Container chest, GolemData data) {
		ItemStack cur = data.items.get(LumberProfession.SAPLING_SLOT);
		if (cur.getCount() >= SAPLING_LOW) return;
		int first = saplingSlot(chest, cur);
		if (first < 0) return;
		ItemStack kind = chest.getItem(first).copyWithCount(1);
		int have = cur.getCount();
		int max = Math.min(64, kind.getMaxStackSize());
		int took = 0;
		for (int i = first; i < chest.getContainerSize() && have + took < max; i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || !ItemStack.isSameItemSameComponents(kind, st)) continue;
			int n = Math.min(max - have - took, st.getCount());
			took += n;
			st.shrink(n);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		data.items.set(LumberProfession.SAPLING_SLOT, kind.copyWithCount(have + took));
		chest.setChanged();
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

	/** Stores drops: sapling-slot merge first (same item), the rest into carry. {@code drops} becomes the leftover. */
	private static void storeInto(GolemData data, List<ItemStack> drops) {
		ItemStack sap = data.items.get(LumberProfession.SAPLING_SLOT);
		SimpleContainer tmp = new SimpleContainer(Math.max(1, drops.size()));
		for (int i = 0; i < drops.size(); i++) {
			ItemStack d = drops.get(i).copy();
			if (!sap.isEmpty() && ItemStack.isSameItemSameComponents(sap, d)) {
				int n = Math.min(d.getCount(), Math.min(64, sap.getMaxStackSize()) - sap.getCount());
				sap.grow(n);
				d.shrink(n);
			}
			tmp.setItem(i, d);
		}
		NonNullList<ItemStack> carry = carryView(data);
		TransporterLogic.takeAll(tmp, carry);
		writeCarry(data, carry);
		drops.clear();
		for (int i = 0; i < tmp.getContainerSize(); i++) if (!tmp.getItem(i).isEmpty()) drops.add(tmp.getItem(i));
	}

	/** Would all drops fit (sapling slot + carry)? Works on a copy of the inventory. */
	private static boolean fitsAll(GolemData data, List<ItemStack> drops) {
		GolemData copy = new GolemData();
		copy.items = NonNullList.withSize(data.items.size(), ItemStack.EMPTY);
		for (int i = 0; i < data.items.size(); i++) copy.items.set(i, data.items.get(i).copy());
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(copy, rest);
		return rest.isEmpty();
	}

	private static void storeAll(GolemData data, List<ItemStack> drops) {
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(data, rest);
		// cannot happen: fitsAll proved the drops fit and nothing ran in between
		if (!rest.isEmpty()) throw new IllegalStateException("lumber drops did not fit: " + rest);
	}
}
