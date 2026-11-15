package com.coppergolemsrevamped.profession.cleaner;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Cleaner work loop: pick up item entities inside the area box, deliver to the output chest. */
public final class CleanerLogic {
	private CleanerLogic() {}

	static final double PICKUP_RANGE_SQR = 1.5 * 1.5;
	static final int REPATH_TICKS = 20;
	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	static final int AFTER_ACTION_TICKS = 5;
	static final double SPEED = 1.0;
	/** Chasing one item this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up item is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;

	private static final class State {
		int cooldown;
		int repath;
		@Nullable UUID target;
		double bestDist;
		int stuck;
		/** Given-up item entity -> game time until which the scan ignores it. */
		final Map<UUID, Long> skip = new HashMap<>();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Block-inclusive box of the area: x/z min..max, y min..max+2 (two blocks of headroom). Null when unset. */
	public static @Nullable AABB box(GolemData data) {
		BlockPos a = data.assignments.get(CleanerProfession.AREA + "_a");
		BlockPos b = data.assignments.get(CleanerProfession.AREA + "_b");
		if (a == null || b == null) return null;
		return new AABB(
			Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
			Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 3, Math.max(a.getZ(), b.getZ()) + 1);
	}

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		AABB box = box(data);
		BlockPos out = data.assignments.get(CleanerProfession.OUTPUT);
		Container chest = out == null ? null : TransporterLogic.containerAt(level, out);
		if (box == null || chest == null) {
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}

		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
			e -> e.isAlive() && !e.hasPickUpDelay() && fits(data.items, e.getItem()) && box.contains(e.position()) && !s.skip.containsKey(e.getUUID()));
		if (!items.isEmpty()) {
			ItemEntity target = items.stream().min(Comparator.comparingDouble(golem::distanceToSqr)).get();
			if (golem.distanceToSqr(target) > PICKUP_RANGE_SQR) {
				if (!chase(s, target.getUUID(), golem.distanceToSqr(target))) {
					skip(s, target.getUUID(), now);
					golem.getNavigation().stop();
					s.repath = 0;
					return;
				}
				if (--s.repath <= 0 || golem.getNavigation().isDone()) {
					Path path = golem.getNavigation().createPath(target, 0);
					// A null path is transient (spawn tick, mid-air, chunk edge): no progress, so the chase timeout decides.
					if (path != null) golem.getNavigation().moveTo(path, SPEED);
					s.repath = REPATH_TICKS;
				}
				return;
			}
			golem.getNavigation().stop();
			s.repath = 0;
			s.target = null;
			pickUp(target, data.items);
			s.cooldown = AFTER_ACTION_TICKS;
			GolemData.save(golem, data);
			return;
		}

		if (TransporterLogic.isEmpty(data.items)) {
			golem.getNavigation().stop();
			s.cooldown = SCAN_TICKS;
			return;
		}
		if (!TransporterLogic.inReach(golem, out)) {
			if (--s.repath <= 0 || golem.getNavigation().isDone()) {
				golem.getNavigation().moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, SPEED);
				s.repath = REPATH_TICKS;
			}
			return;
		}
		golem.getNavigation().stop();
		golem.getLookControl().setLookAt(Vec3.atCenterOf(out));
		s.repath = 0;
		TransporterLogic.insertAll(data.items, chest);
		s.cooldown = TransporterLogic.isEmpty(data.items) ? AFTER_ACTION_TICKS : RETRY_TICKS;
		GolemData.save(golem, data);
	}

	/**
	 * Progress tracker for the current chase; false = give up on {@code id}. Resets when the target changes or the
	 * distance shrank by more than {@link #PROGRESS_EPS}; otherwise counts ticks without progress.
	 * ponytail: a no-progress timeout stands in for a real reachability check; a null path is no progress, not instant give-up.
	 */
	static boolean chase(State s, UUID id, double distSqr) {
		if (!id.equals(s.target) || distSqr < s.bestDist - PROGRESS_EPS) {
			s.target = id;
			s.bestDist = distSqr;
			s.stuck = 0;
			return true;
		}
		return ++s.stuck < GIVE_UP_TICKS;
	}

	/** Ignore {@code id} in the scan for {@link #SKIP_TICKS} from {@code now} and forget the chase. */
	static void skip(State s, UUID id, long now) {
		s.skip.put(id, now + SKIP_TICKS);
		s.target = null;
		s.stuck = 0;
	}

	/** Test hook: give up on {@code id} now (as if unreachable). */
	public static void skipItem(CopperGolem golem, UUID id, long now) {
		skip(STATES.computeIfAbsent(golem, g -> new State()), id, now);
	}

	/** Test hook: is {@code id} currently ignored by this golem's scan. */
	public static boolean isSkipped(CopperGolem golem, UUID id, long now) {
		State s = STATES.get(golem);
		Long until = s == null ? null : s.skip.get(id);
		return until != null && until > now;
	}

	/** Some part of {@code stack} can go into carry (empty slot or same item with room). */
	static boolean fits(NonNullList<ItemStack> carry, ItemStack stack) {
		for (ItemStack c : carry) {
			if (c.isEmpty()) return true;
			if (ItemStack.isSameItemSameComponents(c, stack) && c.getCount() < c.getMaxStackSize()) return true;
		}
		return false;
	}

	/** Moves as much of the entity's stack as fits into carry; the entity keeps the remainder or is discarded. */
	static void pickUp(ItemEntity entity, NonNullList<ItemStack> carry) {
		SimpleContainer tmp = new SimpleContainer(1);
		tmp.setItem(0, entity.getItem().copy());
		TransporterLogic.takeAll(tmp, carry);
		ItemStack rest = tmp.getItem(0);
		if (rest.isEmpty()) entity.discard(); else entity.setItem(rest);
	}
}
