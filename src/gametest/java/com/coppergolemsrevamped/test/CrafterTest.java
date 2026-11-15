package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.profession.crafter.CrafterProfession;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

public class CrafterTest {
	private static final int BUFFER = CrafterProfession.TEMPLATE_SLOTS;

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

	/** Floor, input chest (8 oak planks), table, output chest and a Crafter golem with the sticks template (slots 0 and 3). */
	private static CopperGolem setup(GameTestHelper helper, boolean assignTable, ChestBlockEntity[] chests) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		BlockPos in = new BlockPos(1, 1, 3);
		BlockPos table = new BlockPos(3, 1, 6);
		BlockPos out = new BlockPos(6, 1, 3);
		helper.setBlock(in, Blocks.CHEST);
		helper.setBlock(table, Blocks.CRAFTING_TABLE);
		helper.setBlock(out, Blocks.CHEST);
		chests[0] = helper.getBlockEntity(in, ChestBlockEntity.class);
		chests[1] = helper.getBlockEntity(out, ChestBlockEntity.class);
		chests[0].setItem(0, new ItemStack(Items.OAK_PLANKS, 8));

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, CrafterProfession.ID);
		GolemData data = GolemData.get(golem);
		// slot i = grid row i / 3, column i % 3: slots 0 and 3 are a vertical pair = the sticks recipe
		data.items.set(0, new ItemStack(Items.OAK_PLANKS));
		data.items.set(3, new ItemStack(Items.OAK_PLANKS));
		data.assignments.put("input", helper.absolutePos(in));
		data.assignments.put("output", helper.absolutePos(out));
		if (assignTable) data.assignments.put("table", helper.absolutePos(table));
		GolemData.save(golem, data);
		return golem;
	}

	@GameTest(maxTicks = 1200)
	public void craftsSticksFromTemplate(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.STICK) == 16, "sticks in output: " + count(c[1], Items.STICK));
			helper.assertTrue(total(c[1]) == 16, "output holds other items: " + total(c[1]));
			helper.assertTrue(total(c[0]) == 0, "input still holds " + total(c[0]));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(d.items.get(0).is(Items.OAK_PLANKS) && d.items.get(0).getCount() == 1, "template 0: " + d.items.get(0));
			helper.assertTrue(d.items.get(3).is(Items.OAK_PLANKS) && d.items.get(3).getCount() == 1, "template 3: " + d.items.get(3));
			for (int i = 0; i < 9; i++) {
				if (i != 0 && i != 3) helper.assertTrue(d.items.get(i).isEmpty(), "template " + i + ": " + d.items.get(i));
				helper.assertTrue(d.items.get(BUFFER + i).isEmpty(), "buffer " + i + ": " + d.items.get(BUFFER + i));
			}
		});
	}

	@GameTest(maxTicks = 400)
	public void noCraftWithoutTable(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, false, c);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(total(c[1]) == 0, "output must stay empty, holds " + total(c[1]));
			int buffered = 0;
			GolemData d = GolemData.get(golem);
			for (int i = 0; i < 9; i++) {
				ItemStack s = d.items.get(BUFFER + i);
				if (s.is(Items.OAK_PLANKS)) buffered += s.getCount();
				else helper.assertTrue(s.isEmpty(), "unexpected buffer item " + s);
			}
			helper.assertTrue(count(c[0], Items.OAK_PLANKS) + buffered == 8, "planks not conserved: input "
				+ count(c[0], Items.OAK_PLANKS) + " + buffer " + buffered);
			helper.succeed();
		});
	}

	@GameTest
	public void templateDropsOnProfessionChange(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.runAfterDelay(2, () -> {
			AABB box = golem.getBoundingBox().inflate(4);
			List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
			int planks = 0;
			for (ItemEntity e : drops) if (e.getItem().is(Items.OAK_PLANKS)) planks += e.getItem().getCount();
			helper.assertTrue(planks == 2, "dropped template planks: " + planks);
			helper.assertTrue(GolemData.get(golem).items.isEmpty(), "items must be cleared");
			helper.succeed();
		});
	}

	@GameTest
	public void bufferDropsOnProfessionChange(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		GolemData data = GolemData.get(golem);
		data.items.set(BUFFER, new ItemStack(Items.OAK_PLANKS, 5));
		data.items.set(BUFFER + 1, new ItemStack(Items.STICK, 3));
		GolemData.save(golem, data);
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.runAfterDelay(2, () -> {
			AABB box = golem.getBoundingBox().inflate(4);
			List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
			int planks = 0;
			int sticks = 0;
			for (ItemEntity e : drops) {
				if (e.getItem().is(Items.OAK_PLANKS)) planks += e.getItem().getCount();
				if (e.getItem().is(Items.STICK)) sticks += e.getItem().getCount();
			}
			// 2 template planks + 5 buffered planks
			helper.assertTrue(planks == 7, "dropped planks: " + planks);
			helper.assertTrue(sticks == 3, "dropped sticks: " + sticks);
			helper.assertTrue(GolemData.get(golem).items.isEmpty(), "items must be cleared");
			helper.succeed();
		});
	}

	/** Valid recipe but the table block is gone: no crafting; the planks stay in the input chest / buffer (idle). */
	@GameTest(maxTicks = 400)
	public void noCraftWhenTableReplaced(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		helper.setBlock(new BlockPos(3, 1, 6), Blocks.STONE);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(total(c[1]) == 0, "output must stay empty, holds " + total(c[1]));
			int buffered = 0;
			GolemData d = GolemData.get(golem);
			for (int i = 0; i < 9; i++) {
				ItemStack s = d.items.get(BUFFER + i);
				if (s.is(Items.OAK_PLANKS)) buffered += s.getCount();
				else helper.assertTrue(s.isEmpty(), "unexpected buffer item " + s);
			}
			helper.assertTrue(count(c[0], Items.OAK_PLANKS) + buffered == 8, "planks not conserved: input "
				+ count(c[0], Items.OAK_PLANKS) + " + buffer " + buffered);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 600)
	public void deliversBufferWhenTemplateCleared(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		c[0].setItem(0, ItemStack.EMPTY);
		GolemData data = GolemData.get(golem);
		data.items.set(0, ItemStack.EMPTY);
		data.items.set(3, ItemStack.EMPTY);
		data.items.set(BUFFER, new ItemStack(Items.OAK_PLANKS, 5));
		data.items.set(BUFFER + 1, new ItemStack(Items.STICK, 3));
		GolemData.save(golem, data);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.OAK_PLANKS) == 5, "planks in output: " + count(c[1], Items.OAK_PLANKS) + " buf0=" + GolemData.get(golem).items.get(BUFFER) + " gpos=" + golem.blockPosition() + " prof=" + GolemData.get(golem).profession);
			helper.assertTrue(count(c[1], Items.STICK) == 3, "sticks in output: " + count(c[1], Items.STICK));
			GolemData d = GolemData.get(golem);
			for (int i = 0; i < 9; i++) helper.assertTrue(d.items.get(BUFFER + i).isEmpty(), "buffer " + i + ": " + d.items.get(BUFFER + i));
		});
	}

	@GameTest
	public void templateSlotHoldsOne(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, c);
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GolemMenu menu = new GolemMenu(1, player.getInventory(), golem);
		ItemStack planks = new ItemStack(Items.OAK_PLANKS, 64);
		Slot template = menu.slots.get(9); // row 1, column 0 = template slot 0
		Slot buffer = menu.slots.get(5 * 9); // row 5 = buffer
		helper.assertTrue(template.getMaxStackSize(planks) == 1, "template max stack " + template.getMaxStackSize(planks));
		helper.assertTrue(!menu.canTakeItemForPickAll(planks, template), "template must refuse PICKUP_ALL");
		helper.assertTrue(buffer.getMaxStackSize(planks) == 64, "buffer max stack " + buffer.getMaxStackSize(planks));
		helper.assertTrue(menu.canTakeItemForPickAll(planks, buffer), "buffer must allow PICKUP_ALL");
		helper.assertTrue(!menu.slots.get(4 * 9).getItem().isEmpty() && !menu.slots.get(4 * 9).mayPlace(planks), "row 4 must be a filler separator");
		helper.succeed();
	}
}
