package com.coppergolemsrevamped.profession.farmer;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Farmer work loop. Field = x/z rectangle of the two clicks; soil layer = Y of the lower click, crops at soil Y + 1.
 * Per cell: harvest a mature crop, plant a seed on empty farmland, till tillable soil with the hoe.
 */
public final class FarmerLogic {
	private FarmerLogic() {}

	static final int REPATH_TICKS = 20;
	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	static final int AFTER_ACTION_TICKS = 5;
	static final double SPEED = 1.0;
	/** Walking to one target this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up cell/chest is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;
	/** The seed slot is topped up from the seed chest when it holds fewer than this. */
	static final int SEED_LOW = 16;

	enum Act { HARVEST, PLANT, TILL }

	private static final class State {
		int cooldown;
		int repath;
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
		/** Given-up cell or chest position -> game time until which it is ignored. */
		final Map<BlockPos, Long> skip = new HashMap<>();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** A seed is an item that plants a crop: a {@link BlockItem} whose block is a {@link CropBlock} or {@link StemBlock}. */
	public static boolean isSeed(ItemStack stack) {
		return stack.getItem() instanceof BlockItem bi && (bi.getBlock() instanceof CropBlock || bi.getBlock() instanceof StemBlock);
	}

	/** Soil positions of the field (x outer, z inner, both ascending) at the lower click's Y; empty when unset or larger than 9x9. */
	public static List<BlockPos> cells(GolemData data) {
		BlockPos a = data.assignments.get(FarmerProfession.FIELD + "_a");
		BlockPos b = data.assignments.get(FarmerProfession.FIELD + "_b");
		List<BlockPos> out = new ArrayList<>();
		if (a == null || b == null || !AssignMode.areaFits(a, b, FarmerProfession.MAX_FIELD)) return out;
		int y = Math.min(a.getY(), b.getY());
		for (int x = Math.min(a.getX(), b.getX()); x <= Math.max(a.getX(), b.getX()); x++)
			for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()); z++)
				out.add(new BlockPos(x, y, z));
		return out;
	}

	static boolean tillable(BlockState s) {
		return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT_PATH);
	}

	/** What the cell needs, ignoring inventory; null = nothing to do. */
	static @Nullable Act actFor(ServerLevel level, BlockPos soil) {
		BlockState above = level.getBlockState(soil.above());
		if (above.getBlock() instanceof CropBlock crop) return crop.isMaxAge(above) ? Act.HARVEST : null;
		if (!above.isAir()) return null;
		BlockState s = level.getBlockState(soil);
		if (s.is(Blocks.FARMLAND)) return Act.PLANT;
		return tillable(s) ? Act.TILL : null;
	}

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		List<BlockPos> cells = cells(data);
		BlockPos outPos = data.assignments.get(FarmerProfession.OUTPUT);
		Container out = outPos == null ? null : TransporterLogic.containerAt(level, outPos);
		if (cells.isEmpty() || out == null) {
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);

		ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
		ItemStack seed = data.items.get(FarmerProfession.SEED_SLOT);
		boolean anyTill = false, anyPlant = false;
		for (BlockPos c : cells) {
			Act a = actFor(level, c);
			anyTill |= a == Act.TILL;
			anyPlant |= a == Act.PLANT || a == Act.TILL;
		}

		// 1. hoe
		if (anyTill && (tool.isEmpty() || tool.nextDamageWillBreak())) {
			BlockPos pos = data.assignments.get(FarmerProfession.TOOLS);
			Container chest = pos == null || s.skip.containsKey(pos) ? null : TransporterLogic.containerAt(level, pos);
			if (chest != null && findHoe(chest, tool) >= 0) {
				if (walkTo(s, golem, pos, now, true)) {
					fetchHoe(chest, data);
					done(s, golem, data, AFTER_ACTION_TICKS);
				}
				return;
			}
		}
		// 2. seeds
		if (anyPlant && seed.getCount() < SEED_LOW) {
			BlockPos pos = data.assignments.get(FarmerProfession.SEEDS);
			Container chest = pos == null || s.skip.containsKey(pos) ? null : TransporterLogic.containerAt(level, pos);
			if (chest != null && seedSlot(chest, seed) >= 0) {
				if (walkTo(s, golem, pos, now, true)) {
					fetchSeeds(chest, data);
					done(s, golem, data, AFTER_ACTION_TICKS);
				}
				return;
			}
		}
		// 3. nearest workable cell
		BlockPos best = null;
		Act bestAct = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos c : cells) {
			if (s.skip.containsKey(c)) continue;
			Act a = actFor(level, c);
			if (a == null || !workable(level, data, c, a)) continue;
			double d = golem.distanceToSqr(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
			if (d < bestD) {
				bestD = d;
				best = c;
				bestAct = a;
			}
		}
		if (best != null) {
			if (!walkTo(s, golem, best, now, false)) return;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(best));
			// a plant that cannot survive there (e.g. too dark) must not repeat every 5 ticks: skip the cell
			if (!act(level, golem, data, best, bestAct)) s.skip.put(best, now + SKIP_TICKS);
			done(s, golem, data, AFTER_ACTION_TICKS);
			return;
		}
		// 4. deliver
		if (!carryEmpty(data) && !s.skip.containsKey(outPos)) {
			if (walkTo(s, golem, outPos, now, true)) {
				NonNullList<ItemStack> carry = carryView(data);
				TransporterLogic.insertAll(carry, out);
				writeCarry(data, carry);
				done(s, golem, data, carryEmpty(data) ? AFTER_ACTION_TICKS : RETRY_TICKS);
			}
			return;
		}
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

	/** Inventory-aware: can this act be done right now? */
	private static boolean workable(ServerLevel level, GolemData data, BlockPos soil, Act act) {
		boolean seed = isSeed(data.items.get(FarmerProfession.SEED_SLOT));
		return switch (act) {
			case HARVEST -> {
				BlockState st = level.getBlockState(soil.above());
				yield fitsAll(data, drops(level, soil.above(), st));
			}
			case PLANT -> seed;
			case TILL -> seed && data.items.get(FarmerProfession.TOOL_SLOT).is(ItemTags.HOES);
		};
	}

	private static List<ItemStack> drops(ServerLevel level, BlockPos crop, BlockState st) {
		return Block.getDrops(st, level, crop, null, null, ItemStack.EMPTY);
	}

	/** Does the work; false when nothing was placed (planting failed). */
	private static boolean act(ServerLevel level, CopperGolem golem, GolemData data, BlockPos soil, Act act) {
		BlockPos crop = soil.above();
		return switch (act) {
			case HARVEST -> {
				BlockState st = level.getBlockState(crop);
				List<ItemStack> drops = drops(level, crop, st);
				level.destroyBlock(crop, false, golem);
				storeAll(data, drops);
				yield true;
			}
			case PLANT -> plant(level, data, soil);
			case TILL -> {
				ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
				level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3);
				level.playSound(null, soil, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
				// breaking empties the stack (vanilla behaviour); the item is gone, never duplicated
				tool.hurtAndBreak(1, level, null, broken -> {});
				if (tool.isEmpty()) data.items.set(FarmerProfession.TOOL_SLOT, ItemStack.EMPTY);
				yield plant(level, data, soil);
			}
		};
	}

	/** Plants one seed from the seed slot above {@code soil}; the seed is consumed only when it was placed. */
	private static boolean plant(ServerLevel level, GolemData data, BlockPos soil) {
		ItemStack seed = data.items.get(FarmerProfession.SEED_SLOT);
		if (!isSeed(seed) || !level.getBlockState(soil).is(Blocks.FARMLAND)) return false;
		Block block = ((BlockItem) seed.getItem()).getBlock();
		BlockState st = block.defaultBlockState();
		if (!st.canSurvive(level, soil.above())) return false;
		level.setBlock(soil.above(), st, 3);
		level.playSound(null, soil, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		seed.shrink(1);
		if (seed.isEmpty()) data.items.set(FarmerProfession.SEED_SLOT, ItemStack.EMPTY);
		return true;
	}

	// ---- walking with give-up -------------------------------------------------------------------------------------

	/**
	 * True when the golem is within reach of {@code pos} (soil position of a cell, or a chest) and may act now.
	 * Otherwise keeps walking; a target that shows no progress for {@link #GIVE_UP_TICKS} is skipped for {@link #SKIP_TICKS}.
	 */
	private static boolean walkTo(State s, CopperGolem golem, BlockPos pos, long now, boolean chest) {
		if (TransporterLogic.inReach(golem, pos)) return true;
		// airborne on the first ticks after spawning: paths are null, wait instead of counting no-progress ticks
		if (!golem.onGround()) return false;
		double dist = golem.distanceToSqr(pos.getX() + 0.5, pos.getY() + (chest ? 0 : 1), pos.getZ() + 0.5);
		if (!pos.equals(s.target) || dist < s.bestDist - PROGRESS_EPS) {
			s.target = pos;
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(pos, now + SKIP_TICKS);
			s.target = null;
			s.stuck = 0;
			s.repath = 0;
			golem.getNavigation().stop();
			return false;
		}
		if (--s.repath <= 0 || golem.getNavigation().isDone()) {
			// a null path is transient (spawn tick, mid-air): no progress, the timeout above decides
			golem.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + (chest ? 0 : 1), pos.getZ() + 0.5, SPEED);
			s.repath = REPATH_TICKS;
		}
		return false;
	}

	/** Test hook: is {@code pos} currently ignored by this golem. */
	public static boolean isSkipped(CopperGolem golem, BlockPos pos, long now) {
		State s = STATES.get(golem);
		Long until = s == null ? null : s.skip.get(pos);
		return until != null && until > now;
	}

	// ---- chest fetching -------------------------------------------------------------------------------------------

	/** Slot of a hoe in {@code chest} that may replace {@code tool}; -1 if none. A replacement must not break next hit. */
	static int findHoe(Container chest, ItemStack tool) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.is(ItemTags.HOES) && (tool.isEmpty() || !st.nextDamageWillBreak())) return i;
		}
		return -1;
	}

	/** Empty tool slot: take one hoe. Hoe about to break: swap with a better one (the old hoe goes into that slot). */
	static void fetchHoe(Container chest, GolemData data) {
		ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
		int i = findHoe(chest, tool);
		if (i < 0) return;
		ItemStack fresh = chest.removeItem(i, 1);
		if (tool.isEmpty()) {
			data.items.set(FarmerProfession.TOOL_SLOT, fresh);
		} else if (chest.getItem(i).isEmpty()) {
			chest.setItem(i, tool);
			data.items.set(FarmerProfession.TOOL_SLOT, fresh);
		} else { // stacked hoes (cannot happen in vanilla): put it back, nothing changes
			chest.getItem(i).grow(fresh.getCount());
		}
		chest.setChanged();
	}

	/** Slot in {@code chest} to take seeds from: the first slot with the seed slot's item, or (seed slot empty) the first seed. */
	static int seedSlot(Container chest, ItemStack cur) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty()) continue;
			if (cur.isEmpty() ? isSeed(st) : ItemStack.isSameItemSameComponents(cur, st)) return i;
		}
		return -1;
	}

	/** Takes up to a full stack of the first seed (or tops up the current seed item). */
	static void fetchSeeds(Container chest, GolemData data) {
		ItemStack cur = data.items.get(FarmerProfession.SEED_SLOT);
		int first = seedSlot(chest, cur);
		if (first < 0) return;
		ItemStack kind = chest.getItem(first).copyWithCount(1);
		int have = cur.getCount();
		int max = Math.min(64, kind.getMaxStackSize());
		int took = 0;
		for (int i = first; i < chest.getContainerSize() && have + took < max; i++) {
			ItemStack st = chest.getItem(i);
			// an empty stack never compares equal, so compare against the 1-count copy
			if (st.isEmpty() || !ItemStack.isSameItemSameComponents(kind, st)) continue;
			int n = Math.min(max - have - took, st.getCount());
			took += n;
			st.shrink(n);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		data.items.set(FarmerProfession.SEED_SLOT, kind.copyWithCount(have + took));
		chest.setChanged();
	}

	// ---- carry helpers --------------------------------------------------------------------------------------------

	static NonNullList<ItemStack> carryView(GolemData data) {
		NonNullList<ItemStack> carry = NonNullList.withSize(FarmerProfession.CARRY_SLOTS, ItemStack.EMPTY);
		for (int i = 0; i < carry.size(); i++) carry.set(i, data.items.get(FarmerProfession.CARRY_FROM + i));
		return carry;
	}

	static void writeCarry(GolemData data, NonNullList<ItemStack> carry) {
		for (int i = 0; i < carry.size(); i++) data.items.set(FarmerProfession.CARRY_FROM + i, carry.get(i));
	}

	static boolean carryEmpty(GolemData data) {
		return TransporterLogic.isEmpty(carryView(data));
	}

	/** Stores drops: seed-slot merge first, the rest into carry. {@code drops} becomes the leftover. */
	private static void storeInto(GolemData data, List<ItemStack> drops) {
		ItemStack seed = data.items.get(FarmerProfession.SEED_SLOT);
		SimpleContainer tmp = new SimpleContainer(Math.max(1, drops.size()));
		for (int i = 0; i < drops.size(); i++) {
			ItemStack d = drops.get(i).copy();
			if (!seed.isEmpty() && ItemStack.isSameItemSameComponents(seed, d)) {
				int n = Math.min(d.getCount(), Math.min(64, seed.getMaxStackSize()) - seed.getCount());
				seed.grow(n);
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

	/** Would all drops fit (seed slot + carry)? Works on a copy of the inventory. */
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
		// cannot happen: workable() proved the drops fit (fitsAll) and nothing ran in between
		if (!rest.isEmpty()) throw new IllegalStateException("farmer drops did not fit: " + rest);
	}
}
