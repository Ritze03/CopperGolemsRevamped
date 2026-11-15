package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.lumber.LumberLogic;
import com.coppergolemsrevamped.profession.lumber.LumberProfession;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class LumberTest {
	/** Tree spot (bottom trunk). Test structures are 8x8x8: logs y=1..N, leaves above, all inside. */
	private static final BlockPos T = new BlockPos(3, 1, 3);
	private static final BlockPos IN = new BlockPos(0, 1, 1);
	private static final BlockPos OUT = new BlockPos(0, 1, 5);
	private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

	/** Stone floor, dirt under the tree spot, input and output chest. */
	private static void world(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(T.below(), Blocks.DIRT);
		helper.setBlock(IN, Blocks.CHEST);
		helper.setBlock(OUT, Blocks.CHEST);
	}

	/** Straight oak: {@code logs} logs from T up, then {@code topLeaves} persistent leaves in the column. */
	private static void tree(GameTestHelper helper, int logs, int topLeaves) {
		for (int k = 0; k < logs; k++) helper.setBlock(T.above(k), Blocks.OAK_LOG);
		for (int k = 0; k < topLeaves; k++) helper.setBlock(T.above(logs + k), LEAVES);
	}

	private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
		return helper.getBlockEntity(pos, ChestBlockEntity.class);
	}

	private static CopperGolem lumber(GameTestHelper helper) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(5, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, LumberProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put(LumberProfession.TREE, helper.absolutePos(T));
		data.assignments.put(LumberProfession.INPUT, helper.absolutePos(IN));
		data.assignments.put(LumberProfession.OUTPUT, helper.absolutePos(OUT));
		GolemData.save(golem, data);
		return golem;
	}

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	/** Damage of the first axe in the golem's tool slots, or -1 when it has none. */
	private static int axeDamage(CopperGolem golem) {
		GolemData d = GolemData.get(golem);
		for (int i = 0; i < LumberProfession.TOOL_SLOTS; i++) {
			ItemStack s = d.items.get(LumberProfession.TOOL_FROM + i);
			if (s.is(Items.IRON_AXE)) return s.getDamageValue();
		}
		return -1;
	}

	/**
	 * Oak of 5 logs with 2 leaves on top and persistent leaves around the trunk. Input chest: iron axe + 8 oak saplings.
	 * Output must hold exactly 5 oak logs, the spot an oak sapling, the column above it air, the axe 5 damage (one per
	 * log, none for leaves), and the side leaves must be untouched.
	 */
	@GameTest(maxTicks = 1500)
	public void fellsOakDeliversAndReplants(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		BlockPos[] side = { new BlockPos(2, 5, 3), new BlockPos(4, 5, 3), new BlockPos(3, 5, 2), new BlockPos(3, 5, 4), new BlockPos(2, 4, 3) };
		for (BlockPos p : side) helper.setBlock(p, LEAVES);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 8));
		CopperGolem golem = lumber(helper);

		helper.onEachTick(() -> {
			for (BlockPos p : side) helper.assertTrue(helper.getBlockState(p).is(Blocks.OAK_LEAVES), "side leaves at " + p + " changed: " + helper.getBlockState(p));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			for (int y = 2; y <= 7; y++) helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, y, 3));
			helper.assertTrue(axeDamage(golem) == 5, "axe damage: " + axeDamage(golem));
			helper.assertTrue(count(in, Items.IRON_AXE) == 0, "axe still in the input chest");
			helper.assertTrue(count(out, Items.IRON_AXE) == 0, "axe was delivered");
		});
	}

	/**
	 * The break rule: unit checks of {@link LumberLogic#breakAllowed}, and in the world a log branch and stone blocks next
	 * to the trunk (at the golem's stand position, beside the shaft, above the stand position) must survive the whole
	 * felling, checked every tick.
	 */
	@GameTest(maxTicks = 1500)
	public void onlyBreaksFrontOrAbove(GameTestHelper helper) {
		BlockPos f = new BlockPos(10, 10, 10);
		Direction d = Direction.EAST;
		helper.assertTrue(LumberLogic.breakAllowed(f, d, f.east()), "front must be allowed");
		helper.assertTrue(LumberLogic.breakAllowed(f, d, f.above()), "above must be allowed");
		BlockPos[] denied = { f.east(2), f.west(), f.north(), f.south(), f.below(), f.east().above(), f.east().below(),
			f.north().east(), f.above(2), f.above().west(), f, f.east(3) };
		for (BlockPos p : denied) helper.assertTrue(!LumberLogic.breakAllowed(f, d, p), p.subtract(f) + " must be denied");
		helper.assertTrue(!LumberLogic.breakAllowed(f, Direction.UP, f.east()), "facing up: side must be denied");

		world(helper);
		tree(helper, 4, 1);
		BlockPos[] kept = { new BlockPos(2, 3, 3), new BlockPos(3, 3, 4), new BlockPos(3, 1, 2), new BlockPos(4, 2, 3), new BlockPos(2, 2, 3), new BlockPos(4, 4, 3) };
		helper.setBlock(kept[0], Blocks.OAK_LOG);
		helper.setBlock(kept[1], Blocks.OAK_LOG);
		for (int i = 2; i < kept.length; i++) helper.setBlock(kept[i], Blocks.STONE);
		chest(helper, IN).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, IN).setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		lumber(helper);

		helper.onEachTick(() -> {
			for (int i = 0; i < kept.length; i++)
				helper.assertBlockPresent(i < 2 ? Blocks.OAK_LOG : Blocks.STONE, kept[i]);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(chest(helper, OUT), Items.OAK_LOG) == 4, "oak logs in output: " + count(chest(helper, OUT), Items.OAK_LOG));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
		});
	}

	/**
	 * Carry holds 8 full stacks of cobblestone and 63 oak logs: the second log does not fit while the golem hangs in the
	 * shaft, so it must climb out, deliver, come back and finish. Nothing is lost: output holds all 512 cobblestone and
	 * 63 + 4 logs, the tree is gone and replanted.
	 */
	@GameTest(maxTicks = 2500)
	public void fullCarryDeliversMidTreeAndContinues(GameTestHelper helper) {
		world(helper);
		tree(helper, 4, 0);
		chest(helper, IN).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, IN).setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		for (int i = 0; i < 8; i++) d.items.set(LumberProfession.CARRY_FROM + i, new ItemStack(Items.COBBLESTONE, 64));
		d.items.set(LumberProfession.CARRY_FROM + 8, new ItemStack(Items.OAK_LOG, 63));
		GolemData.save(golem, d);
		ChestBlockEntity out = chest(helper, OUT);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			helper.assertTrue(count(out, Items.OAK_LOG) == 67, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(count(out, Items.COBBLESTONE) == 512, "cobblestone in output: " + count(out, Items.COBBLESTONE));
			for (int i = 0; i < 9; i++) helper.assertTrue(GolemData.get(golem).items.get(LumberProfession.CARRY_FROM + i).isEmpty(), "carry slot " + i + " not empty");
		});
	}

	/** No axe anywhere (input chest holds only saplings and shears): the tree stays untouched. */
	@GameTest(maxTicks = 600)
	public void noAxeNoWork(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		chest(helper, IN).setItem(0, new ItemStack(Items.OAK_SAPLING, 8));
		chest(helper, IN).setItem(1, new ItemStack(Items.SHEARS));
		CopperGolem golem = lumber(helper);
		helper.runAfterDelay(300, () -> {
			for (int k = 0; k < 3; k++) helper.assertBlockPresent(Blocks.OAK_LOG, T.above(k));
			helper.assertBlockPresent(LEAVES.getBlock(), T.above(3));
			helper.assertTrue(axeDamage(golem) == -1, "golem has an axe");
			helper.succeed();
		});
	}

	/**
	 * The guard in {@link LumberLogic#breakBlock}: a golem two blocks from a log, or diagonal to it, may not break it
	 * (DENIED, block survives); the log directly in front is broken (positive control that the hook works).
	 */
	@GameTest
	public void guardDeniesWrongPosition(GameTestHelper helper) {
		world(helper);
		BlockPos far = new BlockPos(3, 1, 3), diag = new BlockPos(4, 1, 4), front = new BlockPos(5, 1, 2);
		helper.setBlock(far, Blocks.OAK_LOG);
		helper.setBlock(diag, Blocks.OAK_LOG);
		helper.setBlock(front, Blocks.OAK_LOG);
		CopperGolem golem = lumber(helper);
		GolemData data = GolemData.get(golem);
		data.items.set(LumberProfession.TOOL_FROM, new ItemStack(Items.IRON_AXE));
		helper.assertTrue(LumberLogic.breakBlock(helper.getLevel(), golem, data, Direction.WEST, helper.absolutePos(far)) == LumberLogic.Result.DENIED, "two blocks away not denied");
		helper.assertTrue(LumberLogic.breakBlock(helper.getLevel(), golem, data, Direction.WEST, helper.absolutePos(diag)) == LumberLogic.Result.DENIED, "diagonal not denied");
		helper.assertBlockPresent(Blocks.OAK_LOG, far);
		helper.assertBlockPresent(Blocks.OAK_LOG, diag);
		helper.assertTrue(LumberLogic.breakBlock(helper.getLevel(), golem, data, Direction.NORTH, helper.absolutePos(front)) == LumberLogic.Result.BROKEN, "block in front not broken");
		helper.assertBlockPresent(Blocks.AIR, front);
		helper.succeed();
	}

	/**
	 * The block above the golem turns to stone while it hangs in the shaft: the golem is held or climbs out, never left
	 * hanging unheld (no multi-tick fall, no damage), ends outside the column and replants.
	 */
	@GameTest(maxTicks = 1500)
	public void deniedInShaftDoesNotDrop(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 0);
		chest(helper, IN).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, IN).setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		CopperGolem golem = lumber(helper);
		float health = golem.getHealth();
		BlockPos at = helper.absolutePos(T);
		boolean[] replaced = { false };
		int[] fall = { 0 };
		double[] lastY = { golem.getY() };
		helper.onEachTick(() -> {
			if (!replaced[0] && golem.getBlockY() >= at.getY() + 2 && golem.getBlockX() == at.getX() && golem.getBlockZ() == at.getZ()) {
				helper.getLevel().setBlockAndUpdate(new BlockPos(at.getX(), golem.getBlockY() + 1, at.getZ()), Blocks.STONE.defaultBlockState());
				replaced[0] = true;
			}
			// held or snapped out: at most one tick of gravity sag in the column, never a fall of several ticks
			boolean inColumn = golem.getBlockX() == at.getX() && golem.getBlockZ() == at.getZ();
			fall[0] = inColumn && golem.getY() < lastY[0] - 0.02 ? fall[0] + 1 : 0;
			lastY[0] = golem.getY();
			helper.assertTrue(fall[0] < 2 && golem.getHealth() == health, "golem fell in the shaft for " + fall[0] + " ticks, health " + golem.getHealth());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(replaced[0], "stone was never placed");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			helper.assertTrue(golem.getBlockX() != at.getX() || golem.getBlockZ() != at.getZ(), "golem still in the column");
		});
	}

	/** All four neighbours of T are stone: no stand spot. The tree is skipped (not retried every tick) and nothing breaks. */
	@GameTest(maxTicks = 600)
	public void noStandSpotDoesNotLock(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		for (Direction d : Direction.Plane.HORIZONTAL) helper.setBlock(T.relative(d), Blocks.STONE);
		chest(helper, IN).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, IN).setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		CopperGolem golem = lumber(helper);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(LumberLogic.isSkipped(golem, helper.absolutePos(T), helper.getLevel().getGameTime()), "tree not skipped");
			for (int k = 0; k < 3; k++) helper.assertBlockPresent(Blocks.OAK_LOG, T.above(k));
			helper.assertBlockPresent(LEAVES.getBlock(), T.above(3));
			helper.succeed();
		});
	}

	/** Break time = ceil(hardness * 30 / speed): faster axes cut faster; the 26.3 axe speeds on an oak log. */
	@GameTest
	public void breakTimeFollowsAxeSpeed(GameTestHelper helper) {
		BlockState log = Blocks.OAK_LOG.defaultBlockState();
		float wood = new ItemStack(Items.WOODEN_AXE).getDestroySpeed(log);
		float iron = new ItemStack(Items.IRON_AXE).getDestroySpeed(log);
		float diamond = new ItemStack(Items.DIAMOND_AXE).getDestroySpeed(log);
		helper.assertTrue(wood == 2.0F && iron == 6.0F && diamond == 8.0F, "axe speeds: " + wood + " " + iron + " " + diamond);
		helper.assertTrue(LumberLogic.breakTicks(2.0F, wood) == 30, "wood " + LumberLogic.breakTicks(2.0F, wood));
		helper.assertTrue(LumberLogic.breakTicks(2.0F, iron) == 10, "iron " + LumberLogic.breakTicks(2.0F, iron));
		helper.assertTrue(LumberLogic.breakTicks(2.0F, diamond) == 8, "diamond " + LumberLogic.breakTicks(2.0F, diamond));
		helper.assertTrue(LumberLogic.breakTicks(0.0F, 1.0F) == 1, "instant blocks take at least 1 tick");
		helper.assertTrue(LumberLogic.breakTicks(2.0F, 1.0F) == 60, "bare hand " + LumberLogic.breakTicks(2.0F, 1.0F));
		helper.succeed();
	}

	/** Declaration: slot filters, inventory size, GUI rows (at most 6 with the assign row) and assign keys. */
	@GameTest
	public void lumberDeclaration(GameTestHelper helper) {
		Profession p = Professions.get(LumberProfession.ID);
		helper.assertTrue(p != null && !p.runsVanillaBrain(), "lumber not registered / runs the vanilla brain");
		helper.assertTrue(p.inventorySize() == 12, "inventory size " + p.inventorySize());
		helper.assertTrue(p.slotRows() + 1 <= 6, "rows " + (p.slotRows() + 1));
		Profession.SlotGroup tools = p.slots().get(0), sapling = p.slots().get(1);
		helper.assertTrue(tools.count() == 2 && tools.maxStack() == 1, "tools group");
		helper.assertTrue(tools.filter().test(new ItemStack(Items.IRON_AXE)) && tools.filter().test(new ItemStack(Items.SHEARS)), "tools take axe and shears");
		helper.assertTrue(!tools.filter().test(new ItemStack(Items.OAK_SAPLING)) && !tools.filter().test(new ItemStack(Items.IRON_HOE)), "tools take only axe/shears");
		helper.assertTrue(sapling.count() == 1 && sapling.maxStack() == 64, "sapling group");
		helper.assertTrue(sapling.filter().test(new ItemStack(Items.OAK_SAPLING)) && !sapling.filter().test(new ItemStack(Items.OAK_LOG)), "sapling filter");
		helper.assertTrue(p.assigns().stream().map(Profession.AssignSpec::key).toList().equals(java.util.List.of("tree", "input", "output")), "assign keys");
		helper.assertTrue(p.assigns().getFirst().kind() == Profession.Kind.BLOCK, "tree kind");
		helper.succeed();
	}
}
