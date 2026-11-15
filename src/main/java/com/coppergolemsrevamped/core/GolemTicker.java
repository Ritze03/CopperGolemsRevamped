package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.ai.FollowMode;
import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.profession.Profession;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class GolemTicker {
	private GolemTicker() {}

	/** Called in place of the vanilla brain tick; runs {@code vanillaBrainTick} only when the vanilla brain should run. */
	public static void tick(ServerLevel level, CopperGolem golem, Runnable vanillaBrainTick) {
		GolemData data = GolemData.get(golem);
		if (WalkTo.tick(level, golem)) return;
		if (data.following) {
			FollowMode.tick(level, golem, data);
			return;
		}
		Profession p = GolemService.profession(golem);
		if (p == null) {
			// No profession: stand still. Why: the vanilla brain wanders and moves items on its own.
			golem.getNavigation().stop();
		} else if (p.runsVanillaBrain()) {
			vanillaBrainTick.run();
		} else {
			p.tick(level, golem, data);
		}
	}
}
