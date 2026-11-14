package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.CopperGolemsRevamped;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModMenus {
	private ModMenus() {}

	public static final ExtendedMenuType<GolemMenu, GolemMenuData> GOLEM = Registry.register(
		BuiltInRegistries.MENU, CopperGolemsRevamped.id("golem"),
		new ExtendedMenuType<>(GolemMenu::new, GolemMenuData.STREAM_CODEC));

	/** Touching this class registers the menu type. */
	public static void init() {}
}
