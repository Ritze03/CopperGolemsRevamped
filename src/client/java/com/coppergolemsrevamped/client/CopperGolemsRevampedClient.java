package com.coppergolemsrevamped.client;

import com.coppergolemsrevamped.menu.ModMenus;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.client.gui.screens.MenuScreens;

public class CopperGolemsRevampedClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModMenus.GOLEM, GolemScreen::new);
	}
}
