package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.farmer.FarmerLogic;
import com.coppergolemsrevamped.profession.farmer.FarmerProfession;

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
			helper.assertTrue(count(seeds, Items.WHEAT_SEEDS) == 20, "wheat seeds left in chest: " + count(seeds, Items.WHEAT_SEEDS));
			helper.assertTrue(count(d.items, Items.WHEAT) == 0, "wheat left in carry");
		});
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

	/** A hoe that breaks on its next use is swapped for a fresh one from the chest; no hoe is lost or duplicated. */
	@GameTest(maxTicks = 1500)
	public void wornHoeIsSwappedNotLost(GameTestHelper helper) {
		world(helper);
		field(helper, 2, 1, Blocks.DIRT);
		chest(helper, TOOLS).setItem(0, new ItemStack(Items.IRON_HOE));
		chest(helper, SEEDS).setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		ItemStack worn = new ItemStack(Items.WOODEN_HOE);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		CopperGolem golem = farmer(helper, 2, 1, (g, d) -> d.items.set(FarmerProfession.TOOL_SLOT, worn));

		helper.onEachTick(() -> {
			int hoes = count(chest(helper, TOOLS), Items.IRON_HOE) + count(chest(helper, TOOLS), Items.WOODEN_HOE)
				+ count(GolemData.get(golem).items, Items.IRON_HOE) + count(GolemData.get(golem).items, Items.WOODEN_HOE);
			helper.assertTrue(hoes == 2, "hoes in total: " + hoes);
		});
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(0, 1, 0));
			helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(1, 1, 0));
			helper.assertTrue(GolemData.get(golem).items.get(FarmerProfession.TOOL_SLOT).is(Items.IRON_HOE), "iron hoe not in the tool slot");
			helper.assertTrue(count(chest(helper, TOOLS), Items.WOODEN_HOE) == 1, "worn hoe not returned to the chest");
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
		for (Item i : new Item[] { Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS, Items.TORCHFLOWER_SEEDS, Items.MELON_SEEDS })
			helper.assertTrue(FarmerLogic.isSeed(new ItemStack(i)), i + " must be a seed");
		for (Item i : new Item[] { Items.WHEAT, Items.STONE, Items.STICK, Items.IRON_HOE })
			helper.assertTrue(!FarmerLogic.isSeed(new ItemStack(i)), i + " must not be a seed");
		helper.succeed();
	}
}
