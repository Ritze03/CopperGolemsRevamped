package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.ai.FollowMode;
import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterProfession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class GolemTicker {
	private static final Logger LOGGER = LoggerFactory.getLogger("coppergolemsrevamped");
	/** Ticks a failing golem's profession tick is skipped before it is retried. */
	static final int COOLDOWN_TICKS = 200;
	/** Minimum ticks between two log lines for the same golem (a golem that keeps failing logs about once a minute). */
	private static final int LOG_INTERVAL = 1200;

	/**
	 * Tests set this so a throwing profession fails the test instead of being swallowed. Why: the safety net exists
	 * for live servers; in GameTests it would hide real bugs.
	 */
	public static volatile boolean rethrow = false;

	/** Golem UUID -> game time until which its profession tick is skipped. */
	private static final Map<UUID, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_LOG = new ConcurrentHashMap<>();

	private GolemTicker() {}

	/** True while the golem is backing off after a failed tick (test hook). */
	public static boolean coolingDown(CopperGolem golem) {
		Long until = COOLDOWN_UNTIL.get(golem.getUUID());
		return until != null && golem.level().getGameTime() < until;
	}

	/**
	 * Safety net: a single golem must never take down the server (user-visible crash on 2026-11-15, an array index error
	 * in the Lumber code). Any RuntimeException from the mod's per-golem work is logged (rate-limited) and the golem's
	 * profession tick is skipped for {@link #COOLDOWN_TICKS}, then retried.
	 */
	public static void tick(ServerLevel level, CopperGolem golem, Runnable vanillaBrainTick) {
		long now = level.getGameTime();
		Long until = COOLDOWN_UNTIL.get(golem.getUUID());
		if (until != null) {
			if (now < until) return;
			COOLDOWN_UNTIL.remove(golem.getUUID());
		}
		try {
			tickUnsafe(level, golem, vanillaBrainTick);
		} catch (RuntimeException e) {
			if (rethrow) throw e;
			UUID id = golem.getUUID();
			COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
			Long last = LAST_LOG.get(id);
			if (last == null || now - last >= LOG_INTERVAL) {
				LAST_LOG.put(id, now);
				Profession p = GolemService.profession(golem);
				LOGGER.error("Copper golem {} (profession {}) failed, pausing it for {} ticks", id, p == null ? "none" : p.id(), COOLDOWN_TICKS, e);
			}
			try {
				golem.getNavigation().stop();
			} catch (RuntimeException ignored) {}
		}
	}

	/** Called in place of the vanilla brain tick; runs {@code vanillaBrainTick} only when the vanilla brain should run. */
	private static void tickUnsafe(ServerLevel level, CopperGolem golem, Runnable vanillaBrainTick) {
		GolemData data = GolemData.get(golem);
		Profession p = GolemService.profession(golem);
		if (p != null) GolemChunkLoader.keep(level, golem);
		else GolemChunkLoader.release(level, golem);
		// old saves kept waxed copper in the sorter's slot; a no-op early return unless a sorter still holds items
		if (p != null && ChestSorterProfession.ID.equals(p.id())) ChestSorterProfession.migrateLegacyItems(level, golem);
		if (p != null && TransporterProfession.ID.equals(p.id())) TransporterProfession.migrate(data); // old saves lack the filter slots
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
