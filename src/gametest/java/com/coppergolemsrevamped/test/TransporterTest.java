package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

public class TransporterTest {
	private static final int STACKS_PER_TYPE = 6;

	/**
	 * 12 full stacks (two item types) in chest A, more than one 9-slot carry, so the golem must make
	 * several trips. The default empty structure is 8x8x8 air: floor at y=0, chests at y=1, x=1 and x=6.
	 */
	@GameTest(maxTicks = 2400)
	public void transporterMovesEverything(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		for (int i = 0; i < STACKS_PER_TYPE; i++) {
			chestA.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			chestA.setItem(STACKS_PER_TYPE + i, new ItemStack(Items.OAK_LOG, 64));
		}

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);

		helper.succeedWhen(() -> {
			int inA = 0;
			for (int i = 0; i < chestA.getContainerSize(); i++) inA += chestA.getItem(i).getCount();
			helper.assertTrue(inA == 0, "chest A still holds " + inA + " items");
			int stone = 0;
			int logs = 0;
			for (int i = 0; i < chestB.getContainerSize(); i++) {
				ItemStack s = chestB.getItem(i);
				if (s.is(Items.COBBLESTONE)) stone += s.getCount();
				else if (s.is(Items.OAK_LOG)) logs += s.getCount();
				else helper.assertTrue(s.isEmpty(), "unexpected item in chest B: " + s);
			}
			helper.assertTrue(stone == STACKS_PER_TYPE * 64, "cobblestone in B: " + stone);
			helper.assertTrue(logs == STACKS_PER_TYPE * 64, "oak logs in B: " + logs);
		});
	}

	// ---- ai/Interact tests (stand positions, reach, chest lids) ----

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static CopperGolem idleGolem(GameTestHelper helper, BlockPos rel) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, rel);
		// A profession without assignments idles in place (a Transporter never walks without both chests).
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		return golem;
	}

	private static int opens(GameTestHelper helper, BlockPos rel) {
		return ChestBlockEntity.getOpenCount(helper.getLevel(), helper.absolutePos(rel));
	}

	/** Nearest ortho neighbour is chosen; the diagonal-up spot is used when the same-level ones cannot be stood on. */
	@GameTest(maxTicks = 80)
	public void standPosPicksNearestOneBlockSpot(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(4, 1, 4);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = idleGolem(helper, new BlockPos(1, 1, 4));
		helper.runAfterDelay(10, () -> {
			var level = helper.getLevel();
			BlockPos spot = Interact.standPos(level, golem, helper.absolutePos(chest));
			helper.assertTrue(helper.absolutePos(new BlockPos(3, 1, 4)).equals(spot), "chest spot was " + spot);
			// Target at y=2 with no support at y=2: the golem stands one block lower (target diagonally up).
			BlockPos up = Interact.standPos(level, golem, helper.absolutePos(new BlockPos(4, 2, 4)));
			helper.assertTrue(up != null && up.getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY()
				&& Math.abs(up.getX() - helper.absolutePos(new BlockPos(4, 1, 4)).getX())
					+ Math.abs(up.getZ() - helper.absolutePos(new BlockPos(4, 1, 4)).getZ()) == 1, "diagonal-up spot was " + up);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void reachRules(GameTestHelper helper) {
		floor(helper);
		CopperGolem golem = idleGolem(helper, new BlockPos(1, 1, 1));
		BlockPos t = new BlockPos(4, 1, 4);
		BlockPos abs = helper.absolutePos(t);
		helper.runAfterDelay(10, () -> {
			golem.setNoGravity(true);
			record Case(int dx, int dy, int dz, boolean expect) {}
			Case[] cases = {
				new Case(-1, 0, 0, true), new Case(1, 0, 0, true), new Case(0, 0, 1, true), new Case(0, 0, -1, true),
				new Case(-1, -1, 0, true),   // target diagonally one block up
				new Case(0, 1, 0, true),     // standing on top, target below
				new Case(-1, 1, 0, true),    // target diagonally below
				new Case(-2, 0, 0, false), new Case(-1, 0, -1, false), new Case(0, 0, 0, false),
				new Case(-1, -2, 0, false), new Case(0, 2, 0, false), new Case(0, -1, 0, false)
			};
			for (Case c : cases) {
				BlockPos f = abs.offset(c.dx, c.dy, c.dz);
				golem.setPos(Vec3.atBottomCenterOf(f));
				helper.assertTrue(Interact.inReach(golem, abs) == c.expect, "inReach " + c + " golem at " + f);
			}
			// The 2-block fallback accepts what the strict rule refuses.
			golem.setPos(Vec3.atBottomCenterOf(abs.offset(-2, 0, 0)));
			helper.assertTrue(!Interact.inReach(golem, abs) && Interact.nearReach(golem, abs), "2-block fallback");
			helper.succeed();
		});
	}

	/** Open count goes up while a golem holds the chest and is back to 0 after the timer. */
	@GameTest(maxTicks = 80)
	public void openChestClosesAfterTimer(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(3, 1, 3);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = idleGolem(helper, new BlockPos(2, 1, 3));
		helper.runAfterDelay(2, () -> {
			Interact.openChest(helper.getLevel(), golem, helper.absolutePos(chest), Interact.Action.TAKE);
			helper.assertTrue(opens(helper, chest) == 1, "opener count after open: " + opens(helper, chest));
			helper.assertTrue(golem.getState() == CopperGolemState.GETTING_ITEM, "state " + golem.getState());
			// Re-opening the same chest extends the timer and must not double count.
			Interact.openChest(helper.getLevel(), golem, helper.absolutePos(chest), Interact.Action.TAKE);
			helper.assertTrue(opens(helper, chest) == 1, "opener count after re-open: " + opens(helper, chest));
		});
		helper.runAfterDelay(2 + Interact.OPEN_TICKS + 12, () -> {
			helper.assertTrue(opens(helper, chest) == 0, "opener count after timer: " + opens(helper, chest));
			helper.assertTrue(!Interact.isOpen(golem), "still registered");
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE, "state " + golem.getState());
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void killedGolemDoesNotLeakOpener(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(3, 1, 3);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = idleGolem(helper, new BlockPos(2, 1, 3));
		helper.runAfterDelay(2, () -> {
			Interact.openChest(helper.getLevel(), golem, helper.absolutePos(chest), Interact.Action.INSERT);
			helper.assertTrue(opens(helper, chest) == 1, "opener count after open: " + opens(helper, chest));
			golem.discard();
		});
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(opens(helper, chest) == 0, "opener count after golem removal: " + opens(helper, chest));
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void walkingAwayClosesChest(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(1, 1, 1);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = idleGolem(helper, new BlockPos(2, 1, 1));
		helper.runAfterDelay(2, () -> {
			Interact.openChest(helper.getLevel(), golem, helper.absolutePos(chest), Interact.Action.TAKE);
			helper.assertTrue(opens(helper, chest) == 1, "opener count after open: " + opens(helper, chest));
			golem.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 6))));
		});
		helper.runAfterDelay(6, () -> {
			helper.assertTrue(opens(helper, chest) == 0, "opener count after walking away: " + opens(helper, chest));
			helper.succeed();
		});
	}

	private static void transporterRun(GameTestHelper helper, BlockPos a, BlockPos b, Runnable perTick, Runnable check) {
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		for (int i = 0; i < 3; i++) chestA.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);
		helper.onEachTick(perTick);
		helper.succeedWhen(check);
	}

	/**
	 * Each time chest A's lid is open the golem must stand on a 1-block spot; both lids are seen open and end closed.
	 */
	@GameTest(maxTicks = 1200)
	public void transporterStandsCloseAndOpensLids(GameTestHelper helper) {
		floor(helper);
		BlockPos a = new BlockPos(1, 1, 1);
		BlockPos b = new BlockPos(1, 1, 6);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		boolean[] seen = new boolean[2];
		CopperGolem[] holder = new CopperGolem[1];
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		transporterRun(helper, a, b, () -> {
			if (holder[0] == null) {
				var golems = helper.getEntities(EntityTypes.COPPER_GOLEM);
				if (!golems.isEmpty()) holder[0] = golems.get(0);
				return;
			}
			if (opens(helper, a) > 0) {
				seen[0] = true;
				helper.assertTrue(Interact.inReach(holder[0], helper.absolutePos(a)), "golem at " + holder[0].blockPosition() + " not 1 block from A");
			}
			if (opens(helper, b) > 0) {
				seen[1] = true;
				helper.assertTrue(Interact.inReach(holder[0], helper.absolutePos(b)), "golem at " + holder[0].blockPosition() + " not 1 block from B");
			}
		}, () -> {
			int stone = 0;
			for (int i = 0; i < chestB.getContainerSize(); i++) stone += chestB.getItem(i).getCount();
			helper.assertTrue(stone == 192, "cobblestone in B: " + stone);
			helper.assertTrue(seen[0] && seen[1], "lids seen open: A=" + seen[0] + " B=" + seen[1]);
			helper.assertTrue(opens(helper, a) == 0 && opens(helper, b) == 0, "lid still open");
		});
	}

	/**
	 * Chest A walled in on every stand spot (two-high ring plus a block on top): no 1-block spot exists, so the golem
	 * falls back to the 2-block reach and still empties it.
	 */
	@GameTest(maxTicks = 1200)
	public void walledOffChestUsesTwoBlockFallback(GameTestHelper helper) {
		floor(helper);
		BlockPos a = new BlockPos(3, 1, 3);
		BlockPos b = new BlockPos(6, 1, 6);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] r : ring) for (int y = 1; y <= 2; y++) helper.setBlock(a.offset(r[0], y - 1, r[1]), Blocks.STONE);
		helper.setBlock(a.above(), Blocks.STONE);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		transporterRun(helper, a, b, () -> {}, () -> {
			int stone = 0;
			for (int i = 0; i < chestB.getContainerSize(); i++) stone += chestB.getItem(i).getCount();
			helper.assertTrue(stone == 192, "cobblestone in B: " + stone);
		});
	}

	// ---- types B cannot take (superdoc/features/transporter.md "Types B refuses") ----

	private static int count(ChestBlockEntity c, net.minecraft.world.item.Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	private static CopperGolem transporter(GameTestHelper helper, BlockPos a, BlockPos b, boolean keepLayout) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.keepLayoutTake = keepLayout; data.keepLayoutStore = keepLayout;
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);
		return golem;
	}

	/**
	 * Keep layout, B has a stick placeholder but no log stack: the golem moves the sticks and never takes the logs (it used
	 * to take 63 logs and stand at B with them forever).
	 */
	@GameTest(maxTicks = 1200)
	public void keepLayoutLeavesTypesBCannotTake(GameTestHelper helper) {
		floor(helper);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		chestA.setItem(0, new ItemStack(Items.STICK, 64));
		chestA.setItem(1, new ItemStack(Items.OAK_LOG, 64));
		chestB.setItem(0, new ItemStack(Items.STICK, 1));
		CopperGolem golem = transporter(helper, a, b, true);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(chestB, Items.STICK) == 64 && count(chestB, Items.OAK_LOG) == 0,
				"B sticks " + count(chestB, Items.STICK) + ", logs " + count(chestB, Items.OAK_LOG));
			helper.assertTrue(count(chestA, Items.OAK_LOG) == 64 && count(chestA, Items.STICK) == 1,
				"A sticks " + count(chestA, Items.STICK) + ", logs " + count(chestA, Items.OAK_LOG));
			helper.assertTrue(TransporterLogic.isEmpty(TransporterLogic.carry(GolemData.get(golem))), "carry not empty: " + GolemData.get(golem).items);
		});
	}

	/** A carry B refuses entirely (B changed meanwhile) goes back to A instead of waiting at B forever. */
	@GameTest(maxTicks = 1200)
	public void carryBRefusesGoesBackToA(GameTestHelper helper) {
		floor(helper);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		chestA.setItem(0, new ItemStack(Items.OAK_LOG, 1));
		chestB.setItem(0, new ItemStack(Items.STICK, 1));
		CopperGolem golem = transporter(helper, a, b, true);
		GolemData data = GolemData.get(golem);
		data.items.set(0, new ItemStack(Items.OAK_LOG, 10));
		GolemData.save(golem, data);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(chestA, Items.OAK_LOG) == 11, "A logs " + count(chestA, Items.OAK_LOG));
			helper.assertTrue(count(chestB, Items.OAK_LOG) == 0 && count(chestB, Items.STICK) == 1, "B changed");
			helper.assertTrue(TransporterLogic.isEmpty(TransporterLogic.carry(GolemData.get(golem))), "carry not empty: " + GolemData.get(golem).items);
		});
	}
}
