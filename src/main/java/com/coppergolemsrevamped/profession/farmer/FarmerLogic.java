package com.coppergolemsrevamped.profession.farmer;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.ToolSwing;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemMending;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

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
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Farmer work loop. Field = x/z rectangle of the two clicks; soil layer = Y of the lower click, crops at soil Y + 1.
 * One tool, two modes: hoe (or none) harvests mature crops, plants, tills; axe harvests only melon/pumpkin blocks.
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
	/** A seed fetch tops the seed slot up to this (a full stack). */
	static final int SEED_TARGET = 64;

	public enum Act { HARVEST, FRUIT, PLANT, TILL }

	private static final class State {
		/** A seed fetch was cut short by the transfer budget: it goes on to {@link #SEED_TARGET}. */
		boolean seedTopUp;
		int cooldown;
		int repath;
		final Interact.Approach walk = new Interact.Approach();
		/** The seed chest could not take all carried seeds: the rest goes to the output chest. */
		boolean seedChestFull;
		@Nullable BlockPos target;
		/** Soil cell being worked on with a tool swing, and ticks swung so far. */
		@Nullable BlockPos swingCell;
		int swingElapsed;
		double bestDist;
		int stuck;
		/** Given-up cell or chest position -> game time until which it is ignored. */
		final Map<BlockPos, Long> skip = new HashMap<>();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/**
	 * A seed plants a harvestable crop: a {@link BlockItem} whose block is a {@link CropBlock}. Stems (fruit is never
	 * harvested) and torchflower (turns into a flower block at its last stage) would block their cell forever.
	 */
	public static boolean isSeed(ItemStack stack) {
		return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock && !(bi.getBlock() instanceof TorchflowerCropBlock);
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

	/** What the tool slot makes the golem: no tool, hoe (crops) or axe (only melon/pumpkin blocks). */
	public enum Mode { NONE, HOE, AXE }

	static Mode modeOf(ItemStack tool) {
		return tool.is(ItemTags.HOES) ? Mode.HOE : tool.is(ItemTags.AXES) ? Mode.AXE : Mode.NONE;
	}

	/** Melon and pumpkin blocks (not carved pumpkins, not stems) at crop level are the axe's work. */
	static boolean isFruit(BlockState st) {
		return st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN);
	}

	/** Axe mode: only a fruit block above the soil is work. */
	static @Nullable Act actForAxe(ServerLevel level, BlockPos soil) {
		return isFruit(level.getBlockState(soil.above())) ? Act.FRUIT : null;
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
		Mode mode = modeOf(tool);
		boolean anyTill = false, anyPlant = false, anyFruit = false;
		for (BlockPos c : cells) {
			anyFruit |= actForAxe(level, c) != null;
			Act a = actFor(level, c);
			anyTill |= a == Act.TILL;
			anyPlant |= a == Act.PLANT || a == Act.TILL;
		}
		// the axe does only the fruit work: it never tills or plants, so it needs no seeds
		if (mode == Mode.AXE) anyPlant = false;

		// 1. tool: fill an empty slot (any hoe/axe: that sets the type) or swap a tool that breaks on its next use
		//    (same type only). Nothing is fetched when this tool type has no work in the field.
		boolean worn = tool.nextDamageWillBreak() || !GolemMending.canUse(tool, data.saveTools); // a Mending tool near its end counts as worn
		boolean toolWork = mode == Mode.NONE ? anyTill || anyFruit : mode == Mode.HOE ? anyTill : anyFruit;
		if (toolWork) {
			BlockPos pos = data.assignments.get(FarmerProfession.TOOLS);
			Container chest = pos == null || s.skip.containsKey(pos) ? null : TransporterLogic.containerAt(level, pos);
			if (chest != null) {
				boolean repair = GolemMending.canRepair(level, data, tool, chest, FarmerLogic::isTool);
				boolean swap = (mode == Mode.NONE || worn) && findTool(chest, tool, data) >= 0;
				boolean park = mode != Mode.NONE && GolemMending.mustPark(tool, data.saveTools) && GolemMending.canPark(chest, tool);
				if (repair || swap || park) {
					if (walkTo(level, s, golem, pos, now)) toolVisit(level, golem, data, s, chest, pos);
					return;
				}
			}
		}
		// 2. seeds
		if (anyPlant && seed.getCount() < (s.seedTopUp ? SEED_TARGET : SEED_LOW)) {
			BlockPos pos = data.assignments.get(FarmerProfession.SEEDS);
			Container chest = pos == null || s.skip.containsKey(pos) ? null : TransporterLogic.containerAt(level, pos);
			if (chest != null && seedSlot(chest, seed, data.keepLayout) >= 0) {
				if (walkTo(level, s, golem, pos, now)) {
					int n = fetchSeeds(chest, data, TransferBudget.remaining(level, golem));
					TransferBudget.spend(level, golem, n);
					// budget used up while seeds are still wanted: stay, keep the lid open, go on next window (not "chest empty")
					s.seedTopUp = TransferBudget.exhausted(level, golem) && data.items.get(FarmerProfession.SEED_SLOT).getCount() < SEED_TARGET
						&& seedSlot(chest, data.items.get(FarmerProfession.SEED_SLOT), data.keepLayout) >= 0;
					Interact.openChest(level, golem, pos, Interact.Action.TAKE);
					done(s, golem, data, s.seedTopUp ? TransferBudget.waitTicks(level) : Interact.OPEN_TICKS);
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
			Act a = mode == Mode.AXE ? actForAxe(level, c) : actFor(level, c);
			if (a == null || !workable(level, data, c, a)) continue;
			double d = golem.distanceToSqr(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
			if (d < bestD) {
				bestD = d;
				best = c;
				bestAct = a;
			}
		}
		if (best != null) {
			// the crop block (soil + 1) is the target: the golem stands beside it (or diagonally below), not on it
			if (!walkTo(level, s, golem, best.above(), now)) return;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(best.above()));
			// tool work swings first (hoe: 1 swing, axe on a fruit: 2); the work lands after the last swing
			int swings = swingsFor(mode, bestAct);
			if (swings > 0) {
				if (!best.equals(s.swingCell)) {
					s.swingCell = best;
					s.swingElapsed = 0;
				}
				int total = swings * ToolSwing.PERIOD;
				if (s.swingElapsed < total) {
					ToolSwing.tick(level, golem, bestAct == Act.TILL ? best : best.above(), s.swingElapsed++, total, bestAct == Act.FRUIT);
					return;
				}
				ToolSwing.end(level, golem);
			}
			// a plant that cannot survive there (e.g. too dark) must not repeat every 5 ticks: skip the cell
			if (!act(level, golem, data, best, bestAct)) s.skip.put(best, now + SKIP_TICKS);
			done(s, golem, data, AFTER_ACTION_TICKS);
			return;
		}
		// 4. deliver: seed items to the seed chest (when assigned and not full), everything else to the output chest
		if (!carryEmpty(data)) {
			BlockPos seedsPos = data.assignments.get(FarmerProfession.SEEDS);
			Container seedsChest = seedsPos == null || s.skip.containsKey(seedsPos) ? null : TransporterLogic.containerAt(level, seedsPos);
			if (!hasCarrySeeds(data)) s.seedChestFull = false;
			if (seedsChest != null && !s.seedChestFull && hasCarrySeeds(data)) {
				if (walkTo(level, s, golem, seedsPos, now)) {
					NonNullList<ItemStack> view = carryView(data);
					for (int i = 0; i < view.size(); i++) if (!isSeed(view.get(i))) view.set(i, ItemStack.EMPTY);
					boolean moved = TransferBudget.insert(level, golem, view, seedsChest, data.keepLayout) > 0;
					for (int i = 0; i < view.size(); i++) {
						if (isSeed(data.items.get(FarmerProfession.CARRY_FROM + i))) data.items.set(FarmerProfession.CARRY_FROM + i, view.get(i));
					}
					if (moved) Interact.openChest(level, golem, seedsPos, Interact.Action.INSERT);
					if (TransferBudget.exhausted(level, golem) && hasCarrySeeds(data)) { // budget, not "chest full": stay and go on
						Interact.openChest(level, golem, seedsPos, Interact.Action.INSERT);
						done(s, golem, data, TransferBudget.waitTicks(level));
						return;
					}
					// leftovers (chest full) go to the output chest next: no loss, no endless retry
					s.seedChestFull = hasCarrySeeds(data);
					done(s, golem, data, moved ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
				}
				return;
			}
			if (!s.skip.containsKey(outPos)) {
				if (walkTo(level, s, golem, outPos, now)) {
					NonNullList<ItemStack> carry = carryView(data);
					boolean moved = TransferBudget.insert(level, golem, carry, out, data.keepLayout) > 0;
					writeCarry(data, carry);
					if (moved) Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
					if (!carryEmpty(data) && TransferBudget.exhausted(level, golem)) { // budget, not "chest full": stay and go on
						Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
						done(s, golem, data, TransferBudget.waitTicks(level));
						return;
					}
					done(s, golem, data, !moved ? RETRY_TICKS : carryEmpty(data) ? Interact.OPEN_TICKS : RETRY_TICKS);
				}
				return;
			}
		}
		// 5. idle (no field work, nothing to deliver): top up damaged Mending tools from the tools chest, whatever they miss
		BlockPos toolsPos = data.assignments.get(FarmerProfession.TOOLS);
		Container toolsChest = toolsPos == null || s.skip.containsKey(toolsPos) ? null : TransporterLogic.containerAt(level, toolsPos);
		if (toolsChest != null && GolemMending.canRepair(level, data, tool, toolsChest, FarmerLogic::isTool, true)) {
			if (walkTo(level, s, golem, toolsPos, now)) {
				int used = GolemMending.repair(level, data, tool, toolsChest, FarmerLogic::isTool, TransferBudget.remaining(level, golem), true);
				TransferBudget.spend(level, golem, used);
				boolean more = TransferBudget.exhausted(level, golem) && GolemMending.canRepair(level, data, tool, toolsChest, FarmerLogic::isTool, true);
				if (used > 0 || more) Interact.openChest(level, golem, toolsPos, Interact.Action.TAKE);
				done(s, golem, data, more ? TransferBudget.waitTicks(level) : used > 0 ? Interact.OPEN_TICKS : SCAN_TICKS);
			}
			return;
		}
		golem.getNavigation().stop();
		s.cooldown = SCAN_TICKS;
	}

	private static boolean hasCarrySeeds(GolemData data) {
		for (ItemStack st : carryView(data)) if (isSeed(st)) return true;
		return false;
	}

	/** Swings before an action: tool work only (hoe harvests and tills, axe breaks a fruit); planting and bare hands none. */
	public static int swingsFor(Mode mode, Act act) {
		if (mode == Mode.NONE) return 0;
		return switch (act) {
			case HARVEST, TILL -> 1;
			case FRUIT -> 2;
			case PLANT -> 0;
		};
	}

	private static void done(State s, CopperGolem golem, GolemData data, int cooldown) {
		s.swingCell = null;
		golem.getNavigation().stop();
		s.walk.reset();
		s.repath = 0;
		s.target = null;
		s.cooldown = cooldown;
		GolemData.save(golem, data);
	}

	/** Inventory-aware cheap pre-check (a separate drop roll): {@link #act} is the authority and re-checks its own roll. */
	private static boolean workable(ServerLevel level, GolemData data, BlockPos soil, Act act) {
		boolean seed = isSeed(data.items.get(FarmerProfession.SEED_SLOT));
		ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
		return switch (act) {
			case HARVEST -> {
				BlockState st = level.getBlockState(soil.above());
				yield fitsAll(data, drops(level, soil.above(), st, false, ItemStack.EMPTY));
			}
			case FRUIT -> {
				// never use the last durability point: the slot must keep the tool (and with it the type)
				if (!tool.is(ItemTags.AXES) || !usable(tool, data)) yield false;
				BlockState st = level.getBlockState(soil.above());
				yield fitsAll(data, drops(level, soil.above(), st, false, tool));
			}
			case PLANT -> seed;
			case TILL -> seed && tool.is(ItemTags.HOES) && usable(tool, data);
		};
	}

	/**
	 * Test hook: when set, replaces the drop roll. Arguments: crop position, true for the roll {@link #act} stores
	 * (false for the {@link #workable} pre-check); a null result means roll normally (the hook is global, tests run in parallel). Lets a test force "the stored roll is bigger than the checked one".
	 */
	public static volatile @Nullable BiFunction<BlockPos, Boolean, List<ItemStack>> testDropRoll;

	private static List<ItemStack> drops(ServerLevel level, BlockPos crop, BlockState st, boolean forAct, ItemStack tool) {
		BiFunction<BlockPos, Boolean, List<ItemStack>> hook = testDropRoll;
		if (hook != null) {
			List<ItemStack> forced = hook.apply(crop, forAct);
			if (forced != null) return forced;
		}
		return Block.getDrops(st, level, crop, null, null, tool);
	}

	/** Does the work; false when nothing was placed (planting failed). */
	private static boolean act(ServerLevel level, CopperGolem golem, GolemData data, BlockPos soil, Act act) {
		BlockPos crop = soil.above();
		return switch (act) {
			case HARVEST -> {
				BlockState st = level.getBlockState(crop);
				// roll once; the roll that is checked is the roll that is stored. Nothing is destroyed unless it fits.
				List<ItemStack> drops = drops(level, crop, st, true, ItemStack.EMPTY);
				if (!fitsAll(data, drops)) yield false;
				level.destroyBlock(crop, false, golem);
				storeAll(data, drops);
				yield true;
			}
			case FRUIT -> {
				ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
				BlockState st = level.getBlockState(crop);
				if (!isFruit(st) || !tool.is(ItemTags.AXES) || !usable(tool, data)) yield false;
				List<ItemStack> drops = drops(level, crop, st, true, tool);
				if (!fitsAll(data, drops)) yield false;
				level.destroyBlock(crop, false, golem);
				storeAll(data, drops);
				GolemMending.damage(level, tool, data.saveTools, 1);
				yield true;
			}
			case PLANT -> plant(level, data, soil);
			case TILL -> {
				ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
				level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3);
				level.playSound(null, soil, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
				// workable() guarantees this is not the last durability point, so the slot never empties
				GolemMending.damage(level, tool, data.saveTools, 1);
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
	 * True when the golem stands within 1 block of {@code pos} (crop block of a cell, or a chest) and may act now
	 * ({@link Interact#approach}, which has its own 2-block fallback). A target that shows no progress for
	 * {@link #GIVE_UP_TICKS} is skipped for {@link #SKIP_TICKS}.
	 */
	private static boolean walkTo(ServerLevel level, State s, CopperGolem golem, BlockPos pos, long now) {
		// airborne on the first ticks after spawning: paths are null, wait instead of counting no-progress ticks
		// (bounded: a golem that never lands, e.g. floating in water, gives up like any stuck walk)
		if (!golem.onGround()) {
			if (++s.stuck >= GIVE_UP_TICKS) giveUp(s, golem, pos, now);
			return false;
		}
		if (Interact.approach(level, golem, pos, s.walk, SPEED)) return true;
		double dist = golem.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (!pos.equals(s.target) || dist < s.bestDist - PROGRESS_EPS) {
			s.target = pos;
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			giveUp(s, golem, pos, now);
		}
		return false;
	}

	private static void giveUp(State s, CopperGolem golem, BlockPos pos, long now) {
		// the cell is keyed by its soil block, the walk target is the crop block above it
		s.skip.put(pos, now + SKIP_TICKS);
		s.skip.put(pos.below(), now + SKIP_TICKS);
		s.target = null;
		s.stuck = 0;
		s.repath = 0;
		s.walk.reset();
		golem.getNavigation().stop();
	}

	/** Test hook: is {@code pos} currently ignored by this golem. */
	public static boolean isSkipped(CopperGolem golem, BlockPos pos, long now) {
		State s = STATES.get(golem);
		Long until = s == null ? null : s.skip.get(pos);
		return until != null && until > now;
	}

	// ---- chest fetching -------------------------------------------------------------------------------------------

	static boolean isTool(ItemStack s) {
		return modeOf(s) != Mode.NONE;
	}

	/** A tool the golem may use now: not on its last durability point, and a Mending tool not near its end. */
	static boolean usable(ItemStack tool, GolemData data) {
		return !tool.nextDamageWillBreak() && GolemMending.canUse(tool, data.saveTools);
	}

	/**
	 * One visit to the tool chest: repair Mending tools with the chest's XP bottles (held one first, then those lying in
	 * the chest), then swap a worn tool for a usable one (same type; the worn one goes into the chest slot), or park a
	 * resting Mending tool when nothing replaces it (the golem then works without a tool: it harvests but does not till).
	 */
	private static void toolVisit(ServerLevel level, CopperGolem golem, GolemData data, State s, Container chest, BlockPos pos) {
		int used = GolemMending.repair(level, data, data.items.get(FarmerProfession.TOOL_SLOT), chest, FarmerLogic::isTool, TransferBudget.remaining(level, golem));
		TransferBudget.spend(level, golem, used);
		ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
		boolean worn = tool.nextDamageWillBreak() || !GolemMending.canUse(tool, data.saveTools);
		boolean acted = used > 0;
		if ((tool.isEmpty() || worn) && findTool(chest, tool, data) >= 0) {
			if (TransferBudget.remaining(level, golem) >= 1) {
				fetchTool(chest, data);
				TransferBudget.spend(level, golem, 1); // a swap counts as one item
				acted = true;
			}
		} else if (GolemMending.mustPark(tool, data.saveTools) && TransferBudget.remaining(level, golem) >= 1 && GolemMending.park(chest, tool)) {
			data.items.set(FarmerProfession.TOOL_SLOT, ItemStack.EMPTY);
			TransferBudget.spend(level, golem, 1);
			acted = true;
		}
		ItemStack now = data.items.get(FarmerProfession.TOOL_SLOT);
		// budget used up while work remains: stay, keep the lid open, go on next window (not "nothing to do")
		boolean more = TransferBudget.exhausted(level, golem) && (GolemMending.canRepair(level, data, now, chest, FarmerLogic::isTool)
			|| GolemMending.mustPark(now, data.saveTools) && GolemMending.canPark(chest, now)
			|| (now.isEmpty() || now.nextDamageWillBreak() || GolemMending.mustPark(now, data.saveTools)) && findTool(chest, now, data) >= 0);
		if (acted || more) Interact.openChest(level, golem, pos, Interact.Action.TAKE);
		done(s, golem, data, more ? TransferBudget.waitTicks(level) : acted ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
	}

	/**
	 * Slot of a tool in {@code chest} that may go into the tool slot; -1 if none. Empty slot: the first hoe or axe.
	 * Otherwise only the same type (hoe for hoe, axe for axe). Never one that breaks on its next use.
	 */
	static int findTool(Container chest, ItemStack tool, GolemData data) {
		Mode mode = modeOf(tool);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			Mode m = modeOf(st);
			if (m != Mode.NONE && (mode == Mode.NONE || m == mode) && usable(st, data)) return i;
		}
		return -1;
	}

	/** Empty tool slot: take one tool. Worn tool: swap with a same-type one (the old tool goes into that chest slot). */
	static void fetchTool(Container chest, GolemData data) {
		ItemStack tool = data.items.get(FarmerProfession.TOOL_SLOT);
		int i = findTool(chest, tool, data);
		if (i < 0) return;
		ItemStack fresh = chest.removeItem(i, 1);
		if (tool.isEmpty()) {
			data.items.set(FarmerProfession.TOOL_SLOT, fresh);
		} else if (chest.getItem(i).isEmpty()) {
			chest.setItem(i, tool);
			data.items.set(FarmerProfession.TOOL_SLOT, fresh);
		} else { // stacked tools (cannot happen in vanilla): put it back, nothing changes
			chest.getItem(i).grow(fresh.getCount());
		}
		chest.setChanged();
	}

	/** Slot in {@code chest} to take seeds from: the first slot with the seed slot's item, or (seed slot empty) the first seed. */
	static int seedSlot(Container chest, ItemStack cur, boolean keepLayout) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || TransporterLogic.takeable(chest, st, keepLayout) <= 0) continue;
			if (cur.isEmpty() ? isSeed(st) : ItemStack.isSameItemSameComponents(cur, st)) return i;
		}
		return -1;
	}

	/** Takes up to a full stack of the first seed (or tops up the current seed item), at most {@code limit}; returns how many. */
	static int fetchSeeds(Container chest, GolemData data, int limit) {
		ItemStack cur = data.items.get(FarmerProfession.SEED_SLOT);
		int first = seedSlot(chest, cur, data.keepLayout);
		if (first < 0 || limit <= 0) return 0;
		ItemStack kind = chest.getItem(first).copyWithCount(1);
		int have = cur.getCount();
		int max = Math.min(Math.min(64, kind.getMaxStackSize()), have + limit);
		int took = 0;
		for (int i = first; i < chest.getContainerSize() && have + took < max; i++) {
			ItemStack st = chest.getItem(i);
			// an empty stack never compares equal, so compare against the 1-count copy
			if (st.isEmpty() || !ItemStack.isSameItemSameComponents(kind, st)) continue;
			int n = Math.min(max - have - took, TransporterLogic.takeable(chest, st, data.keepLayout));
			if (n <= 0) continue;
			took += n;
			st.shrink(n);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		data.items.set(FarmerProfession.SEED_SLOT, kind.copyWithCount(have + took));
		chest.setChanged();
		return took;
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
		// cannot happen: act() checked fitsAll on this very list before destroying the crop
		if (!rest.isEmpty()) throw new IllegalStateException("farmer drops did not fit: " + rest);
	}
}
