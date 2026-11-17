package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemMending;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.butcher.ButcherProfession;
import com.coppergolemsrevamped.profession.farmer.FarmerProfession;
import com.coppergolemsrevamped.profession.lumber.LumberProfession;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Mending for the tool professions (superdoc/features/mending.md): XP bottles in the tool chest repair the tools (7 XP
 * each, vanilla conversion), and a Mending tool at or below {@link GolemMending#SAFE_DURABILITY} is never used
 * (it goes back into the chest, the golem works bare-handed or with another tool).
 */
public class MendingTest {
	private static final int SAFE = GolemMending.SAFE_DURABILITY;

	// Lumber layout (as LumberTest)
	private static final BlockPos T = new BlockPos(3, 1, 3);
	private static final BlockPos IN = new BlockPos(0, 1, 1);
	private static final BlockPos OUT = new BlockPos(0, 1, 5);

	/** A Mending tool with {@code remaining} durability points left. */
	static ItemStack mending(GameTestHelper helper, Item item, int remaining) {
		ItemStack s = new ItemStack(item);
		Holder<Enchantment> m = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
		s.enchant(m, 1);
		s.setDamageValue(s.getMaxDamage() - remaining);
		return s;
	}

	/** Every Mending tool in the golem's slots and in the given containers (D9: exactly the ones put in, never destroyed). */
	private static List<ItemStack> mendingTools(CopperGolem golem, Container... chests) {
		List<ItemStack> l = new ArrayList<>();
		for (ItemStack s : GolemData.get(golem).items) if (GolemMending.hasMending(s)) l.add(s);
		for (Container c : chests) for (int i = 0; i < c.getContainerSize(); i++) if (GolemMending.hasMending(c.getItem(i))) l.add(c.getItem(i));
		return l;
	}

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
		return helper.getBlockEntity(pos, ChestBlockEntity.class);
	}

	// ---- the helper ------------------------------------------------------------------------------------------------

	/** The guard and the conversion: threshold, the last-line damage refusal, 7 XP -> 14 durability (vanilla 2 per XP). */
	@GameTest
	public void guardAndConversion(GameTestHelper helper) {
		ItemStack above = mending(helper, Items.IRON_AXE, SAFE + 1), at = mending(helper, Items.IRON_AXE, SAFE);
		helper.assertTrue(GolemMending.canUse(above, true) && !GolemMending.canUse(at, true) && GolemMending.canUse(at, false), "threshold: " + GolemMending.canUse(above, true) + " " + GolemMending.canUse(at, true));
		ItemStack plain = new ItemStack(Items.IRON_AXE);
		plain.setDamageValue(plain.getMaxDamage() - 1);
		helper.assertTrue(GolemMending.canUse(plain, true), "a plain tool is always usable");
		ItemStack last = mending(helper, Items.IRON_AXE, 1);
		helper.assertTrue(!GolemMending.damage(helper.getLevel(), last, true, 1) && !last.isEmpty() && GolemMending.remaining(last) == 1,
			"damage() must refuse the hit that would break a Mending tool");
		int per = GolemMending.perBottle(helper.getLevel(), at);
		helper.assertTrue(per == 14, "durability per 7 XP bottle: " + per);
		helper.succeed();
	}

	// ---- Lumber ----------------------------------------------------------------------------------------------------

	private static void lumberWorld(GameTestHelper helper, int logs) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(T.below(), Blocks.DIRT);
		helper.setBlock(IN, Blocks.CHEST);
		helper.setBlock(OUT, Blocks.CHEST);
		for (int k = 0; k < logs; k++) helper.setBlock(T.above(k), Blocks.OAK_LOG);
		chest(helper, IN).setItem(10, new ItemStack(Items.OAK_SAPLING, 4));
	}

	private static CopperGolem lumber(GameTestHelper helper, ItemStack axe) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(5, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, LumberProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put(LumberProfession.TREE, helper.absolutePos(T));
		data.assignments.put(LumberProfession.INPUT, helper.absolutePos(IN));
		data.assignments.put(LumberProfession.OUTPUT, helper.absolutePos(OUT));
		data.items.set(LumberProfession.AXE_SLOT, axe);
		GolemData.save(golem, data);
		return golem;
	}

	/** Damaged Mending axe, 5 bottles in the input chest: repaired by exactly what is needed (2 bottles for 20 points), the rest stays. */
	@GameTest(maxTicks = 2500)
	public void lumberRepairsWithBottles(GameTestHelper helper) {
		lumberWorld(helper, 3);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 5));
		ItemStack axe = mending(helper, Items.IRON_AXE, 250 - 20);
		int per = GolemMending.perBottle(helper.getLevel(), axe);
		int need = (20 + per - 1) / per;
		CopperGolem golem = lumber(helper, axe);
		helper.onEachTick(() -> helper.assertTrue(mendingTools(golem, in, out).size() == 1, "the Mending axe was lost or duplicated"));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 3, "logs: " + count(out, Items.OAK_LOG));
			ItemStack a = GolemData.get(golem).items.get(LumberProfession.AXE_SLOT);
			helper.assertTrue(a.is(Items.IRON_AXE) && a.getDamageValue() == 3, "axe repaired to 0, then 3 chops: " + a);
			helper.assertTrue(count(in, Items.EXPERIENCE_BOTTLE) == 5 - need, "bottles left: " + count(in, Items.EXPERIENCE_BOTTLE) + ", need " + need);
			helper.assertTrue(count(out, Items.EXPERIENCE_BOTTLE) == 0, "bottles in the output chest");
		});
	}

	/** Mending axe one point above the threshold, no other axe: one more log, then it rests in the chest, the rest is cut by hand. */
	@GameTest(maxTicks = 4000)
	public void lumberNeverBreaksMendingAxeAndChopsByHand(GameTestHelper helper) {
		lumberWorld(helper, 5);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		CopperGolem golem = lumber(helper, mending(helper, Items.IRON_AXE, SAFE + 1));
		helper.onEachTick(() -> {
			List<ItemStack> l = mendingTools(golem, in, out);
			helper.assertTrue(l.size() == 1 && GolemMending.remaining(l.get(0)) >= SAFE, "the Mending axe broke or was used below the threshold: " + l);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5, "logs: " + count(out, Items.OAK_LOG));
			helper.assertTrue(count(in, Items.IRON_AXE) == 1 && GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).isEmpty(), "the axe is not back in the chest");
			helper.assertTrue(GolemMending.remaining(in.getItem(firstAxe(in))) == SAFE, "axe remaining: " + in.getItem(firstAxe(in)));
		});
	}

	/** The "Save tools" rule covers every enchanted tool, not only Mending: an Efficiency axe rests at the threshold. */
	@GameTest(maxTicks = 4000)
	public void saveToolsParksAnyEnchantedAxe(GameTestHelper helper) {
		lumberWorld(helper, 5);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		ItemStack axe = new ItemStack(Items.IRON_AXE);
		axe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 1);
		axe.setDamageValue(axe.getMaxDamage() - (SAFE + 1));
		CopperGolem golem = lumber(helper, axe);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5, "logs: " + count(out, Items.OAK_LOG));
			helper.assertTrue(count(in, Items.IRON_AXE) == 1 && GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).isEmpty(), "the axe is not back in the chest");
			helper.assertTrue(GolemMending.remaining(in.getItem(firstAxe(in))) == SAFE, "axe remaining: " + in.getItem(firstAxe(in)));
		});
	}

	/** Plain axe breaks as before, and with "Save tools" off a Mending axe breaks too. */
	@GameTest(maxTicks = 4000)
	public void plainAndUnsavedToolsBreak(GameTestHelper helper) {
		lumberWorld(helper, 10); // two golems share the tree, each axe has 3 points
		ChestBlockEntity in = chest(helper, IN);
		ItemStack plain = new ItemStack(Items.IRON_AXE);
		plain.setDamageValue(plain.getMaxDamage() - 3);
		CopperGolem a = lumber(helper, plain);
		CopperGolem b = lumber(helper, mending(helper, Items.IRON_AXE, 3));
		GolemData bd = GolemData.get(b);
		bd.saveTools = false;
		GolemData.save(b, bd);
		helper.succeedWhen(() -> {
			helper.assertTrue(GolemData.get(a).items.get(LumberProfession.AXE_SLOT).isEmpty(), "plain axe should have broken");
			helper.assertTrue(GolemData.get(b).items.get(LumberProfession.AXE_SLOT).isEmpty(), "Mending axe should have broken with Save tools off");
			helper.assertTrue(count(in, Items.IRON_AXE) == 0, "no axe is parked");
		});
	}

	private static int firstAxe(Container c) {
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.IRON_AXE)) return i;
		return 0;
	}

	/** Same, but the chest holds a plain axe: the golem swaps to it, the Mending axe stays in the chest, nothing is lost. */
	@GameTest(maxTicks = 3000)
	public void lumberSwitchesToPlainAxe(GameTestHelper helper) {
		lumberWorld(helper, 5);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		in.setItem(0, new ItemStack(Items.STONE_AXE));
		CopperGolem golem = lumber(helper, mending(helper, Items.IRON_AXE, SAFE + 1));
		helper.onEachTick(() -> {
			List<ItemStack> l = mendingTools(golem, in, out);
			helper.assertTrue(l.size() == 1 && GolemMending.remaining(l.get(0)) >= SAFE, "the Mending axe broke or was used below the threshold: " + l);
			int axes = count(in, Items.STONE_AXE) + count(in, Items.IRON_AXE) + (GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).isEmpty() ? 0 : 1);
			helper.assertTrue(axes == 2, "axes in total: " + axes);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 5, "logs: " + count(out, Items.OAK_LOG));
			helper.assertTrue(GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).is(Items.STONE_AXE), "golem axe is not the plain axe");
			helper.assertTrue(count(in, Items.IRON_AXE) == 1, "the Mending axe is not in the chest");
		});
	}

	/**
	 * A worn Mending axe lies in the input chest (5 left), the golem has none and chops by hand. Bottles arrive later: it
	 * repairs the axe in the chest (more bottles than one transfer window allows), takes it and finishes with it.
	 */
	@GameTest(maxTicks = 5000)
	public void lumberRepairsStoredMendingAxeAndReusesIt(GameTestHelper helper) {
		lumberWorld(helper, 10);
		ChestBlockEntity in = chest(helper, IN), out = chest(helper, OUT);
		ItemStack worn = mending(helper, Items.IRON_AXE, 5);
		in.setItem(0, worn);
		int per = GolemMending.perBottle(helper.getLevel(), worn);
		int need = (245 + per - 1) / per;
		CopperGolem golem = lumber(helper, ItemStack.EMPTY);
		helper.runAfterDelay(150, () -> {
			helper.assertTrue(count(out, Items.OAK_LOG) < 10 && GolemData.get(golem).items.get(LumberProfession.AXE_SLOT).isEmpty(), "not chopping by hand before the bottles");
			in.setItem(1, new ItemStack(Items.EXPERIENCE_BOTTLE, need + 2));
		});
		helper.onEachTick(() -> helper.assertTrue(mendingTools(golem, in, out).size() == 1, "the Mending axe was lost or duplicated"));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.OAK_LOG) == 10, "logs: " + count(out, Items.OAK_LOG));
			ItemStack a = GolemData.get(golem).items.get(LumberProfession.AXE_SLOT);
			helper.assertTrue(a.is(Items.IRON_AXE) && a.getDamageValue() < 10, "the repaired axe is not in use: " + a);
			helper.assertTrue(count(in, Items.EXPERIENCE_BOTTLE) == 2, "bottles left: " + count(in, Items.EXPERIENCE_BOTTLE) + ", need " + need);
		});
	}

	// ---- Butcher ---------------------------------------------------------------------------------------------------

	private static ButcherTest.Pen herd(GameTestHelper helper, int cows, ItemStack sword) {
		ButcherTest.Pen p = ButcherTest.pen(helper, false);
		for (int i = 0; i < cows; i++) ButcherTest.animal(helper, p, EntityTypes.COW, false);
		ButcherTest.setSlot(p, ButcherProfession.TOOL_SLOT, sword);
		return p;
	}

	private static long adultCows(GameTestHelper helper, ButcherTest.Pen p) {
		return ButcherTest.animals(helper, p, Cow.class).stream().filter(a -> !a.isBaby()).count();
	}

	/** Mending sword one point above the threshold: it strikes once, rests in the tool chest, the surplus cow is killed bare-handed. */
	@GameTest(maxTicks = 4000)
	public void butcherNeverBreaksMendingSword(GameTestHelper helper) {
		ButcherTest.Pen p = herd(helper, 9, mending(helper, Items.IRON_SWORD, SAFE + 1));
		helper.onEachTick(() -> {
			List<ItemStack> l = mendingTools(p.golem, p.tools, p.out);
			helper.assertTrue(l.size() == 1 && GolemMending.remaining(l.get(0)) >= SAFE, "the Mending sword broke or was used below the threshold: " + l);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(adultCows(helper, p) == 8, "adult cows: " + adultCows(helper, p));
			helper.assertTrue(ButcherTest.count(p.tools, Items.IRON_SWORD) == 1 && GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT).isEmpty(),
				"the sword is not back in the tool chest");
		});
	}

	/** Damaged Mending sword, bottles in the tool chest: repaired before the cull, bottles consumed as needed. */
	@GameTest(maxTicks = 3000)
	public void butcherRepairsWithBottles(GameTestHelper helper) {
		ItemStack sword = mending(helper, Items.IRON_SWORD, 250 - 100);
		int per = GolemMending.perBottle(helper.getLevel(), sword);
		int need = (100 + per - 1) / per;
		ButcherTest.Pen p = herd(helper, 9, sword);
		p.tools.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 20));
		helper.onEachTick(() -> helper.assertTrue(mendingTools(p.golem, p.tools, p.out).size() == 1, "the Mending sword was lost or duplicated"));
		helper.succeedWhen(() -> {
			helper.assertTrue(adultCows(helper, p) == 8, "adult cows: " + adultCows(helper, p));
			helper.assertTrue(ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE) == 20 - need, "bottles left: " + ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE) + ", need " + need);
			ItemStack s = GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT);
			helper.assertTrue(s.is(Items.IRON_SWORD) && s.getDamageValue() < 5, "sword not repaired: " + s);
		});
	}

	/** Idle Butcher (no animals: nothing to breed, kill or deliver), sword missing 5 points: topped up from the tool chest, 1 bottle. */
	@GameTest(maxTicks = 1500)
	public void butcherTopsUpSwordWhileIdle(GameTestHelper helper) {
		ButcherTest.Pen p = herd(helper, 0, mending(helper, Items.IRON_SWORD, 250 - 5));
		p.tools.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 5));
		helper.succeedWhen(() -> {
			ItemStack s = GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT);
			helper.assertTrue(s.is(Items.IRON_SWORD) && s.getDamageValue() == 0, "sword damage: " + s.getDamageValue());
			helper.assertTrue(ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE) == 4, "bottles left: " + ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE));
		});
	}

	/**
	 * No XP is wasted: a sword missing 5 points costs 3 XP (ceil of 5 / 2), the other 4 XP of the bottle stay in the golem's
	 * store. A second repair of 4 missing points is paid from the store alone: no bottle. XP from bottles = spent + stored.
	 */
	@GameTest(maxTicks = 1500)
	public void mendingWastesNoXp(GameTestHelper helper) {
		ButcherTest.Pen p = herd(helper, 0, mending(helper, Items.IRON_SWORD, 250 - 5));
		p.tools.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 2));
		int[] phase = {0};
		helper.succeedWhen(() -> {
			GolemData d = GolemData.get(p.golem);
			ItemStack s = d.items.get(ButcherProfession.TOOL_SLOT);
			if (phase[0] == 0) {
				helper.assertTrue(s.is(Items.IRON_SWORD) && s.getDamageValue() == 0, "sword damage: " + s.getDamageValue());
				helper.assertTrue(ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE) == 1, "bottles left: " + ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE));
				helper.assertTrue(d.xp == 4, "stored XP after 5 points: " + d.xp + " (7 - 3)");
				s.setDamageValue(4);
				GolemData.save(p.golem, d);
				phase[0] = 1;
			}
			helper.assertTrue(s.getDamageValue() == 0, "second repair pending, damage " + s.getDamageValue());
			helper.assertTrue(ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE) == 1, "the second repair took a bottle: " + ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE));
			int bottleXp = (2 - ButcherTest.count(p.tools, Items.EXPERIENCE_BOTTLE)) * 7, spent = 3 + 2;
			helper.assertTrue(d.xp == 2 && bottleXp == spent + d.xp, "XP stored " + d.xp + ", bottled " + bottleXp + ", spent " + spent);
		});
	}

	// ---- Farmer ----------------------------------------------------------------------------------------------------

	/** Mending hoe at the threshold, no other tool: it goes back, the dirt is not tilled, the ripe wheat is still harvested by hand. */
	@GameTest(maxTicks = 1500)
	public void farmerReturnsMendingHoeAndDoesNotTill(GameTestHelper helper) {
		BlockPos tools = new BlockPos(5, 1, 0), seeds = new BlockPos(5, 1, 2), out = new BlockPos(5, 1, 4);
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(tools, Blocks.CHEST);
		helper.setBlock(seeds, Blocks.CHEST);
		helper.setBlock(out, Blocks.CHEST);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.TORCH);
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.DIRT);
		helper.setBlock(new BlockPos(1, 0, 0), Blocks.DIRT);
		helper.setBlock(new BlockPos(2, 0, 0), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
		helper.setBlock(new BlockPos(2, 1, 0), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		chest(helper, seeds).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, FarmerProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("field_a", helper.absolutePos(new BlockPos(0, 0, 0)));
		data.assignments.put("field_b", helper.absolutePos(new BlockPos(2, 0, 0)));
		data.assignments.put("tools", helper.absolutePos(tools));
		data.assignments.put("seeds", helper.absolutePos(seeds));
		data.assignments.put("output", helper.absolutePos(out));
		data.items.set(FarmerProfession.TOOL_SLOT, mending(helper, Items.IRON_HOE, SAFE));
		GolemData.save(golem, data);
		ChestBlockEntity toolChest = chest(helper, tools), outChest = chest(helper, out);
		helper.onEachTick(() -> {
			helper.assertTrue(mendingTools(golem, toolChest, outChest).size() == 1, "the Mending hoe was lost or duplicated");
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(0, 0, 0));
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 0, 0));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(toolChest, Items.IRON_HOE) == 1 && GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).isEmpty(), "the hoe is not back in the chest");
			helper.assertTrue(count(outChest, Items.WHEAT) == 1, "wheat harvested by hand: " + count(outChest, Items.WHEAT));
		});
	}

	/** A Mending hoe lying worn in the chest is repaired when bottles arrive and then used to till. */
	@GameTest(maxTicks = 2500)
	public void farmerRepairsStoredMendingHoeAndReusesIt(GameTestHelper helper) {
		BlockPos tools = new BlockPos(5, 1, 0), seeds = new BlockPos(5, 1, 2), out = new BlockPos(5, 1, 4);
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(tools, Blocks.CHEST);
		helper.setBlock(seeds, Blocks.CHEST);
		helper.setBlock(out, Blocks.CHEST);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.TORCH);
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.DIRT);
		helper.setBlock(new BlockPos(1, 0, 0), Blocks.DIRT);
		chest(helper, seeds).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		ChestBlockEntity toolChest = chest(helper, tools), outChest = chest(helper, out);
		ItemStack worn = mending(helper, Items.IRON_HOE, 3);
		toolChest.setItem(0, worn);
		int per = GolemMending.perBottle(helper.getLevel(), worn);
		int need = (worn.getMaxDamage() - 3 + per - 1) / per;
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, FarmerProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("field_a", helper.absolutePos(new BlockPos(0, 0, 0)));
		data.assignments.put("field_b", helper.absolutePos(new BlockPos(1, 0, 0)));
		data.assignments.put("tools", helper.absolutePos(tools));
		data.assignments.put("seeds", helper.absolutePos(seeds));
		data.assignments.put("output", helper.absolutePos(out));
		GolemData.save(golem, data);
		helper.runAfterDelay(120, () -> {
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(0, 0, 0));
			toolChest.setItem(1, new ItemStack(Items.EXPERIENCE_BOTTLE, need + 3));
		});
		helper.onEachTick(() -> helper.assertTrue(mendingTools(golem, toolChest, outChest).size() == 1, "the Mending hoe was lost or duplicated"));
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0));
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(t.is(Items.IRON_HOE) && t.getDamageValue() == 2, "hoe repaired, then 2 tills: " + t);
			helper.assertTrue(count(toolChest, Items.EXPERIENCE_BOTTLE) == 3, "bottles left: " + count(toolChest, Items.EXPERIENCE_BOTTLE) + ", need " + need);
		});
	}

	/** Idle Farmer (field of stone: nothing to plant, harvest or till), hoe missing 5 points: topped up from the tools chest, 1 bottle. */
	@GameTest(maxTicks = 1500)
	public void farmerTopsUpHoeWhileIdle(GameTestHelper helper) {
		BlockPos tools = new BlockPos(5, 1, 0), out = new BlockPos(5, 1, 4);
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(tools, Blocks.CHEST);
		helper.setBlock(out, Blocks.CHEST);
		ChestBlockEntity toolChest = chest(helper, tools);
		toolChest.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 5));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, FarmerProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("field_a", helper.absolutePos(new BlockPos(0, 0, 0)));
		data.assignments.put("field_b", helper.absolutePos(new BlockPos(1, 0, 0)));
		data.assignments.put("tools", helper.absolutePos(tools));
		data.assignments.put("output", helper.absolutePos(out));
		data.items.set(FarmerProfession.TOOL_SLOT, mending(helper, Items.IRON_HOE, 250 - 5));
		GolemData.save(golem, data);
		helper.succeedWhen(() -> {
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(t.is(Items.IRON_HOE) && t.getDamageValue() == 0, "hoe damage: " + t.getDamageValue());
			helper.assertTrue(count(toolChest, Items.EXPERIENCE_BOTTLE) == 4, "bottles left: " + count(toolChest, Items.EXPERIENCE_BOTTLE));
		});
	}
}
