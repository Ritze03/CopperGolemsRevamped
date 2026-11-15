package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.menu.Tab;
import com.coppergolemsrevamped.profession.smelter.SmelterLogic;
import com.coppergolemsrevamped.profession.smelter.SmelterProfession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Smelter: even split, the 8-furnace cap, furnace types, and the rules that keep it from ever blocking. */
public class SmelterTest {
	private static final BlockPos IN_POS = new BlockPos(1, 1, 1);
	private static final BlockPos FUEL_POS = new BlockPos(3, 1, 1);
	private static final BlockPos OUT_POS = new BlockPos(6, 1, 1);

	private static int count(ChestBlockEntity chest, Item item) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) n += chest.getItem(i).getCount();
		return n;
	}

	private static int total(ChestBlockEntity chest) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
		return n;
	}

	private static int held(CopperGolem g, Item item) {
		int n = 0;
		for (ItemStack s : GolemData.get(g).items) if (s.is(item)) n += s.getCount();
		return n;
	}

	private static int heldTotal(CopperGolem g) {
		int n = 0;
		for (ItemStack s : GolemData.get(g).items) n += s.getCount();
		return n;
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** The three chests, a Smelter golem and the area (0,1,5)..(7,y1,6); furnaces are placed by the test. */
	private static CopperGolem base(GameTestHelper helper, int areaTopY, ChestBlockEntity[] chests) {
		floor(helper);
		helper.setBlock(IN_POS, Blocks.CHEST);
		helper.setBlock(FUEL_POS, Blocks.CHEST);
		helper.setBlock(OUT_POS, Blocks.CHEST);
		chests[0] = helper.getBlockEntity(IN_POS, ChestBlockEntity.class);
		chests[1] = helper.getBlockEntity(FUEL_POS, ChestBlockEntity.class);
		chests[2] = helper.getBlockEntity(OUT_POS, ChestBlockEntity.class);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, SmelterProfession.ID);
		GolemData d = GolemData.get(golem);
		d.assignments.put("input", helper.absolutePos(IN_POS));
		d.assignments.put("fuel", helper.absolutePos(FUEL_POS));
		d.assignments.put("output", helper.absolutePos(OUT_POS));
		d.assignments.put("furnaces_a", helper.absolutePos(new BlockPos(0, 1, 5)));
		d.assignments.put("furnaces_b", helper.absolutePos(new BlockPos(7, areaTopY, 6)));
		GolemData.save(golem, d);
		return golem;
	}

	private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos pos, Block type) {
		helper.setBlock(pos, type);
		return helper.getBlockEntity(pos, AbstractFurnaceBlockEntity.class);
	}

	private static void addFuel(ChestBlockEntity fuel, int coal) {
		fuel.setItem(0, new ItemStack(Items.COAL, coal));
	}

	private static double orbValue(GameTestHelper helper) {
		AABB box = new AABB(helper.absoluteVec(new Vec3(-1, 0, -1)), helper.absoluteVec(new Vec3(9, 8, 9)));
		double v = 0;
		for (ExperienceOrb o : helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, box)) v += o.getValue();
		return v;
	}

	/** 3 furnaces, 24 ore: 8 each, 24 ingots arrive, XP popped and the stored-XP map cleared. */
	@GameTest(maxTicks = 3800)
	public void evenSplitAcrossThreeFurnaces(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		BlockPos[] fp = {new BlockPos(1, 1, 5), new BlockPos(3, 1, 5), new BlockPos(5, 1, 5)};
		AbstractFurnaceBlockEntity[] f = new AbstractFurnaceBlockEntity[3];
		for (int i = 0; i < 3; i++) f[i] = furnace(helper, fp[i], Blocks.FURNACE);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 24));
		addFuel(c[1], 64);
		int[] max = new int[3];
		helper.onEachTick(() -> {
			for (int i = 0; i < 3; i++) max[i] = Math.max(max[i], f[i].getItem(0).getCount());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 24, "ingots in the output chest: " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(max[0] == 8 && max[1] == 8 && max[2] == 8, "each furnace gets 8, saw " + max[0] + "," + max[1] + "," + max[2]);
			helper.assertTrue(orbValue(helper) >= 10, "XP popped: " + orbValue(helper));
			helper.assertTrue(heldTotal(golem) == 0 && total(c[0]) == 0, "nothing left over");
			for (int i = 0; i < 3; i++) {
				helper.assertTrue(((com.coppergolemsrevamped.mixin.AbstractFurnaceAccessor) f[i]).cgr$recipesUsed().isEmpty(), "stored XP map cleared " + i);
			}
		});
	}

	/** 10 furnaces in the area: only the 8 nearest to the input chest are ever fed. */
	@GameTest(maxTicks = 900)
	public void atMostEightFurnacesAreUsed(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		base(helper, 1, c);
		List<AbstractFurnaceBlockEntity> all = new ArrayList<>();
		List<BlockPos> pos = new ArrayList<>();
		for (int z = 5; z <= 6; z++) {
			for (int x = 1; x <= 5; x++) {
				BlockPos p = new BlockPos(x, 1, z);
				pos.add(p);
				all.add(furnace(helper, p, Blocks.FURNACE));
			}
		}
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 64));
		c[0].setItem(1, new ItemStack(Items.IRON_ORE, 16));
		addFuel(c[1], 64);
		Set<Integer> used = new HashSet<>();
		helper.onEachTick(() -> {
			for (int i = 0; i < all.size(); i++) if (!all.get(i).getItem(0).isEmpty()) used.add(i);
		});
		helper.runAfterDelay(700, () -> {
			helper.assertTrue(used.size() == 8, "furnaces fed: " + used.size());
			int far1 = pos.indexOf(new BlockPos(5, 1, 6)), far2 = pos.indexOf(new BlockPos(4, 1, 6));
			helper.assertTrue(!used.contains(far1) && !used.contains(far2), "the two farthest furnaces stay unused: " + used);
			helper.succeed();
		});
	}

	/** Furnace + smoker, beef then ore: beef goes to both, ore never into the smoker, everything is cooked. */
	@GameTest(maxTicks = 2600)
	public void typeSplitSmokerAndFurnace(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		AbstractFurnaceBlockEntity fur = furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		AbstractFurnaceBlockEntity smo = furnace(helper, new BlockPos(3, 1, 5), Blocks.SMOKER);
		c[0].setItem(0, new ItemStack(Items.BEEF, 4));
		c[0].setItem(1, new ItemStack(Items.IRON_ORE, 2));
		addFuel(c[1], 64);
		boolean[] seen = new boolean[4]; // furnace beef, furnace ore, smoker beef, smoker ore
		helper.onEachTick(() -> {
			seen[0] |= fur.getItem(0).is(Items.BEEF);
			seen[1] |= fur.getItem(0).is(Items.IRON_ORE);
			seen[2] |= smo.getItem(0).is(Items.BEEF);
			seen[3] |= smo.getItem(0).is(Items.IRON_ORE);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.COOKED_BEEF) == 4 && count(c[2], Items.IRON_INGOT) == 2,
				"output: " + count(c[2], Items.COOKED_BEEF) + " beef, " + count(c[2], Items.IRON_INGOT) + " iron");
			helper.assertTrue(seen[0] && seen[2], "beef went into both furnaces");
			helper.assertTrue(seen[1], "the ore went into the furnace");
			helper.assertTrue(!seen[3], "the ore must never enter the smoker");
			helper.assertTrue(heldTotal(golem) == 0, "golem still holds " + heldTotal(golem));
		});
	}

	/** Unsmeltable items are skipped: never pulled, never held, still in the input chest at the end. */
	@GameTest(maxTicks = 1800)
	public void unsmeltableItemsStayInTheInputChest(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		c[0].setItem(0, new ItemStack(Items.DIRT, 10));
		c[0].setItem(1, new ItemStack(Items.IRON_ORE, 2));
		addFuel(c[1], 16);
		helper.onEachTick(() -> helper.assertTrue(held(golem, Items.DIRT) == 0, "the golem pulled dirt"));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 2, "ingots: " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(count(c[0], Items.DIRT) == 10, "dirt in the input chest: " + count(c[0], Items.DIRT));
		});
	}

	/** A furnace whose input holds a player's foreign item: its output is collected, its input is neither touched nor fed. */
	@GameTest(maxTicks = 2200)
	public void foreignInputIsNeverFought(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		AbstractFurnaceBlockEntity a = furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		furnace(helper, new BlockPos(3, 1, 5), Blocks.FURNACE);
		a.setItem(0, new ItemStack(Items.DIRT, 5));
		a.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 4));
		addFuel(c[1], 16);
		helper.onEachTick(() -> helper.assertTrue(a.getItem(0).is(Items.DIRT) && a.getItem(0).getCount() == 5, "the foreign input was touched: " + a.getItem(0)));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 7, "ingots (3 collected + 4 smelted): " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(heldTotal(golem) == 0, "golem holds " + heldTotal(golem));
		});
	}

	/** Output chest full: results stay in the buffer, the other furnace keeps being fed, delivery resumes when room appears. */
	@GameTest(maxTicks = 4200)
	public void fullOutputChestKeepsResultsAndKeepsFeeding(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		AbstractFurnaceBlockEntity b = furnace(helper, new BlockPos(3, 1, 5), Blocks.FURNACE);
		for (int i = 0; i < c[2].getContainerSize(); i++) c[2].setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		GolemData d = GolemData.get(golem);
		d.items.set(0, new ItemStack(Items.IRON_INGOT, 5)); // slot 0 is busy with undeliverable results
		GolemData.save(golem, d);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 6));
		addFuel(c[1], 16);
		int cobble = total(c[2]);
		helper.runAfterDelay(2300, () -> {
			helper.assertTrue(total(c[2]) == cobble && count(c[2], Items.IRON_INGOT) == 0, "the full chest changed");
			int ingots = held(golem, Items.IRON_INGOT) + b.getItem(2).getCount();
			helper.assertTrue(ingots == 11, "all 6 ore were smelted by the free furnace, ingots held: " + ingots);
			helper.assertTrue(total(c[0]) == 0, "input chest drained");
			c[2].setItem(0, ItemStack.EMPTY);
			c[2].setItem(1, ItemStack.EMPTY);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 11, "delivered after room appeared: " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(heldTotal(golem) == 0, "golem holds " + heldTotal(golem));
		});
	}

	/** No fuel anywhere: the golem still collects results and tops the inputs up; nothing is lost. */
	@GameTest(maxTicks = 1200)
	public void noFuelStillCollectsAndFeeds(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		AbstractFurnaceBlockEntity a = furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		a.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 4));
		helper.runAfterDelay(800, () -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 5, "collected results: " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(a.getItem(0).is(Items.IRON_ORE) && a.getItem(0).getCount() == 4, "input topped up: " + a.getItem(0));
			helper.assertTrue(heldTotal(golem) == 0 && total(c[0]) == 0, "nothing stranded");
			helper.assertTrue(SmelterLogic.status(golem).equals("Out of fuel"), "status: " + SmelterLogic.status(golem));
			helper.succeed();
		});
	}

	/** No furnaces in the area, then no targets at all: it idles with a status and touches nothing. */
	@GameTest(maxTicks = 500)
	public void noFurnacesAndMissingTargetsIdle(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 4));
		addFuel(c[1], 4);
		helper.runAfterDelay(250, () -> {
			helper.assertTrue(total(c[0]) == 4 && total(c[1]) == 4 && heldTotal(golem) == 0, "nothing may move");
			helper.assertTrue(SmelterLogic.status(golem).equals("No furnaces in the area"), "status: " + SmelterLogic.status(golem));
			GolemData d = GolemData.get(golem);
			d.assignments.clear();
			GolemData.save(golem, d);
			furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE).setItem(0, new ItemStack(Items.IRON_ORE, 2));
		});
		helper.runAfterDelay(450, () -> {
			helper.assertTrue(total(c[0]) == 4 && heldTotal(golem) == 0, "unassigned: nothing may move");
			helper.assertTrue(SmelterLogic.status(golem).equals("No furnace area set"), "status: " + SmelterLogic.status(golem));
			helper.succeed();
		});
	}

	/** Lava bucket as fuel: the empty bucket ends up in the output chest next to the result. */
	@GameTest(maxTicks = 1500)
	public void lavaBucketLeavesAnEmptyBucketInTheOutput(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 1));
		c[1].setItem(0, new ItemStack(Items.LAVA_BUCKET));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.BUCKET) == 1 && count(c[2], Items.IRON_INGOT) == 1,
				"output: " + count(c[2], Items.BUCKET) + " buckets, " + count(c[2], Items.IRON_INGOT) + " ingots");
			helper.assertTrue(heldTotal(golem) == 0, "golem holds " + heldTotal(golem));
		});
	}

	/** A furnace nobody can reach is given up on; the others finish all the ore. */
	@GameTest(maxTicks = 4500)
	public void unreachableFurnaceIsGivenUp(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 6, c);
		furnace(helper, new BlockPos(3, 1, 5), Blocks.FURNACE);
		furnace(helper, new BlockPos(5, 1, 5), Blocks.FURNACE);
		AbstractFurnaceBlockEntity high = furnace(helper, new BlockPos(1, 5, 5), Blocks.FURNACE);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 6));
		addFuel(c[1], 64);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[2], Items.IRON_INGOT) == 6, "ingots: " + count(c[2], Items.IRON_INGOT));
			helper.assertTrue(high.getItem(0).isEmpty() && high.getItem(2).isEmpty(), "the unreachable furnace was never served");
			helper.assertTrue(heldTotal(golem) == 0, "golem holds " + heldTotal(golem));
		});
	}

	/** Freeze: a frozen Smelter touches nothing and stays put. */
	@GameTest(maxTicks = 400)
	public void frozenSmelterDoesNothing(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		AbstractFurnaceBlockEntity f = furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE);
		c[0].setItem(0, new ItemStack(Items.IRON_ORE, 8));
		addFuel(c[1], 8);
		GolemData d = GolemData.get(golem);
		d.frozen = true;
		GolemData.save(golem, d);
		Vec3 start = golem.position();
		helper.runAfterDelay(250, () -> {
			helper.assertTrue(total(c[0]) == 8 && total(c[1]) == 8 && total(c[2]) == 0 && f.getItem(0).isEmpty(), "a chest or furnace changed");
			helper.assertTrue(heldTotal(golem) == 0, "golem holds " + heldTotal(golem));
			helper.assertTrue(golem.position().distanceTo(start) <= 0.5, "the golem moved");
			helper.succeed();
		});
	}

	/** Inventory tab: label plus one row of 8 buffer slots at row 3, columns 0..7. */
	@GameTest
	@SuppressWarnings("removal")
	public void inventoryTabIsOneRowOfEight(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = base(helper, 1, c);
		ServerPlayer p = helper.makeMockServerPlayerInLevel();
		p.setPos(golem.position());
		GolemMenu menu = new GolemMenu(1, p.getInventory(), golem, Tab.INVENTORY);
		for (int col = 0; col < 8; col++) {
			helper.assertTrue(menu.slots.get(27 + col).mayPlace(new ItemStack(Items.STONE)), "buffer slot at column " + col);
		}
		helper.assertTrue(!menu.slots.get(27 + 8).mayPlace(new ItemStack(Items.STONE)), "no slot at column 8");
		ItemStack label = menu.slots.get(2 * 9 + 3).getItem();
		helper.assertTrue(label.is(Items.FURNACE) && label.getHoverName().getString().equals("Furnace buffers"), "label " + label);
		helper.assertTrue(GolemData.get(golem).items.size() == 8, "8 slots");
		helper.succeed();
	}
}
