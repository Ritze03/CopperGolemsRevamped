package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class GolemDataTest {
	/** Full entity save, then load into a fresh unspawned golem: proves the attachment is persistent. */
	@GameTest
	public void golemDataSurvivesSaveLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, golem, CopperGolemsRevamped.id("transporter"));
		UUID player = UUID.randomUUID();
		BlockPos input = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos output = helper.absolutePos(new BlockPos(5, 1, 6));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", input);
		data.assignments.put("output", output);
		data.items.set(0, new ItemStack(Items.COBBLESTONE, 64));
		data.items.set(4, new ItemStack(Items.DIAMOND, 3));
		data.following = true;
		data.followPlayer = player;
		data.spawnChest = input;
		data.autoName = "Transporter";
		GolemData.save(golem, data);

		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		golem.saveWithoutId(out);
		CompoundTag tag = out.buildResult();

		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		helper.assertTrue(copy != null, "could not create a golem to load into");
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));

		GolemData d = GolemData.get(copy);
		helper.assertTrue(CopperGolemsRevamped.id("transporter").equals(d.profession), "profession: " + d.profession);
		helper.assertTrue(d.assignments.size() == 2, "assignments: " + d.assignments);
		helper.assertTrue(input.equals(d.assignments.get("input")), "input: " + d.assignments.get("input"));
		helper.assertTrue(output.equals(d.assignments.get("output")), "output: " + d.assignments.get("output"));
		helper.assertTrue(d.items.size() == 18, "items size: " + d.items.size());
		helper.assertTrue(ItemStack.matches(d.items.get(0), new ItemStack(Items.COBBLESTONE, 64)), "slot 0: " + d.items.get(0));
		helper.assertTrue(ItemStack.matches(d.items.get(4), new ItemStack(Items.DIAMOND, 3)), "slot 4: " + d.items.get(4));
		helper.assertTrue(input.equals(d.spawnChest), "spawnChest: " + d.spawnChest);
		helper.assertTrue("Transporter".equals(d.autoName), "autoName: " + d.autoName);
		helper.assertTrue(d.following, "following lost");
		helper.assertTrue(player.equals(d.followPlayer), "followPlayer: " + d.followPlayer);
		helper.succeed();
	}

	@GameTest
	public void assignAreaHelpers(GameTestHelper helper) {
		helper.assertTrue(AssignMode.areaFits(new BlockPos(0, 0, 0), new BlockPos(4, 9, 4), 5), "5x5 must fit 5");
		helper.assertTrue(!AssignMode.areaFits(new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), 5), "6 wide must not fit 5");
		helper.assertTrue(AssignMode.inRange(new Vec3(0.5, 0, 0.5), new BlockPos(31, 0, 0)), "31 blocks is in range");
		helper.assertTrue(!AssignMode.inRange(new Vec3(0.5, 0, 0.5), new BlockPos(40, 0, 0)), "40 blocks is out of range");
		helper.succeed();
	}
}
