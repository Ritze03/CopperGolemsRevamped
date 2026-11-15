package com.coppergolemsrevamped.ai;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

/**
 * The per-golem item transfer limit: {@link #ITEMS_PER_WINDOW} items per {@link #WINDOW_TICKS} ticks between a golem and
 * containers, take and insert together. Why: a user request, 16 per 5 ticks = 64 items per second. A window is
 * {@code gameTime / WINDOW_TICKS}; the budget refills to full when a new window starts (transient, never saved).
 * Rules for callers and what is (not) counted: superdoc/architecture/transfer-limit.md.
 */
public final class TransferBudget {
	private TransferBudget() {}

	public static final int ITEMS_PER_WINDOW = 16;
	public static final int WINDOW_TICKS = 5;

	/** golem -> {window index, items used in it}. */
	private static final Map<CopperGolem, long[]> USED = new WeakHashMap<>();

	/** Items the golem may still move in the current window. */
	public static int remaining(ServerLevel level, CopperGolem golem) {
		long[] u = USED.get(golem);
		if (u == null || u[0] != level.getGameTime() / WINDOW_TICKS) return ITEMS_PER_WINDOW;
		return (int) (ITEMS_PER_WINDOW - u[1]);
	}

	public static boolean exhausted(ServerLevel level, CopperGolem golem) {
		return remaining(level, golem) <= 0;
	}

	/** Counts {@code n} moved items against the current window. */
	public static void spend(ServerLevel level, CopperGolem golem, int n) {
		if (n <= 0) return;
		long w = level.getGameTime() / WINDOW_TICKS;
		long[] u = USED.computeIfAbsent(golem, g -> new long[] {w, 0});
		if (u[0] != w) {
			u[0] = w;
			u[1] = 0;
		}
		u[1] += n;
	}

	/**
	 * A profession cooldown that wakes the golem on the first tick of the next window (the tick counts the cooldown down,
	 * then acts on the tick after it reaches 0).
	 */
	public static int waitTicks(ServerLevel level) {
		return WINDOW_TICKS - (int) (level.getGameTime() % WINDOW_TICKS) - 1;
	}

	/** Budgeted {@link TransporterLogic#takeAll}: returns the number of items moved. */
	public static int take(ServerLevel level, CopperGolem golem, Container src, NonNullList<ItemStack> carry) {
		int n = TransporterLogic.takeAll(src, carry, remaining(level, golem));
		spend(level, golem, n);
		return n;
	}

	/** Budgeted {@link TransporterLogic#insertAll}: returns the number of items moved. */
	public static int insert(ServerLevel level, CopperGolem golem, NonNullList<ItemStack> carry, Container dst) {
		int n = TransporterLogic.insertAll(carry, dst, remaining(level, golem));
		spend(level, golem, n);
		return n;
	}
}
