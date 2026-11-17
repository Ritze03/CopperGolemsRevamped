package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.ToolSwing;
import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemMending;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.farmer.FarmerLogic;
import com.coppergolemsrevamped.profession.farmer.FarmerProfession;

import java.util.List;
import java.util.function.BiConsumer;

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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public class FarmerTest {
	private static final BlockPos TOOLS = new BlockPos(5, 1, 0);
	private static final BlockPos SEEDS = new BlockPos(5, 1, 2);
	private static final BlockPos OUT = new BlockPos(5, 1, 4);

	/** Stone floor, three chests (tools, seeds, output) in x=5 and a torch (crops need light >= 8). */
	private static void world(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(TOOLS, Blocks.CHEST);
		helper.setBlock(SEEDS, Blocks.CHEST);
		helper.setBlock(OUT, Blocks.CHEST);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.TORCH);
	}

	private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
		return helper.getBlockEntity(pos, ChestBlockEntity.class);
	}

	/** Field = x 0..w-1, z 0..d-1 at y=0 (soil layer); fills it with {@code soil}. */
	private static void field(GameTestHelper helper, int w, int d, Block soil) {
		for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) helper.setBlock(new BlockPos(x, 0, z), soil);
	}

	private static CopperGolem farmer(GameTestHelper helper, int w, int d, BiConsumer<CopperGolem, GolemData> extra) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, FarmerProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("field_a", helper.absolutePos(new BlockPos(0, 0, 0)));
		data.assignments.put("field_b", helper.absolutePos(new BlockPos(w - 1, 0, d - 1)));
		data.assignments.put("tools", helper.absolutePos(TOOLS));
		data.assignments.put("seeds", helper.absolutePos(SEEDS));
		data.assignments.put("output", helper.absolutePos(OUT));
		extra.accept(golem, data);
		GolemData.save(golem, data);
		return golem;
	}

	private static int count(Iterable<ItemStack> stacks, Item item) {
		int n = 0;
		for (ItemStack s : stacks) if (s.is(item)) n += s.getCount();
		return n;
	}

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	/**
	 * 3x3 moist farmland, four mature wheat. Seed chest: slot 0 beetroot seeds (the FIRST seed), slot 1 wheat seeds.
	 * Every cell must end up with beetroots, wheat seeds must stay in the chest, output holds exactly 4 wheat.
	 */
	@GameTest(maxTicks = 1500)
	public void harvestsReplantsAndOutputs(GameTestHelper helper) {
		world(helper);
		BlockPos[] mature = { new BlockPos(0, 1, 0), new BlockPos(1, 1, 1), new BlockPos(2, 1, 2), new BlockPos(0, 1, 2) };
		for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
			helper.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
		for (BlockPos p : mature) helper.setBlock(p, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		ChestBlockEntity seeds = chest(helper, SEEDS), out = chest(helper, OUT);
		seeds.setItem(0, new ItemStack(Items.BEETROOT_SEEDS, 20));
		seeds.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 20));
		CopperGolem golem = farmer(helper, 3, 3, (g, d) -> {});

		helper.onEachTick(() -> {
			GolemData d = GolemData.get(golem);
			int planted = 0;
			for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
				if (helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.BEETROOTS)) planted++;
			int beet = count(seeds, Items.BEETROOT_SEEDS) + count(out, Items.BEETROOT_SEEDS) + count(d.items, Items.BEETROOT_SEEDS) + planted;
			helper.assertTrue(beet == 20, "beetroot seeds not conserved: " + beet);
		});
		helper.succeedWhen(() -> {
			for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
				helper.assertBlockPresent(Blocks.BEETROOTS, new BlockPos(x, 1, z));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(count(out, Items.WHEAT) == 4, "wheat in output: " + count(out, Items.WHEAT));
			// harvest drops that are seeds come back to the seed chest (never the output)
			helper.assertTrue(count(seeds, Items.WHEAT_SEEDS) >= 20, "wheat seeds left in chest: " + count(seeds, Items.WHEAT_SEEDS));
			helper.assertTrue(count(out, Items.WHEAT_SEEDS) == 0, "seeds in output");
			helper.assertTrue(count(d.items, Items.WHEAT) == 0, "wheat left in carry");
		});
	}

	/**
	 * A hoe harvest swings first (one swing = one chop), a bare-handed harvest does not. Two ripe wheat with an iron hoe:
	 * raised state seen, at least 2 chops, then back to IDLE with nothing active.
	 */
	@GameTest(maxTicks = 1000)
	public void hoeWorkSwingsAndReturnsToIdle(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.FARMLAND);
		for (int x = 0; x < 2; x++) helper.setBlock(new BlockPos(x, 1, 0), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		ChestBlockEntity out = chest(helper, OUT);
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, new ItemStack(Items.IRON_HOE)));
		boolean[] raised = { false };
		int[] chops = { 0 };
		helper.onEachTick(() -> {
			boolean r = golem.getState() == ToolSwing.RAISED;
			if (raised[0] && !r && ToolSwing.active(golem)) chops[0]++;
			raised[0] = r || (raised[0] && ToolSwing.active(golem));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.WHEAT) == 2, "wheat in output: " + count(out, Items.WHEAT));
			helper.assertTrue(chops[0] >= 2, "chops seen: " + chops[0]);
			helper.assertTrue(!ToolSwing.active(golem) && golem.getState() != ToolSwing.RAISED, "golem stuck raised: " + golem.getState());
		});
	}

	/** Swing counts: hoe harvest/till 1, axe on a fruit 2, planting and bare hands none. */
	@GameTest
	public void swingCounts(GameTestHelper helper) {
		helper.assertTrue(FarmerLogic.swingsFor(FarmerLogic.Mode.HOE, FarmerLogic.Act.HARVEST) == 1, "hoe harvest");
		helper.assertTrue(FarmerLogic.swingsFor(FarmerLogic.Mode.HOE, FarmerLogic.Act.TILL) == 1, "hoe till");
		helper.assertTrue(FarmerLogic.swingsFor(FarmerLogic.Mode.AXE, FarmerLogic.Act.FRUIT) == 2, "axe fruit");
		helper.assertTrue(FarmerLogic.swingsFor(FarmerLogic.Mode.HOE, FarmerLogic.Act.PLANT) == 0, "plant");
		helper.assertTrue(FarmerLogic.swingsFor(FarmerLogic.Mode.NONE, FarmerLogic.Act.HARVEST) == 0, "bare hands");
		helper.succeed();
	}

	/** 2x2 dirt, hoe in the tool chest, seeds in the seed chest: till, plant, hoe loses one durability per tilled cell. */
	@GameTest(maxTicks = 1500)
	public void tillsDirtWithHoe(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 2, Blocks.DIRT);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		CopperGolem golem = farmer(helper, 2, 2, (g, d) -> {});

		helper.succeedWhen(() -> {
			for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
				helper.assertBlockPresent(Blocks.FARMLAND, new BlockPos(x, 0, z));
				helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, z));
			}
			ItemStack tool = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(tool.is(Items.IRON_HOE), "tool slot: " + tool);
			helper.assertTrue(tool.getDamageValue() == 4, "hoe damage: " + tool.getDamageValue());
			helper.assertTrue(count(chest(helper, TOOLS), Items.IRON_HOE) == 0, "hoe still in the tool chest");
		});
	}

	/** A plain hoe with 1 durability is used until it breaks (never parked), then the golem fetches a fresh one from the chest. */
	@GameTest(maxTicks = 1500)
	public void plainHoeBreaksThenNextIsFetched(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.DIRT);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		ItemStack worn = new ItemStack(Items.WOODEN_HOE);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, worn));

		helper.onEachTick(() -> helper.assertTrue(count(chest(helper, TOOLS), Items.WOODEN_HOE) == 0, "the 1-durability plain hoe was parked"));
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0));
			helper.assertTrue(GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).is(Items.IRON_HOE), "iron hoe not in the tool slot");
		});
	}

	/**
	 * D9: no item lost, no exception. One mature wheat; the drop roll is forced (test hook): the pre-check roll is one
	 * wheat (fits), the first roll {@code act} stores is one wheat + 3 wheat seeds (does not fit: the carry is 8 slots
	 * of stone + 63 wheat, the seed slot holds carrots). The crop must survive that refusal untouched; the golem then
	 * delivers its carry, and later harvests the crop with a small roll. Invariant every tick: crop standing means
	 * no wheat seed anywhere and exactly 63 wheat; crop gone means exactly the 1 wheat (+3 seeds iff the big roll
	 * was stored, which only happens with room) is somewhere. (One crop only: after one harvest the wheat slot is
	 * full, and a delivery empties the carry, which would legitimately make the big roll fit.)
	 */
	@GameTest(maxTicks = 2600)
	public void harvestWithNearlyFullCarryLosesNothing(GameTestHelper helper) {
		world(helper);
		BlockPos cropPos = new BlockPos(0, 1, 0);
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
		helper.setBlock(cropPos, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		int[] storedRolls = { 0 };
		BlockPos absCrop = helper.absolutePos(cropPos);
		// the hook is global and tests run in parallel: only this test's crop is forced
		FarmerLogic.testDropRoll = (pos, stored) -> {
			if (!pos.equals(absCrop)) return null;
			if (stored) storedRolls[0]++;
			return stored && storedRolls[0] == 1
				? List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS, 3))
				: List.of(new ItemStack(Items.WHEAT));
		};
		ChestBlockEntity out = chest(helper, OUT);
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> {
			for (int i = 0; i < 8; i++) d.items.set(FarmerProfession.CARRY_FROM + i, new ItemStack(Items.STONE, 64));
			d.items.set(FarmerProfession.CARRY_FROM + 8, new ItemStack(Items.WHEAT, 63));
			d.items.set(FarmerProfession.SEED_SLOT, new ItemStack(Items.CARROT, 32));
		});
		helper.onEachTick(() -> {
			GolemData d = GolemData.get(golem);
			boolean standing = helper.getBlockState(cropPos).is(Blocks.WHEAT);
			int wheat = count(d.items, Items.WHEAT) + count(out, Items.WHEAT);
			int seeds = count(d.items, Items.WHEAT_SEEDS) + count(out, Items.WHEAT_SEEDS);
			if (standing) helper.assertTrue(wheat == 63 && seeds == 0, "crop standing but wheat=" + wheat + " seeds=" + seeds + " (refused harvest leaked items)");
			else helper.assertTrue(wheat == 64 && (seeds == 0 || seeds == 3), "crop gone: wheat=" + wheat + " seeds=" + seeds + " (item lost or duplicated) " + d.items + " out=" + out.getItem(0) + out.getItem(1) + out.getItem(2) + " rolls=" + storedRolls[0]);
		});
		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.WHEAT, cropPos);
			helper.assertTrue(storedRolls[0] >= 2, "forced big roll not refused first: " + storedRolls[0]);
			FarmerLogic.testDropRoll = null;
		});
	}

	/** Melon/pumpkin seeds plant stems that are never harvested: they are not seeds, the golem uses the wheat seeds. */
	@GameTest(maxTicks = 1500)
	public void stemSeedsIgnored(GameTestHelper helper) {
		world(helper);
		for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
			helper.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
		ChestBlockEntity seeds = chest(helper, SEEDS);
		seeds.setItem(0, new ItemStack(Items.MELON_SEEDS, 10));
		seeds.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 10));
		CopperGolem golem = farmer(helper, 2, 2, (g, d) -> {});
		helper.onEachTick(() -> {
			for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
				helper.assertBlockNotPresent(Blocks.MELON_STEM, new BlockPos(x, 1, z));
			helper.assertTrue(!GolemData.get(golem).items.get(FarmerProfession.SEED_SLOT).is(Items.MELON_SEEDS), "melon seeds taken");
		});
		helper.succeedWhen(() -> {
			for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, z));
			helper.assertTrue(count(seeds, Items.MELON_SEEDS) == 10, "melon seeds left in chest: " + count(seeds, Items.MELON_SEEDS));
		});
	}

	private static ItemStack worn(Item item) {
		ItemStack st = new ItemStack(item);
		st.setDamageValue(st.getMaxDamage() - 1);
		return st;
	}

	private static boolean carryEmptyOf(CopperGolem golem) {
		GolemData d = GolemData.get(golem);
		for (int i = 0; i < FarmerProfession.CARRY_SLOTS; i++) if (!d.items.get(FarmerProfession.CARRY_FROM + i).isEmpty()) return false;
		return true;
	}

	/**
	 * 1x1 field on stone (nothing to do there). The carry holds wheat, seed items (wheat seeds, carrots) and melon
	 * seeds (not a seed). Seed items go to the seed chest, the rest to the output chest; the lid opens meanwhile.
	 */
	@GameTest(maxTicks = 1500)
	public void seedsGoToSeedChestRestToOutput(GameTestHelper helper) {
		world(helper);
		ChestBlockEntity seeds = chest(helper, SEEDS), out = chest(helper, OUT);
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> {
			d.items.set(FarmerProfession.CARRY_FROM, new ItemStack(Items.WHEAT, 3));
			d.items.set(FarmerProfession.CARRY_FROM + 1, new ItemStack(Items.WHEAT_SEEDS, 5));
			d.items.set(FarmerProfession.CARRY_FROM + 2, new ItemStack(Items.CARROT, 2));
			d.items.set(FarmerProfession.CARRY_FROM + 3, new ItemStack(Items.MELON_SEEDS, 2));
		});
		boolean[] lid = { false };
		helper.onEachTick(() -> lid[0] |= Interact.isOpen(golem));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(seeds, Items.WHEAT_SEEDS) == 5 && count(seeds, Items.CARROT) == 2, "seed chest wrong");
			helper.assertTrue(count(seeds, Items.WHEAT) == 0 && count(seeds, Items.MELON_SEEDS) == 0, "non-seed in seed chest");
			helper.assertTrue(count(out, Items.WHEAT) == 3 && count(out, Items.MELON_SEEDS) == 2, "output wrong");
			helper.assertTrue(count(out, Items.WHEAT_SEEDS) == 0 && count(out, Items.CARROT) == 0, "seed in output");
			helper.assertTrue(lid[0], "lid never opened");
			helper.assertTrue(!Interact.isOpen(golem), "lid still registered open");
			helper.assertTrue(carryEmptyOf(golem), "carry not empty");
		});
	}

	/** Full seed chest: seeds overflow into the output chest, nothing is lost. */
	@GameTest(maxTicks = 1500)
	public void seedsOverflowToOutput(GameTestHelper helper) {
		world(helper);
		ChestBlockEntity seeds = chest(helper, SEEDS), out = chest(helper, OUT);
		for (int i = 0; i < seeds.getContainerSize(); i++) seeds.setItem(i, new ItemStack(Items.STONE, 64));
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> d.items.set(FarmerProfession.CARRY_FROM, new ItemStack(Items.WHEAT_SEEDS, 5)));
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.WHEAT_SEEDS) == 5, "seeds in output: " + count(out, Items.WHEAT_SEEDS));
			helper.assertTrue(carryEmptyOf(golem), "carry not empty");
		});
	}

	/** No seed chest assigned: seeds go to the output chest as before. */
	@GameTest(maxTicks = 1500)
	public void noSeedChestSeedsToOutput(GameTestHelper helper) {
		world(helper);
		ChestBlockEntity out = chest(helper, OUT);
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> {
			d.assignments.remove("seeds");
			d.items.set(FarmerProfession.CARRY_FROM, new ItemStack(Items.WHEAT_SEEDS, 5));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(out, Items.WHEAT_SEEDS) == 5, "seeds in output: " + count(out, Items.WHEAT_SEEDS));
			helper.assertTrue(carryEmptyOf(golem), "carry not empty");
		});
	}

	/**
	 * Axe farmer, 3x2 field: a melon block and a pumpkin block each next to their stem, a carved pumpkin, and a dirt
	 * cell. Fruits are harvested (drops to output, axe loses 1 per block), stems and the carved pumpkin stay, the dirt
	 * is not tilled, nothing is planted, the seed chest is untouched. Each harvest happens within 1 block reach.
	 */
	@GameTest(maxTicks = 2000)
	public void axeHarvestsFruitLeavesStems(GameTestHelper helper) {
		world(helper);
		field(helper, 3, 2, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(2, 0, 1), Blocks.DIRT);
		BlockPos melon = new BlockPos(1, 1, 0), pumpkin = new BlockPos(1, 1, 1);
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.MELON_STEM.defaultBlockState().setValue(StemBlock.AGE, 2));
		helper.setBlock(new BlockPos(0, 1, 1), Blocks.PUMPKIN_STEM.defaultBlockState().setValue(StemBlock.AGE, 2));
		helper.setBlock(melon, Blocks.MELON);
		helper.setBlock(pumpkin, Blocks.PUMPKIN);
		helper.setBlock(new BlockPos(2, 1, 0), Blocks.CARVED_PUMPKIN);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, TOOLS).setItem(1, new ItemStack(Items.IRON_HOE));
		ChestBlockEntity seeds = chest(helper, SEEDS), out = chest(helper, OUT);
		seeds.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 20));
		CopperGolem golem = farmer(helper, 3, 2, (g, d) -> {});
		boolean[] had = { true, true };
		BlockPos[] fruit = { melon, pumpkin };
		helper.onEachTick(() -> {
			for (int i = 0; i < 2; i++) {
				boolean now = !helper.getBlockState(fruit[i]).isAir();
				if (had[i] && !now) helper.assertTrue(Interact.inReach(golem, helper.absolutePos(fruit[i])), "harvested " + fruit[i] + " from " + golem.blockPosition());
				had[i] = now;
			}
			helper.assertBlockPresent(Blocks.MELON_STEM, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.PUMPKIN_STEM, new BlockPos(0, 1, 1));
			helper.assertBlockPresent(Blocks.CARVED_PUMPKIN, new BlockPos(2, 1, 0));
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(2, 0, 1));
			helper.assertBlockNotPresent(Blocks.WHEAT, new BlockPos(2, 1, 1));
		});
		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.MELON, melon);
			helper.assertBlockNotPresent(Blocks.PUMPKIN, pumpkin);
			int slices = count(out, Items.MELON_SLICE);
			helper.assertTrue(slices >= 3 && slices <= 7, "melon slices in output: " + slices);
			helper.assertTrue(count(out, Items.PUMPKIN) == 1, "pumpkins in output: " + count(out, Items.PUMPKIN));
			ItemStack tool = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(tool.is(Items.IRON_AXE) && tool.getDamageValue() == 2, "tool slot: " + tool);
			helper.assertTrue(count(seeds, Items.WHEAT_SEEDS) == 20, "seed chest touched");
			helper.assertTrue(carryEmptyOf(golem), "carry not empty");
		});
	}

	/** A hoe farmer ignores melon blocks (and still plants on the free cell). */
	@GameTest(maxTicks = 1500)
	public void hoeIgnoresMelons(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.MELON);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		farmer(helper, 2, 1, (g, d) -> {});
		helper.onEachTick(() -> helper.assertBlockPresent(Blocks.MELON, new BlockPos(0, 1, 0)));
		helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0)));
	}

	/** Protected enchanted hoe (at the threshold), an axe comes first in the chest: only the hoe replaces it; the axe stays; no tool is lost. */
	@GameTest(maxTicks = 1500)
	public void wornHoeReplacedOnlyByHoe(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.DIRT);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, TOOLS).setItem(1, new ItemStack(Items.IRON_HOE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, MendingTest.mending(helper, Items.WOODEN_HOE, GolemMending.SAFE_DURABILITY)));
		helper.onEachTick(() -> {
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(!t.is(Items.IRON_AXE), "axe replaced the hoe");
			helper.assertTrue(!t.isEmpty(), "tool slot emptied");
		});
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0));
			helper.assertTrue(GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).is(Items.IRON_HOE), "iron hoe not in the tool slot");
			helper.assertTrue(count(chest(helper, TOOLS), Items.IRON_AXE) == 1, "axe left the chest");
			helper.assertTrue(count(chest(helper, TOOLS), Items.WOODEN_HOE) == 1, "worn hoe not returned");
		});
	}

	/** Protected enchanted axe, a hoe comes first in the chest: only the axe replaces it, then both melons are harvested. */
	@GameTest(maxTicks = 1500)
	public void wornAxeReplacedOnlyByAxe(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.MELON);
		helper.setBlock(new BlockPos(1, 1, 0), Blocks.MELON);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		chest(helper, TOOLS).setItem(1, new ItemStack(Items.IRON_AXE));
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, MendingTest.mending(helper, Items.WOODEN_AXE, GolemMending.SAFE_DURABILITY)));
		helper.onEachTick(() -> {
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(!t.is(Items.IRON_HOE), "hoe replaced the axe");
			helper.assertTrue(!t.isEmpty(), "tool slot emptied");
		});
		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.MELON, new BlockPos(0, 1, 0));
			helper.assertBlockNotPresent(Blocks.MELON, new BlockPos(1, 1, 0));
			helper.assertTrue(GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).is(Items.IRON_AXE), "iron axe not in the tool slot");
			helper.assertTrue(count(chest(helper, TOOLS), Items.IRON_HOE) == 1, "hoe left the chest");
			helper.assertTrue(count(chest(helper, TOOLS), Items.WOODEN_AXE) == 1, "worn axe not returned");
		});
	}

	/** No same-type replacement: the plain 1-durability hoe is used until it breaks (it tills a cell), never parked. */
	@GameTest(maxTicks = 500)
	public void plainHoeWithoutReplacementIsUsedUp(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.DIRT);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_AXE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, worn(Items.WOODEN_HOE)));
		helper.runAfterDelay(300, () -> {
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(!t.is(Items.WOODEN_HOE), "the plain hoe was not used up: " + t);
			helper.assertTrue(count(chest(helper, TOOLS), Items.WOODEN_HOE) == 0, "the plain hoe was parked");
			helper.assertTrue(helper.getBlockState(new BlockPos(0, 0, 0)).is(Blocks.FARMLAND)
				|| helper.getBlockState(new BlockPos(1, 0, 0)).is(Blocks.FARMLAND), "the last point tilled nothing");
			helper.succeed();
		});
	}

	/** Same for the axe: a plain 1-durability axe with only a hoe in the chest harvests the pumpkin and breaks. */
	@GameTest(maxTicks = 500)
	public void plainAxeWithoutReplacementIsUsedUp(GameTestHelper helper) {
		world(helper);
		field(helper, 1, 1, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.PUMPKIN);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, worn(Items.WOODEN_AXE)));
		helper.runAfterDelay(300, () -> {
			ItemStack t = GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT);
			helper.assertTrue(!t.is(Items.WOODEN_AXE), "the plain axe was not used up: " + t);
			helper.assertBlockNotPresent(Blocks.PUMPKIN, new BlockPos(0, 1, 0));
			helper.succeed();
		});
	}

	/** Tool slot filter: hoes and axes only. Empty slot takes the first hoe or axe in the chest (here the axe). */
	@GameTest(maxTicks = 1500)
	public void emptySlotTakesFirstToolAndFilter(GameTestHelper helper) {
		world(helper);
		Profession.SlotGroup tool = Professions.get(FarmerProfession.ID).slots().getFirst();
		helper.assertTrue(tool.filter().test(new ItemStack(Items.IRON_AXE)) && tool.filter().test(new ItemStack(Items.IRON_HOE)), "hoe/axe must fit");
		helper.assertTrue(!tool.filter().test(new ItemStack(Items.IRON_PICKAXE)) && !tool.filter().test(new ItemStack(Items.STICK)), "other items must not fit");
		field(helper, 1, 1, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.MELON);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.STICK));
		chest(helper, TOOLS).setItem(1, new ItemStack(Items.IRON_AXE));
		chest(helper, TOOLS).setItem(2, new ItemStack(Items.IRON_HOE));
		CopperGolem golem = farmer(helper, 1, 1, (g, d) -> {});
		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.MELON, new BlockPos(0, 1, 0));
			helper.assertTrue(GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).is(Items.IRON_AXE), "axe not taken first");
			helper.assertTrue(count(chest(helper, TOOLS), Items.IRON_HOE) == 1, "hoe taken");
		});
	}

	@GameTest
	public void fieldLimit9(GameTestHelper helper) {
		Profession p = Professions.get(FarmerProfession.ID);
		Profession.AssignSpec field = p.assigns().stream().filter(a -> a.key().equals("field")).findFirst().orElseThrow();
		helper.assertTrue(field.kind() == Profession.Kind.AREA, "field kind " + field.kind());
		helper.assertTrue(field.maxSize() == 9, "maxSize " + field.maxSize());
		BlockPos o = new BlockPos(0, 5, 0);
		helper.assertTrue(AssignMode.areaFits(o, new BlockPos(8, 5, 8), 9), "9x9 must fit");
		helper.assertTrue(!AssignMode.areaFits(o, new BlockPos(9, 5, 8), 9), "10x9 must not fit");
		helper.assertTrue(!AssignMode.areaFits(o, new BlockPos(8, 5, 9), 9), "9x10 must not fit");
		// an oversized stored field (cannot be assigned, but must never be worked) yields no cells
		GolemData d = new GolemData();
		d.assignments.put("field_a", o);
		d.assignments.put("field_b", new BlockPos(9, 5, 8));
		helper.assertTrue(FarmerLogic.cells(d).isEmpty(), "10x9 field gave cells");
		d.assignments.put("field_b", new BlockPos(8, 7, 8));
		helper.assertTrue(FarmerLogic.cells(d).size() == 81, "9x9 field cells: " + FarmerLogic.cells(d).size());
		helper.assertTrue(FarmerLogic.cells(d).getFirst().getY() == 5, "soil layer is the lower click's Y");
		helper.succeed();
	}

	@GameTest
	public void seedDefinition(GameTestHelper helper) {
		for (Item i : new Item[] { Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS })
			helper.assertTrue(FarmerLogic.isSeed(new ItemStack(i)), i + " must be a seed");
		// stems are never harvested, torchflower becomes a flower block: both would block their cell forever
		for (Item i : new Item[] { Items.WHEAT, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.TORCHFLOWER_SEEDS, Items.STONE, Items.STICK, Items.IRON_HOE })
			helper.assertTrue(!FarmerLogic.isSeed(new ItemStack(i)), i + " must not be a seed");
		helper.succeed();
	}

	/**
	 * Keep layout: seed chest slots hold 3 and 1 wheat seeds, the output chest one wheat in slot 0. Two ripe wheat are
	 * harvested and replanted from the 2 spare seeds (both seed slots keep 1); the wheat goes onto slot 0 only.
	 */
	@GameTest(maxTicks = 1500)
	public void keepLayoutFetchesAndDeliversAroundTheLayout(GameTestHelper helper) {
		world(helper);
		for (int x = 0; x < 2; x++) {
			helper.setBlock(new BlockPos(x, 0, 0), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
			helper.setBlock(new BlockPos(x, 1, 0), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		}
		ChestBlockEntity seeds = chest(helper, SEEDS), out = chest(helper, OUT);
		seeds.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
		seeds.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 1));
		out.setItem(0, new ItemStack(Items.WHEAT, 1));
		farmer(helper, 2, 1, (g, d) -> d.keepLayout = true);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0));
			helper.assertTrue(out.getItem(0).is(Items.WHEAT) && out.getItem(0).getCount() == 3, "wheat slot: " + out.getItem(0));
			for (int i = 1; i < out.getContainerSize(); i++) helper.assertTrue(out.getItem(i).isEmpty(), "output slot " + i + " filled: " + out.getItem(i));
			helper.assertTrue(seeds.getItem(0).is(Items.WHEAT_SEEDS) && seeds.getItem(0).getCount() >= 1, "seed slot 0: " + seeds.getItem(0));
			helper.assertTrue(seeds.getItem(1).is(Items.WHEAT_SEEDS) && seeds.getItem(1).getCount() >= 1, "seed slot 1: " + seeds.getItem(1));
			for (int i = 2; i < seeds.getContainerSize(); i++) helper.assertTrue(seeds.getItem(i).isEmpty(), "seed slot " + i + " filled: " + seeds.getItem(i));
		});
	}
}
