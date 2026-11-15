package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenuOpener;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Opens the golem GUI (a server-side chest menu) in a real client and screenshots it. Not part of
 * {@code check}: run it with {@code runClientGameTest}, inside a headless compositor only
 * (superdoc/architecture/testing.md).
 */
public class GolemGuiClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.guiScale().set(3));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("time set day");
			context.waitTicks(20);

			// (a) no profession
			CopperGolem golem = world.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				CopperGolem g = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.COMMAND);
				g.setPos(player.getX() + 2, player.getY(), player.getZ());
				level.addFreshEntity(g);
				GolemMenuOpener.open(player, g);
				return g;
			});
			context.waitForScreen(ContainerScreen.class);
			context.waitTicks(20);
			context.takeScreenshot("golem-gui-no-profession");

			// (b) transporter with items and one assignment
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.closeContainer();
				GolemService.changeProfession(player.level(), golem, CopperGolemsRevamped.id("transporter"));
				GolemData data = GolemData.get(golem);
				data.items.set(0, new ItemStack(Items.COBBLESTONE, 64));
				data.items.set(1, new ItemStack(Items.OAK_LOG, 32));
				data.assignments.put("input", player.blockPosition().offset(3, 0, 0));
				GolemData.save(golem, data);
				GolemMenuOpener.open(player, golem);
			});
			context.waitTicks(20);
			context.takeScreenshot("golem-gui-transporter");

			// (c) hover the first control slot to show its tooltip (slot centre at GUI scale 3, 1280x720)
			context.getInput().setCursorPos(422, 212);
			context.waitTicks(10);
			context.takeScreenshot("golem-gui-transporter-tooltip");
		}
	}
}
