package com.coppergolemsrevamped.menu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class GolemMenuOpener {
	private GolemMenuOpener() {}

	/** Opens (or reopens, closing the current menu first) the golem menu; snapshots the opening data now. */
	public static void open(ServerPlayer player, CopperGolem golem) {
		GolemMenuData data = GolemMenuData.of(golem);
		player.openMenu(new ExtendedMenuProvider<GolemMenuData>() {
			@Override
			public GolemMenuData getScreenOpeningData(ServerPlayer p) {
				return data;
			}

			@Override
			public Component getDisplayName() {
				return Component.literal("Copper Golem");
			}

			@Override
			public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
				return GolemMenu.server(id, inv, golem, data);
			}
		});
	}
}
