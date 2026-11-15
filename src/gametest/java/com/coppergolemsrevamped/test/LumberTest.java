package com.coppergolemsrevamped.test;

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
	 * no phase is missed) go PICKUP, DELIVER (fresh start), PLANT, BONEMEAL, FELL, PICKUP, DELIVER, PLANT; every step follows
	 * the cycle (PICKUP always into DELIVER, DELIVER always into PLANT, FELL always into PICKUP). Foreign saplings (birch)
	 * dropped after the felling start end up in the input chest (surplus), the logs in the output chest.
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
				helper.spawnItem(Items.BIRCH_SAPLING, new Vec3(4.5, 1.0, 5.5));
				helper.spawnItem(Items.BIRCH_SAPLING, new Vec3(2.5, 1.0, 5.5));
			}
		});
		List<LumberLogic.Phase> expect = List.of(LumberLogic.Phase.PICKUP, LumberLogic.Phase.DELIVER, LumberLogic.Phase.PLANT, LumberLogic.Phase.BONEMEAL,
			LumberLogic.Phase.FELL, LumberLogic.Phase.PICKUP, LumberLogic.Phase.DELIVER, LumberLogic.Phase.PLANT);
		helper.succeedWhen(() -> {
			helper.assertTrue(seq.size() >= expect.size(), "phases so far: " + seq);
			helper.assertTrue(seq.subList(0, expect.size()).equals(expect), "phase order: " + seq);
			for (int i = 0; i + 1 < seq.size(); i++) {
				LumberLogic.Phase a = seq.get(i), b = seq.get(i + 1);
				boolean ok = switch (a) {
					case PICKUP -> b == LumberLogic.Phase.DELIVER;
					case DELIVER -> b == LumberLogic.Phase.PLANT;
					case FELL -> b == LumberLogic.Phase.PICKUP;
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

	/** A golem that starts inside the sapling steps out and waits at least 2 blocks from it (no bone meal: natural growth). */
	@GameTest(maxTicks = 600)
	public void stepsOutOfTheSapling(GameTestHelper helper) {
		world(helper);
		helper.setBlock(T, Blocks.OAK_SAPLING);
		CopperGolem golem = lumber(helper, T);
		BlockPos at = helper.absolutePos(T);
		helper.runAfterDelay(300, () -> {
			int d = Math.max(Math.abs(golem.getBlockX() - at.getX()), Math.abs(golem.getBlockZ() - at.getZ()));
			helper.assertTrue(d >= 2, "golem waits " + d + " blocks from the sapling at " + golem.blockPosition());
			helper.succeed();
		});
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

	/** Declaration: slot filters, inventory size, GUI rows (at most 6 with the assign row) and assign keys. */
	@GameTest
	public void lumberDeclaration(GameTestHelper helper) {
		Profession p = Professions.get(LumberProfession.ID);
		helper.assertTrue(p != null && !p.runsVanillaBrain(), "lumber not registered / runs the vanilla brain");
		helper.assertTrue(p.inventorySize() == 12, "inventory size " + p.inventorySize());
		helper.assertTrue(p.slotRows() <= 6, "rows " + p.slotRows());
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
}
