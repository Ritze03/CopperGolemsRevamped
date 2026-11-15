package com.coppergolemsrevamped.profession.transporter;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.FilterMode;
import com.coppergolemsrevamped.core.GolemData;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The Transporter work loop (chest A -> carry -> chest B) plus static item-moving helpers. */
public final class TransporterLogic {
	private TransporterLogic() {}

	static final int RETRY_TICKS = 40;
	static final double SPEED = 1.0;

	/** Transient per-golem timers; the persistent state is only GolemData items + assignments. */
	private static final class State {
		int cooldown;
		boolean loading;
		final Interact.Approach walk = new Interact.Approach();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		BlockPos a = data.assignments.get("input");
		BlockPos b = data.assignments.get("output");
		Container ca = a == null ? null : containerAt(level, a);
		Container cb = b == null ? null : containerAt(level, b);
		if (ca == null || cb == null) {
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}
		List<ItemStack> carry = carry(data);
		boolean carryEmpty = isEmpty(carry);
		boolean loading = s.loading; // a take cut short by the transfer budget goes on before the carry is delivered
		BlockPos target = loading || carryEmpty ? a : b;
		if (!Interact.approach(level, golem, target, s.walk, SPEED)) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (loading || carryEmpty) {
			boolean moved = TransferBudget.take(level, golem, ca, carry, accepts(data)) > 0;
			if (moved) Interact.openChest(level, golem, target, Interact.Action.TAKE);
			if (TransferBudget.exhausted(level, golem)) {
				// budget used up, not "chest empty": stay, keep the lid open and go on next window
				s.loading = true;
				Interact.openChest(level, golem, target, Interact.Action.TAKE);
				s.cooldown = TransferBudget.waitTicks(level);
			} else {
				s.cooldown = moved || loading ? Interact.OPEN_TICKS : RETRY_TICKS;
				if (moved || loading) s.walk.reset();
				s.loading = false;
			}
		} else {
			if (TransferBudget.insert(level, golem, carry, cb) > 0) Interact.openChest(level, golem, target, Interact.Action.INSERT);
			boolean empty = isEmpty(carry);
			if (empty) s.walk.reset();
			if (empty) s.cooldown = Interact.OPEN_TICKS;
			else if (TransferBudget.exhausted(level, golem)) {
				Interact.openChest(level, golem, target, Interact.Action.INSERT);
				s.cooldown = TransferBudget.waitTicks(level);
			} else s.cooldown = RETRY_TICKS;
		}
		GolemData.save(golem, data);
	}

	/** The carry slots of a Transporter: a live view of the first {@link TransporterProfession#CARRY_SLOTS} items. */
	public static List<ItemStack> carry(GolemData data) {
		return data.items.subList(0, Math.min(TransporterProfession.CARRY_SLOTS, data.items.size()));
	}

	/** True when {@code s} matches a filter slot by item type only ({@link ItemStack#isSameItem}, components ignored). */
	public static boolean inFilter(GolemData data, ItemStack s) {
		int end = Math.min(TransporterProfession.FILTER_FROM + TransporterProfession.FILTER_SLOTS, data.items.size());
		for (int i = TransporterProfession.FILTER_FROM; i < end; i++) {
			ItemStack f = data.items.get(i);
			if (!f.isEmpty() && ItemStack.isSameItem(f, s)) return true;
		}
		return false;
	}

	/** True when the filter has at least one item. */
	public static boolean filterHasItems(GolemData data) {
		int end = Math.min(TransporterProfession.FILTER_FROM + TransporterProfession.FILTER_SLOTS, data.items.size());
		for (int i = TransporterProfession.FILTER_FROM; i < end; i++) if (!data.items.get(i).isEmpty()) return true;
		return false;
	}

	/** Whether the golem takes {@code s} from chest A under the current filter mode. */
	public static boolean passes(GolemData data, ItemStack s) {
		return switch (data.filterMode) {
			case OFF -> true;
			case WHITELIST -> inFilter(data, s);
			case BLACKLIST -> !inFilter(data, s);
		};
	}

	/** {@link #passes} as a predicate. */
	public static Predicate<ItemStack> accepts(GolemData data) {
		return data.filterMode == FilterMode.OFF ? s -> true : s -> passes(data, s);
	}

	/** The container at pos (both halves for a double chest), or null when it is not a container. */
	public static @Nullable Container containerAt(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return level.getBlockEntity(pos) instanceof Container c ? c : null;
	}

	public static boolean isEmpty(List<ItemStack> items) {
		for (ItemStack s : items) if (!s.isEmpty()) return false;
		return true;
	}

	/** Moves items from {@code src} into {@code carry} (merge first, then empty slots). Returns true if anything moved. */
	public static boolean takeAll(Container src, List<ItemStack> carry) {
		return takeAll(src, carry, Integer.MAX_VALUE) > 0;
	}

	/** Like {@link #takeAll(Container, List)} but moves at most {@code limit} items; returns how many moved. */
	public static int takeAll(Container src, List<ItemStack> carry, int limit) {
		return takeAll(src, carry, limit, s -> true);
	}

	/** Like {@link #takeAll(Container, List, int)} but only stacks {@code accept} passes move; the rest stay in {@code src}. */
	public static int takeAll(Container src, List<ItemStack> carry, int limit, Predicate<ItemStack> accept) {
		int total = 0;
		for (int i = 0; i < src.getContainerSize() && total < limit; i++) {
			ItemStack in = src.getItem(i);
			if (in.isEmpty() || !accept.test(in)) continue;
			int before = in.getCount();
			ItemStack rest = addTo(carry, in.copy(), null, limit - total);
			if (rest.getCount() != before) {
				total += before - rest.getCount();
				src.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
			}
		}
		if (total > 0) src.setChanged();
		return total;
	}

	/** Inserts every carried stack into {@code dst}; what does not fit stays in {@code carry}. Returns true if anything moved. */
	public static boolean insertAll(List<ItemStack> carry, Container dst) {
		return insertAll(carry, dst, Integer.MAX_VALUE) > 0;
	}

	/** Like {@link #insertAll(List, Container)} but moves at most {@code limit} items; returns how many moved. */
	public static int insertAll(List<ItemStack> carry, Container dst, int limit) {
		int total = 0;
		for (int i = 0; i < carry.size() && total < limit; i++) {
			ItemStack s = carry.get(i);
			if (s.isEmpty()) continue;
			int before = s.getCount();
			ItemStack rest = addTo(null, s.copy(), dst, limit - total);
			total += before - rest.getCount();
			carry.set(i, rest);
		}
		if (total > 0) dst.setChanged();
		return total;
	}

	/** Adds at most {@code limit} items of stack to a list or a container (exactly one non-null); returns the remainder. */
	private static ItemStack addTo(@Nullable List<ItemStack> list, ItemStack stack, @Nullable Container cont, int limit) {
		int size = list != null ? list.size() : cont.getContainerSize();
		int left = limit;
		for (int pass = 0; pass < 2 && !stack.isEmpty() && left > 0; pass++) {
			for (int i = 0; i < size && !stack.isEmpty() && left > 0; i++) {
				ItemStack cur = list != null ? list.get(i) : cont.getItem(i);
				if (pass == 0 ? cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack) : !cur.isEmpty()) continue;
				if (cont != null && !cont.canPlaceItem(i, stack)) continue;
				int max = Math.min(stack.getMaxStackSize(), cont != null ? cont.getMaxStackSize() : 64);
				int room = max - cur.getCount();
				if (room <= 0) continue;
				int n = Math.min(Math.min(room, stack.getCount()), left);
				left -= n;
				if (cur.isEmpty()) {
					cur = stack.copyWithCount(n);
				} else {
					cur.grow(n);
				}
				stack.shrink(n);
				if (list != null) list.set(i, cur); else cont.setItem(i, cur);
			}
		}
		return stack;
	}
}
