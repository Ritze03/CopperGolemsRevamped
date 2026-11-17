package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Keep layout (superdoc/features/keep-layout.md): never empty a stack, never fill an empty slot. */
public class KeepLayoutTest {
	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	private static CopperGolem keeper(GameTestHelper helper, net.minecraft.resources.Identifier profession) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, profession);
		GolemData data = GolemData.get(golem);
		data.keepLayout = true;
		GolemData.save(golem, data);
		return golem;
	}

	/** A Transporter with keep layout: A keeps 1 in every slot, B's empty slots stay empty, B's same-item slots are filled. */
	@GameTest(maxTicks = 1200)
	public void transporterKeepsLayout(GameTestHelper helper) {
		floor(helper);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		chestA.setItem(0, new ItemStack(Items.STICK, 1));
		chestA.setItem(1, new ItemStack(Items.STICK, 5));
		chestA.setItem(2, new ItemStack(Items.DARK_OAK_LOG, 1));
		chestA.setItem(3, new ItemStack(Items.DARK_OAK_LOG, 8));
		chestA.setItem(4, new ItemStack(Items.STICK, 3));
		chestB.setItem(0, new ItemStack(Items.STICK, 1));
		chestB.setItem(1, new ItemStack(Items.DARK_OAK_LOG, 1));
		chestB.setItem(5, new ItemStack(Items.STICK, 1));

		CopperGolem golem = keeper(helper, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(chestB, Items.STICK) == 8 && count(chestB, Items.DARK_OAK_LOG) == 8,
				"B sticks " + count(chestB, Items.STICK) + ", logs " + count(chestB, Items.DARK_OAK_LOG));
			for (int i = 0; i < 5; i++) helper.assertTrue(chestA.getItem(i).getCount() == 1, "A slot " + i + " is " + chestA.getItem(i));
			for (int i = 0; i < chestB.getContainerSize(); i++) {
				boolean laidOut = i == 0 || i == 1 || i == 5;
				helper.assertTrue(laidOut || chestB.getItem(i).isEmpty(), "B slot " + i + " must stay empty: " + chestB.getItem(i));
			}
			helper.assertTrue(chestB.getItem(0).is(Items.STICK) && chestB.getItem(1).is(Items.DARK_OAK_LOG) && chestB.getItem(5).getCount() == 1, "B layout changed");
			helper.assertTrue(TransporterLogic.isEmpty(TransporterLogic.carry(GolemData.get(golem))), "carry not delivered");
		});
	}

	/** Without the flag: everything moves and empty slots are used (behaviour unchanged). */
	@GameTest
	public void offMovesEverythingAndFillsEmptySlots(GameTestHelper helper) {
		SimpleContainer a = new SimpleContainer(9);
		SimpleContainer b = new SimpleContainer(9);
		a.setItem(0, new ItemStack(Items.STICK, 1));
		a.setItem(1, new ItemStack(Items.DARK_OAK_LOG, 5));
		b.setItem(0, new ItemStack(Items.STICK, 1));
		List<ItemStack> carry = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
		int took = TransporterLogic.takeAll(a, carry, 64, s -> true);
		helper.assertTrue(took == 6 && a.isEmpty(), "off take " + took);
		TransporterLogic.insertAll(carry, b, 64);
		helper.assertTrue(b.getItem(0).getCount() == 2 && b.getItem(1).is(Items.DARK_OAK_LOG), "off insert " + b.getItem(0) + " " + b.getItem(1));
		helper.succeed();
	}

	/** The rule itself on plain containers: take count-1, merge only, exempt unstackable items. */
	@GameTest
	public void ruleOnContainers(GameTestHelper helper) {
		SimpleContainer src = new SimpleContainer(9);
		src.setItem(0, new ItemStack(Items.STICK, 1));
		src.setItem(1, new ItemStack(Items.STICK, 4));
		src.setItem(2, new ItemStack(Items.IRON_AXE, 1));
		List<ItemStack> carry = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
		int took = TransporterLogic.takeAll(src, carry, 64, s -> true, true);
		helper.assertTrue(took == 4 && src.getItem(0).getCount() == 1 && src.getItem(1).getCount() == 1 && src.getItem(2).isEmpty(),
			"take " + took + " " + src.getItem(0) + " " + src.getItem(1) + " " + src.getItem(2) + " (the axe is unstackable and moves)");
		// took counts the 3 sticks plus the axe

		SimpleContainer dst = new SimpleContainer(9);
		dst.setItem(3, new ItemStack(Items.STICK, 62));
		int put = TransporterLogic.insertAll(carry, dst, 64, true);
		// 2 of the 3 sticks fit the 62-stack, the third stays in the carry; the axe goes to an empty slot (unstackable exemption)
		helper.assertTrue(put == 3 && dst.getItem(3).getCount() == 64 && count(dst, Items.IRON_AXE) == 1, "insert " + put + " " + dst.getItem(3));
		helper.assertTrue(TransporterLogic.takeable(src, new ItemStack(Items.STICK, 1), true) == 0
			&& TransporterLogic.takeable(src, new ItemStack(Items.STICK, 1), false) == 1, "takeable");

		SimpleContainer none = new SimpleContainer(9);
		List<ItemStack> c2 = new ArrayList<>(List.of(new ItemStack(Items.STICK, 5)));
		helper.assertTrue(TransporterLogic.insertAll(c2, none, 64, true) == 0 && c2.get(0).getCount() == 5 && none.isEmpty(), "no matching stack means no insert");
		helper.succeed();
	}

	/** Furnace slots are machine slots: the rule does not apply to them. */
	@GameTest
	public void furnaceSlotsAreExempt(GameTestHelper helper) {
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
		FurnaceBlockEntity furnace = helper.getBlockEntity(new BlockPos(1, 1, 1), FurnaceBlockEntity.class);
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 1));
		List<ItemStack> carry = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY));
		helper.assertTrue(TransporterLogic.takeAll(furnace, carry, 64, s -> true, true) == 1 && furnace.getItem(2).isEmpty(), "output slot must empty fully");
		List<ItemStack> ore = new ArrayList<>(List.of(new ItemStack(Items.RAW_IRON, 4)));
		helper.assertTrue(TransporterLogic.insertAll(ore, furnace, 64, true) == 4 && furnace.getItem(0).getCount() == 4, "input slot must fill from empty");
		helper.succeed();
	}

	/** Chest Sorter (vanilla brain, mixin): leaves 1 in the source slot and merges into the existing stack, never an empty slot. */
	@GameTest(maxTicks = 1200)
	public void chestSorterKeepsLayout(GameTestHelper helper) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos dst = new BlockPos(6, 1, 3);
		helper.setBlock(src, Blocks.COPPER_CHEST.weathering().unaffected());
		helper.setBlock(dst, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity to = helper.getBlockEntity(dst, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		from.setItem(1, new ItemStack(Items.COBBLESTONE, 10));
		to.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		CopperGolem golem = keeper(helper, ChestSorterProfession.ID);
		helper.succeedWhen(() -> {
			helper.assertTrue(golem.getMainHandItem().isEmpty() && to.getItem(0).getCount() == 10 && from.getItem(0).getCount() == 1 && from.getItem(1).getCount() == 1,
				"dst0 " + to.getItem(0) + ", src " + from.getItem(0) + "/" + from.getItem(1) + ", hand " + golem.getMainHandItem());
			for (int i = 1; i < to.getContainerSize(); i++) helper.assertTrue(to.getItem(i).isEmpty(), "dst slot " + i + " must stay empty: " + to.getItem(i));
		});
	}

	/** Chest Sorter with a destination that has no matching stack: nothing is put into the empty slots. */
	@GameTest(maxTicks = 600)
	public void chestSorterNeverFillsEmptySlots(GameTestHelper helper) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos dst = new BlockPos(6, 1, 3);
		helper.setBlock(src, Blocks.COPPER_CHEST.weathering().unaffected());
		helper.setBlock(dst, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity to = helper.getBlockEntity(dst, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		to.setItem(0, new ItemStack(Items.STICK, 1));
		CopperGolem golem = keeper(helper, ChestSorterProfession.ID);
		helper.runAfterDelay(500, () -> {
			helper.assertTrue(count(to, Items.COBBLESTONE) == 0 && to.getItem(0).is(Items.STICK), "destination changed: " + to.getItem(1));
			int total = count(from, Items.COBBLESTONE) + golem.getMainHandItem().getCount();
			helper.assertTrue(total == 10 && from.getItem(0).getCount() >= 1, "items lost: src " + from.getItem(0) + ", hand " + golem.getMainHandItem());
			helper.succeed();
		});
	}

	/**
	 * Chest Sorter, unstackable item: vanilla's rule (empty chest or one holding the same item) applies. The nearer chest has
	 * free slots but only a stick, so the axe must go to the farther chest that already holds an axe.
	 */
	@GameTest(maxTicks = 1200)
	public void chestSorterUnstackableKeepsVanillaRule(GameTestHelper helper) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos near = new BlockPos(3, 1, 1);
		BlockPos far = new BlockPos(6, 1, 6);
		helper.setBlock(src, Blocks.COPPER_CHEST.weathering().unaffected());
		helper.setBlock(near, Blocks.CHEST);
		helper.setBlock(far, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity other = helper.getBlockEntity(near, ChestBlockEntity.class);
		ChestBlockEntity same = helper.getBlockEntity(far, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.IRON_AXE));
		other.setItem(0, new ItemStack(Items.STICK, 1));
		same.setItem(0, new ItemStack(Items.IRON_AXE));
		CopperGolem golem = keeper(helper, ChestSorterProfession.ID);
		helper.onEachTick(() -> helper.assertTrue(count(other, Items.IRON_AXE) == 0, "axe put into the chest without an axe"));
		helper.succeedWhen(() -> {
			helper.assertTrue(golem.getMainHandItem().isEmpty() && from.isEmpty() && count(same, Items.IRON_AXE) == 2,
				"src " + from.getItem(0) + ", far axes " + count(same, Items.IRON_AXE) + ", hand " + golem.getMainHandItem());
			helper.assertTrue(count(other, Items.STICK) == 1 && count(other, Items.IRON_AXE) == 0, "near chest changed");
		});
	}

	/**
	 * Chest Sorter: a type no destination accepts (cobblestone in slot 0) is skipped, not picked up and held forever; the
	 * sticks behind it are still sorted.
	 */
	@GameTest(maxTicks = 1200)
	public void chestSorterSkipsTypesNoChestTakes(GameTestHelper helper) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos dst = new BlockPos(6, 1, 3);
		helper.setBlock(src, Blocks.COPPER_CHEST.weathering().unaffected());
		helper.setBlock(dst, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity to = helper.getBlockEntity(dst, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		from.setItem(1, new ItemStack(Items.STICK, 10));
		to.setItem(0, new ItemStack(Items.STICK, 1));
		CopperGolem golem = keeper(helper, ChestSorterProfession.ID);
		helper.succeedWhen(() -> {
			helper.assertTrue(golem.getMainHandItem().isEmpty() && count(to, Items.STICK) == 10 && from.getItem(1).getCount() == 1,
				"dst sticks " + count(to, Items.STICK) + ", src " + from.getItem(0) + "/" + from.getItem(1) + ", hand " + golem.getMainHandItem());
			helper.assertTrue(count(from, Items.COBBLESTONE) == 10 && count(to, Items.COBBLESTONE) == 0, "cobblestone moved");
		});
	}
}
