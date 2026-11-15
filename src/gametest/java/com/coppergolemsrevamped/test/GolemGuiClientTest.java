package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenuOpener;
import com.coppergolemsrevamped.menu.Tab;

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
 * Opens the golem GUI (a server-side chest menu) in a real client and screenshots the tabs. Not part of
 * {@code check}: run it with {@code runClientGameTest}, inside a headless compositor only
 * (superdoc/architecture/testing.md).
 */
public class GolemGuiClientTest implements FabricClientGameTest {
	/** Clears toasts (e.g. "New Recipes Unlocked") so they do not cover the top-right cell, then screenshots. */
	private static void shot(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.waitTick();
		context.takeScreenshot(name);
	}

	/** Reopens the menu on {@code tab} for the first player (the golem keeps its current state). */
	private static void open(TestSingleplayerContext world, ClientGameTestContext context, CopperGolem golem, Tab tab, String shot) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.closeContainer();
			GolemMenuOpener.open(player, golem, tab);
		});
		context.waitTicks(20);
		shot(context, shot);
	}

	/** Switches the profession and lets {@code setup} fill the golem, then opens {@code tab}. */
	private static void scene(TestSingleplayerContext world, ClientGameTestContext context, CopperGolem golem, String profession,
		java.util.function.BiConsumer<ServerPlayer, GolemData> setup, Tab tab, String shot) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.closeContainer();
			GolemService.changeProfession(player.level(), golem, CopperGolemsRevamped.id(profession));
			GolemData data = GolemData.get(golem);
			setup.accept(player, data);
			GolemData.save(golem, data);
			GolemMenuOpener.open(player, golem, tab);
		});
		context.waitTicks(20);
		shot(context, shot);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> mc.options.guiScale().set(3));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("time set day");
			context.waitTicks(20);

			// no profession: opens on Status, then the Profession tab
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
			shot(context, "golem-gui-none-status");
			open(world, context, golem, Tab.PROFESSION, "golem-gui-none-profession");

			// transporter: items and one assignment on every tab; hover the Settings toggle for a tooltip
			scene(world, context, golem, "transporter", (player, data) -> {
				data.items.set(0, new ItemStack(Items.COBBLESTONE, 64));
				data.items.set(1, new ItemStack(Items.OAK_LOG, 32));
				data.assignments.put("input", player.blockPosition().offset(3, 0, 0));
			}, Tab.STATUS, "golem-gui-transporter-status");
			open(world, context, golem, Tab.TARGETS, "golem-gui-transporter-targets");
			open(world, context, golem, Tab.INVENTORY, "golem-gui-transporter-inventory");
			open(world, context, golem, Tab.SETTINGS, "golem-gui-transporter-settings");
			context.getInput().setCursorPos(589, 270);
			context.waitTicks(10);
			shot(context, "golem-gui-transporter-tooltip");

			// chest sorter: no slots, no targets
			scene(world, context, golem, "chest_sorter", (player, data) -> {}, Tab.STATUS, "golem-gui-chest-sorter");

			// cleaner with area and output assigned
			scene(world, context, golem, "cleaner", (player, data) -> {
				data.assignments.put("area_a", player.blockPosition().offset(-4, -1, -4));
				data.assignments.put("area_b", player.blockPosition().offset(4, -1, 4));
				data.assignments.put("output", player.blockPosition().offset(3, 0, 0));
			}, Tab.TARGETS, "golem-gui-cleaner-targets");

			// crafter: sticks recipe remembered (slots 0 and 3), planks in the first cell, empty output = ghost preview
			scene(world, context, golem, "crafter", (player, data) -> {
				data.items.set(0, new ItemStack(Items.OAK_PLANKS));
				data.items.set(3, new ItemStack(Items.OAK_PLANKS));
				data.items.set(9, new ItemStack(Items.OAK_PLANKS, 6));
				data.assignments.put("input", player.blockPosition().offset(3, 0, 0));
				data.assignments.put("table", player.blockPosition().offset(3, 0, 2));
			}, Tab.INVENTORY, "golem-gui-crafter-inventory");
			open(world, context, golem, Tab.RECIPE, "golem-gui-crafter-recipe");

			// farmer: hoe, wheat seeds, a few wheat in carry, every assignment set
			scene(world, context, golem, "farmer", (player, data) -> {
				data.items.set(0, new ItemStack(Items.IRON_HOE));
				data.items.set(1, new ItemStack(Items.WHEAT_SEEDS, 24));
				data.items.set(2, new ItemStack(Items.WHEAT, 7));
				data.items.set(3, new ItemStack(Items.WHEAT_SEEDS, 3));
				data.assignments.put("field_a", player.blockPosition().offset(-4, -1, -4));
				data.assignments.put("field_b", player.blockPosition().offset(4, -1, 4));
				data.assignments.put("tools", player.blockPosition().offset(3, 0, 0));
				data.assignments.put("seeds", player.blockPosition().offset(3, 0, 1));
				data.assignments.put("output", player.blockPosition().offset(3, 0, 2));
			}, Tab.INVENTORY, "golem-gui-farmer-inventory");

			// lumber: axe, 16 oak saplings, some logs in carry
			scene(world, context, golem, "lumber", (player, data) -> {
				data.items.set(0, new ItemStack(Items.IRON_AXE));
				data.items.set(1, new ItemStack(Items.OAK_SAPLING, 16));
				data.items.set(2, new ItemStack(Items.BONE_MEAL, 8));
				data.items.set(3, new ItemStack(Items.OAK_LOG, 12));
				data.items.set(4, new ItemStack(Items.OAK_LOG, 5));
				data.assignments.put("tree", player.blockPosition().offset(-3, 0, 0));
			}, Tab.INVENTORY, "golem-gui-lumber-inventory");
			open(world, context, golem, Tab.STATUS, "golem-gui-lumber-status");
		}
	}
}
