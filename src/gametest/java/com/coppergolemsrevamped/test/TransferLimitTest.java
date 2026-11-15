package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** The per-golem transfer limit (16 items per 5 ticks): superdoc/architecture/transfer-limit.md. */
public class TransferLimitTest {
	private static int total(ChestBlockEntity chest) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
		return n;
	}

	/**
	 * A Transporter moves 256 items from A to B. Every tick the chest totals are sampled; the items that left A or entered
	 * B are summed per 5-tick window and none may exceed 16. Whether the test tick runs before or after the golem's tick
	 * is fixed but not known here, so the windows are cut at both possible offsets and one of them must be clean.
	 * Also: the lid of A stays open for the whole take, and the 512 counted moves (256 out, 256 in) take at least 160 ticks.
	 */
	@GameTest(maxTicks = 1500)
	public void transporterNeverMovesMoreThan16PerWindow(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		for (int i = 0; i < 4; i++) chestA.setItem(i, new ItemStack(Items.COBBLESTONE, 64));

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);

		List<long[]> moves = new ArrayList<>(); // {gameTime, items moved this tick}
		int[] prev = {256, 0};
		long[] takeFirst = {-1};
		long[] takeLast = {-1};
		long[] firstMove = {-1};
		long[] lastMove = {-1};
		List<Long> lidClosedWhileTaking = new ArrayList<>();
		List<Long> openTicks = new ArrayList<>();
		helper.onEachTick(() -> {
			long now = helper.getLevel().getGameTime();
			int ta = total(chestA);
			int tb = total(chestB);
			int out = prev[0] - ta;
			int in = tb - prev[1];
			prev[0] = ta;
			prev[1] = tb;
			if (out + in > 0) {
				moves.add(new long[] {now, out + in});
				if (firstMove[0] < 0) firstMove[0] = now;
				lastMove[0] = now;
			}
			if (out > 0) {
				if (takeFirst[0] < 0) takeFirst[0] = now;
				takeLast[0] = now;
			}
			if (takeFirst[0] >= 0 && ta > 0 && ChestBlockEntity.getOpenCount(helper.getLevel(), helper.absolutePos(a)) == 0) {
				lidClosedWhileTaking.add(now);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(total(chestA) == 0 && total(chestB) == 256 && TransferLimitTest.carryEmpty(golem),
				"A=" + total(chestA) + " B=" + total(chestB));
			int worst = 99;
			for (int off = 0; off < 2; off++) {
				java.util.Map<Long, Integer> perWindow = new java.util.HashMap<>();
				for (long[] m : moves) perWindow.merge((m[0] - off) / TransferBudget.WINDOW_TICKS, (int) m[1], Integer::sum);
				int max = perWindow.values().stream().max(Integer::compare).orElse(0);
				worst = Math.min(worst, max);
			}
			helper.assertTrue(worst <= TransferBudget.ITEMS_PER_WINDOW, "most items moved in one 5-tick window: " + worst);
			long span = lastMove[0] - firstMove[0];
			helper.assertTrue(span >= 150, "256 items out and in took only " + span + " ticks");
			helper.assertTrue(lidClosedWhileTaking.isEmpty(), "the lid of A closed while the golem was still taking: " + lidClosedWhileTaking);
		});
	}

	private static boolean carryEmpty(CopperGolem golem) {
		for (ItemStack s : GolemData.get(golem).items) if (!s.isEmpty()) return false;
		return true;
	}

	/**
	 * A budget-cut visit is not a give-up: a Smelter fetching 64 ore for 8 furnaces (4 windows, the plan is cut several
	 * times) still hands exactly 8 to every furnace and all 64 end up as ingots in the output chest.
	 */
	@GameTest(maxTicks = 6000)
	public void smelterFetchOf64OreCompletes(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = SmelterTest.base(helper, 1, c);
		List<AbstractFurnaceBlockEntity> all = new ArrayList<>();
		for (int z = 5; z <= 6; z++) {
			for (int x = 1; x <= 4; x++) all.add(SmelterTest.furnace(helper, new BlockPos(x, 1, z), Blocks.FURNACE));
		}
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 64));
		SmelterTest.addFuel(c[1], 64);
		int[] max = new int[all.size()];
		helper.onEachTick(() -> {
			for (int i = 0; i < max.length; i++) max[i] = Math.max(max[i], all.get(i).getItem(0).getCount());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(SmelterTest.count(c[2], Items.IRON_INGOT) == 64, "ingots in the output chest: " + SmelterTest.count(c[2], Items.IRON_INGOT));
			for (int m : max) helper.assertTrue(m == 8, "every furnace gets 8, saw " + java.util.Arrays.toString(max));
			helper.assertTrue(SmelterTest.heldTotal(golem) == 0 && SmelterTest.total(c[0]) == 0, "nothing left over");
		});
	}
}
