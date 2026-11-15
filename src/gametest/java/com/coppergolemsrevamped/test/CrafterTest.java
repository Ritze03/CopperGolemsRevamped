package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.crafter.CrafterProfession;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

public class CrafterTest {
	private static final int IN = CrafterProfession.FIRST_INPUT;
	private static final int OUT = CrafterProfession.OUTPUT_SLOT;
	private static final BlockPos IN_POS = new BlockPos(1, 1, 3);
	private static final BlockPos TABLE_POS = new BlockPos(3, 1, 6);
	private static final BlockPos OUT_POS = new BlockPos(6, 1, 3);

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

	/** Count of {@code item} in the golem's input slots and output slot. */
	private static int held(GolemData d, Item item) {
		int n = 0;
		for (int i = IN; i <= OUT; i++) if (d.items.get(i).is(item)) n += d.items.get(i).getCount();
		return n;
	}

	private static int heldTotal(GolemData d) {
		int n = 0;
		for (int i = IN; i <= OUT; i++) n += d.items.get(i).getCount();
		return n;
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** Floor, chests, table and a Crafter golem (no recipe yet) with all three assignments (table optional). */
	private static CopperGolem base(GameTestHelper helper, boolean assignTable, ChestBlockEntity[] chests) {
		floor(helper);
		helper.setBlock(IN_POS, Blocks.CHEST);
		helper.setBlock(TABLE_POS, Blocks.CRAFTING_TABLE);
		helper.setBlock(OUT_POS, Blocks.CHEST);
		chests[0] = helper.getBlockEntity(IN_POS, ChestBlockEntity.class);
		chests[1] = helper.getBlockEntity(OUT_POS, ChestBlockEntity.class);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, CrafterProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(IN_POS));
		data.assignments.put("output", helper.absolutePos(OUT_POS));
		if (assignTable) data.assignments.put("table", helper.absolutePos(TABLE_POS));
		GolemData.save(golem, data);
		return golem;
	}

	/** {@link #base} with the sticks template (cells 0 and 3 = a vertical pair of oak planks) and {@code planks} planks in the input chest. */
	private static CopperGolem setup(GameTestHelper helper, boolean assignTable, int planks, ChestBlockEntity[] chests) {
		CopperGolem golem = base(helper, assignTable, chests);
		chests[0].setItem(0, new ItemStack(Items.OAK_PLANKS, planks));
		GolemData data = GolemData.get(golem);
		data.items.set(0, new ItemStack(Items.OAK_PLANKS));
		data.items.set(3, new ItemStack(Items.OAK_PLANKS));
		GolemData.save(golem, data);
		return golem;
	}

	/** 40 planks = 2 planks per craft, 4 sticks per craft: 32 planks fill the 64-stick output once, 8 planks make 16 more. */
	@GameTest(maxTicks = 1600)
	public void craftsSticksUntilOutputFull(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 40, c);
		boolean[] sawFull = new boolean[1];
		helper.onEachTick(() -> {
			ItemStack out = GolemData.get(golem).items.get(OUT);
			if (out.is(Items.STICK) && out.getCount() == 64) sawFull[0] = true;
			helper.assertTrue(out.isEmpty() || out.is(Items.STICK), "output slot holds " + out);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.STICK) == 80, "sticks in output chest: " + count(c[1], Items.STICK));
			helper.assertTrue(total(c[1]) == 80, "output chest holds other items: " + total(c[1]));
			helper.assertTrue(total(c[0]) == 0, "input chest still holds " + total(c[0]));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(heldTotal(d) == 0, "golem still holds " + heldTotal(d));
			helper.assertTrue(sawFull[0], "the output stack never reached 64 before delivery");
			for (int i = 0; i < 9; i++) {
				boolean plank = i == 0 || i == 3;
				helper.assertTrue(plank ? d.items.get(i).is(Items.OAK_PLANKS) && d.items.get(i).getCount() == 1 : d.items.get(i).isEmpty(),
					"template " + i + ": " + d.items.get(i));
			}
		});
	}

	/**
	 * Repeater = row 0 torch, redstone, torch; row 1 stone x3 (cells 0..5). The input slots must hold exactly the
	 * per-cell item (6 slots, cells 6..8 stay empty) and the repeaters end up in the output chest.
	 */
	@GameTest(maxTicks = 1200)
	public void craftsRepeaterWithPerCellSlots(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = base(helper, true, c);
		c[0].setItem(0, new ItemStack(Items.STONE, 12));
		c[0].setItem(1, new ItemStack(Items.REDSTONE_TORCH, 8));
		c[0].setItem(2, new ItemStack(Items.REDSTONE, 4));
		GolemData data = GolemData.get(golem);
		Item[] cells = {Items.REDSTONE_TORCH, Items.REDSTONE, Items.REDSTONE_TORCH, Items.STONE, Items.STONE, Items.STONE};
		for (int i = 0; i < 6; i++) data.items.set(i, new ItemStack(cells[i]));
		GolemData.save(golem, data);
		boolean[] sawFilled = new boolean[1];
		helper.onEachTick(() -> {
			GolemData d = GolemData.get(golem);
			boolean all = true;
			for (int i = 0; i < 9; i++) {
				ItemStack s = d.items.get(IN + i);
				boolean ok = s.isEmpty() || (i < 6 && s.is(cells[i]));
				helper.assertTrue(ok, "input slot " + i + " holds " + s);
				if (i < 6 && s.getCount() < 4) all = false;
			}
			if (all) sawFilled[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.REPEATER) == 4, "repeaters in output: " + count(c[1], Items.REPEATER));
			helper.assertTrue(total(c[1]) == 4, "output chest holds other items: " + total(c[1]));
			helper.assertTrue(total(c[0]) == 0, "input chest still holds " + total(c[0]));
			helper.assertTrue(heldTotal(GolemData.get(golem)) == 0, "golem still holds " + heldTotal(GolemData.get(golem)));
			helper.assertTrue(sawFilled[0], "input slots never held 4 of each cell item");
		});
	}

	/** Output chest full: the golem keeps the product (no loss, no freeze), and delivers once space appears. */
	@GameTest(maxTicks = 1200)
	public void fullOutputChestKeepsProductAndResumes(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 8, c);
		for (int i = 0; i < c[1].getContainerSize(); i++) c[1].setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		helper.runAfterDelay(400, () -> {
			GolemData d = GolemData.get(golem);
			helper.assertTrue(held(d, Items.STICK) == 16, "sticks held: " + held(d, Items.STICK));
			helper.assertTrue(count(c[1], Items.STICK) == 0, "sticks in a full chest: " + count(c[1], Items.STICK));
			c[1].setItem(5, ItemStack.EMPTY);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.STICK) == 16, "sticks in output chest: " + count(c[1], Items.STICK));
			helper.assertTrue(heldTotal(GolemData.get(golem)) == 0, "golem still holds " + heldTotal(GolemData.get(golem)));
		});
	}

	@GameTest(maxTicks = 400)
	public void noCraftWithoutTable(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, false, 8, c);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(total(c[1]) == 0, "output must stay empty, holds " + total(c[1]));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(held(d, Items.STICK) == 0, "crafted sticks: " + held(d, Items.STICK));
			helper.assertTrue(count(c[0], Items.OAK_PLANKS) + held(d, Items.OAK_PLANKS) == 8, "planks not conserved: input "
				+ count(c[0], Items.OAK_PLANKS) + " + held " + held(d, Items.OAK_PLANKS));
			helper.succeed();
		});
	}

	/** Valid recipe but the table block is gone: no crafting, planks conserved. */
	@GameTest(maxTicks = 400)
	public void noCraftWhenTableReplaced(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 8, c);
		helper.setBlock(TABLE_POS, Blocks.STONE);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(total(c[1]) == 0, "output must stay empty, holds " + total(c[1]));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(held(d, Items.STICK) == 0, "crafted sticks: " + held(d, Items.STICK));
			helper.assertTrue(count(c[0], Items.OAK_PLANKS) + held(d, Items.OAK_PLANKS) == 8, "planks not conserved");
			helper.succeed();
		});
	}

	/** Empty template (no recipe): input slots and the output slot all go to the output chest. */
	@GameTest(maxTicks = 600)
	public void deliversEverythingWhenTemplateCleared(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = base(helper, true, c);
		GolemData data = GolemData.get(golem);
		data.items.set(IN, new ItemStack(Items.OAK_PLANKS, 5));
		data.items.set(IN + 1, new ItemStack(Items.STICK, 3));
		data.items.set(OUT, new ItemStack(Items.STICK, 6));
		GolemData.save(golem, data);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.OAK_PLANKS) == 5, "planks in output: " + count(c[1], Items.OAK_PLANKS));
			helper.assertTrue(count(c[1], Items.STICK) == 9, "sticks in output: " + count(c[1], Items.STICK));
			helper.assertTrue(heldTotal(GolemData.get(golem)) == 0, "golem still holds " + heldTotal(GolemData.get(golem)));
		});
	}

	/** Items in input slots that no longer match their template cell are delivered; the matching one stays; nothing is lost. */
	@GameTest(maxTicks = 600)
	public void staleInputItemsAreDelivered(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 0, c);
		GolemData data = GolemData.get(golem);
		data.items.set(IN, new ItemStack(Items.OAK_PLANKS, 5));       // cell 0 = planks: fine
		data.items.set(IN + 1, new ItemStack(Items.COBBLESTONE, 3));  // cell 1 empty: stale
		data.items.set(IN + 3, new ItemStack(Items.STONE, 2));        // cell 3 = planks: stale
		GolemData.save(golem, data);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.COBBLESTONE) == 3, "cobblestone in output: " + count(c[1], Items.COBBLESTONE));
			helper.assertTrue(count(c[1], Items.STONE) == 2, "stone in output: " + count(c[1], Items.STONE));
			GolemData d = GolemData.get(golem);
			helper.assertTrue(d.items.get(IN).is(Items.OAK_PLANKS) && d.items.get(IN).getCount() == 5, "slot 0: " + d.items.get(IN));
			helper.assertTrue(heldTotal(d) == 5, "golem holds " + heldTotal(d));
			helper.assertTrue(total(c[1]) == 5, "output chest holds " + total(c[1]));
		});
	}

	/**
	 * Lids open at the input and output chest, the golem stands within 1 block of each chest while its lid is open
	 * and of the table whenever the output stack grew, and every lid is closed at the end.
	 */
	@GameTest(maxTicks = 1200)
	public void lidsOpenAndReachIsOneBlock(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 8, c);
		BlockPos in = helper.absolutePos(IN_POS);
		BlockPos out = helper.absolutePos(OUT_POS);
		BlockPos table = helper.absolutePos(TABLE_POS);
		boolean[] seen = new boolean[3];
		int[] last = new int[1];
		helper.onEachTick(() -> {
			if (ChestBlockEntity.getOpenCount(helper.getLevel(), in) > 0) {
				seen[0] = true;
				helper.assertTrue(Interact.inReach(golem, in), "golem at " + golem.blockPosition() + " not 1 block from the input chest");
			}
			if (ChestBlockEntity.getOpenCount(helper.getLevel(), out) > 0) {
				seen[1] = true;
				helper.assertTrue(Interact.inReach(golem, out), "golem at " + golem.blockPosition() + " not 1 block from the output chest");
			}
			int now = GolemData.get(golem).items.get(OUT).getCount();
			if (now > last[0]) {
				seen[2] = true;
				helper.assertTrue(Interact.inReach(golem, table), "golem at " + golem.blockPosition() + " crafted farther than 1 block from the table");
			}
			last[0] = now;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(c[1], Items.STICK) == 16, "sticks in output chest: " + count(c[1], Items.STICK));
			helper.assertTrue(seen[0] && seen[1] && seen[2], "seen in-lid " + seen[0] + " out-lid " + seen[1] + " crafted " + seen[2]);
			helper.assertTrue(ChestBlockEntity.getOpenCount(helper.getLevel(), in) == 0
				&& ChestBlockEntity.getOpenCount(helper.getLevel(), out) == 0, "lid still open");
		});
	}

	/** Rows 4; template cols 0..2, input cols 4..6 (mirroring the template), output col 8 row 2, filler elsewhere. */
	@GameTest
	public void guiLayoutIsCraftingTableLike(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 0, c);
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GolemMenu menu = new GolemMenu(1, player.getInventory(), golem);
		int slotRows = Professions.get(CrafterProfession.ID).slotRows();
		helper.assertTrue(slotRows == 3, "slotRows " + slotRows);
		helper.assertTrue(menu.slots.size() == 4 * 9 + 36, "slots " + menu.slots.size());
		ItemStack planks = new ItemStack(Items.OAK_PLANKS, 64);
		boolean[] special = new boolean[4 * 9];
		for (int i = 0; i < 9; i++) {
			int row = 1 + i / 3;
			int t = row * 9 + i % 3;
			int in = row * 9 + 4 + i % 3;
			special[t] = true;
			special[in] = true;
			Slot template = menu.slots.get(t);
			Slot input = menu.slots.get(in);
			helper.assertTrue(template.getMaxStackSize(planks) == 1, "template " + i + " max stack " + template.getMaxStackSize(planks));
			helper.assertTrue(!menu.canTakeItemForPickAll(planks, template), "template " + i + " must refuse PICKUP_ALL");
			helper.assertTrue(template.getContainerSlot() == i, "template " + i + " container slot " + template.getContainerSlot());
			helper.assertTrue(input.getContainerSlot() == IN + i, "input " + i + " container slot " + input.getContainerSlot());
			helper.assertTrue(input.getMaxStackSize(planks) == 64, "input " + i + " max stack " + input.getMaxStackSize(planks));
			helper.assertTrue(menu.canTakeItemForPickAll(planks, input), "input " + i + " must allow PICKUP_ALL");
			// cells 0 and 3 are planks in the template
			boolean plank = i == 0 || i == 3;
			helper.assertTrue(input.mayPlace(planks) == plank, "input " + i + " mayPlace planks = " + input.mayPlace(planks));
			helper.assertTrue(!input.mayPlace(new ItemStack(Items.STICK)), "input " + i + " must refuse sticks");
		}
		Slot output = menu.slots.get(2 * 9 + 8);
		special[2 * 9 + 8] = true;
		helper.assertTrue(output.getContainerSlot() == OUT, "output container slot " + output.getContainerSlot());
		helper.assertTrue(!output.mayPlace(planks), "players must not place into the output");
		for (int cell = 9; cell < 4 * 9; cell++) {
			if (special[cell]) continue;
			Slot s = menu.slots.get(cell);
			helper.assertTrue(s.getItem().is(Items.STAINED_GLASS_PANE.orange()) && !s.mayPlace(planks), "cell " + cell + " must be filler");
		}
		helper.succeed();
	}

	/** Shift-click routes planks into the input slot of a plank cell, never into the 1-stack template. */
	@GameTest
	public void shiftClickFillsInputNotTemplate(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 0, c);
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GolemMenu menu = new GolemMenu(1, player.getInventory(), golem);
		player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 64)); // first main-inventory slot = menu slot 36
		menu.quickMoveStack(player, 4 * 9);
		GolemData d = GolemData.get(golem);
		helper.assertTrue(d.items.get(IN).is(Items.OAK_PLANKS) && d.items.get(IN).getCount() == 64, "input 0: " + d.items.get(IN));
		helper.assertTrue(d.items.get(0).getCount() == 1 && d.items.get(1).isEmpty(), "template changed: " + d.items.get(1));
		helper.succeed();
	}

	private static int dropped(GameTestHelper helper, CopperGolem golem, Item item) {
		AABB box = golem.getBoundingBox().inflate(4);
		List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
		int n = 0;
		for (ItemEntity e : drops) if (e.getItem().is(item)) n += e.getItem().getCount();
		return n;
	}

	/** Without a player the template, input and output slots all drop at the golem and the stored list is cleared. */
	@GameTest
	public void itemsDropOnProfessionChange(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = setup(helper, true, 0, c);
		GolemData data = GolemData.get(golem);
		data.items.set(IN, new ItemStack(Items.OAK_PLANKS, 5));
		data.items.set(IN + 1, new ItemStack(Items.COBBLESTONE, 2));
		data.items.set(OUT, new ItemStack(Items.STICK, 3));
		GolemData.save(golem, data);
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.runAfterDelay(2, () -> {
			// 2 template planks + 5 input planks
			helper.assertTrue(dropped(helper, golem, Items.OAK_PLANKS) == 7, "dropped planks: " + dropped(helper, golem, Items.OAK_PLANKS));
			helper.assertTrue(dropped(helper, golem, Items.COBBLESTONE) == 2, "dropped cobblestone: " + dropped(helper, golem, Items.COBBLESTONE));
			helper.assertTrue(dropped(helper, golem, Items.STICK) == 3, "dropped sticks: " + dropped(helper, golem, Items.STICK));
			helper.assertTrue(GolemData.get(golem).items.isEmpty(), "items must be cleared");
			helper.succeed();
		});
	}
}
