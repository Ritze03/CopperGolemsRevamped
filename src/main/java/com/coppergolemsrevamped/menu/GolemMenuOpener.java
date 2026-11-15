package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class GolemMenuOpener {
	private GolemMenuOpener() {}

	/** Opens the golem menu on the Status tab. */
	public static void open(ServerPlayer player, CopperGolem golem) {
		open(player, golem, Tab.STATUS);
	}

	/** Opens (or reopens, closing the current menu first) the golem menu on {@code tab}; the profession is read now. */
	public static void open(ServerPlayer player, CopperGolem golem, Tab tab) {
		Profession p = GolemService.profession(golem);
		Component title = Component.literal("Golem — ").append(p == null ? Component.literal("None") : p.name());
		player.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GolemMenu(id, inv, golem, tab), title));
	}
}
