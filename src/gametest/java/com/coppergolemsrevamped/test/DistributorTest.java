package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.distributor.DistributorLogic;
import com.coppergolemsrevamped.profession.distributor.DistributorProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Distributor: a multiple of N per item type, split evenly over the inventories of the area, leftovers returned. */
public class DistributorTest {
	private static final BlockPos IN_POS = new BlockPos(1, 1, 1);
	/** Two rows with a free row between them, so no two chests merge and the golem can walk between them. */
	private static final BlockPos[] SPOTS = {
		new BlockPos(0, 1, 5), new BlockPos(2, 1, 5), new BlockPos(4, 1, 5), new BlockPos(6, 1, 5),
		new BlockPos(0, 1, 7), new BlockPos(2, 1, 7), new BlockPos(4, 1, 7), new BlockPos(6, 1, 7)};
	private static final Block[] MIX = {Blocks.CHEST, Blocks.BARREL, Blocks.HOPPER, Blocks.DROPPER,
		Blocks.CHEST, Blocks.BARREL, Blocks.HOPPER, Blocks.DROPPER};

	private static Container at(GameTestHelper helper, BlockPos pos) {
		return TransporterLogic.containerAt(helper.getLevel(), helper.absolutePos(pos));
	}

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	private static int held(CopperGolem g) {
		int n = 0;
		for (ItemStack s : GolemData.get(g).items) n += s.getCount();
		return n;
	}

	/** Floor, input chest and a Distributor golem with the area (0,1,5)..(7,1,7); the targets are placed by the test. */
	private static CopperGolem base(GameTestHelper helper, boolean keepLayout) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(IN_POS, Blocks.CHEST);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, DistributorProfession.ID);
		GolemData d = GolemData.get(golem);
		d.assignments.put("input", helper.absolutePos(IN_POS));
		d.assignments.put("targets_a", helper.absolutePos(new BlockPos(0, 1, 5)));
		d.assignments.put("targets_b", helper.absolutePos(new BlockPos(7, 1, 7)));
		d.keepLayout = keepLayout;
		GolemData.save(golem, d);
		return golem;
	}

	private static void place(GameTestHelper helper, int n) {
		for (int i = 0; i < n; i++) helper.setBlock(SPOTS[i], MIX[i]);
	}

	/** 8 targets (chest, barrel, hopper, dropper, twice), 70 iron: 64 taken, 8 each, 6 stay in the input. */
	@GameTest(maxTicks = 2000)
	public void eightTargetsTakeMultipleOfEight(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		place(helper, 8);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
		in.setItem(1, new ItemStack(Items.IRON_INGOT, 6));
		helper.succeedWhen(() -> {
			for (int i = 0; i < 8; i++) {
				helper.assertTrue(count(at(helper, SPOTS[i]), Items.IRON_INGOT) == 8, "target " + i + " has " + count(at(helper, SPOTS[i]), Items.IRON_INGOT));
			}
			helper.assertTrue(count(in, Items.IRON_INGOT) == 6, "6 stay in the input: " + count(in, Items.IRON_INGOT));
			helper.assertTrue(held(golem) == 0, "nothing carried");
		});
	}

	/** 7 targets, 30 iron: 28 distributed (4 each), 2 stay. */
	@GameTest(maxTicks = 2000)
	public void sevenTargetsTakeMultipleOfSeven(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		place(helper, 7);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 30));
		helper.succeedWhen(() -> {
			for (int i = 0; i < 7; i++) {
				helper.assertTrue(count(at(helper, SPOTS[i]), Items.IRON_INGOT) == 4, "target " + i + " has " + count(at(helper, SPOTS[i]), Items.IRON_INGOT));
			}
			helper.assertTrue(count(in, Items.IRON_INGOT) == 2, "2 stay in the input: " + count(in, Items.IRON_INGOT));
			helper.assertTrue(held(golem) == 0, "nothing carried");
		});
	}

	/** A double chest is one inventory: 3 singles + 1 double = 4 targets, 40 iron -> 10 each (not 8 over 5). */
	@GameTest(maxTicks = 2000)
	public void doubleChestCountsAsOne(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		BlockState chest = Blocks.CHEST.defaultBlockState();
		helper.setBlock(SPOTS[0], chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		helper.setBlock(new BlockPos(1, 1, 5), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		helper.setBlock(new BlockPos(3, 1, 5), Blocks.CHEST);
		helper.setBlock(new BlockPos(5, 1, 5), Blocks.BARREL);
		helper.setBlock(new BlockPos(7, 1, 5), Blocks.CHEST);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(at(helper, SPOTS[0]), Items.IRON_INGOT) == 10, "double chest has " + count(at(helper, SPOTS[0]), Items.IRON_INGOT));
			for (BlockPos p : new BlockPos[] {new BlockPos(3, 1, 5), new BlockPos(5, 1, 5), new BlockPos(7, 1, 5)}) {
				helper.assertTrue(count(at(helper, p), Items.IRON_INGOT) == 10, p + " has " + count(at(helper, p), Items.IRON_INGOT));
			}
			helper.assertTrue(count(in, Items.IRON_INGOT) == 0 && held(golem) == 0, "all 40 distributed");
		});
	}

	/** Fewer than N of an item: nothing is taken, the status says why. */
	@GameTest(maxTicks = 400)
	public void fewerThanNTakesNothing(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		place(helper, 8);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 7));
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(count(in, Items.IRON_INGOT) == 7, "input untouched: " + count(in, Items.IRON_INGOT));
			helper.assertTrue(held(golem) == 0, "nothing carried");
			for (int i = 0; i < 8; i++) helper.assertTrue(count(at(helper, SPOTS[i]), Items.IRON_INGOT) == 0, "target " + i + " empty");
			helper.assertTrue(DistributorLogic.status(golem).equals("Input has fewer than 8 of any item"), "status: " + DistributorLogic.status(golem));
			helper.succeed();
		});
	}

	/**
	 * One of 4 chests is full: its share comes back to the input chest and is distributed again, and nothing is lost.
	 * The input shrinks 40 -> 10 -> 4 -> 1 over three rounds, the 3 usable chests end with 10 + 2 + 1 more.
	 */
	@GameTest(maxTicks = 4000)
	public void fullTargetReturnsItsShare(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		for (int i = 0; i < 4; i++) helper.setBlock(SPOTS[i], Blocks.CHEST);
		Container full = at(helper, SPOTS[0]);
		for (int i = 0; i < full.getContainerSize(); i++) full.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(full, Items.IRON_INGOT) == 0, "the full chest got nothing");
			int sum = 0;
			for (int i = 1; i < 4; i++) sum += count(at(helper, SPOTS[i]), Items.IRON_INGOT);
			helper.assertTrue(held(golem) == 0, "nothing carried");
			helper.assertTrue(sum + count(in, Items.IRON_INGOT) == 40, "nothing lost: " + sum + " + " + count(in, Items.IRON_INGOT));
			helper.assertTrue(count(in, Items.IRON_INGOT) == 1 && sum == 39, "settled: input " + count(in, Items.IRON_INGOT) + ", targets " + sum);
		});
	}

	/**
	 * Watches the input chest every tick and records whether its count of any of {@code items} ever went up. With an
	 * input that divides evenly by N and targets that have room, nothing may come back: a rise means a share was cut short.
	 */
	private static boolean[] watchNoRefill(GameTestHelper helper, Container in, Item... items) {
		boolean[] rose = {false};
		int[] last = new int[items.length];
		for (int k = 0; k < items.length; k++) last[k] = count(in, items[k]);
		helper.onEachTick(() -> {
			for (int k = 0; k < items.length; k++) {
				int now = count(in, items[k]);
				if (now > last[k]) rose[0] = true;
				last[k] = now;
			}
		});
		return rose;
	}

	/**
	 * 2 targets, 64 iron: a share of 32 per visit is twice the transfer budget (16 per window). The golem stays at each
	 * target over budget windows until the full 32 is in; nothing goes back to the input mid-cycle.
	 */
	@GameTest(maxTicks = 2000)
	public void shareLargerThanBudgetDeliveredInOneVisit(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		place(helper, 2);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
		boolean[] rose = watchNoRefill(helper, in, Items.IRON_INGOT);
		helper.succeedWhen(() -> {
			helper.assertTrue(!rose[0], "the input chest was refilled mid-cycle (a budget-cut share was dropped)");
			for (int i = 0; i < 2; i++) {
				helper.assertTrue(count(at(helper, SPOTS[i]), Items.IRON_INGOT) == 32, "target " + i + " has " + count(at(helper, SPOTS[i]), Items.IRON_INGOT));
			}
			helper.assertTrue(count(in, Items.IRON_INGOT) == 0 && held(golem) == 0, "all 64 distributed");
		});
	}

	/**
	 * 2 targets, 20 iron + 20 gold: each visit delivers 10 + 10 = 20, more than one budget window. The second type is
	 * not truncated to what was left of the window; nothing goes back to the input mid-cycle.
	 */
	@GameTest(maxTicks = 2000)
	public void severalTypesOverBudgetDeliveredInOneVisit(GameTestHelper helper) {
		CopperGolem golem = base(helper, false);
		place(helper, 2);
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 20));
		in.setItem(1, new ItemStack(Items.GOLD_INGOT, 20));
		boolean[] rose = watchNoRefill(helper, in, Items.IRON_INGOT, Items.GOLD_INGOT);
		helper.succeedWhen(() -> {
			helper.assertTrue(!rose[0], "the input chest was refilled mid-cycle (a budget-cut share was dropped)");
			for (int i = 0; i < 2; i++) {
				Container t = at(helper, SPOTS[i]);
				helper.assertTrue(count(t, Items.IRON_INGOT) == 10 && count(t, Items.GOLD_INGOT) == 10,
					"target " + i + " has " + count(t, Items.IRON_INGOT) + " iron, " + count(t, Items.GOLD_INGOT) + " gold");
			}
			helper.assertTrue(count(in, Items.IRON_INGOT) == 0 && count(in, Items.GOLD_INGOT) == 0 && held(golem) == 0, "all distributed");
		});
	}

	/**
	 * Keep layout: the input keeps 1 per slot; targets only get items into an existing matching stack. Targets 0 and 1
	 * hold 1 iron, targets 2 and 3 none: the share of 2 and 3 goes back (merged into the input's iron slot).
	 */
	@GameTest(maxTicks = 4000)
	public void keepLayoutRespectedOnBothSides(GameTestHelper helper) {
		CopperGolem golem = base(helper, true);
		for (int i = 0; i < 4; i++) helper.setBlock(SPOTS[i], Blocks.CHEST);
		at(helper, SPOTS[0]).setItem(0, new ItemStack(Items.IRON_INGOT, 1));
		at(helper, SPOTS[1]).setItem(0, new ItemStack(Items.IRON_INGOT, 1));
		Container in = at(helper, IN_POS);
		in.setItem(0, new ItemStack(Items.IRON_INGOT, 33));
		boolean[] below = {false};
		helper.onEachTick(() -> {
			if (in.getItem(0).getCount() < 1) below[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(!below[0], "the input slot was never emptied");
			for (int i = 0; i < 2; i++) {
				Container t = at(helper, SPOTS[i]);
				helper.assertTrue(count(t, Items.IRON_INGOT) == 16, "target " + i + " has " + count(t, Items.IRON_INGOT));
				helper.assertTrue(t.getItem(0).getCount() == 16, "all in the matching slot " + i);
			}
			for (int i = 2; i < 4; i++) helper.assertTrue(count(at(helper, SPOTS[i]), Items.IRON_INGOT) == 0, "target " + i + " (no matching stack) stays empty");
			helper.assertTrue(held(golem) == 0, "nothing carried");
			helper.assertTrue(in.getItem(0).getCount() == 3 && count(in, Items.IRON_INGOT) == 3, "input settled at 3: " + count(in, Items.IRON_INGOT));
		});
	}
}
