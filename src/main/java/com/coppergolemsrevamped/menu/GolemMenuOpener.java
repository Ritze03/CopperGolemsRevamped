package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class GolemMenuOpener {
	private GolemMenuOpener() {}

	/** Opens (or reopens, closing the current menu first) the golem menu; the layout is read from the golem now. */
	public static void open(ServerPlayer player, CopperGolem golem) {
		Profession p = GolemService.profession(golem);
		Component title = p == null ? Component.literal("Copper Golem")
			: Component.literal("Copper Golem — ").append(p.name());
		player.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GolemMenu(id, inv, golem), title));
	}
}
