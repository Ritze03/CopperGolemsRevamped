package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public class TransporterTest {
	private static final int STACKS_PER_TYPE = 6;

	/**
	 * 12 full stacks (two item types) in chest A, more than one 9-slot carry, so the golem must make
	 * several trips. The default empty structure is 8x8x8 air: floor at y=0, chests at y=1, x=1 and x=6.
	 */
	@GameTest(maxTicks = 2400)
	public void transporterMovesEverything(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity chestB = helper.getBlockEntity(b, ChestBlockEntity.class);
		for (int i = 0; i < STACKS_PER_TYPE; i++) {
			chestA.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			chestA.setItem(STACKS_PER_TYPE + i, new ItemStack(Items.OAK_LOG, 64));
		}

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", helper.absolutePos(b));
		GolemData.save(golem, data);

		helper.succeedWhen(() -> {
			int inA = 0;
			for (int i = 0; i < chestA.getContainerSize(); i++) inA += chestA.getItem(i).getCount();
			helper.assertTrue(inA == 0, "chest A still holds " + inA + " items");
			int stone = 0;
			int logs = 0;
			for (int i = 0; i < chestB.getContainerSize(); i++) {
				ItemStack s = chestB.getItem(i);
				if (s.is(Items.COBBLESTONE)) stone += s.getCount();
				else if (s.is(Items.OAK_LOG)) logs += s.getCount();
				else helper.assertTrue(s.isEmpty(), "unexpected item in chest B: " + s);
			}
			helper.assertTrue(stone == STACKS_PER_TYPE * 64, "cobblestone in B: " + stone);
			helper.assertTrue(logs == STACKS_PER_TYPE * 64, "oak logs in B: " + logs);
		});
	}
}
