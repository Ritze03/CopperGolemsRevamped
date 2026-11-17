package com.coppergolemsrevamped.ai;

import com.coppergolemsrevamped.core.GolemData;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;

/** Follow mode: walk after {@link GolemData#followPlayer}. Driven by GolemTicker instead of the brain. */
public final class FollowMode {
	private FollowMode() {}

	static final double START_DISTANCE = 3.0;
	static final double STOP_DISTANCE = 2.0;
	static final int REPATH_TICKS = 10;
	static final double SPEED = 1.0;

	/** Follow turns itself off after the player was absent (offline, other dimension, farther than this) this long. */
	public static final int ABSENT_TICKS = 600;
	static final double ABSENT_DISTANCE = 32.0;
	/** Transient: consecutive ticks the followed player was absent. */
	private static final Map<CopperGolem, int[]> ABSENT = new WeakHashMap<>();

	/** Transient: ticks until the next path recompute. */
	private static final Map<CopperGolem, int[]> REPATH = new WeakHashMap<>();

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		Player player = data.followPlayer == null ? null : level.getPlayerByUUID(data.followPlayer);
		int[] absent = ABSENT.computeIfAbsent(golem, g -> new int[1]);
		if (player != null && !player.isSpectator() && golem.distanceTo(player) <= ABSENT_DISTANCE) absent[0] = 0;
		else if (++absent[0] >= ABSENT_TICKS) {
			// Why: a golem following an offline / far player stood idle forever; it resumes work instead.
			absent[0] = 0;
			data.following = false;
			GolemData.save(golem, data);
			golem.getNavigation().stop();
			return;
		}
		if (player == null || player.isSpectator()) {
			golem.getNavigation().stop();
			return;
		}
		int[] timer = REPATH.computeIfAbsent(golem, g -> new int[1]);
		if (timer[0] > 0) timer[0]--;
		double dist = golem.distanceTo(player);
		if (dist > START_DISTANCE) {
			if (timer[0] <= 0) {
				golem.getNavigation().moveTo(player, SPEED);
				timer[0] = REPATH_TICKS;
			}
		} else if (dist <= STOP_DISTANCE) {
			golem.getNavigation().stop();
			golem.getLookControl().setLookAt(player);
		}
	}
}
