package com.coppergolemsrevamped.profession.transporter;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;

import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
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

	static final double REACH_HORIZONTAL = 2.0;
	/** Chest Y minus golem Y must be within [-2, +1] (vanilla reach: chest 2 below to 1 above). */
	static final int REACH_DOWN = 2;
	static final int REACH_UP = 1;
	static final int RETRY_TICKS = 40;
	static final double SPEED = 1.0;

	/** Transient per-golem timers; the persistent state is only GolemData items + assignments. */
	private static final class State {
		int cooldown;
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
		boolean carryEmpty = isEmpty(data.items);
		BlockPos target = carryEmpty ? a : b;
		if (!Interact.approach(level, golem, target, s.walk, SPEED)) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (carryEmpty) {
			boolean moved = takeAll(ca, data.items);
			if (moved) Interact.openChest(level, golem, target, Interact.Action.TAKE);
			s.cooldown = moved ? Interact.OPEN_TICKS : RETRY_TICKS;
			if (moved) s.walk.reset();
		} else {
			if (insertAll(data.items, cb)) Interact.openChest(level, golem, target, Interact.Action.INSERT);
			s.cooldown = isEmpty(data.items) ? Interact.OPEN_TICKS : RETRY_TICKS;
			if (isEmpty(data.items)) s.walk.reset();
		}
		GolemData.save(golem, data);
	}

	/** Legacy 2-block reach, still used by Crafter, Farmer and Lumber until they move to {@code ai/Interact}. */
	public static boolean inReach(CopperGolem golem, BlockPos chest) {
		double dx = golem.getX() - (chest.getX() + 0.5);
		double dz = golem.getZ() - (chest.getZ() + 0.5);
		if (dx * dx + dz * dz > REACH_HORIZONTAL * REACH_HORIZONTAL) return false;
		int dy = chest.getY() - golem.blockPosition().getY();
		return dy >= -REACH_DOWN && dy <= REACH_UP;
	}

	/** The container at pos (both halves for a double chest), or null when it is not a container. */
	public static @Nullable Container containerAt(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return level.getBlockEntity(pos) instanceof Container c ? c : null;
	}

	public static boolean isEmpty(NonNullList<ItemStack> items) {
		for (ItemStack s : items) if (!s.isEmpty()) return false;
		return true;
	}

	/** Moves items from {@code src} into {@code carry} (merge first, then empty slots). Returns true if anything moved. */
	public static boolean takeAll(Container src, NonNullList<ItemStack> carry) {
		boolean moved = false;
		for (int i = 0; i < src.getContainerSize(); i++) {
			ItemStack in = src.getItem(i);
			if (in.isEmpty()) continue;
			int before = in.getCount();
			ItemStack rest = addTo(carry, in.copy(), null);
			if (rest.getCount() != before) {
				moved = true;
				src.setItem(i, rest.isEmpty() ? ItemStack.EMPTY : rest);
			}
		}
		if (moved) src.setChanged();
		return moved;
	}

	/** Inserts every carried stack into {@code dst}; what does not fit stays in {@code carry}. Returns true if anything moved. */
	public static boolean insertAll(NonNullList<ItemStack> carry, Container dst) {
		boolean moved = false;
		for (int i = 0; i < carry.size(); i++) {
			ItemStack s = carry.get(i);
			if (s.isEmpty()) continue;
			int before = s.getCount();
			ItemStack rest = addTo(null, s.copy(), dst);
			if (rest.getCount() != before) moved = true;
			carry.set(i, rest);
		}
		if (moved) dst.setChanged();
		return moved;
	}

	/** Adds stack to a list or a container (exactly one non-null); returns the remainder. */
	private static ItemStack addTo(@Nullable NonNullList<ItemStack> list, ItemStack stack, @Nullable Container cont) {
		int size = list != null ? list.size() : cont.getContainerSize();
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int i = 0; i < size && !stack.isEmpty(); i++) {
				ItemStack cur = list != null ? list.get(i) : cont.getItem(i);
				if (pass == 0 ? cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack) : !cur.isEmpty()) continue;
				if (cont != null && !cont.canPlaceItem(i, stack)) continue;
				int max = Math.min(stack.getMaxStackSize(), cont != null ? cont.getMaxStackSize() : 64);
				int room = max - cur.getCount();
				if (room <= 0) continue;
				int n = Math.min(room, stack.getCount());
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
