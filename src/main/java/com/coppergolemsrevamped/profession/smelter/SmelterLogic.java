package com.coppergolemsrevamped.profession.smelter;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;
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
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
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
	/** Results below this wait in a furnace that is still burning with input left (fewer trips). */
	static final int COLLECT_MIN = 8;
	static final int MAX_Y_SPAN = 32;

	private static final RecipeManager.CachedCheck<SingleRecipeInput, SmeltingRecipe> SMELT = RecipeManager.createCheck(RecipeType.SMELTING);
	private static final RecipeManager.CachedCheck<SingleRecipeInput, BlastingRecipe> BLAST = RecipeManager.createCheck(RecipeType.BLASTING);
	private static final RecipeManager.CachedCheck<SingleRecipeInput, SmokingRecipe> SMOKE = RecipeManager.createCheck(RecipeType.SMOKING);

	/** What a buffer slot holds. Transient; after a restart it is derived from the item ({@link #kindOf}). */
	enum Kind { RAW, FUEL, RESULT }

	/** Order of a pass. */
	enum Phase { FETCH, FEED, FUEL_FETCH, FUEL, COLLECT, DELIVER, RETURN }

	record Work(Phase phase, BlockPos pos, int slot) {}

	record Move(int slot, ItemStack stack) {}

	private static final class State {
		int cooldown;
		int phase;
		int served;
		@Nullable Work work;
		final BlockPos[] fur = new BlockPos[SLOTS];
		long scanAt;
		final Kind[] kind = new Kind[SLOTS];
		final ItemStack[] seen = new ItemStack[SLOTS];
		final long[] changedAt = new long[SLOTS];
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
	 * What slot i holds. The flag set when the golem filled it wins; without one (restart, a player's edit) it is derived:
	 * smeltable -> RAW, fuel -> FUEL, anything else -> RESULT (delivered). Why derived this way: an unknown item must
	 * never be pushed into a furnace it does not fit, and an unusable one must reach a chest.
	 */
	private static @Nullable Kind kindOf(Env e, int i) {
		ItemStack st = slot(e, i);
		if (st.isEmpty()) return null;
		Kind k = e.s.kind[i];
		if (k != null) return k;
		if (smeltsAnywhere(e.level, st)) return Kind.RAW;
		return isFuel(st) ? Kind.FUEL : Kind.RESULT;
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
		for (int i = 0; i < SLOTS; i++) if (usable(e, i) != null) n++;
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
		for (int i = 0; i < SLOTS; i++) {
			if (s.fur[i] != null && !inArea.contains(s.fur[i])) s.fur[i] = null;
			if (s.fur[i] != null) have.add(s.fur[i]);
		}
		Vec3 ref = e.inPos != null ? Vec3.atCenterOf(e.inPos) : e.golem.position();
		List<BlockPos> fresh = new ArrayList<>();
		for (BlockPos p : area) if (!have.contains(p)) fresh.add(p);
		fresh.sort(Comparator.<BlockPos>comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(ref))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		int next = 0;
		for (int i = 0; i < SLOTS && next < fresh.size(); i++) if (s.fur[i] == null) s.fur[i] = fresh.get(next++);
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
		for (int i = 0; i < SLOTS; i++) if (usable(e, i) != null && slot(e, i).isEmpty()) free.add(i);
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
				int r = Math.min(type.getMaxStackSize(), f.getMaxStackSize()) - in0.getCount();
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
		for (int i = 0; i < SLOTS; i++) {
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
		return smelts(e.level, f, st) && Math.min(st.getMaxStackSize(), f.getMaxStackSize()) - in0.getCount() > 0;
	}

	/** Slot i holds fuel that furnace i's fuel slot can take now. */
	private static boolean fuelable(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		ItemStack st = slot(e, i);
		if (f == null || st.isEmpty() || kindOf(e, i) != Kind.FUEL || !isFuel(st)) return false;
		ItemStack fs = f.getItem(1);
		return (fs.isEmpty() || same(fs, st)) && st.getMaxStackSize() - fs.getCount() > 0;
	}

	/** Room for {@code incoming} in buffer i as a RESULT stack. */
	private static boolean resultRoom(Env e, int i, ItemStack incoming) {
		ItemStack st = slot(e, i);
		if (st.isEmpty()) return true;
		return kindOf(e, i) == Kind.RESULT && same(st, incoming) && st.getCount() < st.getMaxStackSize();
	}

	/** The result is worth a trip: plenty of it, or the furnace is done, cannot cook its input, or has no fuel burning. */
	private static boolean resultReady(Env e, AbstractFurnaceBlockEntity f) {
		ItemStack res = f.getItem(2);
		if (res.isEmpty()) return false;
		ItemStack in0 = f.getItem(0);
		return res.getCount() >= COLLECT_MIN || in0.isEmpty() || !smelts(e.level, f, in0) || !lit(e.level, f.getBlockPos());
	}

	private static boolean fuelLeftover(AbstractFurnaceBlockEntity f) {
		ItemStack fs = f.getItem(1);
		return !fs.isEmpty() && !isFuel(fs);
	}

	private static boolean collectable(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		if (f == null) return false;
		if (resultReady(e, f) && resultRoom(e, i, f.getItem(2))) return true;
		return fuelLeftover(f) && resultRoom(e, i, f.getItem(1));
	}

	private static boolean chestOk(Env e, @Nullable BlockPos pos, @Nullable Container c) {
		return pos != null && c != null && !skipped(e.s, pos, e.now);
	}

	// ---- picking work ----

	private static @Nullable Work nearest(Env e, Phase ph, java.util.function.IntPredicate ok) {
		Work best = null;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < SLOTS; i++) {
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
				for (int i = 0; i < SLOTS; i++) if (kindOf(e, i) == Kind.RESULT) return new Work(ph, e.outPos, -1);
				return null;
			default:
				for (int i = 0; i < SLOTS; i++) {
					Kind k = kindOf(e, i);
					if (k == null || k == Kind.RESULT || e.now - e.s.changedAt[i] < RETURN_TICKS) continue;
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
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		if (data.items.size() < SLOTS) {
			NonNullList<ItemStack> grown = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
			for (int i = 0; i < data.items.size(); i++) grown.set(i, data.items.get(i));
			data.items = grown;
			GolemData.save(golem, data);
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		BlockPos in = data.assignments.get(SmelterProfession.INPUT);
		BlockPos fuel = data.assignments.get(SmelterProfession.FUEL);
		BlockPos out = data.assignments.get(SmelterProfession.OUTPUT);
		Env e = new Env(level, golem, data, s, now, in, in == null ? null : TransporterLogic.containerAt(level, in),
			fuel, fuel == null ? null : TransporterLogic.containerAt(level, fuel),
			out, out == null ? null : TransporterLogic.containerAt(level, out));
		for (int i = 0; i < SLOTS; i++) {
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
		s.cooldown = perform(e, w);
		GolemData.save(golem, data);
	}

	private static String idleStatus(Env e) {
		if (e.data.assignments.get(SmelterProfession.FURNACES + "_a") == null) return "No furnace area set";
		int n = 0;
		for (int i = 0; i < SLOTS; i++) if (e.s.fur[i] != null) n++;
		if (n == 0) return "No furnaces in the area";
		if (usableCount(e) == 0) return "No reachable furnaces";
		boolean holdsResults = false;
		for (int i = 0; i < SLOTS; i++) holdsResults |= kindOf(e, i) == Kind.RESULT;
		if (holdsResults) return "Output chest is full or missing";
		boolean cooking = false;
		boolean noFuel = false;
		for (int i = 0; i < SLOTS; i++) {
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
				List<Move> moves = planFetch(e);
				for (Move m : moves) {
					removeFrom(e.in, m.stack(), m.stack().getCount());
					e.data.items.set(m.slot(), m.stack().copy());
					s.kind[m.slot()] = Kind.RAW;
				}
				return opened(e, w, !moves.isEmpty(), Interact.Action.TAKE);
			}
			case FUEL_FETCH -> {
				List<Move> moves = planFuel(e);
				for (Move m : moves) {
					removeFrom(e.fuel, m.stack(), m.stack().getCount());
					e.data.items.set(m.slot(), m.stack().copy());
					s.kind[m.slot()] = Kind.FUEL;
				}
				return opened(e, w, !moves.isEmpty(), Interact.Action.TAKE);
			}
			case FEED -> {
				s.served |= 1 << w.slot();
				if (feedable(e, w.slot())) {
					AbstractFurnaceBlockEntity f = usable(e, w.slot());
					ItemStack st = e.data.items.get(w.slot());
					ItemStack in0 = f.getItem(0);
					int n = Math.min(st.getCount(), Math.min(st.getMaxStackSize(), f.getMaxStackSize()) - in0.getCount());
					f.setItem(0, st.copyWithCount(in0.getCount() + n));
					st.shrink(n);
					if (st.isEmpty()) e.data.items.set(w.slot(), ItemStack.EMPTY);
				} else {
					s.scanAt = 0;
				}
				return ACTION_TICKS;
			}
			case FUEL -> {
				s.served |= 1 << w.slot();
				if (fuelable(e, w.slot())) {
					AbstractFurnaceBlockEntity f = usable(e, w.slot());
					ItemStack st = e.data.items.get(w.slot());
					ItemStack fs = f.getItem(1);
					int n = Math.min(st.getCount(), st.getMaxStackSize() - fs.getCount());
					f.setItem(1, st.copyWithCount(fs.getCount() + n));
					st.shrink(n);
					if (st.isEmpty()) e.data.items.set(w.slot(), ItemStack.EMPTY);
				} else {
					s.scanAt = 0;
				}
				return ACTION_TICKS;
			}
			case COLLECT -> {
				s.served |= 1 << w.slot();
				collect(e, w.slot());
				return ACTION_TICKS;
			}
			case DELIVER -> {
				List<Integer> idxs = new ArrayList<>();
				for (int i = 0; i < SLOTS; i++) if (kindOf(e, i) == Kind.RESULT) idxs.add(i);
				boolean moved = moveOut(e.data, idxs, e.out);
				boolean left = false;
				for (int i : idxs) left |= !e.data.items.get(i).isEmpty();
				if (left) s.outBlockedUntil = e.now + BLOCK_TICKS;
				return opened(e, w, moved, Interact.Action.INSERT);
			}
			default -> {
				boolean raw = kindOf(e, w.slot()) == Kind.RAW;
				Container c = raw ? e.in : e.fuel;
				boolean moved = moveOut(e.data, List.of(w.slot()), c);
				if (!e.data.items.get(w.slot()).isEmpty()) {
					if (raw) s.inBlockedUntil = e.now + BLOCK_TICKS; else s.fuelBlockedUntil = e.now + BLOCK_TICKS;
				}
				return opened(e, w, moved, Interact.Action.INSERT);
			}
		}
	}

	private static int opened(Env e, Work w, boolean moved, Interact.Action action) {
		if (moved) {
			Interact.openChest(e.level, e.golem, w.pos(), action);
			e.s.walk.reset();
			return Interact.OPEN_TICKS;
		}
		return RETRY_TICKS;
	}

	/**
	 * Takes the result (or the fuel slot's leftover, e.g. the empty bucket of lava) of furnace i into its buffer and pops
	 * the furnace's stored XP at the furnace. The map is cleared afterwards (vanilla does that only for a player), so the XP
	 * is paid once and the map cannot grow forever.
	 */
	private static void collect(Env e, int i) {
		AbstractFurnaceBlockEntity f = usable(e, i);
		if (f == null) {
			e.s.scanAt = 0;
			return;
		}
		boolean result = resultReady(e, f) && resultRoom(e, i, f.getItem(2));
		int idx = result ? 2 : 1;
		ItemStack src = f.getItem(idx);
		if (src.isEmpty() || !(result || fuelLeftover(f))) return;
		ItemStack st = e.data.items.get(i);
		int n = Math.min(src.getCount(), src.getMaxStackSize() - st.getCount());
		if (n <= 0) return;
		if (st.isEmpty()) e.data.items.set(i, src.copyWithCount(n)); else st.grow(n);
		e.s.kind[i] = Kind.RESULT;
		ItemStack rest = src.copyWithCount(src.getCount() - n);
		f.setItem(idx, rest.isEmpty() ? ItemStack.EMPTY : rest);
		if (result) {
			f.getRecipesToAwardAndPopExperience(e.level, Vec3.atCenterOf(f.getBlockPos()));
			((AbstractFurnaceAccessor) f).cgr$recipesUsed().clear();
		}
		f.setChanged();
	}

	/** Moves the stacks at {@code idxs} into {@code dst}; what does not fit stays in its slot (nothing lost). */
	private static boolean moveOut(GolemData data, List<Integer> idxs, Container dst) {
		NonNullList<ItemStack> carry = NonNullList.create();
		for (int idx : idxs) carry.add(data.items.get(idx));
		boolean moved = TransporterLogic.insertAll(carry, dst);
		for (int k = 0; k < idxs.size(); k++) data.items.set(idxs.get(k), carry.get(k));
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
