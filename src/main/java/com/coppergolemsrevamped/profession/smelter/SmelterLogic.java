package com.coppergolemsrevamped.profession.smelter;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemXp;
import com.coppergolemsrevamped.mixin.AbstractFurnaceAccessor;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Smelter work loop. {@link GolemData#items}: slot i is the buffer of furnace i. A pass cycles through the
 * {@link Phase}s; each phase serves every furnace that needs it once, so nothing starves and nothing waits on one
 * blocked target. See superdoc/features/smelter.md for the rules and their reasons.
 */
public final class SmelterLogic {
	private SmelterLogic() {}

	static final int SLOTS = SmelterProfession.SLOTS;
	static final int FURNACES = SmelterProfession.MAX_FURNACES;
	/** The XP bottles the golem makes from its stored XP. Always delivered, never fed. */
	static final int XP_SLOT = SmelterProfession.XP_SLOT;
	/** First of the 3 shared result slots (RESULT_FIRST..SLOTS-1): every furnace's results, merged, delivered to the output chest. */
	static final int RESULT_FIRST = SmelterProfession.RESULT_FIRST;
	static final int RETRY_TICKS = 40;
	static final int ACTION_TICKS = 10;
	static final int RESCAN_TICKS = 100;
	/** A buffer that made no progress for this long goes back to its chest. */
	static final int RETURN_TICKS = 200;
	/** A full chest is not retried for this long (the rest of the loop goes on). */
	static final int BLOCK_TICKS = 200;
	/** An unreachable target is ignored for this long. */
	static final int SKIP_TICKS = 600;
	static final int GIVE_UP_TICKS = 200;
	static final double PROGRESS_EPS = 0.1;
	static final double SPEED = 1.0;
	static final int FUEL_TARGET = 8;
	/**
	 * A furnace's input is topped up to this many items only (about 8 x 200 ticks of cooking). Why: the golem returns to
	 * collect sooner instead of stocking 64, so products come out sooner; the cycle is shorter than the batch cooks, so
	 * every furnace stays busy.
	 */
	public static final int BATCH = 8;
	static final int MAX_Y_SPAN = 32;

	private static final RecipeManager.CachedCheck<SingleRecipeInput, SmeltingRecipe> SMELT = RecipeManager.createCheck(RecipeType.SMELTING);
	private static final RecipeManager.CachedCheck<SingleRecipeInput, BlastingRecipe> BLAST = RecipeManager.createCheck(RecipeType.BLASTING);
	private static final RecipeManager.CachedCheck<SingleRecipeInput, SmokingRecipe> SMOKE = RecipeManager.createCheck(RecipeType.SMOKING);

	/**
	 * What a furnace buffer holds (buffers are input only, results live in the result slots). Transient; after a restart
	 * it is derived from the item ({@link #kindOf}).
	 */
	enum Kind { RAW, FUEL }

	/**
	 * Order of a cycle: empty the furnaces first so products come out sooner, then restock. A phase without work is
	 * skipped; WAIT is reported (not part of the cycle) while nothing can be done. FUEL_FETCH (trip to the fuel chest) and
	 * FUEL (top up the fuel slots) are one phase to the outside ({@link #phaseOf}: both read FUEL).
	 */
	public enum Phase { COLLECT, DELIVER, FETCH, FEED, FUEL_FETCH, FUEL, RETURN, WAIT }

	/** Test hook: called on every change of the visible phase (transient state). */
	public static @Nullable BiConsumer<CopperGolem, Phase> phaseListener;

	public static @Nullable Phase phaseOf(CopperGolem golem) {
		State st = STATES.get(golem);
		return st == null ? null : st.shown;
	}

	private static void show(State s, CopperGolem golem, Phase p) {
		if (p == Phase.FUEL_FETCH) p = Phase.FUEL;
		if (p == s.shown) return;
		s.shown = p;
		BiConsumer<CopperGolem, Phase> l = phaseListener;
		if (l != null) l.accept(golem, p);
	}

	record Work(Phase phase, BlockPos pos, int slot) {}

	record Move(int slot, ItemStack stack) {}

	private static final class State {
		int cooldown;
		int phase;
		@Nullable Phase shown;
		int served;
		@Nullable Work work;
		/** The current work was cut short by the transfer budget: it stays the work and goes on next window. */
		boolean pending;
		/** The unfinished part of a FETCH / FUEL_FETCH plan (kept so the even split over the furnaces survives the cut). */
		@Nullable List<Move> moves;
		final BlockPos[] fur = new BlockPos[FURNACES];
		long scanAt;
		final Kind[] kind = new Kind[FURNACES];
		final ItemStack[] seen = new ItemStack[FURNACES];
		final long[] changedAt = new long[FURNACES];
		final Map<BlockPos, Long> skip = new HashMap<>();
		long outBlockedUntil, inBlockedUntil, fuelBlockedUntil;
		final Interact.Approach walk = new Interact.Approach();
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
		String status = "Working";

		State() {
			java.util.Arrays.fill(seen, ItemStack.EMPTY);
		}
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Everything one tick needs; containers are null when the chest is unassigned, gone or given up on. */
	private record Env(ServerLevel level, CopperGolem golem, GolemData data, State s, long now,
		@Nullable BlockPos inPos, @Nullable Container in,
		@Nullable BlockPos fuelPos, @Nullable Container fuel,
		@Nullable BlockPos outPos, @Nullable Container out) {}

	/** The Status line ("Smelting in 3 furnaces", "No furnaces in the area", ...). */
	public static String status(CopperGolem golem) {
		State s = STATES.get(golem);
		return s == null ? "Working" : s.status;
	}

	// ---- furnace and recipe helpers ----

	static @Nullable AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, @Nullable BlockPos p) {
		if (p == null || !level.hasChunkAt(p)) return null;
		return level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f && !f.isRemoved() ? f : null;
	}

	/** True when this furnace type has a recipe for the item (the same check the furnace itself runs). */
	static boolean smelts(ServerLevel level, AbstractFurnaceBlockEntity f, ItemStack stack) {
		if (stack.isEmpty()) return false;
		SingleRecipeInput in = new SingleRecipeInput(stack.copyWithCount(1));
		if (f instanceof BlastFurnaceBlockEntity) return BLAST.getRecipeFor(in, level).isPresent();
		if (f instanceof SmokerBlockEntity) return SMOKE.getRecipeFor(in, level).isPresent();
		return SMELT.getRecipeFor(in, level).isPresent();
	}

	private static boolean smeltsAnywhere(ServerLevel level, ItemStack stack) {
		SingleRecipeInput in = new SingleRecipeInput(stack.copyWithCount(1));
		return SMELT.getRecipeFor(in, level).isPresent() || BLAST.getRecipeFor(in, level).isPresent()
			|| SMOKE.getRecipeFor(in, level).isPresent();
	}

	static boolean isFuel(ItemStack s) {
		return !s.isEmpty() && s.has(DataComponents.COOKING_FUEL);
	}

	private static boolean same(ItemStack a, ItemStack b) {
		return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
	}

	private static boolean lit(ServerLevel level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return st.hasProperty(BlockStateProperties.LIT) && st.getValue(BlockStateProperties.LIT);
	}

	// ---- slot helpers ----

	private static ItemStack slot(Env e, int i) {
		return i < e.data.items.size() ? e.data.items.get(i) : ItemStack.EMPTY;
	}

	/**
	 * What furnace buffer i (0..7) holds. The flag set when the golem filled it wins; without one (restart, a player's
	 * edit) it is derived: smeltable -> RAW, fuel -> FUEL, anything else -> RAW too (it fits no furnace, so RETURN takes
	 * it back to the input chest). Why only two kinds: results no longer live in the buffers, so a restart cannot mistake
	 * stone for a result; only an item that is both smeltable and fuel (logs) is still read as RAW.
	 */
	private static @Nullable Kind kindOf(Env e, int i) {
		ItemStack st = slot(e, i);
		if (st.isEmpty()) return null;
		Kind k = e.s.kind[i];
		if (k != null) return k;
		if (smeltsAnywhere(e.level, st)) return Kind.RAW;
		return isFuel(st) ? Kind.FUEL : Kind.RAW;
	}

	/** Slots DELIVER empties into the output chest: the XP bottle slot and the result slots (the non-empty ones). */
	private static List<Integer> deliverSlots(Env e) {
		List<Integer> idxs = new ArrayList<>();
		if (!slot(e, XP_SLOT).isEmpty()) idxs.add(XP_SLOT);
		for (int i = RESULT_FIRST; i < SLOTS; i++) if (!slot(e, i).isEmpty()) idxs.add(i);
		return idxs;
	}

	/** How many of {@code src} fit into the result slots now (room in same-item stacks, plus empty slots). */
	static int resultSpace(GolemData d, ItemStack src) {
		int max = src.getMaxStackSize();
		int space = 0;
		for (int i = RESULT_FIRST; i < SLOTS && i < d.items.size(); i++) {
			ItemStack st = d.items.get(i);
			if (st.isEmpty()) space += max; else if (same(st, src)) space += Math.max(0, max - st.getCount());
		}
		return space;
	}

	/** Puts up to {@code n} of {@code src} into the result slots, merging same items first; returns how many went in. */
	static int addResult(GolemData d, ItemStack src, int n) {
		int max = src.getMaxStackSize();
		int left = n;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = RESULT_FIRST; i < SLOTS && i < d.items.size() && left > 0; i++) {
				ItemStack st = d.items.get(i);
				if (pass == 0 && same(st, src)) {
					int t = Math.min(left, max - st.getCount());
					if (t > 0) {
						st.grow(t);
						left -= t;
					}
				} else if (pass == 1 && st.isEmpty()) {
					int t = Math.min(left, max);
					d.items.set(i, src.copyWithCount(t));
					left -= t;
				}
			}
		}
		return n - left;
	}

	private static boolean skipped(State s, @Nullable BlockPos p, long now) {
		Long until = p == null ? null : s.skip.get(p);
		return until != null && until > now;
	}

	/** Furnace i exists, is loaded and has not been given up on. */
	private static @Nullable AbstractFurnaceBlockEntity usable(Env e, int i) {
		BlockPos p = e.s.fur[i];
		if (p == null || skipped(e.s, p, e.now)) return null;
		return furnaceAt(e.level, p);
	}

	private static int usableCount(Env e) {
		int n = 0;
		for (int i = 0; i < FURNACES; i++) if (usable(e, i) != null) n++;
		return n;
	}

	// ---- furnace scan ----

	/**
	 * Re-reads the area. Furnaces already in a slot keep it while they exist (so a buffer keeps its furnace); free
	 * slots take the nearest new furnaces to the input chest, in a stable order (distance, then x, y, z).
	 */
	private static void rescan(Env e) {
		State s = e.s;
		s.scanAt = e.now + RESCAN_TICKS;
		BlockPos a = e.data.assignments.get(SmelterProfession.FURNACES + "_a");
		BlockPos b = e.data.assignments.get(SmelterProfession.FURNACES + "_b");
		List<BlockPos> area = new ArrayList<>();
		if (a != null && b != null) {
			int y0 = Math.min(a.getY(), b.getY());
			int y1 = Math.min(Math.max(a.getY(), b.getY()), y0 + MAX_Y_SPAN - 1);
			for (BlockPos p : BlockPos.betweenClosed(Math.min(a.getX(), b.getX()), y0, Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), y1, Math.max(a.getZ(), b.getZ()))) {
				if (furnaceAt(e.level, p) != null) area.add(p.immutable());
			}
		}
		Set<BlockPos> inArea = new HashSet<>(area);
		Set<BlockPos> have = new HashSet<>();
		for (int i = 0; i < FURNACES; i++) {
			if (s.fur[i] != null && !inArea.contains(s.fur[i])) s.fur[i] = null;
			if (s.fur[i] != null) have.add(s.fur[i]);
		}
		Vec3 ref = e.inPos != null ? Vec3.atCenterOf(e.inPos) : e.golem.position();
		List<BlockPos> fresh = new ArrayList<>();
		for (BlockPos p : area) if (!have.contains(p)) fresh.add(p);
		fresh.sort(Comparator.<BlockPos>comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(ref))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		int next = 0;
		for (int i = 0; i < FURNACES && next < fresh.size(); i++) if (s.fur[i] == null) s.fur[i] = fresh.get(next++);
	}

	// ---- plans ----

	private static void addType(List<ItemStack> types, List<Integer> totals, ItemStack c) {
		for (int k = 0; k < types.size(); k++) {
			if (same(types.get(k), c)) {
				totals.set(k, totals.get(k) + c.getCount());
				return;
			}
		}
		types.add(c.copyWithCount(1));
		totals.add(c.getCount());
	}

	/**
	 * Which items to take from the input chest into which empty buffer. For each smeltable item type in chest order:
	 * the furnaces that can take it (their type has a recipe, input empty or the same item, room left) share it evenly,
	 * one item at a time to the furnace with the lowest level, so 24 ore over 3 furnaces is 8 + 8 + 8. A furnace claimed
	 * by one type gets nothing else this round.
	 */
	private static List<Move> planFetch(Env e) {
		if (e.in == null) return List.of();
		Set<Integer> free = new LinkedHashSet<>();
		for (int i = 0; i < FURNACES; i++) if (usable(e, i) != null && slot(e, i).isEmpty()) free.add(i);
		if (free.isEmpty()) return List.of();
		List<ItemStack> types = new ArrayList<>();
		List<Integer> totals = new ArrayList<>();
		for (int j = 0; j < e.in.getContainerSize(); j++) {
			ItemStack c = e.in.getItem(j);
			if (!c.isEmpty()) addType(types, totals, c);
		}
		List<Move> moves = new ArrayList<>();
		for (int t = 0; t < types.size() && !free.isEmpty(); t++) {
			ItemStack type = types.get(t);
			List<Integer> elig = new ArrayList<>();
			List<Integer> level = new ArrayList<>();
			List<Integer> room = new ArrayList<>();
			for (int i : free) {
				AbstractFurnaceBlockEntity f = usable(e, i);
				if (f == null || !smelts(e.level, f, type)) continue;
				ItemStack in0 = f.getItem(0);
				if (!in0.isEmpty() && !same(in0, type)) continue;
				int r = Math.min(BATCH, Math.min(type.getMaxStackSize(), f.getMaxStackSize())) - in0.getCount();
				if (r <= 0) continue;
				elig.add(i);
				level.add(in0.getCount());
				room.add(r);
			}
			if (elig.isEmpty()) continue;
			int[] got = new int[elig.size()];
			int left = totals.get(t);
			while (left > 0) {
				int best = -1;
				for (int k = 0; k < got.length; k++) {
					if (got[k] >= room.get(k)) continue;
					if (best < 0 || level.get(k) + got[k] < level.get(best) + got[best]) best = k;
				}
				if (best < 0) break;
				got[best]++;
				left--;
			}
			for (int k = 0; k < got.length; k++) {
				if (got[k] <= 0) continue;
				moves.add(new Move(elig.get(k), type.copyWithCount(got[k])));
				free.remove(elig.get(k));
			}
		}
		return moves;
	}

	/**
	 * Fuel for the furnaces that have something to cook and a low fuel slot (empty or at most half of
	 * {@link #FUEL_TARGET}), into their empty buffers. A furnace with fuel in it gets only that fuel item; a scarce fuel
	 * is shared one item at a time. A furnace whose fuel slot holds a non-fuel leftover is skipped until it is collected.
	 */
	private static List<Move> planFuel(Env e) {
		if (e.fuel == null) return List.of();
		List<ItemStack> types = new ArrayList<>();
		List<Integer> totals = new ArrayList<>();
		for (int j = 0; j < e.fuel.getContainerSize(); j++) {
			ItemStack c = e.fuel.getItem(j);
			if (isFuel(c)) addType(types, totals, c);
		}
		if (types.isEmpty()) return List.of();
		List<Integer> needy = new ArrayList<>();
		List<Integer> typeOf = new ArrayList<>();
		List<Integer> want = new ArrayList<>();
		for (int i = 0; i < FURNACES; i++) {
			AbstractFurnaceBlockEntity f = usable(e, i);
			if (f == null || !slot(e, i).isEmpty()) continue;
			ItemStack in0 = f.getItem(0);
			if (in0.isEmpty() || !smelts(e.level, f, in0)) continue;
			ItemStack fs = f.getItem(1);
			int t;
			int cap;
			if (fs.isEmpty()) {
				t = 0;
				cap = Math.min(FUEL_TARGET, types.get(0).getMaxStackSize());
			} else {
				if (!isFuel(fs)) continue;
				t = -1;
				for (int k = 0; k < types.size(); k++) if (same(types.get(k), fs)) t = k;
				if (t < 0) continue;
				cap = Math.min(FUEL_TARGET, fs.getMaxStackSize());
				if (fs.getCount() > cap / 2) continue;
			}
			int w = cap - fs.getCount();
			if (w <= 0) continue;
			needy.add(i);
			typeOf.add(t);
			want.add(w);
		}
		int[] got = new int[needy.size()];
		int[] pool = totals.stream().mapToInt(Integer::intValue).toArray();
		boolean progress = true;
		while (progress) {
			progress = false;
			for (int k = 0; k < got.length; k++) {
				int t = typeOf.get(k);
				if (got[k] < want.get(k) && pool[t] > 0) {
					got[k]++;
					pool[t]--;
					progress = true;
				}
			}
		}
		List<Move> moves = new ArrayList<>();
		for (int k = 0; k < got.length; k++) {
			if (got[k] > 0) moves.add(new Move(needy.get(k), types.get(typeOf.get(k)).copyWithCount(got[k])));
		}
		return moves;
	}

	private static void removeFrom(Container c, ItemStack like, int n) {
		for (int j = 0; j < c.getContainerSize() && n > 0; j++) {
			ItemStack s = c.getItem(j);
			if (!same(s, like)) continue;
			int t = Math.min(n, s.getCount());
			s.shrink(t);
			if (s.isEmpty()) c.setItem(j, ItemStack.EMPTY);
			n -= t;
		}
		c.setChanged();
	}

	// ---- per-furnace predicates ----

	/** Slot i holds raw items that furnace i can take now. */
	private static boolean feedable(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		ItemStack st = slot(e, i);
		if (f == null || st.isEmpty() || kindOf(e, i) != Kind.RAW) return false;
		ItemStack in0 = f.getItem(0);
		if (!in0.isEmpty() && !same(in0, st)) return false;
		return smelts(e.level, f, st) && Math.min(BATCH, Math.min(st.getMaxStackSize(), f.getMaxStackSize())) - in0.getCount() > 0;
	}

	/** Slot i holds fuel that furnace i's fuel slot can take now. */
	private static boolean fuelable(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		ItemStack st = slot(e, i);
		if (f == null || st.isEmpty() || kindOf(e, i) != Kind.FUEL || !isFuel(st)) return false;
		ItemStack fs = f.getItem(1);
		return (fs.isEmpty() || same(fs, st)) && st.getMaxStackSize() - fs.getCount() > 0;
	}

	private static boolean fuelLeftover(AbstractFurnaceBlockEntity f) {
		ItemStack fs = f.getItem(1);
		return !fs.isEmpty() && !isFuel(fs);
	}

	private static boolean collectable(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		if (f == null) return false;
		if (!f.getItem(2).isEmpty() && resultSpace(e.data, f.getItem(2)) > 0) return true;
		return fuelLeftover(f) && resultSpace(e.data, f.getItem(1)) > 0;
	}

	private static boolean chestOk(Env e, @Nullable BlockPos pos, @Nullable Container c) {
		return pos != null && c != null && !skipped(e.s, pos, e.now);
	}

	// ---- picking work ----

	private static @Nullable Work nearest(Env e, Phase ph, java.util.function.IntPredicate ok) {
		Work best = null;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < FURNACES; i++) {
			if ((e.s.served & (1 << i)) != 0 || !ok.test(i)) continue;
			BlockPos p = e.s.fur[i];
			double d = e.golem.distanceToSqr(Vec3.atCenterOf(p));
			if (d < bd) {
				bd = d;
				best = new Work(ph, p, i);
			}
		}
		return best;
	}

	private static @Nullable Work find(Env e, Phase ph) {
		switch (ph) {
			case FETCH:
				return chestOk(e, e.inPos, e.in) && !planFetch(e).isEmpty() ? new Work(ph, e.inPos, -1) : null;
			case FEED:
				return nearest(e, ph, i -> feedable(e, i));
			case FUEL_FETCH:
				return chestOk(e, e.fuelPos, e.fuel) && !planFuel(e).isEmpty() ? new Work(ph, e.fuelPos, -1) : null;
			case FUEL:
				return nearest(e, ph, i -> fuelable(e, i));
			case COLLECT:
				return nearest(e, ph, i -> collectable(e, i));
			case DELIVER:
				if (!chestOk(e, e.outPos, e.out) || e.now < e.s.outBlockedUntil) return null;
				return deliverSlots(e).isEmpty() ? null : new Work(ph, e.outPos, -1);
			default:
				for (int i = 0; i < FURNACES; i++) {
					Kind k = kindOf(e, i);
					if (k == null || e.now - e.s.changedAt[i] < RETURN_TICKS) continue;
					if (k == Kind.RAW && !feedable(e, i) && chestOk(e, e.inPos, e.in) && e.now >= e.s.inBlockedUntil) {
						return new Work(ph, e.inPos, i);
					}
					if (k == Kind.FUEL && !fuelable(e, i) && chestOk(e, e.fuelPos, e.fuel) && e.now >= e.s.fuelBlockedUntil) {
						return new Work(ph, e.fuelPos, i);
					}
				}
				return null;
		}
	}

	/** First phase from the current one that has work; entering a new phase starts a fresh pass (nobody served yet). */
	private static @Nullable Work pick(Env e) {
		Phase[] phases = Phase.values();
		for (int k = 0; k < phases.length; k++) {
			int idx = (e.s.phase + k) % phases.length;
			Work w = find(e, phases[idx]);
			if (w != null) {
				if (k > 0) e.s.served = 0;
				e.s.phase = idx;
				return w;
			}
		}
		e.s.served = 0;
		return null;
	}

	// ---- tick ----

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		// before the cooldown: until the list has grown GolemMenu:stillValid closes an open menu, so it must be one tick
		if (data.items.size() < SLOTS) {
			migrate(level, data);
			GolemData.save(golem, data);
		}
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		if (sweepXpSlot(data)) GolemData.save(golem, data);
		if (GolemXp.fillSlot(data, XP_SLOT)) GolemData.save(golem, data);
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		BlockPos in = data.assignments.get(SmelterProfession.INPUT);
		BlockPos fuel = data.assignments.get(SmelterProfession.FUEL);
		BlockPos out = data.assignments.get(SmelterProfession.OUTPUT);
		Env e = new Env(level, golem, data, s, now, in, in == null ? null : TransporterLogic.containerAt(level, in),
			fuel, fuel == null ? null : TransporterLogic.containerAt(level, fuel),
			out, out == null ? null : TransporterLogic.containerAt(level, out));
		for (int i = 0; i < FURNACES; i++) {
			ItemStack cur = slot(e, i);
			if (cur.isEmpty()) s.kind[i] = null;
			if (!ItemStack.matches(cur, s.seen[i])) {
				s.seen[i] = cur.copy();
				s.changedAt[i] = now;
			}
		}
		if (now >= s.scanAt) rescan(e);

		Work w = s.work;
		if (w != null && skipped(s, w.pos(), now)) w = null;
		if (w == null) w = pick(e);
		s.work = w;
		show(s, golem, w == null ? Phase.WAIT : w.phase());
		if (w == null) {
			golem.getNavigation().stop();
			s.target = null;
			s.status = idleStatus(e);
			s.cooldown = RETRY_TICKS;
			return;
		}
		s.status = "Smelting in " + usableCount(e) + (usableCount(e) == 1 ? " furnace" : " furnaces");
		if (!walkTo(e, w.pos())) {
			if (skipped(s, w.pos(), now + 1) || s.target == null) s.work = null;
			return;
		}
		golem.getLookControl().setLookAt(Vec3.atCenterOf(w.pos()));
		s.work = null;
		s.pending = false;
		s.cooldown = perform(e, w);
		if (s.pending) s.work = w;
		GolemData.save(golem, data);
	}

	/**
	 * Grows an older golem's list (8 or 9 slots) to {@code SLOTS}, items kept. Results an older version left in the
	 * furnace buffers move to the result slots: what is neither smeltable nor fuel is a result for sure (a smeltable one
	 * such as stone is not told from raw items and is simply fed or returned, never lost). Whatever does not fit the
	 * result slots stays in its buffer, where RETURN takes it back to the input chest.
	 */
	private static void migrate(ServerLevel level, GolemData data) {
		NonNullList<ItemStack> grown = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		for (int i = 0; i < data.items.size(); i++) grown.set(i, data.items.get(i));
		data.items = grown;
		for (int i = 0; i < FURNACES; i++) {
			ItemStack st = data.items.get(i);
			if (st.isEmpty() || smeltsAnywhere(level, st) || isFuel(st)) continue;
			int n = addResult(data, st, st.getCount());
			st.shrink(n);
			if (st.isEmpty()) data.items.set(i, ItemStack.EMPTY);
		}
	}

	/** An item an older version left in the XP slot (not a bottle) moves to the result slots; true if anything moved. */
	private static boolean sweepXpSlot(GolemData data) {
		ItemStack st = data.items.get(XP_SLOT);
		if (st.isEmpty() || st.is(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE)) return false;
		int n = addResult(data, st, st.getCount());
		st.shrink(n);
		if (st.isEmpty()) data.items.set(XP_SLOT, ItemStack.EMPTY);
		return n > 0;
	}

	private static String idleStatus(Env e) {
		if (e.data.assignments.get(SmelterProfession.FURNACES + "_a") == null) return "No furnace area set";
		int n = 0;
		for (int i = 0; i < FURNACES; i++) if (e.s.fur[i] != null) n++;
		if (n == 0) return "No furnaces in the area";
		if (usableCount(e) == 0) return "No reachable furnaces";
		if (!deliverSlots(e).isEmpty()) return "Output chest is full or missing";
		boolean cooking = false;
		boolean noFuel = false;
		for (int i = 0; i < FURNACES; i++) {
			AbstractFurnaceBlockEntity f = usable(e, i);
			if (f == null || f.getItem(0).isEmpty() || !smelts(e.level, f, f.getItem(0))) continue;
			cooking = true;
			if (f.getItem(1).isEmpty() && !lit(e.level, f.getBlockPos())) noFuel = true;
		}
		if (noFuel) return "Out of fuel";
		return cooking ? "Smelting in " + usableCount(e) + (usableCount(e) == 1 ? " furnace" : " furnaces") : "Nothing to smelt";
	}

	/** Does the work at the target; returns the cooldown. Every step re-checks its preconditions, so a stale plan does nothing. */
	private static int perform(Env e, Work w) {
		State s = e.s;
		switch (w.phase()) {
			case FETCH -> {
				List<Move> moves = s.moves != null ? s.moves : planFetch(e);
				s.moves = null;
				return fetched(e, w, e.in, moves, Kind.RAW);
			}
			case FUEL_FETCH -> {
				List<Move> moves = s.moves != null ? s.moves : planFuel(e);
				s.moves = null;
				return fetched(e, w, e.fuel, moves, Kind.FUEL);
			}
			case FEED -> {
				if (feedable(e, w.slot())) {
					AbstractFurnaceBlockEntity f = usable(e, w.slot());
					ItemStack st = e.data.items.get(w.slot());
					ItemStack in0 = f.getItem(0);
					int n = Math.min(st.getCount(), Math.min(BATCH, Math.min(st.getMaxStackSize(), f.getMaxStackSize())) - in0.getCount());
					int g = Math.min(n, TransferBudget.remaining(e.level, e.golem));
					if (g > 0) {
						f.setItem(0, st.copyWithCount(in0.getCount() + g));
						st.shrink(g);
						if (st.isEmpty()) e.data.items.set(w.slot(), ItemStack.EMPTY);
						TransferBudget.spend(e.level, e.golem, g);
					}
					if (g < n) return cutShort(e);
				} else {
					s.scanAt = 0;
				}
				s.served |= 1 << w.slot();
				return ACTION_TICKS;
			}
			case FUEL -> {
				if (fuelable(e, w.slot())) {
					AbstractFurnaceBlockEntity f = usable(e, w.slot());
					ItemStack st = e.data.items.get(w.slot());
					ItemStack fs = f.getItem(1);
					int n = Math.min(st.getCount(), st.getMaxStackSize() - fs.getCount());
					int g = Math.min(n, TransferBudget.remaining(e.level, e.golem));
					if (g > 0) {
						f.setItem(1, st.copyWithCount(fs.getCount() + g));
						st.shrink(g);
						if (st.isEmpty()) e.data.items.set(w.slot(), ItemStack.EMPTY);
						TransferBudget.spend(e.level, e.golem, g);
					}
					if (g < n) return cutShort(e);
				} else {
					s.scanAt = 0;
				}
				s.served |= 1 << w.slot();
				return ACTION_TICKS;
			}
			case COLLECT -> {
				if (collect(e, w.slot())) return cutShort(e);
				s.served |= 1 << w.slot();
				return ACTION_TICKS;
			}
			case DELIVER -> {
				List<Integer> idxs = deliverSlots(e);
				boolean moved = moveOut(e, idxs, e.out);
				boolean left = false;
				for (int i : idxs) left |= !e.data.items.get(i).isEmpty();
				boolean cut = left && TransferBudget.exhausted(e.level, e.golem);
				if (left && !cut) s.outBlockedUntil = e.now + BLOCK_TICKS;
				return opened(e, w, moved, Interact.Action.INSERT, cut);
			}
			default -> {
				boolean raw = kindOf(e, w.slot()) == Kind.RAW;
				Container c = raw ? e.in : e.fuel;
				boolean moved = moveOut(e, List.of(w.slot()), c);
				boolean left = !e.data.items.get(w.slot()).isEmpty();
				boolean cut = left && TransferBudget.exhausted(e.level, e.golem);
				if (left && !cut) {
					if (raw) s.inBlockedUntil = e.now + BLOCK_TICKS; else s.fuelBlockedUntil = e.now + BLOCK_TICKS;
				}
				return opened(e, w, moved, Interact.Action.INSERT, cut);
			}
		}
	}

	/** The budget ran out mid-transfer (not "chest full" or "nothing to do"): the work stays and goes on next window. */
	private static int cutShort(Env e) {
		e.s.pending = true;
		return TransferBudget.waitTicks(e.level);
	}

	/**
	 * Takes the planned moves from {@code chest} into the buffers, as far as the budget allows. What is left because of the
	 * budget is kept as the plan for the next window; a move the chest can no longer fill is dropped.
	 */
	private static int fetched(Env e, Work w, Container chest, List<Move> moves, Kind kind) {
		List<Move> rest = new ArrayList<>();
		boolean moved = false;
		for (Move m : moves) {
			int want = m.stack().getCount();
			int avail = 0;
			for (int j = 0; j < chest.getContainerSize(); j++) if (same(chest.getItem(j), m.stack())) avail += chest.getItem(j).getCount();
			int budget = TransferBudget.remaining(e.level, e.golem);
			int n = Math.min(Math.min(want, avail), budget);
			ItemStack cur = e.data.items.get(m.slot());
			if (n > 0 && (cur.isEmpty() || same(cur, m.stack()))) {
				removeFrom(chest, m.stack(), n);
				if (cur.isEmpty()) e.data.items.set(m.slot(), m.stack().copyWithCount(n)); else cur.grow(n);
				e.s.kind[m.slot()] = kind;
				TransferBudget.spend(e.level, e.golem, n);
				moved = true;
			} else {
				n = 0;
			}
			if (n < Math.min(want, avail) && budget - n <= 0) rest.add(new Move(m.slot(), m.stack().copyWithCount(want - n)));
		}
		if (rest.isEmpty()) return opened(e, w, moved, Interact.Action.TAKE, false);
		e.s.moves = rest;
		return opened(e, w, moved, Interact.Action.TAKE, true);
	}

	private static int opened(Env e, Work w, boolean moved, Interact.Action action, boolean cut) {
		if (cut) {
			Interact.openChest(e.level, e.golem, w.pos(), action);
			return cutShort(e);
		}
		if (moved) {
			Interact.openChest(e.level, e.golem, w.pos(), action);
			e.s.walk.reset();
			return Interact.OPEN_TICKS;
		}
		return RETRY_TICKS;
	}

	/**
	 * Takes the result (or the fuel slot's leftover, e.g. the empty bucket of lava) of furnace i into the shared result slots and moves
	 * the furnace's stored XP into the golem's store ({@link #takeFurnaceXp}).
	 */
	private static boolean collect(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		if (f == null) {
			e.s.scanAt = 0;
			return false;
		}
		boolean result = !f.getItem(2).isEmpty() && resultSpace(e.data, f.getItem(2)) > 0;
		int idx = result ? 2 : 1;
		ItemStack src = f.getItem(idx);
		if (src.isEmpty() || !(result || fuelLeftover(f))) return false;
		int full = Math.min(src.getCount(), resultSpace(e.data, src));
		if (full <= 0) return false;
		int n = Math.min(full, TransferBudget.remaining(e.level, e.golem));
		if (n <= 0) return true;
		TransferBudget.spend(e.level, e.golem, n);
		addResult(e.data, src, n);
		ItemStack rest = src.copyWithCount(src.getCount() - n);
		f.setItem(idx, rest.isEmpty() ? ItemStack.EMPTY : rest);
		f.setChanged();
		if (n < full) return true; // cut short by the budget: the XP is paid with the last part
		if (result) {
			GolemXp.add(e.data, takeFurnaceXp(e.level, f));
		}
		return false;
	}

	/**
	 * The XP the furnace has stored, computed like vanilla's {@code createExperience} (recipe XP x count, the fraction is
	 * rounded up with that probability), then the map is cleared (vanilla does that only for a player), so it is paid once
	 * and cannot grow forever. No orbs are spawned: the golem keeps the XP ({@link GolemXp}).
	 */
	private static int takeFurnaceXp(ServerLevel level, AbstractFurnaceBlockEntity f) {
		var used = ((AbstractFurnaceAccessor) f).cgr$recipesUsed();
		int total = 0;
		for (var en : used.reference2IntEntrySet()) {
			var holder = level.recipeAccess().byKey(en.getKey());
			if (holder.isEmpty() || !(holder.get().value() instanceof AbstractCookingRecipe r)) continue;
			float xp = en.getIntValue() * r.experience();
			int whole = Mth.floor(xp);
			float frac = Mth.frac(xp);
			if (frac != 0.0F && level.getRandom().nextFloat() < frac) whole++;
			total += whole;
		}
		used.clear();
		return total;
	}

	/** Moves the stacks at {@code idxs} into {@code dst}; what does not fit stays in its slot (nothing lost). */
	private static boolean moveOut(Env e, List<Integer> idxs, Container dst) {
		NonNullList<ItemStack> carry = NonNullList.create();
		for (int idx : idxs) carry.add(e.data.items.get(idx));
		boolean moved = TransferBudget.insert(e.level, e.golem, carry, dst) > 0;
		for (int k = 0; k < idxs.size(); k++) e.data.items.set(idxs.get(k), carry.get(k));
		return moved;
	}

	// ---- walking ----

	/**
	 * One tick of walking to the 1-block stand spot of {@code target}; true once there. Not getting closer for
	 * {@link #GIVE_UP_TICKS} (airborne ticks do not count) gives the target up for {@link #SKIP_TICKS}: the golem carries
	 * on with the other furnaces and chests instead of idling.
	 */
	private static boolean walkTo(Env e, BlockPos target) {
		State s = e.s;
		CopperGolem golem = e.golem;
		double dist = golem.distanceToSqr(Vec3.atBottomCenterOf(target));
		if (!target.equals(s.target)) {
			s.target = target;
			s.bestDist = dist;
			s.stuck = 0;
		}
		if (Interact.approach(e.level, golem, target, s.walk, SPEED)) {
			s.stuck = 0;
			s.bestDist = dist;
			return true;
		}
		if (dist < s.bestDist - PROGRESS_EPS) {
			s.bestDist = dist;
			s.stuck = 0;
		} else if (golem.onGround() && ++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(target, e.now + SKIP_TICKS);
			s.walk.reset();
			golem.getNavigation().stop();
			s.target = null;
			s.stuck = 0;
		}
		return false;
	}
}
