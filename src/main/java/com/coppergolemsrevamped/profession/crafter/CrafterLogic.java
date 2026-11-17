package com.coppergolemsrevamped.profession.crafter;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Crafter work loop. {@link GolemData#items}: 0..8 template, 9..17 input slots (slot 9+i holds only template cell
 * i's item), 18 the output stack. The decision is stateless (derived from the slots, the input chest and the recipe
 * each tick); only timers and walk progress are transient. See superdoc/features/crafter.md.
 */
public final class CrafterLogic {
	private CrafterLogic() {}

	static final int RETRY_TICKS = 40;
	static final int CRAFT_TICKS = 10;
	static final double SPEED = 1.0;
	/** Walking without getting closer for this long = unreachable (10 s); the golem then idles before retrying. */
	static final int GIVE_UP_TICKS = 200;
	static final int GIVE_UP_IDLE_TICKS = 600;
	static final double PROGRESS_EPS = 0.1;
	static final int STACK = 64;

	private static final RecipeManager.CachedCheck<CraftingInput, CraftingRecipe> CHECK =
		RecipeManager.createCheck(RecipeType.CRAFTING);

	/**
	 * A resolved recipe for the template. {@code cell[i]} = template cell i (one item) or EMPTY, {@code leftover[i]} =
	 * what crafting leaves in cell i (empty bucket, bottle) or EMPTY.
	 */
	public record Plan(ItemStack result, ItemStack[] cell, ItemStack[] leftover) {}

	private static final class State {
		int cooldown;
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
		/** FETCH or DELIVER cut short by the transfer budget: it goes on before anything else is decided. */
		@Nullable Job sticky;
		/** Where the fetch round-robin continues after a cut (see {@link #fetch}). */
		final int[] cursor = new int[1];
		final Interact.Approach walk = new Interact.Approach();
		/** The last {@link #plan} and the template and recipe manager it was made for (see {@link #cachedPlan}). */
		@Nullable Plan plan;
		final ItemStack[] planFor = new ItemStack[CrafterProfession.TEMPLATE_SLOTS];
		@Nullable Object planRecipes;
		long plannedAt;
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Drops this golem's transient state ({@link com.coppergolemsrevamped.profession.Profession.Forget}). */
	public static void forget(CopperGolem golem) {
		STATES.remove(golem);
	}

	// ---- recipe ----

	/** The server's crafting recipe for the template (trimmed like the vanilla crafting menu), or null when none. */
	public static @Nullable Plan plan(ServerLevel level, GolemData data) {
		List<ItemStack> grid = new ArrayList<>();
		ItemStack[] cell = new ItemStack[CrafterProfession.TEMPLATE_SLOTS];
		for (int i = 0; i < cell.length; i++) {
			ItemStack s = i < data.items.size() ? data.items.get(i) : ItemStack.EMPTY;
			cell[i] = s.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(1);
			grid.add(cell[i]);
		}
		CraftingInput.Positioned pos = CraftingInput.ofPositioned(3, 3, grid);
		CraftingInput input = pos.input();
		if (input.isEmpty()) return null;
		Optional<RecipeHolder<CraftingRecipe>> r = CHECK.getRecipeFor(input, level);
		if (r.isEmpty()) return null;
		CraftingRecipe recipe = r.get().value();
		ItemStack result = recipe.assemble(input);
		if (result.isEmpty()) return null;
		ItemStack[] leftover = new ItemStack[cell.length];
		java.util.Arrays.fill(leftover, ItemStack.EMPTY);
		NonNullList<ItemStack> rem = recipe.getRemainingItems(input);
		for (int j = 0; j < rem.size(); j++) {
			int c = (pos.top() + j / input.width()) * 3 + pos.left() + j % input.width();
			if (c >= 0 && c < leftover.length) leftover[c] = rem.get(j).copy();
		}
		return new Plan(result, cell, leftover);
	}

	/** A cached {@link #plan} is reused at most this long even when the template is unchanged (a datapack reload safety net). */
	static final int REPLAN_TICKS = 100;

	/**
	 * {@link #plan} for the tick loop, reused while the template cells and the server's recipe manager are the same.
	 * Why: the recipe lookup, assemble and remaining-items calls ran on every walking tick (perf review), but the plan
	 * depends only on the template. {@link Plan} stacks are never mutated by the loop (it copies them), so sharing is safe.
	 */
	private static @Nullable Plan cachedPlan(ServerLevel level, GolemData data, State s) {
		Object recipes = level.getServer().getRecipeManager();
		long now = level.getGameTime();
		boolean same = recipes == s.planRecipes && now - s.plannedAt < REPLAN_TICKS;
		for (int i = 0; i < s.planFor.length && same; i++) {
			ItemStack t = at(data, i), c = s.planFor[i];
			same = c != null && (t.isEmpty() ? c.isEmpty() : !c.isEmpty() && ItemStack.isSameItemSameComponents(t, c));
		}
		if (same) return s.plan;
		s.plan = plan(level, data);
		for (int i = 0; i < s.planFor.length; i++) s.planFor[i] = at(data, i).copyWithCount(1);
		s.planRecipes = recipes;
		s.plannedAt = now;
		return s.plan;
	}

	/** What the template would craft right now (one craft's result), or EMPTY: the Inventory tab's ghost output. */
	public static ItemStack preview(ServerLevel level, GolemData data) {
		Plan p = plan(level, data);
		return p == null ? ItemStack.EMPTY : p.result();
	}

	// ---- slot helpers (all on GolemData.items) ----

	private static ItemStack at(GolemData data, int idx) {
		return idx < data.items.size() ? data.items.get(idx) : ItemStack.EMPTY;
	}

	private static boolean same(ItemStack a, ItemStack b) {
		return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
	}

	private static boolean used(Plan p, int cell) {
		return !p.cell()[cell].isEmpty();
	}

	/** Input slot i holds something that does not belong there (template changed, leftover) or a leftover cell is overstocked. */
	private static boolean stale(GolemData data, @Nullable Plan p, int i) {
		ItemStack s = at(data, CrafterProfession.FIRST_INPUT + i);
		if (s.isEmpty()) return false;
		ItemStack t = at(data, i);
		if (!same(t, s)) return true;
		// a cell whose craft leaves a leftover holds exactly one item, so the leftover has the emptied slot to go to
		return p != null && !p.leftover()[i].isEmpty() && s.getCount() > 1;
	}

	private static boolean anyStale(GolemData data, @Nullable Plan p) {
		for (int i = 0; i < CrafterProfession.INPUT_SLOTS; i++) if (stale(data, p, i)) return true;
		return false;
	}

	private static int outMax(Plan p) {
		return Math.min(STACK, p.result().getMaxStackSize());
	}

	/** Free room in the output stack for this recipe's result; 0 when it holds a different item. */
	static int outRoom(GolemData data, Plan p) {
		ItemStack out = at(data, CrafterProfession.OUTPUT_SLOT);
		if (out.isEmpty()) return outMax(p);
		return same(out, p.result()) ? Math.max(0, outMax(p) - out.getCount()) : 0;
	}

	private static boolean outputHasItems(GolemData data) {
		return !at(data, CrafterProfession.OUTPUT_SLOT).isEmpty();
	}

	/** Every used cell's input slot holds its item, the result fits the output, and each leftover has a place. */
	public static boolean canCraft(GolemData data, Plan p) {
		if (outRoom(data, p) < p.result().getCount()) return false;
		for (int i = 0; i < CrafterProfession.TEMPLATE_SLOTS; i++) {
			if (!used(p, i)) continue;
			ItemStack s = at(data, CrafterProfession.FIRST_INPUT + i);
			if (!same(s, p.cell()[i])) return false;
			ItemStack lo = p.leftover()[i];
			if (lo.isEmpty()) continue;
			int left = s.getCount() - 1;
			if (left == 0) continue;
			if (!same(s, lo) || left + lo.getCount() > Math.min(STACK, s.getMaxStackSize())) return false;
		}
		return true;
	}

	/** One craft: 1 item from every used input slot, leftovers into the emptied slot, the result into the output. */
	static void craft(GolemData data, Plan p) {
		for (int i = 0; i < CrafterProfession.TEMPLATE_SLOTS; i++) {
			if (!used(p, i)) continue;
			int idx = CrafterProfession.FIRST_INPUT + i;
			ItemStack s = data.items.get(idx);
			s.shrink(1);
			ItemStack lo = p.leftover()[i];
			if (s.isEmpty()) data.items.set(idx, lo.isEmpty() ? ItemStack.EMPTY : lo.copy());
			else if (!lo.isEmpty()) s.grow(lo.getCount());
		}
		int o = CrafterProfession.OUTPUT_SLOT;
		ItemStack out = data.items.get(o);
		if (out.isEmpty()) data.items.set(o, p.result().copy());
		else out.grow(p.result().getCount());
	}

	/** How many items input slot i should hold: a full stack, capped to what fills the output; 1 when a leftover returns there. */
	static int target(GolemData data, Plan p, int i) {
		if (!p.leftover()[i].isEmpty()) return 1;
		int crafts = Math.max(1, outRoom(data, p) / p.result().getCount());
		return Math.min(crafts, Math.min(STACK, p.cell()[i].getMaxStackSize()));
	}

	/** The chest holds something of {@code like} that may be taken (keep layout: not the last item of a stackable slot). */
	private static boolean chestHas(Container c, ItemStack like, boolean keepLayout) {
		for (int j = 0; j < c.getContainerSize(); j++) {
			ItemStack s = c.getItem(j);
			if (same(s, like) && TransporterLogic.takeable(c, s, keepLayout) > 0) return true;
		}
		return false;
	}

	/** Some used input slot is below its target and the chest holds its item. */
	static boolean canFetch(GolemData data, Plan p, Container cin) {
		for (int i = 0; i < CrafterProfession.TEMPLATE_SLOTS; i++) {
			if (!used(p, i)) continue;
			ItemStack s = at(data, CrafterProfession.FIRST_INPUT + i);
			if (s.getCount() < target(data, p, i) && chestHas(cin, p.cell()[i], data.keepLayout)) return true;
		}
		return false;
	}

	/**
	 * Tops up the used input slots from the chest, one item at a time round-robin over the cells, so a scarce item is
	 * shared fairly (8 planks for 2 cells = 4 each) instead of the first cell taking all. Moves at most {@code limit}
	 * items and returns how many. {@code cursor[0]} is the cell the round-robin continues at: a fetch cut short by the
	 * transfer budget resumes there, so the split stays fair across windows.
	 */
	static int fetch(GolemData data, Plan p, Container cin, int limit, int[] cursor) {
		int n = CrafterProfession.TEMPLATE_SLOTS;
		int[] want = new int[n];
		for (int i = 0; i < n; i++) {
			if (used(p, i)) want[i] = target(data, p, i) - at(data, CrafterProfession.FIRST_INPUT + i).getCount();
		}
		int moved = 0;
		int i = cursor[0] % n;
		int idle = 0;
		while (moved < limit && idle < n) {
			boolean took = false;
			if (want[i] > 0) {
				for (int j = 0; j < cin.getContainerSize() && !took; j++) {
					ItemStack c = cin.getItem(j);
					if (!same(c, p.cell()[i]) || TransporterLogic.takeable(cin, c, data.keepLayout) <= 0) continue;
					ItemStack one = c.split(1);
					if (c.isEmpty()) cin.setItem(j, ItemStack.EMPTY);
					int idx = CrafterProfession.FIRST_INPUT + i;
					ItemStack s = data.items.get(idx);
					if (s.isEmpty()) data.items.set(idx, one); else s.grow(1);
					want[i]--;
					moved++;
					took = true;
				}
			}
			idle = took ? 0 : idle + 1;
			i = (i + 1) % n;
		}
		cursor[0] = i;
		if (moved > 0) cin.setChanged();
		return moved;
	}

	/**
	 * Moves the stacks at {@code idxs} into {@code cout}; what does not fit stays in its slot (nothing lost).
	 * Moves at most {@code limit} items; returns how many moved.
	 */
	static int deliver(GolemData data, List<Integer> idxs, Container cout, int limit) {
		NonNullList<ItemStack> carry = NonNullList.create();
		for (int idx : idxs) carry.add(data.items.get(idx));
		int moved = TransporterLogic.insertAll(carry, cout, limit, data.keepLayout);
		for (int k = 0; k < idxs.size(); k++) data.items.set(idxs.get(k), carry.get(k));
		return moved;
	}

	// ---- loop ----

	enum Job { FETCH, CRAFT, DELIVER, DELIVER_ALL, IDLE }

	private static Job decide(GolemData data, @Nullable Plan p, Container cin) {
		if (p == null) return Job.IDLE;
		if (anyStale(data, p)) return Job.DELIVER;
		if (canCraft(data, p)) return Job.CRAFT;
		boolean outFull = outRoom(data, p) < p.result().getCount();
		if (outFull) return outputHasItems(data) ? Job.DELIVER : Job.IDLE;
		if (canFetch(data, p, cin)) return Job.FETCH;
		return outputHasItems(data) ? Job.DELIVER : Job.IDLE;
	}

	/** Data slots a delivery moves out, without the output slot: the stale input slots, or all of them (no recipe). */
	private static List<Integer> deliverIdxs(GolemData data, @Nullable Plan plan, boolean all) {
		List<Integer> idxs = new ArrayList<>();
		for (int i = 0; i < CrafterProfession.INPUT_SLOTS; i++) {
			if (all ? !at(data, CrafterProfession.FIRST_INPUT + i).isEmpty() : stale(data, plan, i)) idxs.add(CrafterProfession.FIRST_INPUT + i);
		}
		return idxs;
	}

	private static boolean holdsAnything(GolemData data) {
		for (int i = CrafterProfession.FIRST_INPUT; i < CrafterProfession.TOTAL_SLOTS; i++) {
			if (!at(data, i).isEmpty()) return true;
		}
		return false;
	}

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		// golems saved with the older 18-slot layout: the old buffer slots are now input slots (delivered as stale)
		if (data.items.size() < CrafterProfession.TOTAL_SLOTS) {
			NonNullList<ItemStack> grown = NonNullList.withSize(CrafterProfession.TOTAL_SLOTS, ItemStack.EMPTY);
			for (int i = 0; i < data.items.size(); i++) grown.set(i, data.items.get(i));
			data.items = grown;
			GolemData.save(golem, data);
		}
		BlockPos in = data.assignments.get(CrafterProfession.INPUT);
		BlockPos table = data.assignments.get(CrafterProfession.TABLE);
		BlockPos out = data.assignments.get(CrafterProfession.OUTPUT);
		Container cin = in == null ? null : TransporterLogic.containerAt(level, in);
		Container cout = out == null ? null : TransporterLogic.containerAt(level, out);
		Plan plan = cachedPlan(level, data, s);
		Job job;
		if (plan == null) {
			// no valid recipe (template empty or invalid): input and output slots would be stranded, so they all go to the output chest
			job = cout != null && holdsAnything(data) ? Job.DELIVER_ALL : Job.IDLE;
		} else if (cin == null || cout == null || table == null || !Interact.loaded(level, table) || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
			job = Job.IDLE;
		} else {
			job = decide(data, plan, cin);
		}
		// a fetch or delivery cut short by the transfer budget is finished first (not "chest empty/full")
		if (s.sticky != null) {
			if (plan != null && s.sticky == Job.FETCH && cin != null && job != Job.IDLE && canFetch(data, plan, cin)) job = Job.FETCH;
			else if (cout != null && s.sticky != Job.FETCH && job != Job.IDLE && (plan == null) == (s.sticky == Job.DELIVER_ALL)
				&& (outputHasItems(data) || !deliverIdxs(data, plan, s.sticky == Job.DELIVER_ALL).isEmpty())) job = s.sticky;
			else s.sticky = null;
		}
		BlockPos target = switch (job) {
			case FETCH -> in;
			case CRAFT -> table;
			case DELIVER, DELIVER_ALL -> out;
			case IDLE -> null;
		};
		if (target == null) {
			idle(golem, s, RETRY_TICKS);
			return;
		}
		if (!walkTo(level, golem, s, target)) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		switch (job) {
			case FETCH -> {
				int n = fetch(data, plan, cin, TransferBudget.remaining(level, golem), s.cursor);
				TransferBudget.spend(level, golem, n);
				if (n > 0 || TransferBudget.exhausted(level, golem)) Interact.openChest(level, golem, in, Interact.Action.TAKE);
				if (TransferBudget.exhausted(level, golem)) {
					s.sticky = Job.FETCH;
					s.cooldown = TransferBudget.waitTicks(level);
				} else {
					s.sticky = null;
					s.cursor[0] = 0;
					s.cooldown = Interact.OPEN_TICKS;
					s.walk.reset();
				}
			}
			case CRAFT -> {
				craft(data, plan);
				s.cooldown = CRAFT_TICKS;
			}
			case DELIVER, DELIVER_ALL -> {
				List<Integer> idxs = deliverIdxs(data, plan, job == Job.DELIVER_ALL);
				idxs.add(CrafterProfession.OUTPUT_SLOT);
				int n = deliver(data, idxs, cout, TransferBudget.remaining(level, golem));
				TransferBudget.spend(level, golem, n);
				boolean moved = n > 0;
				if (moved) Interact.openChest(level, golem, out, Interact.Action.INSERT);
				boolean left = false;
				for (int idx : idxs) left |= !data.items.get(idx).isEmpty();
				if (left && TransferBudget.exhausted(level, golem)) {
					// budget used up, not "chest full": stay, keep the lid open, go on next window
					s.sticky = job;
					Interact.openChest(level, golem, out, Interact.Action.INSERT);
					s.cooldown = TransferBudget.waitTicks(level);
				} else {
					s.sticky = null;
					// chest full: keep the items and retry; never freeze, never lose
					s.cooldown = left || !moved ? RETRY_TICKS : Interact.OPEN_TICKS;
					if (!left) s.walk.reset();
				}
			}
			case IDLE -> s.cooldown = RETRY_TICKS;
		}
		GolemData.save(golem, data);
	}

	private static void idle(CopperGolem golem, State s, int ticks) {
		golem.getNavigation().stop();
		s.target = null;
		s.stuck = 0;
		s.cooldown = ticks;
	}

	/**
	 * One tick of walking to the 1-block stand spot of {@code target} ({@link Interact#approach}); true once there.
	 * Gives up (idles) when the golem stops getting closer for {@link #GIVE_UP_TICKS}; airborne ticks do not count.
	 */
	private static boolean walkTo(ServerLevel level, CopperGolem golem, State s, BlockPos target) {
		double dist = golem.distanceToSqr(Vec3.atBottomCenterOf(target));
		if (!target.equals(s.target)) {
			s.target = target;
			s.bestDist = dist;
			s.stuck = 0;
		}
		if (Interact.approach(level, golem, target, s.walk, SPEED)) {
			s.stuck = 0;
			s.bestDist = dist;
			return true;
		}
		if (dist < s.bestDist - PROGRESS_EPS) {
			s.bestDist = dist;
			s.stuck = 0;
		} else if (golem.onGround() && ++s.stuck >= GIVE_UP_TICKS) {
			s.walk.reset();
			idle(golem, s, GIVE_UP_IDLE_TICKS);
		}
		return false;
	}
}
