package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;

/** Ways a golem used to leave its workplace silently; see superdoc/features/golem-safety.md. */
public class GolemSafetyTest {
	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** Follow with nobody around turns itself off after 600 ticks, and not before. */
	@GameTest(maxTicks = 800)
	public void followTurnsOffWhenPlayerAbsent(GameTestHelper helper) {
		floor(helper);
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, ChestSorterProfession.ID);
		GolemData d = GolemData.get(golem);
		d.following = true;
		d.followPlayer = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
		GolemData.save(golem, d);
		helper.runAfterDelay(300, () -> helper.assertTrue(GolemData.get(golem).following, "follow ended early"));
		helper.succeedWhen(() -> helper.assertTrue(!GolemData.get(golem).following, "still following"));
	}

	/** A working golem that a vehicle picked up climbs out. */
	@GameTest(maxTicks = 100)
	public void workingGolemDismounts(GameTestHelper helper) {
		floor(helper);
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, ChestSorterProfession.ID);
		Minecart cart = helper.spawn(EntityTypes.MINECART, new BlockPos(2, 1, 2));
		helper.assertTrue(golem.startRiding(cart, true, false), "could not mount");
		helper.succeedWhen(() -> helper.assertTrue(!golem.isPassenger(), "still a passenger"));
	}

	/** A golem inside a solid block (a growing tree, melon, pumpkin) is moved to free ground next to it. */
	@GameTest(maxTicks = 100)
	public void golemInsideBlockStepsOut(GameTestHelper helper) {
		floor(helper);
		BlockPos block = new BlockPos(3, 1, 3);
		helper.setBlock(block, Blocks.STONE);
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, block);
		helper.assertTrue(golem.isInWall(), "setup: golem is not inside the block");
		helper.succeedWhen(() -> {
			helper.assertTrue(!golem.isInWall(), "still inside the block");
			helper.assertTrue(!golem.blockPosition().equals(helper.absolutePos(block)), "not moved");
		});
	}
}
