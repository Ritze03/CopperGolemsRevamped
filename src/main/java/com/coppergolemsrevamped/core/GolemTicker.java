package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.ai.FollowMode;
import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class GolemTicker {
	private GolemTicker() {}

	/** Called in place of the vanilla brain tick; runs {@code vanillaBrainTick} only when the vanilla brain should run. */
	public static void tick(ServerLevel level, CopperGolem golem, Runnable vanillaBrainTick) {
		GolemData data = GolemData.get(golem);
		Profession p = GolemService.profession(golem);
		if (p != null) GolemChunkLoader.keep(level, golem);
		else GolemChunkLoader.release(level, golem);
		// old saves kept waxed copper in the sorter's slot; a no-op early return unless a sorter still holds items
		if (p != null && ChestSorterProfession.ID.equals(p.id())) ChestSorterProfession.migrateLegacyItems(level, golem);
		if (golem.tickCount % GolemHandDisplay.PERIOD == 0) GolemHandDisplay.sync(golem);
		if (data.frozen) {
			// Freeze: no profession tick, no vanilla brain, no follow, no WalkTo; phase and WalkTo state stay for the unfreeze.
			golem.getNavigation().stop();
			Interact.closeChest(golem);
			return;
		}
		if (WalkTo.tick(level, golem)) return;
		if (data.following) {
			FollowMode.tick(level, golem, data);
			return;
		}
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
