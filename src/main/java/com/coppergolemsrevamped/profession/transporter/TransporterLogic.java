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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
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
		/** Far-walk progress (target in an unloaded chunk): target walked to, closest horizontal distance, ticks without progress. */
		@Nullable BlockPos farTarget;
		double farBest;
		int farStuck;
		/** Give-up: ticks left of the cooldown, where to wait (last position with progress), and the status text. */
		int giveUp;
		@Nullable Vec3 retreat;
		@Nullable String status;
	}

	/** Far-walk give-up (superdoc/features/chunk-loading.md, "Unloaded targets"). */
	static final double FAR_MAX = 256;
	static final double FAR_PROGRESS = 2;
	static final int FAR_STUCK_TICKS = 200;
	static final int FAR_COOLDOWN = 1200;

	/** Status line while the golem has given up on an unreachable far chest, else null. */
	public static @Nullable String status(CopperGolem golem) {
		State s = STATES.get(golem);
		return s != null && s.giveUp > 0 ? s.status : null;
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Drops this golem's transient state ({@link com.coppergolemsrevamped.profession.Profession.Forget}). */
	public static void forget(CopperGolem golem) {
		STATES.remove(golem);
	}

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.giveUp > 0) {
			// gave up on a far chest: wait near the last position with progress, then try again
			s.giveUp--;
			Vec3 r = s.retreat;
			if (r != null && golem.position().distanceToSqr(r) > 4 && golem.getNavigation().isDone()) {
				golem.getNavigation().moveTo(r.x, r.y, r.z, SPEED);
			}
			return;
		}
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		BlockPos a = data.assignments.get("input");
		BlockPos b = data.assignments.get("output");
		Container ca = a == null ? null : containerAt(level, a);
		Container cb = b == null ? null : containerAt(level, b);
		// A chest in an unloaded chunk (far from the golem) is not missing: the golem walks towards it when it is the
		// next target (Interact.approach never reads it). A missing or unassigned chest still means "wait".
		boolean farA = a != null && ca == null && !Interact.loaded(level, a);
		boolean farB = b != null && cb == null && !Interact.loaded(level, b);
		if ((ca == null && !farA) || (cb == null && !farB)) {
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}
		List<ItemStack> carry = carry(data);
		boolean loading = s.loading; // a take cut short by the transfer budget goes on before the carry is delivered
		boolean take = loading || isEmpty(carry);
		// A carry B refuses entirely (B full, or changed under Keep layout) goes back to A instead of idling at B forever.
		// An unloaded B is walked to with the carry; it is checked once it is loaded.
		boolean deliver = !take && (cb == null || acceptsAny(carry, cb, data.keepLayoutStore));
		BlockPos target = deliver ? b : a;
		Container here = deliver ? cb : ca;
		if (Interact.loaded(level, target)) s.farTarget = null;
		else if (farGiveUp(golem, s, target, deliver)) return;
		if (!Interact.approach(level, golem, target, s.walk, SPEED) || here == null) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (take) {
			// Only types B can take right now: a type B never accepts (Keep layout, no matching stack) would be carried forever.
			// With B unloaded that check waits for B: a type it refuses then comes back to A (see deliver above).
			Predicate<ItemStack> wanted = cb == null ? accepts(data) : accepts(data).and(st -> canInsert(cb, st, data.keepLayoutStore));
			boolean moved = TransferBudget.take(level, golem, ca, carry, wanted, data.keepLayoutTake) > 0;
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
			// deliver to B, or put back into A what B refuses
			if (TransferBudget.insert(level, golem, carry, deliver ? cb : ca, data.keepLayoutStore) > 0) Interact.openChest(level, golem, target, Interact.Action.INSERT);
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

	/**
	 * Tracks the walk towards a chest in an unloaded chunk and gives up (true) when the target is more than
	 * {@link #FAR_MAX} blocks away or the horizontal distance has not improved by {@link #FAR_PROGRESS} within
	 * {@link #FAR_STUCK_TICKS}. Why: with no real path (river, cliff, another continent) the walk never ends and the
	 * golem's chunk ticket follows it into the wilderness.
	 */
	private static boolean farGiveUp(CopperGolem golem, State s, BlockPos target, boolean toB) {
		Vec3 delta = Vec3.atCenterOf(target).subtract(golem.position());
		double d = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (!target.equals(s.farTarget)) {
			s.farTarget = target.immutable();
			s.farBest = d;
			s.farStuck = 0;
			s.retreat = golem.position();
		} else if (d < s.farBest - FAR_PROGRESS) {
			s.farBest = d;
			s.farStuck = 0;
			s.retreat = golem.position();
		} else s.farStuck++;
		if (d <= FAR_MAX && s.farStuck < FAR_STUCK_TICKS) return false;
		golem.getNavigation().stop();
		s.walk.reset();
		s.farTarget = null;
		s.giveUp = FAR_COOLDOWN;
		s.status = "Can't reach chest " + (toB ? "B" : "A") + " (" + (d > FAR_MAX ? "too far" : "no path") + ")";
		return true;
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

	/**
	 * The container at pos (both halves for a double chest), or null when it is not a container or its chunk is not
	 * loaded ({@link Interact#loaded}: never loads a chunk; callers treat null as "not available right now" and retry).
	 */
	public static @Nullable Container containerAt(Level level, BlockPos pos) {
		if (!Interact.loaded(level, pos)) return null;
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
		return takeAll(src, carry, limit, accept, false);
	}

	/**
	 * Like {@link #takeAll(Container, List, int, Predicate)}; with {@code keepLayout} a stackable slot is never taken below
	 * 1 item (see {@link #takeable}).
	 */
	public static int takeAll(Container src, List<ItemStack> carry, int limit, Predicate<ItemStack> accept, boolean keepLayout) {
		int total = 0;
		for (int i = 0; i < src.getContainerSize() && total < limit; i++) {
			ItemStack in = src.getItem(i);
			if (in.isEmpty() || !accept.test(in)) continue;
			int before = in.getCount();
			int avail = takeable(src, in, keepLayout);
			if (avail <= 0) continue;
			ItemStack rest = addTo(carry, in.copyWithCount(avail), null, limit - total, false);
			int moved = avail - rest.getCount();
			if (moved > 0) {
				total += moved;
				src.setItem(i, before == moved ? ItemStack.EMPTY : in.copyWithCount(before - moved));
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
		return insertAll(carry, dst, limit, false);
	}

	/** Like {@link #insertAll(List, Container, int)}; with {@code keepLayout} a stackable item only merges into non-empty slots. */
	public static int insertAll(List<ItemStack> carry, Container dst, int limit, boolean keepLayout) {
		int total = 0;
		for (int i = 0; i < carry.size() && total < limit; i++) {
			ItemStack s = carry.get(i);
			if (s.isEmpty()) continue;
			int before = s.getCount();
			ItemStack rest = addTo(null, s.copy(), dst, limit - total, keepLayout);
			total += before - rest.getCount();
			carry.set(i, rest);
		}
		if (total > 0) dst.setChanged();
		return total;
	}

	/**
	 * Keep layout (superdoc/features/keep-layout.md): true when the rule applies to {@code stack} in {@code c}. Unstackable
	 * items are exempt (a one-axe tool slot could otherwise never be used) and so are furnace slots (machine slots, not a
	 * human layout).
	 */
	private static boolean layoutRule(boolean keepLayout, Container c, ItemStack stack) {
		return keepLayout && stack.getMaxStackSize() > 1 && !(c instanceof AbstractFurnaceBlockEntity);
	}

	/** Items that may be taken from the slot holding {@code in}: all but 1 under keep layout, else all. */
	public static int takeable(Container src, ItemStack in, boolean keepLayout) {
		return layoutRule(keepLayout, src, in) ? in.getCount() - 1 : in.getCount();
	}

	/** Keep layout insert of one stack (Chest Sorter mixin): adds into {@code dst}, returns the remainder. */
	public static ItemStack insertStack(ItemStack stack, Container dst, boolean keepLayout) {
		return addTo(null, stack, dst, Integer.MAX_VALUE, keepLayout);
	}

	/**
	 * True when {@code stack} would at least partly fit into {@code dst} under the keep layout rule (no change made). Mirrors
	 * {@link #addTo}, {@code canPlaceItem} included, so a "yes" here means an insert really moves something.
	 */
	public static boolean canInsert(Container dst, ItemStack stack, boolean keepLayout) {
		boolean layout = layoutRule(keepLayout, dst, stack);
		for (int i = 0; i < dst.getContainerSize(); i++) {
			ItemStack cur = dst.getItem(i);
			boolean room = cur.isEmpty() ? !layout : ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < Math.min(stack.getMaxStackSize(), dst.getMaxStackSize());
			if (room && dst.canPlaceItem(i, stack)) return true;
		}
		return false;
	}

	/** True when some carried stack would at least partly fit into {@code dst} ({@link #canInsert}). */
	public static boolean acceptsAny(List<ItemStack> carry, Container dst, boolean keepLayout) {
		for (ItemStack st : carry) if (!st.isEmpty() && canInsert(dst, st, keepLayout)) return true;
		return false;
	}

	/**
	 * Adds at most {@code limit} items of stack to a list or a container (exactly one non-null); returns the remainder.
	 * {@code keepLayout} applies to containers only: merge into non-empty slots, skip the empty-slot pass.
	 */
	private static ItemStack addTo(@Nullable List<ItemStack> list, ItemStack stack, @Nullable Container cont, int limit, boolean keepLayout) {
		int passes = cont != null && layoutRule(keepLayout, cont, stack) ? 1 : 2;
		int size = list != null ? list.size() : cont.getContainerSize();
		int left = limit;
		for (int pass = 0; pass < passes && !stack.isEmpty() && left > 0; pass++) {
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
