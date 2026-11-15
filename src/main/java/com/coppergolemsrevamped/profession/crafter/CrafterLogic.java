package com.coppergolemsrevamped.profession.crafter;

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
import net.minecraft.world.SimpleContainer;
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
 * Crafter work loop. {@link GolemData#items} slots 0..8 are the template, 9..17 the buffer. The decision is
 * stateless (derived from the buffer, the input chest and the recipe each tick); only timers are transient.
 */
public final class CrafterLogic {
	private CrafterLogic() {}

	static final int REPATH_TICKS = 20;
	static final int RETRY_TICKS = 40;
	static final int AFTER_ACTION_TICKS = 5;
	static final int CRAFT_TICKS = 10;
	static final double SPEED = 1.0;
	/** Walking without getting closer for this long = unreachable (10 s); the golem then idles before retrying. */
	static final int GIVE_UP_TICKS = 200;
	static final int GIVE_UP_IDLE_TICKS = 600;
	static final double PROGRESS_EPS = 0.1;

	private static final RecipeManager.CachedCheck<CraftingInput, CraftingRecipe> CHECK =
		RecipeManager.createCheck(RecipeType.CRAFTING);

	/** A resolved recipe for a template: the result, the remainders of one craft and the ingredient counts. */
	public record Plan(CraftingInput input, ItemStack result, List<ItemStack> remainders, List<ItemStack> need) {}

	private static final class State {
		int cooldown;
		int repath;
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	// ---- recipe ----

	/** The template as a trimmed crafting input (one copy per non-empty slot); null when the template is empty. */
	public static @Nullable CraftingInput templateInput(GolemData data) {
		List<ItemStack> grid = new ArrayList<>();
		for (int i = 0; i < CrafterProfession.TEMPLATE_SLOTS; i++) {
			ItemStack s = i < data.items.size() ? data.items.get(i) : ItemStack.EMPTY;
			grid.add(s.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(1));
		}
		CraftingInput input = CraftingInput.ofPositioned(3, 3, grid).input();
		return input.isEmpty() ? null : input;
	}

	/**
	 * The server's crafting recipe matching the template, or empty. {@link RecipeManager#createCheck} memoises the last
	 * matching recipe, so an unchanged template costs one {@code matches} call, and a /reload is picked up.
	 */
	public static Optional<RecipeHolder<CraftingRecipe>> recipeFor(ServerLevel level, GolemData data) {
		CraftingInput input = templateInput(data);
		return input == null ? Optional.empty() : CHECK.getRecipeFor(input, level);
	}

	/** Result, remainders and ingredient counts for the template; null when there is no recipe. */
	public static @Nullable Plan plan(ServerLevel level, GolemData data) {
		CraftingInput input = templateInput(data);
		if (input == null) return null;
		Optional<RecipeHolder<CraftingRecipe>> r = CHECK.getRecipeFor(input, level);
		if (r.isEmpty()) return null;
		CraftingRecipe recipe = r.get().value();
		ItemStack result = recipe.assemble(input);
		if (result.isEmpty()) return null;
		List<ItemStack> remainders = new ArrayList<>();
		for (ItemStack s : recipe.getRemainingItems(input)) if (!s.isEmpty()) remainders.add(s.copy());
		// ingredient counts per distinct item + components (the template's own stacks, one per slot)
		List<ItemStack> need = new ArrayList<>();
		for (ItemStack s : input.items()) {
			if (s.isEmpty()) continue;
			boolean merged = false;
			for (ItemStack n : need) {
				if (ItemStack.isSameItemSameComponents(n, s)) {
					n.grow(1);
					merged = true;
					break;
				}
			}
			if (!merged) need.add(s.copyWithCount(1));
		}
		return new Plan(input, result, remainders, need);
	}

	// ---- buffer helpers (public for tests) ----

	public static SimpleContainer buffer(GolemData data) {
		SimpleContainer c = new SimpleContainer(CrafterProfession.BUFFER_SLOTS);
		for (int i = 0; i < CrafterProfession.BUFFER_SLOTS; i++) {
			int idx = CrafterProfession.TEMPLATE_SLOTS + i;
			if (idx < data.items.size()) c.setItem(i, data.items.get(idx).copy());
		}
		return c;
	}

	public static void storeBuffer(GolemData data, SimpleContainer buf) {
		for (int i = 0; i < CrafterProfession.BUFFER_SLOTS; i++) {
			int idx = CrafterProfession.TEMPLATE_SLOTS + i;
			if (idx < data.items.size()) data.items.set(idx, buf.getItem(i).copy());
		}
	}

	private static SimpleContainer copyOf(Container buf) {
		SimpleContainer copy = new SimpleContainer(buf.getContainerSize());
		for (int i = 0; i < buf.getContainerSize(); i++) copy.setItem(i, buf.getItem(i).copy());
		return copy;
	}

	private static int count(Container c, ItemStack like) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (ItemStack.isSameItemSameComponents(s, like)) n += s.getCount();
		}
		return n;
	}

	/** Full ingredient sets the buffer can craft right now. */
	public static int sets(Plan plan, Container buf) {
		int sets = Integer.MAX_VALUE;
		for (ItemStack n : plan.need()) sets = Math.min(sets, count(buf, n) / n.getCount());
		return sets == Integer.MAX_VALUE ? 0 : sets;
	}

	private static boolean isIngredient(Plan plan, ItemStack s) {
		for (ItemStack n : plan.need()) if (ItemStack.isSameItemSameComponents(n, s)) return true;
		return false;
	}

	/** True when the buffer holds anything that is not an ingredient (crafted results, remainders, strays). */
	private static boolean hasProducts(Plan plan, Container buf) {
		for (int i = 0; i < buf.getContainerSize(); i++) {
			ItemStack s = buf.getItem(i);
			if (!s.isEmpty() && !isIngredient(plan, s)) return true;
		}
		return false;
	}

	private static boolean fits(Container buf, List<ItemStack> stacks) {
		SimpleContainer copy = copyOf(buf);
		for (ItemStack s : stacks) if (!copy.addItem(s.copy()).isEmpty()) return false;
		return true;
	}

	private static List<ItemStack> products(Plan plan) {
		List<ItemStack> l = new ArrayList<>();
		l.add(plan.result().copy());
		for (ItemStack r : plan.remainders()) l.add(r.copy());
		return l;
	}

	/**
	 * Crafts once inside {@code buf}: removes one set of ingredients, adds result and remainders. False (buffer
	 * untouched) when there are no ingredients or the products do not fit.
	 */
	public static boolean craftOnce(Plan plan, SimpleContainer buf) {
		if (sets(plan, buf) < 1) return false;
		SimpleContainer trial = copyOf(buf);
		for (ItemStack n : plan.need()) removeLike(trial, n, n.getCount());
		for (ItemStack p : products(plan)) if (!trial.addItem(p).isEmpty()) return false;
		for (int i = 0; i < buf.getContainerSize(); i++) buf.setItem(i, trial.getItem(i));
		return true;
	}

	private static void removeLike(Container c, ItemStack like, int amount) {
		for (int i = 0; i < c.getContainerSize() && amount > 0; i++) {
			ItemStack s = c.getItem(i);
			if (!ItemStack.isSameItemSameComponents(s, like)) continue;
			int n = Math.min(amount, s.getCount());
			s.shrink(n);
			amount -= n;
			if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
		}
	}

	/** Removes every non-ingredient stack from the buffer and returns them. */
	private static NonNullList<ItemStack> takeProducts(Plan plan, SimpleContainer buf) {
		NonNullList<ItemStack> out = NonNullList.create();
		for (int i = 0; i < buf.getContainerSize(); i++) {
			ItemStack s = buf.getItem(i);
			if (s.isEmpty() || isIngredient(plan, s)) continue;
			out.add(s);
			buf.setItem(i, ItemStack.EMPTY);
		}
		return out;
	}

	/**
	 * Largest k such that the input holds the missing ingredients for k crafts, the buffer can hold them and still
	 * has room for one craft's products. 0 when not even one craft is possible.
	 */
	public static int fetchSets(Plan plan, Container input, Container buf) {
		int max = Integer.MAX_VALUE;
		for (ItemStack n : plan.need()) {
			// the buffer's existing stock of this ingredient counts towards k
			max = Math.min(max, (count(buf, n) + count(input, n)) / n.getCount());
		}
		if (max == Integer.MAX_VALUE) return 0;
		// linear search downward: k is at most 9 slots x 64 items
		for (int k = Math.min(max, 576); k >= 1; k--) {
			List<ItemStack> want = new ArrayList<>();
			for (ItemStack n : plan.need()) {
				int missing = n.getCount() * k - count(buf, n);
				if (missing > 0) want.add(n.copyWithCount(missing));
			}
			want.addAll(products(plan));
			if (fits(buf, want)) return k;
		}
		return 0;
	}

	/** Moves {@code k} sets of ingredients (less what the buffer already holds) from {@code input} into the buffer. */
	public static void fetch(Plan plan, int k, Container input, SimpleContainer buf) {
		for (ItemStack n : plan.need()) {
			int missing = n.getCount() * k - count(buf, n);
			for (int i = 0; i < input.getContainerSize() && missing > 0; i++) {
				ItemStack s = input.getItem(i);
				if (!ItemStack.isSameItemSameComponents(s, n)) continue;
				int take = Math.min(missing, s.getCount());
				ItemStack moved = s.split(take);
				if (s.isEmpty()) input.setItem(i, ItemStack.EMPTY);
				ItemStack rest = buf.addItem(moved);
				// cannot happen after fetchSets, but never lose an item: put it back
				if (!rest.isEmpty()) {
					ItemStack back = input.getItem(i);
					if (back.isEmpty()) input.setItem(i, rest); else back.grow(rest.getCount());
				}
				missing -= take - rest.getCount();
			}
		}
		input.setChanged();
	}

	// ---- loop ----

	private enum Job { FETCH, CRAFT, DELIVER, IDLE }

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		BlockPos in = data.assignments.get(CrafterProfession.INPUT);
		BlockPos table = data.assignments.get(CrafterProfession.TABLE);
		BlockPos out = data.assignments.get(CrafterProfession.OUTPUT);
		Container cin = in == null ? null : TransporterLogic.containerAt(level, in);
		Container cout = out == null ? null : TransporterLogic.containerAt(level, out);
		boolean tableOk = table != null && level.getBlockState(table).is(Blocks.CRAFTING_TABLE);
		boolean sized = data.items.size() >= CrafterProfession.TEMPLATE_SLOTS + CrafterProfession.BUFFER_SLOTS;
		Plan recipe = sized ? plan(level, data) : null;
		SimpleContainer buf = buffer(data);
		if (sized && recipe == null && cout != null && !isEmpty(buf)) {
			// no valid recipe (template empty or invalid): the buffer would be stranded, so everything goes to the output
			deliverAll(level, golem, data, s, buf, out, cout);
			return;
		}
		Plan plan = cin == null || cout == null || !tableOk ? null : recipe;
		if (plan == null) {
			idle(golem, s, RETRY_TICKS);
			return;
		}
		Job job = decide(plan, cin, buf);
		BlockPos target = switch (job) {
			case FETCH -> in;
			case CRAFT -> table;
			case DELIVER -> out;
			case IDLE -> null;
		};
		if (target == null) {
			idle(golem, s, RETRY_TICKS);
			return;
		}
		if (!TransporterLogic.inReach(golem, target)) {
			walk(golem, s, target);
			return;
		}
		golem.getNavigation().stop();
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		s.repath = 0;
		s.target = null;
		s.stuck = 0;
		switch (job) {
			case FETCH -> {
				fetch(plan, fetchSets(plan, cin, buf), cin, buf);
				s.cooldown = AFTER_ACTION_TICKS;
			}
			case CRAFT -> {
				craftOnce(plan, buf);
				s.cooldown = CRAFT_TICKS;
			}
			case DELIVER -> {
				NonNullList<ItemStack> prods = takeProducts(plan, buf);
				TransporterLogic.insertAll(prods, cout);
				// what did not fit goes back into the buffer, never lost; it always fits: the freed slots are the ones the stacks came from
				for (ItemStack rest : prods) if (!rest.isEmpty()) buf.addItem(rest);
				s.cooldown = hasProducts(plan, buf) ? RETRY_TICKS : AFTER_ACTION_TICKS;
			}
			case IDLE -> s.cooldown = RETRY_TICKS;
		}
		storeBuffer(data, buf);
		GolemData.save(golem, data);
	}

	private static boolean isEmpty(Container c) {
		for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) return false;
		return true;
	}

	/** No recipe: walk to the output chest and move the whole buffer there; what does not fit stays in the buffer. */
	private static void deliverAll(ServerLevel level, CopperGolem golem, GolemData data, State s, SimpleContainer buf,
		BlockPos out, Container cout) {
		if (!TransporterLogic.inReach(golem, out)) {
			walk(golem, s, out);
			return;
		}
		golem.getNavigation().stop();
		golem.getLookControl().setLookAt(Vec3.atCenterOf(out));
		s.repath = 0;
		s.target = null;
		s.stuck = 0;
		NonNullList<ItemStack> all = NonNullList.create();
		for (int i = 0; i < buf.getContainerSize(); i++) {
			ItemStack st = buf.getItem(i);
			if (st.isEmpty()) continue;
			all.add(st);
			buf.setItem(i, ItemStack.EMPTY);
		}
		TransporterLogic.insertAll(all, cout);
		for (ItemStack rest : all) if (!rest.isEmpty()) buf.addItem(rest); // same slots as freed above, always fits
		s.cooldown = isEmpty(buf) ? AFTER_ACTION_TICKS : RETRY_TICKS;
		storeBuffer(data, buf);
		GolemData.save(golem, data);
	}

	private static Job decide(Plan plan, Container input, SimpleContainer buf) {
		if (sets(plan, buf) >= 1) {
			if (craftOnce(plan, copyOf(buf))) return Job.CRAFT;
			return hasProducts(plan, buf) ? Job.DELIVER : Job.IDLE;
		}
		if (hasProducts(plan, buf)) return Job.DELIVER;
		return fetchSets(plan, input, buf) >= 1 ? Job.FETCH : Job.IDLE;
	}

	private static void idle(CopperGolem golem, State s, int ticks) {
		golem.getNavigation().stop();
		s.target = null;
		s.stuck = 0;
		s.cooldown = ticks;
	}

	/** Walk towards {@code target}; gives up (idles) when the path is missing or the distance stops shrinking. */
	private static void walk(CopperGolem golem, State s, BlockPos target) {
		double dist = golem.distanceToSqr(Vec3.atBottomCenterOf(target));
		if (!target.equals(s.target)) {
			s.target = target;
			s.bestDist = dist;
			s.stuck = 0;
			s.repath = 0;
		}
		if (dist < s.bestDist - PROGRESS_EPS) {
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			idle(golem, s, GIVE_UP_IDLE_TICKS);
			return;
		}
		if (--s.repath <= 0 || golem.getNavigation().isDone()) {
			// airborne (just spawned / falling): createPath returns null, which would read as unreachable and idle 600 ticks
			if (!golem.onGround()) {
				s.repath = 0;
				return;
			}
			if (golem.getNavigation().createPath(target, 0) == null) {
				idle(golem, s, GIVE_UP_IDLE_TICKS);
				return;
			}
			golem.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, SPEED);
			s.repath = REPATH_TICKS;
		}
	}
}
