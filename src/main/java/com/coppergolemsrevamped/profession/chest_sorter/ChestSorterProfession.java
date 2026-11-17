package com.coppergolemsrevamped.profession.chest_sorter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/** Vanilla sorting behaviour; a Chest Sorter visits up to 64 chests before it gives up (vanilla: 10). */
public final class ChestSorterProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("chest_sorter");
	/** Vanilla's literal in {@code TransportItemsBetweenContainers#setVisitedBlockPos}. */
	public static final int VANILLA_VISITED_LIMIT = 10;
	public static final int SORTER_VISITED_LIMIT = 64;

	private ChestSorterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Chest Sorter"),
			List.of(),
			List.of(),
			true,
			(level, golem, data) -> {}));
	}

	/** Horizontal / vertical distance (blocks) from home beyond which a Chest Sorter walks back. */
	public static final double HOME_RADIUS = 24.0;
	public static final double HOME_HEIGHT = 8.0;
	/** Walking back ends inside this horizontal distance (hysteresis: the vanilla brain would stroll straight out again). */
	static final double HOME_SETTLED = 12.0;
	/** One path leg is at most this long: the pathfinder's range is short, a target 40 blocks away gets no path. */
	static final double LEG = 14.0;
	private static final Set<CopperGolem> RETURNING = Collections.newSetFromMap(new WeakHashMap<>());

	/**
	 * Keeps a Chest Sorter near its home (superdoc/features/golem-safety.md). Vanilla's sorting searches around the
	 * golem's current position and strolls randomly once no chest is in range; our chunk ticket keeps it ticking, so it
	 * drifted away for good. True while walking back (the caller then skips the vanilla brain). An old save without a
	 * home gets the current position; a home in another dimension is replaced the same way.
	 */
	public static boolean walkHome(ServerLevel level, CopperGolem golem, GolemData data) {
		if (data.home == null || !data.home.dimension().equals(level.dimension())) {
			data.home = GlobalPos.of(level.dimension(), golem.blockPosition());
			GolemData.save(golem, data);
			return false;
		}
		BlockPos home = data.home.pos();
		double dx = home.getX() + 0.5 - golem.getX(), dz = home.getZ() + 0.5 - golem.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		double dy = Math.abs(home.getY() - golem.getY());
		boolean far = flat > HOME_RADIUS || dy > HOME_HEIGHT;
		if (!far && !(RETURNING.contains(golem) && (flat > HOME_SETTLED || dy > HOME_HEIGHT / 2))) {
			RETURNING.remove(golem);
			return false;
		}
		RETURNING.add(golem);
		if (golem.getNavigation().isDone() || golem.tickCount % 40 == 0) {
			double k = flat <= LEG ? 1.0 : LEG / flat;
			golem.getNavigation().moveTo(golem.getX() + dx * k, home.getY(), golem.getZ() + dz * k, 1.0);
		}
		return true;
	}

	/** Chests a body may visit before it gives up: 64 for a Chest Sorter golem, vanilla's 10 otherwise. */
	public static int visitedLimit(PathfinderMob body) {
		if (body instanceof CopperGolem golem && ID.equals(GolemData.get(golem).profession)) return SORTER_VISITED_LIMIT;
		return VANILLA_VISITED_LIMIT;
	}

	/**
	 * Old saves kept waxed copper blocks in a Chest Sorter's slot; the slot is gone, so drop them at the golem and
	 * clear the list. No-op when the list is empty.
	 * Why called from {@code GolemTicker}: a vanilla-brain profession's {@code tick} is never called, and the visited-limit
	 * query is a pure getter that must not mutate. Until the first tick the items stay saved, and death or a
	 * profession change drops them too.
	 */
	public static void migrateLegacyItems(ServerLevel level, CopperGolem golem) {
		GolemData data = GolemData.get(golem);
		if (!ID.equals(data.profession) || data.items.isEmpty()) return;
		GolemService.dropAll(level, golem);
		data = GolemData.get(golem);
		data.items = NonNullList.create();
		GolemData.save(golem, data);
	}
}
