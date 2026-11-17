package com.coppergolemsrevamped.profession.distributor;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Distributor work loop: FETCH (input chest, a multiple of N per item type) -> DISTRIBUTE (every inventory of the
 * round gets {@code amount / N}) -> RETURN (leftovers back to the input chest). Everything the golem holds lives in
 * {@link GolemData#items}, so a restart loses nothing. See superdoc/features/distributor.md for the rules and reasons.
 */
public final class DistributorLogic {
	private DistributorLogic() {}

	static final int RETRY_TICKS = 40;
	static final int RESCAN_TICKS = 100;
	static final int SKIP_TICKS = 600;
	static final int GIVE_UP_TICKS = 200;
	static final int MAX_Y_SPAN = 32;
	static final double PROGRESS_EPS = 0.1;
	static final double SPEED = 1.0;

	enum Phase { FETCH, DISTRIBUTE, RETURN }

	/** An item type (count 1) with a mutable amount. */
	private static final class Amount {
		final ItemStack type;
		int n;

		Amount(ItemStack type, int n) {
			this.type = type;
			this.n = n;
		}
	}

	private static final class State {
		int cooldown;
		Phase phase = Phase.FETCH;
		long scanAt;
		/** Inventories found by the last scan. */
		List<BlockPos> targets = new ArrayList<>();
		/** The inventories of the current round (snapshot, nearest first), and the one being served. */
		List<BlockPos> round = new ArrayList<>();
		int idx;
		/** FETCH: what is still to take. */
		@Nullable List<Amount> plan;
		/** DISTRIBUTE: the share of every item type per inventory, and what the current inventory still gets. */
		@Nullable List<Amount> shares;
		@Nullable List<Amount> quota;
		final Map<BlockPos, Long> skip = new HashMap<>();
		final Interact.Approach walk = new Interact.Approach();
		@Nullable BlockPos target;
		double bestDist;
		int stuck;
		String status = "Working";
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	public static String status(CopperGolem golem) {
		State s = STATES.get(golem);
		return s == null ? "Working" : s.status;
	}

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		BlockPos inPos = data.assignments.get(DistributorProfession.INPUT);
		Container in = inPos == null ? null : TransporterLogic.containerAt(level, inPos);
		if (in == null) {
			golem.getNavigation().stop();
			s.status = "No input chest set";
			s.cooldown = RETRY_TICKS;
			return;
		}
		if (now >= s.scanAt) rescan(level, data, s, inPos);
		List<ItemStack> carry = TransporterLogic.carry(data);
		boolean acted = switch (s.phase) {
			case FETCH -> fetch(level, golem, data, s, inPos, in, carry);
			case DISTRIBUTE -> distribute(level, golem, data, s, carry);
			case RETURN -> giveBack(level, golem, data, s, inPos, in, carry);
		};
		if (acted) GolemData.save(golem, data);
	}

	// ---- scan ----

	/** True for block entities with machine-slot semantics: not an inventory to fill. */
	static boolean isMachine(BlockEntity be) {
		return be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity
			|| be instanceof CrafterBlockEntity || be instanceof JukeboxBlockEntity || be instanceof LecternBlockEntity
			|| be instanceof ChiseledBookShelfBlockEntity || be instanceof DecoratedPotBlockEntity;
	}

	/** The other half of a double chest at {@code p}, or null for anything else. */
	private static @Nullable BlockPos otherHalf(ServerLevel level, BlockPos p) {
		BlockState st = level.getBlockState(p);
		if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return p.relative(ChestBlock.getConnectedDirection(st));
		}
		return null;
	}

	/** Every inventory in the area (a double chest once), except the input chest and machines. */
	private static void rescan(ServerLevel level, GolemData data, State s, BlockPos inPos) {
		s.scanAt = level.getGameTime() + RESCAN_TICKS;
		List<BlockPos> found = new ArrayList<>();
		BlockPos a = data.assignments.get(DistributorProfession.TARGETS + "_a");
		BlockPos b = data.assignments.get(DistributorProfession.TARGETS + "_b");
		if (a != null && b != null) {
			Set<BlockPos> seen = new HashSet<>();
			seen.add(inPos);
			BlockPos inOther = otherHalf(level, inPos);
			if (inOther != null) seen.add(inOther);
			int y0 = Math.min(a.getY(), b.getY());
			int y1 = Math.min(Math.max(a.getY(), b.getY()), y0 + MAX_Y_SPAN - 1);
			for (BlockPos p : BlockPos.betweenClosed(Math.min(a.getX(), b.getX()), y0, Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), y1, Math.max(a.getZ(), b.getZ()))) {
				if (!level.hasChunkAt(p) || seen.contains(p)) continue;
				BlockEntity be = level.getBlockEntity(p);
				if (!(be instanceof Container) || be.isRemoved() || isMachine(be)) continue;
				BlockPos pos = p.immutable();
				seen.add(pos);
				BlockPos other = otherHalf(level, pos);
				if (other != null) seen.add(other);
				found.add(pos);
			}
		}
		s.targets = found;
	}

	/** Greedy nearest-first order starting at {@code from}. */
	private static List<BlockPos> nearestFirst(List<BlockPos> all, BlockPos from) {
		List<BlockPos> left = new ArrayList<>(all);
		List<BlockPos> out = new ArrayList<>();
		Vec3 cur = Vec3.atCenterOf(from);
		while (!left.isEmpty()) {
			BlockPos best = left.get(0);
			double bd = Double.MAX_VALUE;
			for (BlockPos p : left) {
				double d = Vec3.atCenterOf(p).distanceToSqr(cur);
				if (d < bd || (d == bd && p.compareTo(best) < 0)) {
					bd = d;
					best = p;
				}
			}
			left.remove(best);
			out.add(best);
			cur = Vec3.atCenterOf(best);
		}
		return out;
	}

	// ---- item helpers ----

	private static boolean same(ItemStack a, ItemStack b) {
		return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
	}

	/** Merges {@code stack} into the list (same item first, then empty slots); returns how many items did not fit. */
	private static int mergeInto(List<ItemStack> list, ItemStack stack) {
		ItemStack rest = stack.copy();
		for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
			for (int i = 0; i < list.size() && !rest.isEmpty(); i++) {
				ItemStack cur = list.get(i);
				if (pass == 0 ? !same(cur, rest) : !cur.isEmpty()) continue;
				int room = rest.getMaxStackSize() - cur.getCount();
				if (room <= 0) continue;
				int n = Math.min(room, rest.getCount());
				if (cur.isEmpty()) list.set(i, rest.copyWithCount(n)); else cur.grow(n);
				rest.shrink(n);
			}
		}
		return rest.getCount();
	}

	/** Index of the first carry slot holding {@code type}, or -1. */
	private static int slotOf(List<ItemStack> carry, ItemStack type) {
		for (int i = 0; i < carry.size(); i++) if (same(carry.get(i), type)) return i;
		return -1;
	}

	private static List<ItemStack> copyOf(List<ItemStack> list) {
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack c : list) out.add(c.copy());
		return out;
	}

	/**
	 * What to take from the input chest: per item type (chest order) {@code floor(takeable / n) * n}, limited by what
	 * still fits the carry (also a multiple of n). Types with fewer than n takeable are left alone.
	 */
	private static List<Amount> planFetch(Container in, List<ItemStack> carry, int n, boolean keep) {
		List<Amount> types = new ArrayList<>();
		for (int i = 0; i < in.getContainerSize(); i++) {
			ItemStack st = in.getItem(i);
			if (st.isEmpty()) continue;
			int take = TransporterLogic.takeable(in, st, keep);
			if (take <= 0) continue;
			Amount found = null;
			for (Amount t : types) if (same(t.type, st)) found = t;
			if (found == null) types.add(new Amount(st.copyWithCount(1), take)); else found.n += take;
		}
		List<ItemStack> sim = copyOf(carry);
		List<Amount> plan = new ArrayList<>();
		for (Amount t : types) {
			int want = t.n / n * n;
			if (want <= 0) continue;
			int fit = want - mergeInto(copyOf(sim), t.type.copyWithCount(want));
			int amt = fit / n * n;
			if (amt <= 0) continue;
			mergeInto(sim, t.type.copyWithCount(amt)); // reserve the room for the next type
			plan.add(new Amount(t.type, amt));
		}
		return plan;
	}

	/** Per item type in the carry: {@code total / n} (types with a share of 0 are left out, they go back). */
	private static List<Amount> sharesOf(List<ItemStack> carry, int n) {
		List<Amount> totals = new ArrayList<>();
		for (ItemStack st : carry) {
			if (st.isEmpty()) continue;
			Amount found = null;
			for (Amount t : totals) if (same(t.type, st)) found = t;
			if (found == null) totals.add(new Amount(st.copyWithCount(1), st.getCount())); else found.n += st.getCount();
		}
		List<Amount> out = new ArrayList<>();
		for (Amount t : totals) if (t.n / n > 0) out.add(new Amount(t.type, t.n / n));
		return out;
	}

	private static String distributing(int n) {
		return "Distributing to " + n + (n == 1 ? " inventory" : " inventories");
	}

	// ---- phases ----

	private static boolean fetch(ServerLevel level, CopperGolem golem, GolemData data, State s, BlockPos inPos, Container in, List<ItemStack> carry) {
		if (s.plan == null) {
			if (!TransporterLogic.isEmpty(carry)) {
				// items from before a restart: serve them like a fresh round
				s.round = nearestFirst(s.targets, inPos);
				s.phase = s.round.isEmpty() ? Phase.RETURN : Phase.DISTRIBUTE;
				s.shares = null;
				s.quota = null;
				s.idx = 0;
				return false;
			}
			int n = s.targets.size();
			if (n == 0) {
				golem.getNavigation().stop();
				s.status = "No inventories in the area";
				s.cooldown = RETRY_TICKS;
				return false;
			}
			List<Amount> plan = planFetch(in, carry, n, data.keepLayout);
			if (plan.isEmpty()) {
				golem.getNavigation().stop();
				s.status = "Input has fewer than " + n + " of any item";
				s.cooldown = RETRY_TICKS;
				return false;
			}
			s.plan = plan;
			s.round = nearestFirst(s.targets, inPos);
		}
		s.status = distributing(s.round.size());
		if (!walkTo(level, golem, s, inPos)) return false;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
		boolean moved = false;
		for (Iterator<Amount> it = s.plan.iterator(); it.hasNext(); ) {
			Amount a = it.next();
			int limit = Math.min(a.n, TransferBudget.remaining(level, golem));
			if (limit <= 0) break;
			int got = TransporterLogic.takeAll(in, carry, limit, x -> same(x, a.type), data.keepLayout);
			TransferBudget.spend(level, golem, got);
			moved |= got > 0;
			a.n -= got;
			if (a.n <= 0 || got < limit) it.remove(); else break;
		}
		if (moved) Interact.openChest(level, golem, inPos, Interact.Action.TAKE);
		if (!s.plan.isEmpty()) {
			// cut by the transfer budget, not "chest empty": stay and go on next window
			Interact.openChest(level, golem, inPos, Interact.Action.TAKE);
			s.cooldown = TransferBudget.waitTicks(level);
			return moved;
		}
		s.plan = null;
		s.walk.reset();
		if (TransporterLogic.isEmpty(carry)) {
			s.cooldown = RETRY_TICKS;
			return moved;
		}
		s.phase = Phase.DISTRIBUTE;
		s.shares = null;
		s.quota = null;
		s.idx = 0;
		s.cooldown = Interact.OPEN_TICKS;
		return true;
	}

	private static boolean distribute(ServerLevel level, CopperGolem golem, GolemData data, State s, List<ItemStack> carry) {
		if (s.shares == null) s.shares = sharesOf(carry, Math.max(1, s.round.size()));
		if (s.shares.isEmpty() || s.idx >= s.round.size()) {
			s.phase = Phase.RETURN;
			s.walk.reset();
			s.target = null;
			return false;
		}
		s.status = distributing(s.round.size());
		BlockPos tp = s.round.get(s.idx);
		Long until = s.skip.get(tp);
		Container c = until != null && until > level.getGameTime() ? null : TransporterLogic.containerAt(level, tp);
		if (c == null) {
			// gone, unloaded or given up on: next inventory, and look at the area again soon
			s.scanAt = 0;
			s.idx++;
			s.quota = null;
			s.target = null;
			return false;
		}
		if (!walkTo(level, golem, s, tp)) return false;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(tp));
		if (s.quota == null) {
			s.quota = new ArrayList<>();
			for (Amount a : s.shares) s.quota.add(new Amount(a.type, a.n));
		}
		int moved = 0;
		boolean cut = false;
		outer:
		for (Amount q : s.quota) {
			// Re-find a carry slot each pass instead of stepping i++: a budget-limited insert leaves the same slot
			// non-empty, and stepping past it would end the type with q.n > 0 and silently drop the rest of the share.
			while (q.n > 0) {
				int budget = TransferBudget.remaining(level, golem);
				if (budget <= 0) {
					cut = true; // stay here; the remaining quota goes in next window
					break outer;
				}
				int i = slotOf(carry, q.type);
				if (i < 0) break; // carry has no more of this type
				int lim = Math.min(q.n, Math.min(carry.get(i).getCount(), budget));
				int got = TransporterLogic.insertAll(carry.subList(i, i + 1), c, lim, data.keepLayout);
				TransferBudget.spend(level, golem, got);
				moved += got;
				q.n -= got;
				if (got < lim) break; // no room for more of this type here: the rest of its share comes back
			}
			q.n = 0; // reached only when not cut: this type is done for this inventory
		}
		if (moved > 0) Interact.openChest(level, golem, tp, Interact.Action.INSERT);
		if (cut) {
			Interact.openChest(level, golem, tp, Interact.Action.INSERT);
			s.cooldown = TransferBudget.waitTicks(level);
		} else {
			s.idx++;
			s.quota = null;
			s.walk.reset();
			s.cooldown = moved > 0 ? Interact.OPEN_TICKS : 5;
		}
		return true;
	}

	private static boolean giveBack(ServerLevel level, CopperGolem golem, GolemData data, State s, BlockPos inPos, Container in, List<ItemStack> carry) {
		if (TransporterLogic.isEmpty(carry)) {
			s.phase = Phase.FETCH;
			s.round = new ArrayList<>();
			s.plan = null;
			s.shares = null;
			s.quota = null;
			s.idx = 0;
			s.target = null;
			return false;
		}
		s.status = "Returning the leftovers to the input chest";
		if (!walkTo(level, golem, s, inPos)) return false;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
		int n = TransferBudget.insert(level, golem, carry, in, data.keepLayout);
		if (n > 0) Interact.openChest(level, golem, inPos, Interact.Action.INSERT);
		if (TransporterLogic.isEmpty(carry)) {
			s.walk.reset();
			s.cooldown = Interact.OPEN_TICKS;
		} else if (TransferBudget.exhausted(level, golem)) {
			Interact.openChest(level, golem, inPos, Interact.Action.INSERT);
			s.cooldown = TransferBudget.waitTicks(level);
		} else {
			// input chest is full: hold the items and try again, never drop them
			s.status = "Input chest is full: holding the leftovers";
			s.cooldown = RETRY_TICKS;
		}
		return n > 0;
	}

	// ---- walking ----

	/** Walk to the stand spot of {@code target}; true once there. No progress for 200 ticks skips the target for 600. */
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
			s.skip.put(target, level.getGameTime() + SKIP_TICKS);
			s.walk.reset();
			golem.getNavigation().stop();
			s.target = null;
			s.stuck = 0;
		}
		return false;
	}
}
