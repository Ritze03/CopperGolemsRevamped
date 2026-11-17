package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.ai.ToolSwing;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.lumber.LumberLogic;
import com.coppergolemsrevamped.profession.lumber.LumberProfession;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
		return lumber(helper, new BlockPos(5, 1, 3));
	}

	private static CopperGolem lumber(GameTestHelper helper, BlockPos spawn) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, spawn);
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

	/** Damage of the golem's axe, or -1 when it has none. */
	private static int axeDamage(CopperGolem golem) {
		ItemStack s = GolemData.get(golem).items.get(LumberProfession.AXE_SLOT);
		return s.isEmpty() ? -1 : s.getDamageValue();
	}

	/** Count of {@code item} in the golem's supply slots. */
	private static int supplies(CopperGolem golem, Item item) {
		int n = 0;
		GolemData d = GolemData.get(golem);
		for (int i = 0; i < LumberProfession.SUPPLY_SLOTS; i++) {
			ItemStack st = d.items.get(LumberProfession.SUPPLY_FROM + i);
			if (st.is(item)) n += st.getCount();
		}
		return n;
	}

	private static void give(ChestBlockEntity in, Item axe, Item sapling, int saplings) {
		in.setItem(0, new ItemStack(axe));
		in.setItem(1, new ItemStack(sapling, saplings));
	}

	/**
	 * Oak of 5 logs with 2 leaves on top and persistent leaves around the trunk. Felled from the ground: the golem is never
	 * inside the trunk column and never above T.y + 1 (no climbing), every leaf block stays. Output holds exactly 5 oak
	 * logs, T is an oak sapling, the axe has 5 damage (one per log).
	 */
	@GameTest(maxTicks = 1500)
	public void fellsOakFromTheGround(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		BlockPos[] side = { new BlockPos(2, 5, 3), new BlockPos(4, 5, 3), new BlockPos(3, 5, 2), new BlockPos(3, 5, 4), new BlockPos(2, 4, 3), new BlockPos(3, 6, 3), new BlockPos(3, 7, 3) };
		for (BlockPos p : side) helper.setBlock(p, LEAVES);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);

		helper.onEachTick(() -> {
			for (BlockPos p : side) helper.assertTrue(helper.getBlockState(p).is(Blocks.OAK_LEAVES), "leaves at " + p + " changed: " + helper.getBlockState(p));
			helper.assertTrue(golem.getY() < at.getY() + 1.5, "golem climbed to y " + golem.getY());
			helper.assertTrue(golem.getBlockX() != at.getX() || golem.getBlockZ() != at.getZ(), "golem is in the trunk column");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			for (int y = 2; y <= 5; y++) helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, y, 3));
			helper.assertTrue(axeDamage(golem) == 5, "axe damage: " + axeDamage(golem));
			helper.assertTrue(count(in, Items.IRON_AXE) == 0 && count(out, Items.IRON_AXE) == 0, "axe moved to a chest");
		});
	}

	/**
	 * A branchy tree: trunk of 6, branches to the east (two logs sideways), a diagonal branch to the west and one to the
	 * north. All 13 logs end in the output. Not part of the tree and therefore untouched: an unconnected log, a log of
	 * another wood kind touching the trunk, and a log diagonally below T (the flood never goes below T's Y).
	 */
	@GameTest(maxTicks = 2500)
	public void fellsBranchyTree(GameTestHelper helper) {
		world(helper);
		for (int k = 0; k < 6; k++) helper.setBlock(T.above(k), Blocks.OAK_LOG);
		BlockPos[] branches = { new BlockPos(4, 3, 3), new BlockPos(5, 3, 3), new BlockPos(2, 4, 3), new BlockPos(1, 5, 3), new BlockPos(1, 6, 3), new BlockPos(3, 5, 2), new BlockPos(3, 6, 1) };
		for (BlockPos p : branches) helper.setBlock(p, Blocks.OAK_LOG);
		BlockPos stray = new BlockPos(7, 6, 7), birch = new BlockPos(4, 2, 3), below = new BlockPos(4, 0, 4);
		helper.setBlock(stray, Blocks.OAK_LOG);
		helper.setBlock(birch, Blocks.BIRCH_LOG);
		helper.setBlock(below, Blocks.OAK_LOG);
		ChestBlockEntity out = chest(helper, OUT);
		give(chest(helper, IN), Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);

		helper.onEachTick(() -> {
			helper.assertBlockPresent(Blocks.OAK_LOG, stray);
			helper.assertBlockPresent(Blocks.BIRCH_LOG, birch);
			helper.assertBlockPresent(Blocks.OAK_LOG, below);
			helper.assertTrue(golem.getY() < at.getY() + 1.5, "golem climbed to y " + golem.getY());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 13, "oak logs in output: " + count(out, Items.OAK_LOG));
			for (BlockPos p : branches) helper.assertBlockPresent(Blocks.AIR, p);
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
		});
	}

	/**
	 * A 2x2 dark oak trunk (4 high) with a branch log: all 18 logs end in the output, four dark oak saplings stand on the
	 * 2x2 square afterwards and the golem keeps the other four.
	 */
	@GameTest(maxTicks = 2500)
	public void fellsTwoByTwoAndReplantsFour(GameTestHelper helper) {
		world(helper);
		BlockPos[] cells = { T, T.east(), T.south(), T.east().south() };
		for (BlockPos c : cells) {
			helper.setBlock(c.below(), Blocks.DIRT);
			for (int k = 0; k < 4; k++) helper.setBlock(c.above(k), Blocks.DARK_OAK_LOG);
		}
		BlockPos[] extra = { new BlockPos(5, 4, 3), new BlockPos(2, 5, 3) };
		for (BlockPos p : extra) helper.setBlock(p, Blocks.DARK_OAK_LOG);
		ChestBlockEntity out = chest(helper, OUT);
		give(chest(helper, IN), Items.IRON_AXE, Items.DARK_OAK_SAPLING, 8);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);

		helper.onEachTick(() -> helper.assertTrue(golem.getY() < at.getY() + 1.5, "golem climbed to y " + golem.getY()));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.DARK_OAK_LOG) == 18, "dark oak logs in output: " + count(out, Items.DARK_OAK_LOG));
			for (BlockPos c : cells) helper.assertBlockPresent(Blocks.DARK_OAK_SAPLING, c);
			helper.assertTrue(supplies(golem, Items.DARK_OAK_SAPLING) == 4, "saplings left: " + supplies(golem, Items.DARK_OAK_SAPLING));
		});
	}

	/**
	 * No tree: a sapling stands on T and drops lie around it (saplings, sticks, one far in a corner). The golem walks to
	 * each: sticks end in the output chest, saplings in its supply slot (it holds none yet), no item entity is left.
	 */
	@GameTest(maxTicks = 1500)
	public void collectsGroundDrops(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		helper.spawnItem(Items.OAK_SAPLING, new Vec3(6.5, 1.0, 6.5));
		helper.spawnItem(Items.STICK, new Vec3(1.5, 1.0, 2.5));
		helper.spawnItem(Items.STICK, new Vec3(6.5, 1.0, 1.5));
		helper.spawnItem(Items.STICK, new Vec3(2.5, 1.0, 6.5));
		ChestBlockEntity out = chest(helper, OUT);
		CopperGolem golem = lumber(helper);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STICK) == 3, "sticks in output: " + count(out, Items.STICK));
			helper.assertTrue(supplies(golem, Items.OAK_SAPLING) == 1, "saplings in supply: " + supplies(golem, Items.OAK_SAPLING));
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, helper.getBounds().inflate(4)).isEmpty(), "item entities left");
		});
	}

	/**
	 * Bone meal in the input chest: the golem fetches it and grows the sapling on T (bone meal is random, so 64 of it),
	 * then fells the grown tree. Passes when logs reached the output and bone meal was used.
	 */
	@GameTest(maxTicks = 4000)
	public void bonemealGrowsTheSaplingThenFellsIt(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		in.setItem(2, new ItemStack(Items.OAK_SAPLING, 8));
		CopperGolem golem = lumber(helper);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) >= 3, "oak logs in output: " + count(out, Items.OAK_LOG));
			int bone = count(in, Items.BONE_MEAL) + supplies(golem, Items.BONE_MEAL);
			helper.assertTrue(bone < 64, "bone meal untouched: " + bone);
		});
	}

	/**
	 * Carry holds 8 full stacks of cobblestone and 63 oak logs: the second log does not fit, so the golem must walk to the
	 * output chest, deliver, come back and finish. Nothing is lost: output holds all 512 cobblestone and 63 + 4 logs, the
	 * tree is gone and replanted.
	 */
	@GameTest(maxTicks = 2500)
	public void fullCarryDeliversMidTreeAndContinues(GameTestHelper helper) {
		world(helper);
		tree(helper, 4, 0);
		give(chest(helper, IN), Items.IRON_AXE, Items.OAK_SAPLING, 4);
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

	/** No axe anywhere (input chest holds only saplings): the tree stays untouched. */
	@GameTest(maxTicks = 600)
	public void noAxeNoWork(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		chest(helper, IN).setItem(0, new ItemStack(Items.OAK_SAPLING, 8));
		CopperGolem golem = lumber(helper);
		helper.runAfterDelay(300, () -> {
			for (int k = 0; k < 3; k++) helper.assertBlockPresent(Blocks.OAK_LOG, T.above(k));
			helper.assertBlockPresent(LEAVES.getBlock(), T.above(3));
			helper.assertTrue(axeDamage(golem) == -1, "golem has an axe");
			helper.succeed();
		});
	}

	/**
	 * The golem carries an iron axe that breaks on its next use; the input chest holds a diamond axe. It swaps before
	 * cutting: the worn axe is in the chest afterwards (not lost), the golem holds the diamond axe, all logs delivered.
	 */
	@GameTest(maxTicks = 1500)
	public void wornAxeIsSwapped(GameTestHelper helper) {
		world(helper);
		tree(helper, 4, 0);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.DIAMOND_AXE));
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		ItemStack worn = new ItemStack(Items.IRON_AXE);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		d.items.set(LumberProfession.AXE_SLOT, worn);
		GolemData.save(golem, d);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 4, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(count(in, Items.IRON_AXE) == 1, "worn axe in the chest: " + count(in, Items.IRON_AXE));
			helper.assertTrue(GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).is(Items.DIAMOND_AXE), "golem axe is not the diamond axe");
		});
	}

	/**
	 * The axe has 1 durability left and the chest holds no replacement: the chop breaks it, the slot becomes empty, no
	 * exception (it used to crash with index -1), the golem idles. A new axe put in the chest later is fetched and the work finishes.
	 */
	@GameTest(maxTicks = 2500)
	public void brokenAxeEmptiesSlotAndRefetches(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 0);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 4));
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		ItemStack worn = new ItemStack(Items.IRON_AXE);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		d.items.set(LumberProfession.AXE_SLOT, worn);
		GolemData.save(golem, d);
		boolean[] gaveNew = { false };

		helper.succeedWhen(() -> {
			if (!gaveNew[0]) {
				helper.assertTrue(helper.getBlockState(T.above()).is(Blocks.OAK_LOG) && !helper.getBlockState(T).is(Blocks.OAK_LOG),
					"first log not chopped yet");
				helper.assertTrue(GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).isEmpty(), "axe slot empty after the break");
				in.setItem(0, new ItemStack(Items.DIAMOND_AXE));
				gaveNew[0] = true;
				helper.assertTrue(false, "new axe offered");
			}
			helper.assertTrue(count(out, Items.OAK_LOG) == 3, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).is(Items.DIAMOND_AXE), "golem fetched the new axe");
		});
	}

	/**
	 * All four neighbours of T are stone, so there is no 1-block stand spot: the golem falls back to the 2-block reach
	 * and still fells the tree (the Lumber reach exception), leaves untouched.
	 */
	@GameTest(maxTicks = 2500)
	public void walledInTrunkIsFelledFromTwoBlocks(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) helper.setBlock(T.relative(d), Blocks.STONE);
		give(chest(helper, IN), Items.IRON_AXE, Items.OAK_SAPLING, 4);
		lumber(helper);
		ChestBlockEntity out = chest(helper, OUT);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 3, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertBlockPresent(LEAVES.getBlock(), T.above(3));
		});
	}

	/**
	 * The tree stands inside a closed 3-high stone ring the golem cannot enter: no stand spot is reachable, so after the
	 * give-up time the tree is skipped (not retried every tick) and nothing breaks.
	 */
	@GameTest(maxTicks = 900)
	public void unreachableTreeIsSkipped(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
			if (x != 1 && x != 5 && z != 1 && z != 5) continue;
			for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
		}
		give(chest(helper, IN), Items.IRON_AXE, Items.OAK_SAPLING, 4);
		CopperGolem golem = lumber(helper, new BlockPos(6, 1, 3));
		helper.runAfterDelay(600, () -> {
			helper.assertTrue(LumberLogic.isSkipped(golem, helper.absolutePos(T), helper.getLevel().getGameTime()), "tree not skipped");
			for (int k = 0; k < 3; k++) helper.assertBlockPresent(Blocks.OAK_LOG, T.above(k));
			helper.succeed();
		});
	}

	/**
	 * The whole cycle in order: T is empty, the chest holds an axe, saplings, bone meal. Phases (from the phase listener, so
	 * no phase is missed) go PICKUP, DELIVER (fresh start), PLANT, BONEMEAL, FELL, DELIVER, PLANT, BONEMEAL; every step follows
	 * the cycle (PICKUP always into DELIVER, DELIVER always into PLANT, FELL always straight into DELIVER: no wait after the
	 * last log). Foreign saplings (birch) put into the carry when the felling starts end up in the input chest (surplus),
	 * the logs in the output chest.
	 */
	@GameTest(maxTicks = 5000)
	public void fullCycleOrder(GameTestHelper helper) {
		world(helper);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 8));
		in.setItem(2, new ItemStack(Items.BONE_MEAL, 64));
		CopperGolem golem = lumber(helper);
		List<LumberLogic.Phase> seq = new java.util.ArrayList<>();
		boolean[] dropped = { false };
		LumberLogic.phaseListener = (g, p) -> {
			if (g == golem) seq.add(p);
		};
		BlockPos at = helper.absolutePos(T);
		helper.onEachTick(() -> {
			if (helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS)) helper.assertTrue(!golem.blockPosition().equals(at), "golem stands in the sapling");
			if (!dropped[0] && seq.contains(LumberLogic.Phase.FELL)) {
				dropped[0] = true;
				GolemData d = GolemData.get(golem);
				d.items.set(LumberProfession.CARRY_FROM, new ItemStack(Items.BIRCH_SAPLING, 2));
				GolemData.save(golem, d);
			}
		});
		List<LumberLogic.Phase> expect = List.of(LumberLogic.Phase.PICKUP, LumberLogic.Phase.DELIVER, LumberLogic.Phase.PLANT, LumberLogic.Phase.BONEMEAL,
			LumberLogic.Phase.FELL, LumberLogic.Phase.DELIVER, LumberLogic.Phase.PLANT, LumberLogic.Phase.BONEMEAL);
		helper.succeedWhen(() -> {
			helper.assertTrue(seq.size() >= expect.size(), "phases so far: " + seq);
			helper.assertTrue(seq.subList(0, expect.size()).equals(expect), "phase order: " + seq);
			for (int i = 0; i + 1 < seq.size(); i++) {
				LumberLogic.Phase a = seq.get(i), b = seq.get(i + 1);
				boolean ok = switch (a) {
					case PICKUP -> b == LumberLogic.Phase.DELIVER;
					case DELIVER -> b == LumberLogic.Phase.PLANT;
					case FELL -> b == LumberLogic.Phase.DELIVER;
					case PLANT -> b != LumberLogic.Phase.DELIVER && b != LumberLogic.Phase.PLANT;
					case BONEMEAL -> b == LumberLogic.Phase.FELL || b == LumberLogic.Phase.PICKUP;
				};
				helper.assertTrue(ok, "bad step " + a + " -> " + b + " in " + seq);
			}
			helper.assertTrue(count(in, Items.BIRCH_SAPLING) == 2, "surplus birch saplings in input: " + count(in, Items.BIRCH_SAPLING));
			helper.assertTrue(count(out, Items.BIRCH_SAPLING) == 0, "birch saplings in output: " + count(out, Items.BIRCH_SAPLING));
			helper.assertTrue(count(out, Items.OAK_LOG) >= 3, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(count(in, Items.OAK_LOG) == 0, "logs in input chest");
			LumberLogic.phaseListener = null;
		});
	}

	/** Saplings anywhere the golem owns them: supply slots, carry, input chest. */
	private static int saplingTotal(CopperGolem golem, Container in) {
		int n = count(in, Items.OAK_SAPLING) + supplies(golem, Items.OAK_SAPLING);
		GolemData d = GolemData.get(golem);
		for (int i = 0; i < LumberProfession.CARRY_SLOTS; i++) {
			ItemStack st = d.items.get(LumberProfession.CARRY_FROM + i);
			if (st.is(Items.OAK_SAPLING)) n += st.getCount();
		}
		return n;
	}

	private static int boneTotal(CopperGolem golem, Container in) {
		return count(in, Items.BONE_MEAL) + supplies(golem, Items.BONE_MEAL);
	}

	/**
	 * Bug "lumber golems keep losing all their saplings": with bone meal the wait is near zero, so drops lying around were
	 * never collected. Sapling standing, 64 bone meal, plenty of saplings. When the golem enters BONEMEAL a sapling and two
	 * sticks are dropped far from the footprint: not one bone meal may be used before all three are in the golem.
	 */
	@GameTest(maxTicks = 3000)
	public void dropsAreCollectedBeforeBoneMeal(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		in.setItem(2, new ItemStack(Items.OAK_SAPLING, 20));
		CopperGolem golem = lumber(helper);
		boolean[] spawned = { false };
		boolean[] collected = { false };
		helper.onEachTick(() -> {
			if (!spawned[0]) {
				if (LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL && boneTotal(golem, in) == 64) {
					helper.spawnItem(Items.OAK_SAPLING, new Vec3(6.5, 1.0, 6.5));
					helper.spawnItem(Items.STICK, new Vec3(6.5, 1.0, 1.5));
					helper.spawnItem(Items.STICK, new Vec3(1.5, 1.0, 6.5));
					spawned[0] = true;
				}
				return;
			}
			int left = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, helper.getBounds().inflate(2)).size();
			if (left == 0) collected[0] = true;
			if (!collected[0]) helper.assertTrue(boneTotal(golem, in) == 64, "bone meal used while " + left + " drops lie in the box");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(collected[0], "drops not collected yet");
			helper.assertTrue(boneTotal(golem, in) < 64, "bone meal never used afterwards");
		});
	}

	/**
	 * Low stock (golem and input chest hold no sapling, one is needed for the next planting): the golem does not bone-meal
	 * while drops keep arriving. (Drops land where the golem stands: one on the far side of the sapling is skipped by the
	 * path rule, see lumber.md.) A sapling drop lands every 60 ticks after the golem enters BONEMEAL; the first bone meal may
	 * only be used once the stock covers the next planting with a spare (2 saplings), never at stock 0 or 1.
	 */
	@GameTest(maxTicks = 3000)
	public void lowSaplingStockWaitsForDropsBeforeBoneMeal(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		CopperGolem golem = lumber(helper);
		int[] start = { -1 };
		int[] stockWhenUsed = { -1 };
		helper.onEachTick(() -> {
			int now = (int) helper.getTick();
			if (start[0] < 0) {
				if (LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL) start[0] = now;
				return;
			}
			int dt = now - start[0];
			if (dt >= 5 && dt % 60 == 5 && dt <= 125) helper.spawnItem(Items.OAK_SAPLING, helper.relativeVec(golem.position()));
			if (stockWhenUsed[0] < 0 && boneTotal(golem, in) < 64) stockWhenUsed[0] = saplingTotal(golem, in);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(stockWhenUsed[0] >= 0, "no bone meal used yet");
			helper.assertTrue(stockWhenUsed[0] >= 2, "bone meal used at sapling stock " + stockWhenUsed[0]);
		});
	}

	/**
	 * Several bone meal cycles: each time a tree has been felled a sapling drop is spawned (what the decaying leaves
	 * would give). The golem must have it back before the next tree is grown, so the sapling stock (golem + carry + input
	 * chest) never falls below its start value of 3 at any tree, and no spawned drop is left on the ground.
	 */
	@GameTest(maxTicks = 9000)
	public void boneMealCyclesKeepTheSaplingStock(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		in.setItem(2, new ItemStack(Items.OAK_SAPLING, 3));
		CopperGolem golem = lumber(helper);
		net.minecraft.world.entity.item.ItemEntity[] drop = { null };
		int[] trees = { 0 };
		boolean[] pendingFell = { false };
		LumberLogic.Phase[] last = { null };
		// polled, not phaseListener: that hook is static and the tests run side by side
		helper.onEachTick(() -> {
			LumberLogic.Phase p = LumberLogic.phaseOf(golem);
			if (p != last[0]) {
				last[0] = p;
				if (p == LumberLogic.Phase.FELL) {
					trees[0]++;
					pendingFell[0] = true;
					helper.assertTrue(drop[0] == null || drop[0].isRemoved(), "tree " + trees[0] + " grew while the last drop still lay there: drop " + (drop[0] == null ? "none" : drop[0].blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)).toString()) + " golem " + golem.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
					helper.assertTrue(saplingTotal(golem, in) >= 3, "tree " + trees[0] + ": sapling stock " + saplingTotal(golem, in));
				}
				if (p == LumberLogic.Phase.DELIVER && pendingFell[0] && trees[0] <= 4) {
					pendingFell[0] = false;
					drop[0] = helper.spawnItem(Items.OAK_SAPLING, helper.relativeVec(golem.position()));
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(trees[0] >= 5, "trees felled: " + trees[0]);
		});
	}

	/**
	 * A learned 2x2 site (spruce, which would also grow from one sapling) with only 3 saplings in hand plants nothing, not
	 * even a single one; with a 4th it plants all four.
	 */
	@GameTest(maxTicks = 1500)
	public void twoByTwoSiteWaitsForFourSaplings(GameTestHelper helper) {
		world(helper);
		BlockPos[] cells = { T, T.east(), T.south(), T.east().south() };
		for (BlockPos c : cells) helper.setBlock(c.below(), Blocks.DIRT);
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		d.assignments.put(LumberProfession.SITE_2X2, helper.absolutePos(T));
		d.items.set(LumberProfession.SUPPLY_FROM, new ItemStack(Items.SPRUCE_SAPLING, 3));
		GolemData.save(golem, d);
		helper.runAfterDelay(400, () -> {
			for (BlockPos c : cells) helper.assertBlockPresent(Blocks.AIR, c);
			helper.assertTrue(supplies(golem, Items.SPRUCE_SAPLING) == 3, "saplings: " + supplies(golem, Items.SPRUCE_SAPLING));
			GolemData g = GolemData.get(golem);
			g.items.set(LumberProfession.SUPPLY_FROM, new ItemStack(Items.SPRUCE_SAPLING, 4));
			GolemData.save(golem, g);
		});
		helper.succeedWhen(() -> {
			for (BlockPos c : cells) helper.assertBlockPresent(Blocks.SPRUCE_SAPLING, c);
		});
	}

	/**
	 * Never in the sapling: while a sapling stands on T and bone meal grows it (64 bone meal, then the felling), the golem's
	 * feet block is never T's column and its health never drops.
	 */
	@GameTest(maxTicks = 4000)
	public void neverStandsInTheSapling(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);
		float health = golem.getHealth();
		helper.onEachTick(() -> {
			if (helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS)) {
				helper.assertTrue(golem.getBlockX() != at.getX() || golem.getBlockZ() != at.getZ(), "golem stands in the sapling at " + golem.blockPosition());
			}
			helper.assertTrue(golem.getHealth() >= health, "golem took damage: " + golem.getHealth());
		});
		helper.succeedWhen(() -> helper.assertTrue(count(out, Items.OAK_LOG) >= 3, "oak logs in output: " + count(out, Items.OAK_LOG)));
	}

	/**
	 * Waiting for natural growth (BONEMEAL, no bone meal anywhere): drops that appear around the sapling (2 sticks,
	 * 2 apples, 1 sapling, all at least 2 blocks from T; one stick in the ring next to T, picked up as well) are picked up
	 * into the carry while the sapling is still a sapling; the golem is never in the sapling's volume, and the phase stays BONEMEAL.
	 */
	@GameTest(maxTicks = 1500)
	public void collectsDropsWhileWaitingForGrowth(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);
		boolean[] spawned = {false};
		helper.onEachTick(() -> {
			if (!helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS)) return;
			helper.assertTrue(!LumberLogic.inVolume(golem.blockPosition(), at, List.of(at)), "golem is in the sapling volume at " + golem.blockPosition());
			if (!spawned[0] && LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL) {
				spawned[0] = true;
				helper.spawnItem(Items.STICK, new Vec3(1.5, 1.0, 2.5));
				helper.spawnItem(Items.STICK, new Vec3(6.5, 1.0, 1.5));
				helper.spawnItem(Items.APPLE, new Vec3(5.5, 1.0, 6.5));
				helper.spawnItem(Items.APPLE, new Vec3(1.5, 1.0, 6.5));
				helper.spawnItem(Items.OAK_SAPLING, new Vec3(6.5, 1.0, 6.5));
				helper.spawnItem(Items.STICK, new Vec3(4.5, 1.0, 3.5)); // right next to T: collected too
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spawned[0], "still waiting for the BONEMEAL phase");
			helper.assertTrue(helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS), "the sapling grew before the check");
			helper.assertTrue(LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL, "phase " + LumberLogic.phaseOf(golem));
			int[] n = new int[3];
			for (ItemStack st : GolemData.get(golem).items) {
				if (st.is(Items.STICK)) n[0] += st.getCount();
				if (st.is(Items.APPLE)) n[1] += st.getCount();
				if (st.is(Items.OAK_SAPLING)) n[2] += st.getCount();
			}
			helper.assertTrue(n[0] == 3 && n[1] == 2 && n[2] == 1, "carried sticks/apples/saplings: " + n[0] + "/" + n[1] + "/" + n[2]);
		});
	}

	/**
	 * Waiting for growth with XP bottles in the input chest: a Mending axe that misses only 5 points (below one bottle's
	 * worth, so the felling visit rule would ignore it) is repaired to full during the wait, with exactly one bottle.
	 */
	@GameTest(maxTicks = 1500)
	public void repairsMendingAxeWhileWaitingForGrowth(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		ChestBlockEntity in = chest(helper, IN);
		in.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 5));
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		d.items.set(LumberProfession.AXE_SLOT, MendingTest.mending(helper, Items.IRON_AXE, 250 - 5));
		GolemData.save(golem, d);
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS), "the sapling grew before the check");
			helper.assertTrue(axeDamage(golem) == 0, "axe damage: " + axeDamage(golem));
			helper.assertTrue(count(in, Items.EXPERIENCE_BOTTLE) == 4, "bottles left: " + count(in, Items.EXPERIENCE_BOTTLE));
		});
	}

	/**
	 * A sapling of the golem's own kind stands at T (no bone meal): a sapling and an apple lying exactly one block from the
	 * footprint are picked up while the sapling still stands (the old 2-block margin ignored them).
	 */
	@GameTest(maxTicks = 1500)
	public void collectsDropsOneBlockFromTheSapling(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);
		boolean[] spawned = {false};
		helper.onEachTick(() -> {
			if (!helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS)) return;
			helper.assertTrue(!LumberLogic.inVolume(golem.blockPosition(), at, List.of(at)), "golem is in the sapling volume at " + golem.blockPosition());
			if (!spawned[0] && LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL) {
				spawned[0] = true;
				helper.spawnItem(Items.OAK_SAPLING, new Vec3(4.5, 1.0, 3.5));
				helper.spawnItem(Items.APPLE, new Vec3(2.5, 1.0, 3.5));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spawned[0], "still waiting for the BONEMEAL phase");
			helper.assertTrue(helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS), "the sapling grew before the check");
			int apples = 0, saplings = 0;
			for (ItemStack st : GolemData.get(golem).items) {
				if (st.is(Items.APPLE)) apples += st.getCount();
				if (st.is(Items.OAK_SAPLING)) saplings += st.getCount();
			}
			helper.assertTrue(apples == 1 && saplings >= 1, "carried apples/saplings: " + apples + "/" + saplings);
		});
	}

	/**
	 * An apple lies on the sapling's own cell (inside the footprint, the danger area): the golem sucks it up from a safe cell
	 * 1..2 blocks away, never stands in the sapling volume, and the sapling still stands.
	 */
	@GameTest(maxTicks = 1500)
	public void collectsDropInsideTheSaplingFromOutside(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper);
		BlockPos at = helper.absolutePos(T);
		boolean[] spawned = {false};
		helper.onEachTick(() -> {
			if (!helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS)) return;
			helper.assertTrue(!LumberLogic.inVolume(golem.blockPosition(), at, List.of(at)), "golem is in the sapling volume at " + golem.blockPosition());
			if (!spawned[0] && LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL) {
				spawned[0] = true;
				helper.spawnItem(Items.APPLE, new Vec3(T.getX() + 0.5, T.getY(), T.getZ() + 0.5));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spawned[0], "still waiting for the BONEMEAL phase");
			helper.assertTrue(helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS), "the sapling grew before the check");
			int apples = 0;
			for (ItemStack st : GolemData.get(golem).items) if (st.is(Items.APPLE)) apples += st.getCount();
			helper.assertTrue(apples == 1, "carried apples: " + apples);
		});
	}

	/** A golem that starts inside the sapling steps out and waits 1..2 blocks from it (no bone meal: natural growth). */
	@GameTest(maxTicks = 600)
	public void stepsOutOfTheSapling(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper, T);
		BlockPos at = helper.absolutePos(T);
		helper.runAfterDelay(300, () -> {
			int d = Math.max(Math.abs(golem.getBlockX() - at.getX()), Math.abs(golem.getBlockZ() - at.getZ()));
			helper.assertTrue(d >= 1 && d <= 2, "golem waits " + d + " blocks from the sapling at " + golem.blockPosition());
			helper.succeed();
		});
	}

	/**
	 * Pickup delay (review finding): an apple thrown at the waiting golem's feet with a player's 40-tick pickup delay is not
	 * taken while the delay runs (before the fix it went within one 20-tick idle), but is collected once it has run out.
	 */
	@GameTest(maxTicks = 1500)
	public void pickupDelayedDropIsNotTakenAtOnce(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper);
		ItemEntity[] drop = { null };
		long[] at = { -1 };
		helper.onEachTick(() -> {
			if (drop[0] == null) {
				if (LumberLogic.phaseOf(golem) == LumberLogic.Phase.BONEMEAL && golem.onGround() && golem.getNavigation().isDone()) {
					drop[0] = helper.spawnItem(Items.APPLE, helper.relativeVec(golem.position()));
					drop[0].setPickUpDelay(40); // what Player.drop gives a thrown item
					drop[0].setDeltaMovement(Vec3.ZERO);
					at[0] = helper.getTick();
				}
				return;
			}
			long dt = helper.getTick() - at[0];
			if (dt < 38) helper.assertTrue(drop[0].isAlive(), "taken " + dt + " ticks after the throw, inside its pickup delay");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(drop[0] != null && drop[0].isRemoved(), "apple not collected yet");
			int apples = 0;
			for (ItemStack st : GolemData.get(golem).items) if (st.is(Items.APPLE)) apples += st.getCount();
			helper.assertTrue(apples == 1, "carried apples: " + apples);
		});
	}

	/**
	 * A golem standing inside the sapling volume with a drop on the sapling cell: it steps out to the ring and sucks the drop
	 * up from there. Before the fix the start node made every path "cross" the sapling, no detour existed, and the drop was
	 * skipped for 1200 ticks.
	 */
	@GameTest(maxTicks = 600)
	public void collectsDropWhileStandingInTheSapling(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper, T);
		helper.spawnItem(Items.APPLE, new Vec3(T.getX() + 0.5, T.getY(), T.getZ() + 0.5));
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getBlockState(T).is(net.minecraft.tags.BlockTags.SAPLINGS), "the sapling is gone");
			int apples = 0;
			for (ItemStack st : GolemData.get(golem).items) if (st.is(Items.APPLE)) apples += st.getCount();
			helper.assertTrue(apples == 1, "carried apples: " + apples);
		});
	}

	/**
	 * An obstructed sapling (stone right above it: vanilla accepts bone meal, consumes it and fails to place the tree) does
	 * not drain the input chest: the golem stops after at most one stage step plus 2 x BONE_MAX_FAILS (16) unchanged uses,
	 * and stops fetching, so the count is stable from then on.
	 */
	@GameTest(maxTicks = 1200)
	public void obstructedSaplingDoesNotDrainBoneMeal(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		helper.setBlock(T.above(), Blocks.STONE);
		ChestBlockEntity in = chest(helper, IN);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.BONE_MEAL, 64));
		in.setItem(2, new ItemStack(Items.OAK_SAPLING, 20));
		CopperGolem golem = lumber(helper);
		int[] usedAt700 = { -1 };
		helper.runAfterDelay(700, () -> {
			usedAt700[0] = 64 - boneTotal(golem, in);
			helper.assertTrue(usedAt700[0] >= 1, "no bone meal tried at all");
			helper.assertTrue(usedAt700[0] <= 33, "bone meal used on an obstructed sapling: " + usedAt700[0]);
		});
		helper.runAfterDelay(1100, () -> {
			int used = 64 - boneTotal(golem, in);
			helper.assertTrue(used == usedAt700[0], "still applying: " + usedAt700[0] + " used at tick 700, " + used + " at 1100");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			helper.succeed();
		});
	}

	// ---- strip logs (superdoc/features/lumber.md "Strip logs") ----

	private static CopperGolem stripper(GameTestHelper helper, boolean strip) {
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		d.stripLogs = strip;
		GolemData.save(golem, d);
		return golem;
	}

	/** Strip on: the 5-log oak ends as 5 stripped oak logs (no plain ones, none lost), 2 durability per log (strip + break). */
	@GameTest(maxTicks = 2000)
	public void stripLogsGivesStrippedLogs(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = stripper(helper, true);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) == 5, "stripped logs in output: " + count(out, Items.STRIPPED_OAK_LOG));
			helper.assertTrue(count(out, Items.OAK_LOG) == 0, "plain logs in output: " + count(out, Items.OAK_LOG));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
			helper.assertTrue(axeDamage(golem) == 10, "axe damage: " + axeDamage(golem));
		});
	}

	/** The strip is a visible step: while felling, some tick shows a stripped log standing in the tree before it is broken. */
	@GameTest(maxTicks = 2000)
	public void stripLogsIsVisibleStep(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = stripper(helper, true);
		int[] seenTicks = { 0 };
		helper.onEachTick(() -> {
			for (int k = 0; k < 5; k++) if (helper.getBlockState(T.above(k)).is(Blocks.STRIPPED_OAK_LOG)) {
				seenTicks[0]++;
				break;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) == 5, "stripped logs in output: " + count(out, Items.STRIPPED_OAK_LOG));
			helper.assertTrue(axeDamage(golem) == 10, "axe damage: " + axeDamage(golem));
			helper.assertTrue(seenTicks[0] >= 5 * LumberLogic.STRIP_TICKS, "stripped log standing for only " + seenTicks[0] + " ticks");
		});
	}

	/** Plain and stripped logs mixed in one trunk (what an interruption between strip and break leaves) are one tree and fully felled. */
	@GameTest(maxTicks = 2000)
	public void fellsMixedPlainAndStrippedTree(GameTestHelper helper) {
		world(helper);
		for (int k = 0; k < 5; k++) helper.setBlock(T.above(k), k % 2 == 0 ? Blocks.OAK_LOG : Blocks.STRIPPED_OAK_LOG);
		helper.setBlock(T.above(5), LEAVES);
		helper.assertTrue(LumberLogic.detectTree(helper.getLevel(), helper.absolutePos(T), List.of()).size() == 5, "mixed trunk is one tree");
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = stripper(helper, true);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) == 5 && count(out, Items.OAK_LOG) == 0, "logs in output: " + count(out, Items.STRIPPED_OAK_LOG) + "/" + count(out, Items.OAK_LOG));
			helper.assertTrue(axeDamage(golem) == 8, "axe damage (3 plain x2 + 2 stripped x1): " + axeDamage(golem));
		});
	}

	/** Strip off (the default, also for a fresh golem): plain logs and 1 durability per log, as before. */
	@GameTest(maxTicks = 2000)
	public void stripLogsOffByDefault(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = lumber(helper);
		helper.assertTrue(!GolemData.get(golem).stripLogs, "default must be off");
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5 && count(out, Items.STRIPPED_OAK_LOG) == 0, "logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(axeDamage(golem) == 5, "axe damage: " + axeDamage(golem));
		});
	}

	/**
	 * A Mending axe 12 above the threshold with strip on: it strips and breaks while it can take both hits, then rests in the
	 * chest (at the threshold) and the rest is cut by hand (plain logs). The axe is never broken or lost, never below 9.
	 */
	@GameTest(maxTicks = 2500)
	public void stripLogsNeverBreaksMendingAxe(GameTestHelper helper) {
		world(helper);
		tree(helper, 5, 2);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 8));
		CopperGolem golem = stripper(helper, true);
		GolemData d = GolemData.get(golem);
		d.items.set(LumberProfession.AXE_SLOT, MendingTest.mending(helper, Items.IRON_AXE, 12));
		GolemData.save(golem, d);
		helper.onEachTick(() -> {
			int axes = 0, low = 0;
			List<ItemStack> all = new java.util.ArrayList<>(List.of(GolemData.get(golem).items.get(LumberProfession.AXE_SLOT)));
			for (int i = 0; i < in.getContainerSize(); i++) all.add(in.getItem(i));
			for (ItemStack a : all) if (a.is(Items.IRON_AXE)) {
				axes++;
				if (com.coppergolemsrevamped.core.GolemMending.remaining(a) < 9) low++;
			}
			helper.assertTrue(axes == 1 && low == 0, "the Mending axe broke or fell too low: " + axes + "/" + low);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) + count(out, Items.OAK_LOG) == 5, "logs: " + count(out, Items.STRIPPED_OAK_LOG) + "/" + count(out, Items.OAK_LOG));
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) >= 1 && count(out, Items.OAK_LOG) >= 1, "expected stripped logs first and plain ones once the axe rests");
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
		});
	}

	/** The vanilla mapping covers logs (axis kept), wood, stems and hyphae; stripped logs, bare hands, strip off, last durability point give nothing. */
	@GameTest
	public void strippedStateFollowsVanilla(GameTestHelper helper) {
		world(helper);
		GolemData d = new GolemData();
		d.stripLogs = true;
		ItemStack axe = new ItemStack(Items.IRON_AXE);
		BlockPos p = T.above(3), abs = helper.absolutePos(p);
		net.minecraft.world.level.block.Block[][] pairs = {
			{ Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG }, { Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_WOOD }, { Blocks.CRIMSON_STEM, Blocks.STRIPPED_CRIMSON_STEM },
			{ Blocks.WARPED_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE }, { Blocks.CHERRY_LOG, Blocks.STRIPPED_CHERRY_LOG } };
		for (net.minecraft.world.level.block.Block[] pr : pairs) {
			helper.setBlock(p, pr[0]);
			BlockState r = LumberLogic.strippedState(helper.getLevel(), abs, d, axe);
			helper.assertTrue(r != null && r.is(pr[1]), pr[0] + " strips to " + r);
		}
		var axis = net.minecraft.world.level.block.RotatedPillarBlock.AXIS;
		helper.setBlock(p, Blocks.OAK_LOG.defaultBlockState().setValue(axis, net.minecraft.core.Direction.Axis.X));
		BlockState x = LumberLogic.strippedState(helper.getLevel(), abs, d, axe);
		helper.assertTrue(x != null && x.getValue(axis) == net.minecraft.core.Direction.Axis.X, "axis kept: " + x);
		helper.assertTrue(x.is(net.minecraft.tags.BlockTags.LOGS), "a stripped log is in #logs");
		helper.setBlock(p, Blocks.STRIPPED_OAK_LOG);
		helper.assertTrue(LumberLogic.strippedState(helper.getLevel(), abs, d, axe) == null, "stripped log has no stripped form");
		helper.assertTrue(LumberLogic.detectTree(helper.getLevel(), abs, List.of()).contains(abs), "tree detection accepts a stripped log");
		helper.setBlock(p, Blocks.OAK_LOG);
		helper.assertTrue(LumberLogic.strippedState(helper.getLevel(), abs, d, ItemStack.EMPTY) == null, "bare hands do not strip");
		d.stripLogs = false;
		helper.assertTrue(LumberLogic.strippedState(helper.getLevel(), abs, d, axe) == null, "strip off does not strip");
		d.stripLogs = true;
		axe.setDamageValue(axe.getMaxDamage() - 1);
		helper.assertTrue(LumberLogic.strippedState(helper.getLevel(), abs, d, axe) == null, "last durability point: break only");
		helper.succeed();
	}

	/** An already stripped trunk (also what an interruption between strip and break leaves) is felled normally, with no strip hit. */
	@GameTest(maxTicks = 1500)
	public void fellsAlreadyStrippedTree(GameTestHelper helper) {
		world(helper);
		for (int k = 0; k < 4; k++) helper.setBlock(T.above(k), Blocks.STRIPPED_OAK_LOG);
		helper.setBlock(T.above(4), LEAVES);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = stripper(helper, true);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.STRIPPED_OAK_LOG) == 4, "stripped logs in output: " + count(out, Items.STRIPPED_OAK_LOG));
			helper.assertTrue(axeDamage(golem) == 4, "axe damage (no strip hit on a stripped log): " + axeDamage(golem));
		});
	}

	/** Persisted across a full entity save and load; data without the field loads as off. */
	@GameTest
	public void stripLogsSurvivesSaveLoad(GameTestHelper helper) {
		net.minecraft.server.level.ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, golem, LumberProfession.ID);
		for (boolean on : new boolean[] { true, false }) {
			GolemData cur = GolemData.get(golem);
			cur.stripLogs = on;
			GolemData.save(golem, cur);
			var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
			golem.saveWithoutId(out);
			CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
			helper.assertTrue(copy != null, "could not create a golem to load into");
			copy.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));
			helper.assertTrue(GolemData.get(copy).stripLogs == on, "stripLogs after load: " + GolemData.get(copy).stripLogs + ", saved " + on);
		}
		helper.assertTrue(GolemData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, new net.minecraft.nbt.CompoundTag()).result().map(g -> !g.stripLogs).orElse(false),
			"data without the field must load as off");
		helper.succeed();
	}

	/** The break rule: only logs of the detected tree within the horizontal reach, any height. */
	@GameTest
	public void breakAllowedRules(GameTestHelper helper) {
		BlockPos a = new BlockPos(10, 64, 10);
		Set<BlockPos> tree = Set.of(a, a.above(20), a.east(4), a.east(8));
		helper.assertTrue(LumberLogic.breakAllowed(a.getX() + 1.5, a.getZ() + 0.5, tree, a), "adjacent log");
		helper.assertTrue(LumberLogic.breakAllowed(a.getX() + 1.5, a.getZ() + 0.5, tree, a.above(20)), "20 blocks up is reachable (the golem reaches up)");
		helper.assertTrue(LumberLogic.breakAllowed(a.getX() + 0.5, a.getZ() + 0.5, tree, a.east(4)), "4 sideways");
		helper.assertTrue(!LumberLogic.breakAllowed(a.getX() + 0.5, a.getZ() + 0.5, tree, a.east(8)), "8 sideways is out of reach");
		helper.assertTrue(!LumberLogic.breakAllowed(a.getX() + 1.5, a.getZ() + 0.5, tree, a.north()), "a log outside the tree");
		helper.succeed();
	}

	/** Flood fill: 26-neighbourhood, same wood kind only, never below T, only from T. */
	@GameTest
	public void detectTreeRules(GameTestHelper helper) {
		world(helper);
		for (int k = 0; k < 3; k++) helper.setBlock(T.above(k), Blocks.OAK_LOG);
		helper.setBlock(new BlockPos(4, 4, 4), Blocks.OAK_LOG); // diagonal chain from the trunk top
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_LOG); // unconnected
		helper.setBlock(new BlockPos(4, 1, 3), Blocks.SPRUCE_LOG); // other kind
		helper.setBlock(new BlockPos(2, 0, 2), Blocks.OAK_LOG); // below T
		Set<BlockPos> tree = LumberLogic.detectTree(helper.getLevel(), helper.absolutePos(T), List.of());
		helper.assertTrue(tree.size() == 4, "tree size " + tree.size());
		helper.assertTrue(tree.contains(helper.absolutePos(new BlockPos(4, 4, 4))), "diagonal log missing");
		helper.assertTrue(LumberLogic.detectTree(helper.getLevel(), helper.absolutePos(new BlockPos(5, 1, 5)), List.of()).isEmpty(), "not a log: no tree");
		helper.succeed();
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

	/**
	 * Chopping is visible and continuous: an iron axe (10 base ticks) still takes two whole swings (24 ticks) per log, and
	 * from the first chop until the last log is gone the golem holds the interaction state with no IDLE pulse (also not
	 * between two logs); when the tree is done it is back to IDLE with no swing left active (no crack, no stuck pose).
	 */
	@GameTest(maxTicks = 1500)
	public void chopsHoldTheInteractionStateAndReturnsToIdle(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 1);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		give(in, Items.IRON_AXE, Items.OAK_SAPLING, 8);
		CopperGolem golem = lumber(helper);
		boolean[] started = { false };
		int[] heldTicks = { 0 };
		long[] lastBreak = { -1 };
		int[] logsLeft = { 3 };
		helper.onEachTick(() -> {
			int left = 0;
			for (int k = 0; k < 3; k++) if (helper.getBlockState(T.above(k)).is(Blocks.OAK_LOG)) left++;
			boolean held = golem.getState() == ToolSwing.HELD && ToolSwing.active(golem);
			if (held) { started[0] = true; heldTicks[0]++; }
			// all logs are in reach from the spot, so no walk: no IDLE until the last log breaks (the break tick itself may be IDLE-free too)
			else if (started[0] && left > 0) helper.fail("state left the held interaction mid-tree: " + golem.getState() + ", logs left " + left);
			if (left < logsLeft[0]) {
				long now = helper.getLevel().getGameTime();
				// the first log needs the walk too; later ones must each take two whole swings
				if (lastBreak[0] >= 0) helper.assertTrue(now - lastBreak[0] >= 2L * ToolSwing.PERIOD, "log broke after " + (now - lastBreak[0]) + " ticks");
				lastBreak[0] = now;
				logsLeft[0] = left;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 3, "oak logs in output: " + count(out, Items.OAK_LOG));
			helper.assertTrue(heldTicks[0] >= 3 * 2 * ToolSwing.PERIOD - 3, "held ticks seen: " + heldTicks[0]);
			helper.assertTrue(!ToolSwing.active(golem), "a swing is still active");
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE, "golem not back to IDLE: " + golem.getState());
		});
	}

	/** {@link ToolSwing}: whole swings with a minimum, and a swing nobody ends is cleaned up (never stuck raised). */
	@GameTest
	public void toolSwingRulesAndCleanup(GameTestHelper helper) {
		helper.assertTrue(ToolSwing.swingTicks(10, 2) == 24 && ToolSwing.swingTicks(8, 2) == 24, "fast axes take 2 swings");
		helper.assertTrue(ToolSwing.swingTicks(30, 2) == 36 && ToolSwing.swingTicks(60, 2) == 60, "slow axes round up to whole swings");
		helper.assertTrue(ToolSwing.swingTicks(1, 1) == ToolSwing.PERIOD, "one swing minimum");
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		golem.setNoAi(true); // a vanilla golem near a neighbour test's chests would set its own interaction state
		ToolSwing.tick(helper.getLevel(), golem, helper.absolutePos(new BlockPos(3, 1, 3)), 0, 24, false);
		helper.assertTrue(golem.getState() == ToolSwing.RAISED && ToolSwing.active(golem), "raised on swing start");
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE && !ToolSwing.active(golem), "stale swing cleaned up: " + golem.getState());
			helper.succeed();
		});
	}

	/** {@link ToolSwing#hold}: never IDLE across whole swings and the chop tick, clearCrack keeps the state, a stale hold is cleaned up. */
	@GameTest(maxTicks = 80)
	public void toolSwingHoldAndCleanup(GameTestHelper helper) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		golem.setNoAi(true); // a vanilla golem near a neighbour test's chests would set its own interaction state
		BlockPos log = helper.absolutePos(new BlockPos(3, 1, 3));
		int total = 2 * ToolSwing.PERIOD;
		int[] e = { 0 };
		helper.onEachTick(() -> {
			if (e[0] >= total) return;
			ToolSwing.hold(helper.getLevel(), golem, log, e[0]++, total, true);
			helper.assertTrue(golem.getState() == ToolSwing.HELD && ToolSwing.active(golem), "hold keeps the state at elapsed " + (e[0] - 1) + ": " + golem.getState());
		});
		helper.runAfterDelay(total + 1, () -> {
			ToolSwing.clearCrack(helper.getLevel(), golem);
			helper.assertTrue(golem.getState() == ToolSwing.HELD && ToolSwing.active(golem), "clearCrack keeps the held state: " + golem.getState());
		});
		helper.runAfterDelay(total + 8, () -> {
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE && !ToolSwing.active(golem), "stale hold cleaned up: " + golem.getState());
			helper.succeed();
		});
	}

	/** Declaration: slot filters, inventory size, GUI visibility and assign keys. */
	@GameTest
	public void lumberDeclaration(GameTestHelper helper) {
		Profession p = Professions.get(LumberProfession.ID);
		helper.assertTrue(p != null && !p.runsVanillaBrain(), "lumber not registered / runs the vanilla brain");
		helper.assertTrue(p.inventorySize() == 12, "inventory size " + p.inventorySize());
		helper.assertTrue(p.slots().stream().allMatch(Profession.SlotGroup::visible), "every lumber group is shown in the Inventory tab");
		Profession.SlotGroup tool = p.slots().get(0), supplies = p.slots().get(1), carry = p.slots().get(2);
		helper.assertTrue(tool.count() == 1 && tool.maxStack() == 1, "tool group");
		helper.assertTrue(tool.filter().test(new ItemStack(Items.IRON_AXE)) && !tool.filter().test(new ItemStack(Items.SHEARS)), "tool takes only axes");
		helper.assertTrue(supplies.count() == 2 && supplies.maxStack() == 64, "supplies group");
		helper.assertTrue(supplies.filter().test(new ItemStack(Items.OAK_SAPLING)) && supplies.filter().test(new ItemStack(Items.BONE_MEAL)), "supplies take saplings and bone meal");
		helper.assertTrue(!supplies.filter().test(new ItemStack(Items.OAK_LOG)) && !supplies.filter().test(new ItemStack(Items.STICK)), "supplies filter");
		helper.assertTrue(carry.count() == 9, "carry group");
		helper.assertTrue(LumberProfession.CARRY_FROM == 3 && LumberProfession.SUPPLY_FROM == 1 && LumberProfession.AXE_SLOT == 0, "slot indices");
		helper.assertTrue(p.assigns().stream().map(Profession.AssignSpec::key).toList().equals(List.of("tree", "input", "output")), "assign keys");
		helper.assertTrue(p.assigns().getFirst().kind() == Profession.Kind.BLOCK, "tree kind");
		helper.succeed();
	}

	/**
	 * Keep layout: the input chest holds saplings in slots of 3 and 1, the output chest one log in slot 0. The golem fetches
	 * only the 2 spare saplings (both slots keep 1), and the logs go onto the existing log slot only (empty slots stay empty).
	 */
	@GameTest(maxTicks = 2500)
	public void keepLayoutFetchesAndDeliversAroundTheLayout(GameTestHelper helper) {
		world(helper);
		tree(helper, 3, 0);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.IRON_AXE));
		in.setItem(1, new ItemStack(Items.OAK_SAPLING, 3));
		in.setItem(2, new ItemStack(Items.OAK_SAPLING, 1));
		out.setItem(0, new ItemStack(Items.OAK_LOG, 1));
		CopperGolem golem = lumber(helper);
		GolemData d = GolemData.get(golem);
		d.keepLayout = true;
		GolemData.save(golem, d);

		helper.succeedWhen(() -> {
			helper.assertTrue(out.getItem(0).is(Items.OAK_LOG) && out.getItem(0).getCount() == 4, "log slot: " + out.getItem(0));
			for (int i = 1; i < out.getContainerSize(); i++) helper.assertTrue(out.getItem(i).isEmpty(), "output slot " + i + " filled: " + out.getItem(i));
			helper.assertTrue(in.getItem(1).is(Items.OAK_SAPLING) && in.getItem(1).getCount() >= 1, "input slot 1: " + in.getItem(1));
			helper.assertTrue(in.getItem(2).is(Items.OAK_SAPLING) && in.getItem(2).getCount() >= 1, "input slot 2: " + in.getItem(2));
			for (int i = 3; i < in.getContainerSize(); i++) helper.assertTrue(in.getItem(i).isEmpty(), "input slot " + i + " filled: " + in.getItem(i));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, T);
		});
	}
}
