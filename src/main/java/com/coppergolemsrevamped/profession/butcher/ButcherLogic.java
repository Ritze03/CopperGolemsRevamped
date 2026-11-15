package com.coppergolemsrevamped.profession.butcher;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.ToolSwing;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.cleaner.CleanerLogic;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Butcher work loop. Priorities each tick: pick up drops, fetch a weapon (only when there is something to cull), cull
 * the surplus, breed, deliver, idle. Every branch that does something returns, so one action happens per tick. See
 * superdoc/features/butcher.md for the rules and the reasons behind them.
 */
public final class ButcherLogic {
	private ButcherLogic() {}

	/** Adults of one kind that stay alive; every unprotected adult beyond this is culled. */
	public static final int KEEP_ADULTS = 8;
	/** Breeding of a kind stops at this many babies in the area (they grow up into the next generation). */
	public static final int MAX_BABIES = 8;
	/** ... and at this many animals of the kind (adults and babies together), so the pen cannot explode. */
	public static final int MAX_POPULATION = 24;
	/** Culling needs this many free carry slots: one kill drops up to two kinds of item (leather and beef). */
	static final int MIN_FREE_CARRY = 2;
	/** One chest visit takes at most this much breeding food, so other kinds' food is not starved. */
	static final int FOOD_FETCH = 16;
	/** A pair needs two items of food. */
	static final int FOOD_PER_PAIR = 2;

	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	static final int AFTER_ACTION_TICKS = 5;
	static final int REPATH_TICKS = 10;
	static final double SPEED = 1.0;
	static final double PICKUP_RANGE_SQR = 1.5 * 1.5;
	/** Squared distance within which the golem hits or feeds an animal (and at most 1.5 blocks apart in height). */
	static final double REACH_SQR = 2.2 * 2.2;
	/** Chasing one target this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up animal or item is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;

	/**
	 * The kinds the Butcher manages. Why a list and not "every Animal with breeding food": wild wolves, polar bears and
	 * hoglins hit back, cats, parrots, axolotls and turtles are protected or pets in practice, horses and llamas are
	 * mounts. Only plain farm animals are ever culled.
	 */
	public static final Set<EntityType<?>> FARM_ANIMALS = Set.of(
		EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN,
		EntityTypes.RABBIT, EntityTypes.GOAT);

	private static final class State {
		int cooldown;
		final Interact.Approach walk = new Interact.Approach();
		@Nullable UUID chasing;
		double best;
		int stuck;
		int repath;
		final Map<UUID, Long> skip = new HashMap<>();
		/** The animal being hit and the tick of the current swing (0..{@link ToolSwing#PERIOD}-1). */
		@Nullable UUID engaged;
		int swing;
		/** The pair being fed: first and second animal. */
		@Nullable UUID pairA, pairB;
		String doing = "Looking around";
		// test-visible record of the last strike
		int strikes;
		int lastLooting = -1;
		boolean handWasReal;
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** A kind's animals in the area: unprotected adults (the cullable herd), all adults, babies. */
	private static final class Herd {
		final EntityType<?> type;
		final List<Animal> adults = new ArrayList<>();
		int allAdults;
		int babies;

		Herd(EntityType<?> type) {
			this.type = type;
		}

		int surplus() {
			return ButcherLogic.surplus(adults.size());
		}
	}

	// ---- pure rules (public for tests) ----------------------------------------------------------------------------

	public static boolean isWeapon(ItemStack s) {
		return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES);
	}

	/** Breeding food of any managed kind. */
	public static boolean isFood(ItemStack s) {
		return s.is(ItemTags.COW_FOOD) || s.is(ItemTags.SHEEP_FOOD) || s.is(ItemTags.PIG_FOOD)
			|| s.is(ItemTags.CHICKEN_FOOD) || s.is(ItemTags.GOAT_FOOD) || s.is(ItemTags.RABBIT_FOOD);
	}

	/** How many of {@code adults} unprotected adults are killed: everything beyond {@link #KEEP_ADULTS}. */
	public static int surplus(int adults) {
		return Math.max(0, adults - KEEP_ADULTS);
	}

	/** THE breeding cap: fewer than {@link #MAX_BABIES} babies and fewer than {@link #MAX_POPULATION} animals of the kind. */
	public static boolean breedAllowed(int adults, int babies) {
		return babies < MAX_BABIES && adults + babies < MAX_POPULATION;
	}

	/** Animals the golem never touches: named, owned (tamed), leashed, ridden or carrying a rider. Babies are handled apart. */
	public static boolean isProtected(Animal a) {
		return a.hasCustomName() || a.isLeashed() || a.isPassenger() || a.isVehicle()
			|| (a instanceof OwnableEntity o && o.getOwnerReference() != null);
	}

	/** Can be fed to breed: an unprotected adult that is not in love and not on its breeding cooldown. */
	static boolean eligible(Animal a) {
		return a.isAlive() && !a.isBaby() && a.getAge() == 0 && a.canFallInLove() && !isProtected(a);
	}

	/** Damage of one hit: 1 plus the weapon's attack damage, then the enchantment and item bonuses (Sharpness, Mace). */
	static float attackDamage(ServerLevel level, ItemStack weapon, Entity target, DamageSource source) {
		double[] dmg = {1.0};
		weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
			if (attr.equals(Attributes.ATTACK_DAMAGE) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) dmg[0] += mod.amount();
		});
		float f = EnchantmentHelper.modifyDamage(level, weapon, target, source, (float) dmg[0]);
		return f + weapon.getItem().getAttackDamageBonus(target, f, source);
	}

	/** The area box (shared with the Cleaner), or null when unset. */
	public static @Nullable AABB box(GolemData data) {
		return CleanerLogic.box(data);
	}

	/** Test hooks: strikes dealt, the Looting level seen at the last strike (-1 before the first), and whether the real tool was in hand then. */
	public static int strikes(CopperGolem golem) {
		State s = STATES.get(golem);
		return s == null ? 0 : s.strikes;
	}

	public static int lastLooting(CopperGolem golem) {
		State s = STATES.get(golem);
		return s == null ? -1 : s.lastLooting;
	}

	public static boolean handWasReal(CopperGolem golem) {
		State s = STATES.get(golem);
		return s != null && s.handWasReal;
	}

	/** Status line for the Status tab. */
	public static String status(CopperGolem golem) {
		State s = STATES.get(golem);
		return s == null ? "Looking around" : s.doing;
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		AABB box = box(data);
		BlockPos outPos = data.assignments.get(ButcherProfession.OUTPUT);
		Container out = outPos == null ? null : TransporterLogic.containerAt(level, outPos);
		if (box == null || out == null) {
			s.doing = "Waiting for targets";
			idle(level, golem, s, RETRY_TICKS);
			return;
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);

		Map<EntityType<?>, Herd> herds = scan(level, box);
		ItemStack tool = data.items.get(ButcherProfession.TOOL_SLOT);
		boolean armed = isWeapon(tool);
		boolean cullNeeded = false;
		for (Herd h : herds.values()) if (h.surplus() > 0 && !cullable(h, s).isEmpty()) cullNeeded = true;

		// 1. drops on the ground
		if (pickup(level, golem, data, s, box, now)) return;
		// 2. a weapon, only when there is something to cull
		if (cullNeeded && (tool.isEmpty() || tool.nextDamageWillBreak()) && fetchWeapon(level, golem, data, s)) return;
		// 3. cull: needs a weapon and room for the drops
		if (armed && cullNeeded && freeCarry(data) >= MIN_FREE_CARRY && cull(level, golem, data, s, herds, now)) return;
		endCull(level, golem, s);
		// 4. breed
		if (breed(level, golem, data, s, herds, armed, now)) return;
		// 5. deliver
		if (!TransporterLogic.isEmpty(carryView(data))) {
			s.doing = "Delivering to the output chest";
			if (!Interact.approach(level, golem, outPos, s.walk, SPEED)) return;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(outPos));
			NonNullList<ItemStack> carry = carryView(data);
			if (TransporterLogic.insertAll(carry, out)) Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
			writeCarry(data, carry);
			boolean empty = TransporterLogic.isEmpty(carry);
			s.cooldown = empty ? Interact.OPEN_TICKS : RETRY_TICKS;
			if (empty) s.walk.reset();
			GolemData.save(golem, data);
			return;
		}
		s.doing = "Herd is in balance";
		idle(level, golem, s, SCAN_TICKS);
	}

	private static void idle(ServerLevel level, CopperGolem golem, State s, int cooldown) {
		golem.getNavigation().stop();
		endCull(level, golem, s);
		s.cooldown = cooldown;
	}

	private static void endCull(ServerLevel level, CopperGolem golem, State s) {
		if (s.engaged != null || s.swing != 0) {
			s.engaged = null;
			s.swing = 0;
			ToolSwing.end(level, golem);
		}
	}

	// ---- scanning -------------------------------------------------------------------------------------------------

	private static Map<EntityType<?>, Herd> scan(ServerLevel level, AABB box) {
		Map<EntityType<?>, Herd> herds = new LinkedHashMap<>();
		for (Animal a : level.getEntitiesOfClass(Animal.class, box,
			e -> e.isAlive() && FARM_ANIMALS.contains(e.getType()) && box.contains(e.position()))) {
			Herd h = herds.computeIfAbsent(a.getType(), Herd::new);
			if (a.isBaby()) {
				h.babies++;
			} else {
				h.allAdults++;
				if (!isProtected(a)) h.adults.add(a);
			}
		}
		return herds;
	}

	/** The herd's adults the golem may target now (not given up on). */
	private static List<Animal> cullable(Herd h, State s) {
		List<Animal> out = new ArrayList<>();
		for (Animal a : h.adults) if (!s.skip.containsKey(a.getUUID())) out.add(a);
		return out;
	}

	// ---- picking up drops -----------------------------------------------------------------------------------------

	private static boolean pickup(ServerLevel level, CopperGolem golem, GolemData data, State s, AABB box, long now) {
		NonNullList<ItemStack> carry = carryView(data);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
			e -> e.isAlive() && !e.hasPickUpDelay() && CleanerLogic.fits(carry, e.getItem())
				&& box.contains(e.position()) && !s.skip.containsKey(e.getUUID()));
		if (items.isEmpty()) return false;
		ItemEntity target = items.stream().min(Comparator.comparingDouble(golem::distanceToSqr)).get();
		s.doing = "Collecting drops";
		endCull(level, golem, s);
		if (golem.distanceToSqr(target) > PICKUP_RANGE_SQR) {
			if (!chase(level, golem, s, target, now, SPEED)) golem.getNavigation().stop();
			return true;
		}
		golem.getNavigation().stop();
		s.chasing = null;
		CleanerLogic.pickUp(target, carry);
		writeCarry(data, carry);
		s.cooldown = AFTER_ACTION_TICKS;
		GolemData.save(golem, data);
		return true;
	}

	/**
	 * One tick of walking to {@code target}. Distance shrinking by more than {@link #PROGRESS_EPS} (squared) resets the
	 * stuck counter; {@link #GIVE_UP_TICKS} without progress skips the target for {@link #SKIP_TICKS} and returns false.
	 * A null path is transient (spawn tick, mid-air), so it is no progress rather than an instant give-up.
	 */
	private static boolean chase(ServerLevel level, CopperGolem golem, State s, Entity target, long now, double speed) {
		double d = golem.distanceToSqr(target);
		if (!target.getUUID().equals(s.chasing) || d < s.best - PROGRESS_EPS) {
			s.chasing = target.getUUID();
			s.best = d;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(target.getUUID(), now + SKIP_TICKS);
			s.chasing = null;
			s.stuck = 0;
			s.repath = 0;
			s.engaged = null;
			s.swing = 0;
			return false;
		}
		if (--s.repath <= 0 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(target, 0);
			if (path != null) golem.getNavigation().moveTo(path, speed);
			s.repath = REPATH_TICKS;
		}
		return true;
	}

	private static boolean inReach(CopperGolem golem, Entity e) {
		return golem.distanceToSqr(e) <= REACH_SQR && Math.abs(golem.getY() - e.getY()) <= 1.5;
	}

	// ---- culling --------------------------------------------------------------------------------------------------

	private static boolean cull(ServerLevel level, CopperGolem golem, GolemData data, State s, Map<EntityType<?>, Herd> herds, long now) {
		Animal target = null;
		Herd herd = null;
		for (Herd h : herds.values()) {
			if (h.surplus() <= 0) continue;
			for (Animal a : cullable(h, s)) {
				boolean engaged = a.getUUID().equals(s.engaged);
				boolean better = target == null || (engaged && !target.getUUID().equals(s.engaged))
					|| (!target.getUUID().equals(s.engaged) && golem.distanceToSqr(a) < golem.distanceToSqr(target));
				if (better) {
					target = a;
					herd = h;
				}
			}
		}
		if (target == null) return false;
		if (!target.getUUID().equals(s.engaged)) {
			s.engaged = target.getUUID();
			s.swing = 0;
			ToolSwing.end(level, golem);
		}
		s.doing = "Culling " + plural(herd.type) + " (" + herd.adults.size() + " adults, keeping " + KEEP_ADULTS + ")";
		if (s.swing == 0 && !inReach(golem, target)) {
			if (!chase(level, golem, s, target, now, SPEED)) golem.getNavigation().stop();
			return true;
		}
		golem.getNavigation().stop();
		golem.getLookControl().setLookAt(target);
		ToolSwing.tick(level, golem, target.blockPosition(), s.swing, ToolSwing.PERIOD, false);
		if (s.swing == ToolSwing.RAISED_TICKS && inReach(golem, target)) {
			strike(level, golem, data, s, target);
			if (!target.isAlive()) {
				s.engaged = null;
				s.chasing = null;
				s.swing = 0;
				ToolSwing.end(level, golem);
				s.cooldown = AFTER_ACTION_TICKS;
				return true;
			}
		}
		s.swing = (s.swing + 1) % ToolSwing.PERIOD;
		return true;
	}

	/**
	 * One hit with the real weapon stack. Why the hand swap: Looting is read from the attacker's main hand
	 * ({@code EnchantedCountIncreaseFunction} uses {@code EnchantmentHelper:getEnchantmentLevel(looting, attacker)}), and the
	 * damage source's weapon is the attacker's main hand too. The hand normally holds a display copy
	 * ({@code GolemHandDisplay}), which is up to 10 ticks stale after a weapon swap, so for the hit the real stack from the
	 * tool slot is put in the hand and the display is put back right after. Death loot is rolled inside
	 * {@code hurtServer}, so the swap covers it. Durability then goes to that same real stack.
	 */
	static void strike(ServerLevel level, CopperGolem golem, GolemData data, State s, Animal target) {
		ItemStack tool = data.items.get(ButcherProfession.TOOL_SLOT);
		ItemStack prev = golem.getMainHandItem();
		golem.setItemSlot(EquipmentSlot.MAINHAND, tool);
		try {
			Holder<Enchantment> looting = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING);
			s.lastLooting = EnchantmentHelper.getEnchantmentLevel(looting, golem);
			s.handWasReal = golem.getMainHandItem() == tool;
			DamageSource source = tool.getDamageSource(golem);
			target.hurtServer(level, source, attackDamage(level, tool, target, source));
		} finally {
			golem.setItemSlot(EquipmentSlot.MAINHAND, prev);
		}
		s.strikes++;
		Weapon weapon = tool.get(DataComponents.WEAPON);
		tool.hurtAndBreak(weapon == null ? 1 : weapon.itemDamagePerAttack(), level, null, broken -> {});
		if (tool.isEmpty()) data.items.set(ButcherProfession.TOOL_SLOT, ItemStack.EMPTY);
		GolemData.save(golem, data);
	}

	// ---- breeding -------------------------------------------------------------------------------------------------

	private static boolean breed(ServerLevel level, CopperGolem golem, GolemData data, State s, Map<EntityType<?>, Herd> herds,
		boolean armed, long now) {
		// a pair already in progress: finish it
		Animal a = animal(level, s.pairA), b = animal(level, s.pairB);
		if (a != null && b != null && a.isAlive() && b.isAlive() && a.getType() == b.getType() && !a.isBaby() && !b.isBaby()) {
			Animal next = a.canFallInLove() ? a : b.canFallInLove() ? b : null;
			if (next != null && foodSlot(data, next) >= 0 && !s.skip.containsKey(next.getUUID())) {
				return feed(level, golem, data, s, next, plural(next.getType()), now);
			}
		}
		s.pairA = s.pairB = null;

		for (Herd h : herds.values()) {
			if (!breedAllowed(h.allAdults, h.babies)) continue;
			// a pending cull comes first: no point feeding animals that are about to be killed (without a weapon it never comes)
			if (h.surplus() > 0 && armed) continue;
			List<Animal> el = new ArrayList<>();
			for (Animal x : h.adults) if (eligible(x) && !s.skip.containsKey(x.getUUID())) el.add(x);
			if (el.size() < 2) continue;
			Animal first = el.stream().min(Comparator.comparingDouble(golem::distanceToSqr)).get();
			if (foodCount(data, first) >= FOOD_PER_PAIR) {
				Animal second = el.stream().filter(x -> x != first).min(Comparator.comparingDouble(first::distanceToSqr)).get();
				s.pairA = first.getUUID();
				s.pairB = second.getUUID();
				return feed(level, golem, data, s, first, plural(h.type), now);
			}
			if (fetchFood(level, golem, data, s, first)) return true;
		}
		return false;
	}

	private static @Nullable Animal animal(ServerLevel level, @Nullable UUID id) {
		return id != null && level.getEntity(id) instanceof Animal a ? a : null;
	}

	/** Walk to {@code a} and feed it (love mode, one food used); true while it is busy with this. */
	private static boolean feed(ServerLevel level, CopperGolem golem, GolemData data, State s, Animal a, String kind, long now) {
		s.doing = "Breeding " + kind;
		if (!inReach(golem, a)) {
			if (!chase(level, golem, s, a, now, SPEED)) {
				golem.getNavigation().stop();
				s.pairA = s.pairB = null;
			}
			return true;
		}
		golem.getNavigation().stop();
		golem.getLookControl().setLookAt(a);
		int slot = foodSlot(data, a);
		ItemStack food = data.items.get(slot);
		food.shrink(1);
		if (food.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		a.setInLove(null);
		s.chasing = null;
		s.cooldown = AFTER_ACTION_TICKS;
		GolemData.save(golem, data);
		return true;
	}

	/** Data slot of the food slot holding food this animal eats, or -1. */
	static int foodSlot(GolemData data, Animal a) {
		for (int i = ButcherProfession.FOOD_FROM; i < ButcherProfession.FOOD_FROM + ButcherProfession.FOOD_SLOTS; i++) {
			ItemStack st = data.items.get(i);
			if (!st.isEmpty() && a.isFood(st)) return i;
		}
		return -1;
	}

	private static int foodCount(GolemData data, Animal a) {
		int n = 0;
		for (int i = ButcherProfession.FOOD_FROM; i < ButcherProfession.FOOD_FROM + ButcherProfession.FOOD_SLOTS; i++) {
			ItemStack st = data.items.get(i);
			if (!st.isEmpty() && a.isFood(st)) n += st.getCount();
		}
		return n;
	}

	/** Chest slot holding food {@code a} eats, or -1. */
	private static int chestFood(Container chest, Animal a) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (!st.isEmpty() && a.isFood(st)) return i;
		}
		return -1;
	}

	/**
	 * Walks to the food chest and takes up to {@link #FOOD_FETCH} items of food {@code a} eats into a food slot: one that
	 * already holds the same item, else an empty one, else (both hold other food) the first slot is emptied into the chest
	 * first. Returns false when there is nothing to fetch or no room can be made, so the caller moves on.
	 */
	private static boolean fetchFood(ServerLevel level, CopperGolem golem, GolemData data, State s, Animal a) {
		BlockPos pos = data.assignments.get(ButcherProfession.FOOD);
		Container chest = pos == null ? null : TransporterLogic.containerAt(level, pos);
		if (chest == null || chestFood(chest, a) < 0 || !canMakeRoom(chest, data, chest.getItem(chestFood(chest, a)))) return false;
		s.doing = "Fetching breeding food";
		if (!Interact.approach(level, golem, pos, s.walk, SPEED)) return true;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		int src = chestFood(chest, a);
		ItemStack wanted = chest.getItem(src);
		int dst = foodTarget(data, wanted);
		ItemStack cur = data.items.get(dst);
		if (!cur.isEmpty() && !ItemStack.isSameItemSameComponents(cur, wanted)) {
			NonNullList<ItemStack> one = NonNullList.withSize(1, cur);
			TransporterLogic.insertAll(one, chest);
			data.items.set(dst, one.get(0));
			cur = data.items.get(dst);
			if (!cur.isEmpty()) return false; // chest refused it: canMakeRoom is a prediction, never lose the stack
			src = chestFood(chest, a);
			if (src < 0) return false;
			wanted = chest.getItem(src);
		}
		int room = Math.min(FOOD_FETCH, wanted.getMaxStackSize() - cur.getCount());
		int n = Math.min(room, wanted.getCount());
		if (n <= 0) return false;
		ItemStack taken = chest.removeItem(src, n);
		if (cur.isEmpty()) data.items.set(dst, taken); else cur.grow(taken.getCount());
		chest.setChanged();
		Interact.openChest(level, golem, pos, Interact.Action.TAKE);
		s.walk.reset();
		s.cooldown = Interact.OPEN_TICKS;
		GolemData.save(golem, data);
		return true;
	}

	/** Food slot to receive {@code wanted}: same item with room, else empty, else slot 0 (to be emptied into the chest). */
	private static int foodTarget(GolemData data, ItemStack wanted) {
		for (int i = ButcherProfession.FOOD_FROM; i < ButcherProfession.FOOD_FROM + ButcherProfession.FOOD_SLOTS; i++) {
			ItemStack st = data.items.get(i);
			if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, wanted) && st.getCount() < st.getMaxStackSize()) return i;
		}
		for (int i = ButcherProfession.FOOD_FROM; i < ButcherProfession.FOOD_FROM + ButcherProfession.FOOD_SLOTS; i++) {
			if (data.items.get(i).isEmpty()) return i;
		}
		return ButcherProfession.FOOD_FROM;
	}

	/** Mirrors {@link #fetchFood}: a slot exists, or the chest has a free slot or a mergeable stack for what it would receive back. */
	private static boolean canMakeRoom(Container chest, GolemData data, ItemStack wanted) {
		int dst = foodTarget(data, wanted);
		ItemStack cur = data.items.get(dst);
		if (cur.isEmpty() || ItemStack.isSameItemSameComponents(cur, wanted)) return cur.getCount() < wanted.getMaxStackSize();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty()) return true;
			if (ItemStack.isSameItemSameComponents(st, cur) && st.getCount() + cur.getCount() <= st.getMaxStackSize()) return true;
		}
		return false;
	}

	// ---- weapon ---------------------------------------------------------------------------------------------------

	/** Chest slot of a weapon that may be taken: fills the empty slot or replaces a worn one (about to break); -1 if none. */
	static int fetchableWeapon(Container chest, ItemStack cur) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (isWeapon(st) && st.getCount() == 1 && (cur.isEmpty() || !st.nextDamageWillBreak())) return i;
		}
		return -1;
	}

	/** Walks to the tool chest and takes a weapon (a worn one goes into the chest slot: nothing lost). False when there is none. */
	private static boolean fetchWeapon(ServerLevel level, CopperGolem golem, GolemData data, State s) {
		BlockPos pos = data.assignments.get(ButcherProfession.TOOLS);
		Container chest = pos == null ? null : TransporterLogic.containerAt(level, pos);
		ItemStack cur = data.items.get(ButcherProfession.TOOL_SLOT);
		if (chest == null || fetchableWeapon(chest, cur) < 0) return false;
		s.doing = "Fetching a weapon";
		endCull(level, golem, s);
		if (!Interact.approach(level, golem, pos, s.walk, SPEED)) return true;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		int i = fetchableWeapon(chest, cur);
		if (i >= 0) {
			ItemStack fresh = chest.removeItem(i, 1);
			if (!cur.isEmpty()) chest.setItem(i, cur); // only single-item slots are offered, so slot i is empty now
			data.items.set(ButcherProfession.TOOL_SLOT, fresh);
			chest.setChanged();
			Interact.openChest(level, golem, pos, Interact.Action.TAKE);
		}
		s.walk.reset();
		s.cooldown = Interact.OPEN_TICKS;
		GolemData.save(golem, data);
		return true;
	}

	// ---- carry ----------------------------------------------------------------------------------------------------

	static NonNullList<ItemStack> carryView(GolemData data) {
		NonNullList<ItemStack> view = NonNullList.withSize(ButcherProfession.CARRY_SLOTS, ItemStack.EMPTY);
		for (int i = 0; i < view.size(); i++) view.set(i, data.items.get(ButcherProfession.CARRY_FROM + i));
		return view;
	}

	static void writeCarry(GolemData data, NonNullList<ItemStack> view) {
		for (int i = 0; i < view.size(); i++) data.items.set(ButcherProfession.CARRY_FROM + i, view.get(i));
	}

	static int freeCarry(GolemData data) {
		int n = 0;
		for (int i = 0; i < ButcherProfession.CARRY_SLOTS; i++) if (data.items.get(ButcherProfession.CARRY_FROM + i).isEmpty()) n++;
		return n;
	}

	/** "cow" -> "cows", "sheep" -> "sheep" (the registry path, no language file needed on a server). */
	static String plural(EntityType<?> type) {
		String n = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		return n.equals("sheep") ? n : n + "s";
	}
}
