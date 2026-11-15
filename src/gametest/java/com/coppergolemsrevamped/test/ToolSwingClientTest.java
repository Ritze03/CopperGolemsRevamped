package com.coppergolemsrevamped.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Screenshots a golem holding an axe at rest and in the tool-use pose (headless sway only, see testing.md). */
public class ToolSwingClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("time set day");
			context.waitTicks(20);
			CopperGolem golem = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				CopperGolem g = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.COMMAND);
				g.setPos(player.getX() + 1.8, player.getY(), player.getZ());
				g.setYRot(0);
				g.setYBodyRot(0);
				g.setYHeadRot(0);
				g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
				level.addFreshEntity(g);
				return g;
			});
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.connection.teleport(player.getX(), player.getY(), player.getZ(), -90, 20);
			});
			context.waitTicks(30);
			context.takeScreenshot("swing-idle");
			// the real Lumber loop on a 3-log tree: screenshots every 2 ticks while it chops
			CopperGolem lumber = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos base = player.blockPosition();
				BlockPos tree = base.offset(3, 0, 3);
				for (int k = 0; k < 3; k++) level.setBlockAndUpdate(tree.above(k), Blocks.OAK_LOG.defaultBlockState());
				level.setBlockAndUpdate(base.offset(0, 0, 6), Blocks.CHEST.defaultBlockState());
				level.setBlockAndUpdate(base.offset(1, 0, 6), Blocks.CHEST.defaultBlockState());
				golem.discard();
				CopperGolem g = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.COMMAND);
				g.setPos(base.getX() + 1.5, base.getY(), base.getZ() + 3.5);
				level.addFreshEntity(g);
				GolemService.changeProfession(level, g, CopperGolemsRevamped.id("lumber"));
				GolemData d = GolemData.get(g);
				d.items.set(0, new ItemStack(Items.IRON_AXE));
				d.assignments.put("tree", tree);
				d.assignments.put("input", base.offset(0, 0, 6));
				d.assignments.put("output", base.offset(1, 0, 6));
				GolemData.save(g, d);
				player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() - 1.5, -35, 15);
				return g;
			});
			context.waitTicks(10);
			for (int t = 0; t < 40; t++) {
				context.waitTicks(2);
				context.takeScreenshot("chop-" + String.format("%02d", t));
			}
		}
	}
}
