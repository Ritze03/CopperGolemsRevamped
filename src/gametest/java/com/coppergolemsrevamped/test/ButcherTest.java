package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.butcher.ButcherLogic;
import com.coppergolemsrevamped.profession.butcher.ButcherProfession;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Butcher. Every test builds a 6x6 pen (x/z 1..6) inside a 2-high stone wall so animals cannot leave; the three chests
 * sit in the west wall (tools z=2, food z=3, output z=4), so the golem reaches them from inside the pen.
 */
public class ButcherTest {
	private static final BlockPos TOOLS = new BlockPos(0, 1, 2);
	private static final BlockPos FOOD = new BlockPos(0, 1, 3);
	private static final BlockPos OUT = new BlockPos(0, 1, 4);

	static final class Pen {
		ChestBlockEntity tools, food, out;
		CopperGolem golem;
		GolemData data;
		AABB all;
		int placed;
	}

	static Pen pen(GameTestHelper helper, boolean withFoodChest) {
		Pen p = new Pen();
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		for (int i = 0; i < 8; i++) {
			for (int y = 1; y <= 2; y++) {
				for (BlockPos w : new BlockPos[] {new BlockPos(0, y, i), new BlockPos(7, y, i), new BlockPos(i, y, 0), new BlockPos(i, y, 7)}) {
					helper.setBlock(w, Blocks.COBBLESTONE);
				}
			}
		}
		helper.setBlock(TOOLS, Blocks.CHEST);
		helper.setBlock(FOOD, Blocks.CHEST);
		helper.setBlock(OUT, Blocks.CHEST);
		p.tools = helper.getBlockEntity(TOOLS, ChestBlockEntity.class);
		p.food = helper.getBlockEntity(FOOD, ChestBlockEntity.class);
		p.out = helper.getBlockEntity(OUT, ChestBlockEntity.class);
		p.all = new AABB(helper.absoluteVec(new Vec3(1, 0, 1)), helper.absoluteVec(new Vec3(7, 4, 7)));
		p.golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(6, 1, 6));
		GolemService.changeProfession(helper.getLevel(), p.golem, ButcherProfession.ID);
		p.data = GolemData.get(p.golem);
		p.data.assignments.put("area_a", helper.absolutePos(new BlockPos(1, 0, 1)));
		p.data.assignments.put("area_b", helper.absolutePos(new BlockPos(6, 0, 6)));
		p.data.assignments.put(ButcherProfession.TOOLS, helper.absolutePos(TOOLS));
		if (withFoodChest) p.data.assignments.put(ButcherProfession.FOOD, helper.absolutePos(FOOD));
		p.data.assignments.put(ButcherProfession.OUTPUT, helper.absolutePos(OUT));
		GolemData.save(p.golem, p.data);
		return p;
	}

	static <T extends Animal> T animal(GameTestHelper helper, Pen p, EntityType<T> type, boolean baby) {
		int i = p.placed++;
		T a = helper.spawn(type, new BlockPos(1 + i % 5, 1, 1 + (i / 5) % 5));
		a.setPersistenceRequired();
		if (baby) a.setBaby(true);
		return a;
	}

	static <T extends Animal> List<T> animals(GameTestHelper helper, Pen p, Class<T> cls) {
		return helper.getLevel().getEntitiesOfClass(cls, p.all, Animal::isAlive);
	}

	private static long adults(List<? extends Animal> l) {
		return l.stream().filter(a -> !a.isBaby()).count();
	}

	private static long babies(List<? extends Animal> l) {
		return l.stream().filter(Animal::isBaby).count();
	}

	static int count(ChestBlockEntity chest, Item item) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) n += chest.getItem(i).getCount();
		return n;
	}

	static int total(ChestBlockEntity chest) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
		return n;
	}

	private static int groundItems(GameTestHelper helper, Pen p) {
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, p.all, ItemEntity::isAlive).size();
	}

	private static ItemStack lootingSword(GameTestHelper helper, int level) {
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		if (level > 0) {
			Holder<Enchantment> looting = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING);
			sword.enchant(looting, level);
		}
		return sword;
	}

	static void setSlot(Pen p, int slot, ItemStack s) {
		p.data.items.set(slot, s);
		GolemData.save(p.golem, p.data);
	}

	// ---- culling --------------------------------------------------------------------------------------------------

	/** 12 adult cows and 3 calves: exactly 4 adults are culled, the calves stay, the drops reach the output chest. */
	@GameTest(maxTicks = 4000)
	public void cullsSurplusKeepsEightAdultsAndCalves(GameTestHelper helper) {
		Pen p = pen(helper, false);
		p.tools.setItem(0, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 12; i++) animal(helper, p, EntityTypes.COW, false);
		for (int i = 0; i < 3; i++) animal(helper, p, EntityTypes.COW, true);
		helper.onEachTick(() -> {
			List<?> cows = animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class);
			helper.assertTrue(adults(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class)) >= 8, "fewer than 8 adults: " + cows.size());
			helper.assertTrue(babies(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class)) == 3, "a calf was touched");
		});
		helper.succeedWhen(() -> {
			var cows = animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class);
			helper.assertTrue(adults(cows) == 8, "adults: " + adults(cows));
			helper.assertTrue(babies(cows) == 3, "calves: " + babies(cows));
			helper.assertTrue(groundItems(helper, p) == 0, "drops still on the ground");
			helper.assertTrue(p.data.items.subList(ButcherProfession.CARRY_FROM, p.data.items.size()).stream().allMatch(ItemStack::isEmpty), "carry not delivered");
			int beef = count(p.out, Items.BEEF), leather = count(p.out, Items.LEATHER);
			helper.assertTrue(beef >= 4, "beef (at least one per kill): " + beef);
			helper.assertTrue(beef + leather + count(p.out, Items.EXPERIENCE_BOTTLE) == total(p.out), "unexpected items in the output chest");
			// the weapon is in the golem's slot exactly once, nowhere else
			helper.assertTrue(p.data.items.get(ButcherProfession.TOOL_SLOT).is(Items.IRON_SWORD), "no sword in the tool slot");
			helper.assertTrue(total(p.tools) == 0, "the tool chest still holds items");
		});
	}

	/** A named cow and a leashed cow are never touched; the surplus is taken from the plain ones only. */
	@GameTest(maxTicks = 3000)
	public void namedAndLeashedAreNeverTouched(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		var named = animal(helper, p, EntityTypes.COW, false);
		named.setCustomName(Component.literal("Bessie"));
		helper.setBlock(new BlockPos(6, 1, 1), Blocks.OAK_FENCE);
		var leashed = animal(helper, p, EntityTypes.COW, false);
		leashed.setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(helper.getLevel(), helper.absolutePos(new BlockPos(6, 1, 1))), true);
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		helper.onEachTick(() -> {
			helper.assertTrue(named.isAlive(), "the named cow was killed");
			helper.assertTrue(leashed.isAlive(), "the leashed cow was killed");
		});
		helper.succeedWhen(() -> {
			var cows = animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class);
			helper.assertTrue(cows.size() == 10, "cows left (8 plain + 2 protected): " + cows.size());
			helper.assertTrue(groundItems(helper, p) == 0 && p.data.items.subList(ButcherProfession.CARRY_FROM, p.data.items.size()).stream().allMatch(ItemStack::isEmpty),
				"drops not delivered yet");
		});
	}

	@GameTest
	public void protectionRules(GameTestHelper helper) {
		Pen p = pen(helper, false);
		var plain = animal(helper, p, EntityTypes.COW, false);
		helper.assertTrue(!ButcherLogic.isProtected(plain), "a plain cow is not protected");
		var named = animal(helper, p, EntityTypes.COW, false);
		named.setCustomName(Component.literal("Bessie"));
		helper.assertTrue(ButcherLogic.isProtected(named), "named");
		helper.setBlock(new BlockPos(6, 1, 1), Blocks.OAK_FENCE);
		var sheep = animal(helper, p, EntityTypes.SHEEP, false);
		sheep.setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(helper.getLevel(), helper.absolutePos(new BlockPos(6, 1, 1))), true);
		helper.assertTrue(ButcherLogic.isProtected(sheep), "leashed");
		Wolf wolf = helper.spawn(EntityTypes.WOLF, new BlockPos(3, 1, 3));
		Player player = helper.makeMockServerPlayerInLevel();
		wolf.tame(player);
		helper.assertTrue(ButcherLogic.isProtected(wolf), "tamed wolf");
		helper.assertTrue(!ButcherLogic.FARM_ANIMALS.contains(EntityTypes.WOLF), "wolves are not managed at all");
		helper.succeed();
	}

	@GameTest
	public void countingRules(GameTestHelper helper) {
		helper.assertTrue(ButcherLogic.surplus(8) == 0 && ButcherLogic.surplus(7) == 0 && ButcherLogic.surplus(0) == 0, "no surplus up to 8");
		helper.assertTrue(ButcherLogic.surplus(9) == 1 && ButcherLogic.surplus(12) == 4, "surplus beyond 8");
		helper.assertTrue(ButcherLogic.breedAllowed(4, 0), "4 adults, no babies");
		helper.assertTrue(ButcherLogic.breedAllowed(8, 7), "7 babies are fewer than 8");
		helper.assertTrue(!ButcherLogic.breedAllowed(4, 8), "8 babies stop breeding");
		helper.assertTrue(ButcherLogic.breedAllowed(16, 7) && !ButcherLogic.breedAllowed(16, 8), "baby cap");
		helper.assertTrue(!ButcherLogic.breedAllowed(20, 4) && ButcherLogic.breedAllowed(19, 4), "24 animals stop breeding");
		helper.succeed();
	}

	// ---- looting and durability ------------------------------------------------------------------------------------

	private static void lootingCase(GameTestHelper helper, int level) {
		Pen p = pen(helper, false);
		ItemStack sword = lootingSword(helper, level);
		setSlot(p, ButcherProfession.TOOL_SLOT, sword);
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		helper.succeedWhen(() -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 8, "no kill yet");
			helper.assertTrue(ButcherLogic.strikes(p.golem) >= 1, "no strike recorded");
			helper.assertTrue(ButcherLogic.lastLooting(p.golem) == level, "looting level seen by the loot context: " + ButcherLogic.lastLooting(p.golem));
			helper.assertTrue(ButcherLogic.handWasReal(p.golem), "the real tool stack was not in the golem's hand during the hit");
			helper.assertTrue(p.golem.getMainHandItem() != GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT),
				"the real stack stayed in the hand after the hit");
		});
	}

	/** The level the loot function reads ({@code EnchantmentHelper:getEnchantmentLevel(looting, attacker)}) is the tool's. */
	@GameTest(maxTicks = 1500)
	public void lootingThreeIsAppliedToTheKill(GameTestHelper helper) {
		lootingCase(helper, 3);
	}

	@GameTest(maxTicks = 1500)
	public void noLootingWithoutTheEnchantment(GameTestHelper helper) {
		lootingCase(helper, 0);
	}

	/** Durability is taken from the real slot stack: one point per hit (iron sword), and a kill takes more than one hit. */
	@GameTest(maxTicks = 1500)
	public void durabilityGoesToTheRealStack(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		helper.succeedWhen(() -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 8, "no kill yet");
			int strikes = ButcherLogic.strikes(p.golem);
			helper.assertTrue(strikes >= 2, "a cow (10 health) takes at least two sword hits: " + strikes);
			ItemStack tool = GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT);
			helper.assertTrue(tool.is(Items.IRON_SWORD) && tool.getDamageValue() == strikes, "sword damage " + tool.getDamageValue() + " after " + strikes + " hits");
		});
	}

	/** A plain sword with 1 durability is used until it breaks (never parked), then a fresh one is taken from the tool chest. */
	@GameTest(maxTicks = 2000)
	public void plainSwordBreaksThenNextIsFetched(GameTestHelper helper) {
		Pen p = pen(helper, false);
		ItemStack worn = new ItemStack(Items.IRON_SWORD);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		setSlot(p, ButcherProfession.TOOL_SLOT, worn);
		p.tools.setItem(0, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		helper.succeedWhen(() -> {
			ItemStack slot = GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT);
			helper.assertTrue(slot.is(Items.IRON_SWORD) && slot.getDamageValue() < slot.getMaxDamage() - 1, "no fresh sword in the slot: " + slot);
			helper.assertTrue(count(p.tools, Items.IRON_SWORD) == 0, "the worn sword was parked in the chest");
		});
	}

	// ---- breeding -------------------------------------------------------------------------------------------------

	/** 4 adult pigs and carrots in the food chest: babies appear, food is consumed in pairs, nothing is lost or duplicated. */
	@GameTest(maxTicks = 3000)
	public void breedsPigsWithCarrots(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.food.setItem(0, new ItemStack(Items.CARROT, 8));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		java.util.function.IntSupplier carrots = () -> count(p.food, Items.CARROT)
			+ GolemData.get(p.golem).items.stream().filter(s -> s.is(Items.CARROT)).mapToInt(ItemStack::getCount).sum();
		helper.onEachTick(() -> {
			var pigs = animals(helper, p, net.minecraft.world.entity.animal.pig.Pig.class);
			helper.assertTrue(adults(pigs) == 4, "adults: " + adults(pigs) + " (no pig may be killed: no weapon)");
			helper.assertTrue(babies(pigs) <= ButcherLogic.MAX_BABIES, "baby cap broken");
			helper.assertTrue(groundItems(helper, p) == 0, "food dropped on the ground");
		});
		helper.succeedWhen(() -> {
			var pigs = animals(helper, p, net.minecraft.world.entity.animal.pig.Pig.class);
			helper.assertTrue(babies(pigs) >= 1, "no baby yet");
			int used = 8 - carrots.getAsInt();
			helper.assertTrue(used > 0 && used % 2 == 0 && used >= 2 * babies(pigs), "carrots used: " + used + " for " + babies(pigs) + " babies");
			helper.assertTrue(count(p.out, Items.CARROT) == 0, "food went to the output chest");
		});
	}

	/** At the baby cap nothing is fed: the food stays untouched. */
	@GameTest(maxTicks = 600)
	public void noBreedingAtTheBabyCap(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.food.setItem(0, new ItemStack(Items.CARROT, 16));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		for (int i = 0; i < ButcherLogic.MAX_BABIES; i++) animal(helper, p, EntityTypes.PIG, true);
		helper.runAfterDelay(400, () -> {
			helper.assertTrue(count(p.food, Items.CARROT) == 16, "carrots taken: " + count(p.food, Items.CARROT));
			helper.assertTrue(GolemData.get(p.golem).items.stream().noneMatch(s -> s.is(Items.CARROT)), "the golem fetched food");
			var pigs = animals(helper, p, net.minecraft.world.entity.animal.pig.Pig.class);
			helper.assertTrue(babies(pigs) == ButcherLogic.MAX_BABIES && adults(pigs) == 4, "herd changed: " + adults(pigs) + "/" + babies(pigs));
			helper.succeed();
		});
	}

	// ---- edge cases -----------------------------------------------------------------------------------------------

	/** No weapon anywhere: nothing is killed, but breeding goes on. Stray ground items are not collected any more (see butcher.md). */
	@GameTest(maxTicks = 3000)
	public void noWeaponStillBreeds(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.food.setItem(0, new ItemStack(Items.CARROT, 8));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		helper.onEachTick(() -> {
			var cows = animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class);
			helper.assertTrue(cows.size() == 10, "a cow was killed without a weapon: " + cows.size());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(babies(animals(helper, p, net.minecraft.world.entity.animal.pig.Pig.class)) >= 1, "no baby pig");
		});
	}

	/** The recorded phases run BREED, KILL, DELIVER; a kill is never followed by BREED without a DELIVER in between. */
	@GameTest(maxTicks = 1500)
	public void cycleOrder(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		List<ButcherLogic.Phase> seen = new java.util.ArrayList<>();
		ButcherLogic.phaseListener = (g, ph) -> {
			if (g == p.golem) seen.add(ph);
		};
		helper.succeedWhen(() -> {
			helper.assertTrue(count(p.out, Items.BEEF) >= 1, "nothing delivered yet");
			ButcherLogic.phaseListener = null;
			helper.assertTrue(seen.size() >= 3 && seen.get(0) == ButcherLogic.Phase.BREED && seen.get(1) == ButcherLogic.Phase.KILL
				&& seen.get(2) == ButcherLogic.Phase.DELIVER, "phases: " + seen);
			for (int i = 1; i < seen.size(); i++) {
				helper.assertTrue(!(seen.get(i - 1) == ButcherLogic.Phase.KILL && seen.get(i) != ButcherLogic.Phase.DELIVER), "KILL not followed by DELIVER: " + seen);
			}
		});
	}

	/** Death drops are taken into the carry at the moment of the kill: no item entity ever appears, even with a full output chest. */
	@GameTest(maxTicks = 1500)
	public void dropsGoStraightToCarry(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 27; i++) p.out.setItem(i, new ItemStack(Items.DIRT, 64)); // nothing can be delivered, the carry keeps the drops
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		helper.onEachTick(() -> helper.assertTrue(groundItems(helper, p) == 0, "an item entity from the kill exists"));
		helper.succeedWhen(() -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 8, "no kill yet");
			int beef = GolemData.get(p.golem).items.stream().filter(s -> s.is(Items.BEEF)).mapToInt(ItemStack::getCount).sum();
			helper.assertTrue(beef >= 1, "no beef in the carry: " + beef);
			helper.assertTrue(groundItems(helper, p) == 0, "drops on the ground");
		});
	}

	/** Carry with fewer than 2 free slots: no kill (drops could not be taken); nothing in the carry or the full chest changes. */
	@GameTest(maxTicks = 700)
	public void noCullingWithoutCarryRoom(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 8; i++) p.data.items.set(ButcherProfession.CARRY_FROM + i, new ItemStack(Items.COBBLESTONE, 64));
		GolemData.save(p.golem, p.data);
		for (int i = 0; i < 27; i++) p.out.setItem(i, new ItemStack(Items.DIRT, 64));
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		helper.runAfterDelay(500, () -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 10, "a cow was killed");
			helper.assertTrue(total(p.out) == 27 * 64 && count(p.out, Items.DIRT) == 27 * 64, "the full output chest changed");
			int cobble = GolemData.get(p.golem).items.stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
			helper.assertTrue(cobble == 8 * 64, "carry changed: " + cobble);
			helper.succeed();
		});
	}

	/** Output full and exactly 2 free carry slots: one kill fills them, culling stops, the carry keeps everything (no loss). */
	@GameTest(maxTicks = 1500)
	public void fullOutputStopsCullingKeepsTheCarry(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 7; i++) p.data.items.set(ButcherProfession.CARRY_FROM + i, new ItemStack(Items.COBBLESTONE, 64));
		GolemData.save(p.golem, p.data);
		for (int i = 0; i < 27; i++) p.out.setItem(i, new ItemStack(Items.DIRT, 64));
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		helper.runAfterDelay(1000, () -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 9, "exactly one kill expected, cows left: "
				+ animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size());
			helper.assertTrue(count(p.out, Items.DIRT) == 27 * 64 && total(p.out) == 27 * 64, "the full output chest changed");
			var items = GolemData.get(p.golem).items;
			int cobble = items.stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
			int beef = items.stream().filter(s -> s.is(Items.BEEF)).mapToInt(ItemStack::getCount).sum();
			helper.assertTrue(cobble == 7 * 64, "cobblestone in the carry: " + cobble);
			helper.assertTrue(beef >= 1, "the drops were lost, beef in the carry: " + beef);
			helper.assertTrue(groundItems(helper, p) == 0, "drops left on the ground");
			helper.succeed();
		});
	}

	/** A frozen Butcher does nothing: no kill, no feeding, no walking, items stay put. */
	@GameTest(maxTicks = 600)
	public void frozenButcherDoesNothing(GameTestHelper helper) {
		Pen p = pen(helper, true);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		p.food.setItem(0, new ItemStack(Items.WHEAT, 8));
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		p.data.frozen = true;
		GolemData.save(p.golem, p.data);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 10, "a cow was killed while frozen");
			helper.assertTrue(count(p.food, Items.WHEAT) == 8, "food taken while frozen");
			// not a distance check: wandering cows may push a standing golem
			helper.assertTrue(p.golem.getNavigation().isDone(), "the golem is walking");
			helper.assertTrue(ButcherLogic.strikes(p.golem) == 0, "it struck");
			helper.succeed();
		});
	}

	@GameTest
	public void butcherDeclaration(GameTestHelper helper) {
		Profession p = Professions.get(ButcherProfession.ID);
		helper.assertTrue(p != null && !p.runsVanillaBrain(), "registered, own tick");
		helper.assertTrue(p.inventorySize() == 12, "slots: " + p.inventorySize());
		helper.assertTrue(p.group("tool").inHand() && p.group("tool").count() == 1, "tool group shown in hand");
		helper.assertTrue(p.group("food").count() == ButcherProfession.FOOD_SLOTS && p.group("carry").count() == 9, "food and carry groups");
		helper.assertTrue(ButcherLogic.isWeapon(new ItemStack(Items.IRON_SWORD)) && ButcherLogic.isWeapon(new ItemStack(Items.IRON_AXE))
			&& !ButcherLogic.isWeapon(new ItemStack(Items.IRON_PICKAXE)), "weapon filter");
		helper.assertTrue(ButcherLogic.isFood(new ItemStack(Items.WHEAT)) && ButcherLogic.isFood(new ItemStack(Items.CARROT))
			&& !ButcherLogic.isFood(new ItemStack(Items.COBBLESTONE)), "food filter");
		helper.assertTrue(p.assigns().size() == 4 && p.assigns().get(0).maxSize() == 16, "assigns: area (16), tools, food, output");
		helper.assertTrue(!p.help().isEmpty() && p.assigns().stream().noneMatch(a -> a.help().isEmpty()), "help texts");
		helper.succeed();
	}

	// ---- victim order: breeding cooldown first -------------------------------------------------------------------------

	/** 10 adult cows, 3 on a breeding cooldown: exactly 2 are culled and both are cooldown cows (they cannot breed anyway). */
	@GameTest(maxTicks = 3000)
	public void cooldownAnimalsAreCulledFirst(GameTestHelper helper) {
		Pen p = pen(helper, false);
		setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		var cows = new java.util.ArrayList<net.minecraft.world.entity.animal.cow.Cow>();
		for (int i = 0; i < 10; i++) cows.add(animal(helper, p, EntityTypes.COW, false));
		// the cooldown cows are spawned last (farthest from the golem), so "nearest first" alone would not pick them
		for (int i = 7; i < 10; i++) cows.get(i).setAge(6000);
		helper.onEachTick(() -> {
			for (int i = 0; i < 7; i++) helper.assertTrue(cows.get(i).isAlive(), "a breedable cow was culled before the cooldown ones: " + i);
		});
		helper.succeedWhen(() -> {
			long dead = cows.stream().filter(c -> !c.isAlive()).count();
			helper.assertTrue(dead == 2, "killed: " + dead);
			helper.assertTrue(cows.subList(7, 10).stream().filter(c -> !c.isAlive()).count() == 2, "both victims have the cooldown");
		});
	}

	// ---- one-sided push --------------------------------------------------------------------------------------------

	/** 6 cows crowd a frozen Butcher: it does not move, the cows are pushed away. */
	@GameTest(maxTicks = 300)
	public void crowdDoesNotMoveTheButcher(GameTestHelper helper) {
		Pen p = pen(helper, false);
		p.data.frozen = true;
		GolemData.save(p.golem, p.data);
		Vec3 start = p.golem.position();
		var cows = new java.util.ArrayList<net.minecraft.world.entity.animal.cow.Cow>();
		Vec3 centre = new Vec3(6.5, 1, 6.5);
		double[][] off = {{0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.25, 0.25}, {-0.25, -0.25}};
		for (double[] o : off) {
			var c = helper.spawnWithNoFreeWill(EntityTypes.COW, centre.add(o[0], 0, o[1]));
			c.setPersistenceRequired();
			cows.add(c);
		}
		Vec3[] cowStart = cows.stream().map(net.minecraft.world.entity.Entity::position).toArray(Vec3[]::new);
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(p.golem.position().distanceTo(start) < 0.05, "the Butcher moved " + p.golem.position().distanceTo(start));
			double maxMoved = 0;
			for (int i = 0; i < cows.size(); i++) maxMoved = Math.max(maxMoved, cows.get(i).position().distanceTo(cowStart[i]));
			helper.assertTrue(maxMoved > 0.2, "no cow was pushed, max " + maxMoved);
			helper.succeed();
		});
	}

	/** Pair rules, called directly: an animal does not move a Butcher but is moved; a player moves it; a non-Butcher golem is pushed as in vanilla. */
	@GameTest(maxTicks = 100)
	public void pushIsOneSidedOnlyAgainstAnimals(GameTestHelper helper) {
		Pen p = pen(helper, false);
		var cow = helper.spawnWithNoFreeWill(EntityTypes.COW, new Vec3(5.5, 1, 6.5));
		cow.setDeltaMovement(Vec3.ZERO);
		p.golem.setDeltaMovement(Vec3.ZERO);
		cow.push(p.golem);
		helper.assertTrue(p.golem.getDeltaMovement().lengthSqr() == 0, "a cow pushed the Butcher: " + p.golem.getDeltaMovement());
		helper.assertTrue(cow.getDeltaMovement().lengthSqr() > 0, "the cow was not pushed");
		cow.setDeltaMovement(Vec3.ZERO);
		p.golem.push(cow); // the golem's own tick against the cow
		helper.assertTrue(p.golem.getDeltaMovement().lengthSqr() == 0, "the Butcher was moved by pushing a cow");
		helper.assertTrue(cow.getDeltaMovement().lengthSqr() > 0, "the cow was not pushed by the Butcher");
		@SuppressWarnings("removal")
		net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setPos(p.golem.getX() - 0.3, p.golem.getY(), p.golem.getZ());
		player.push(p.golem);
		helper.assertTrue(p.golem.getDeltaMovement().lengthSqr() > 0, "a player cannot push the Butcher");
		var other = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new Vec3(3.5, 1, 3.5));
		other.setDeltaMovement(Vec3.ZERO);
		cow.setPos(other.getX() - 0.3, other.getY(), other.getZ());
		cow.push(other);
		helper.assertTrue(other.getDeltaMovement().lengthSqr() > 0, "a golem without the Butcher profession is not pushed as in vanilla");
		helper.succeed();
	}

	/** Keep layout: the food chest's carrots are fetched but the slot never drops below 1. */
	@GameTest(maxTicks = 3000)
	public void keepLayoutFoodFetchLeavesOne(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.data.keepLayout = true;
		GolemData.save(p.golem, p.data);
		p.food.setItem(0, new ItemStack(Items.CARROT, 8));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		boolean[] taken = {false};
		helper.onEachTick(() -> {
			helper.assertTrue(p.food.getItem(0).getCount() >= 1, "the food slot was emptied");
			if (p.food.getItem(0).getCount() < 8) taken[0] = true;
		});
		helper.succeedWhen(() -> helper.assertTrue(taken[0], "no food was fetched"));
	}

	/** Keep layout: a food slot holding a single carrot offers nothing, so nothing is fetched and the golem does not loop. */
	@GameTest(maxTicks = 700)
	public void keepLayoutSingleFoodItemIsNotTaken(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.data.keepLayout = true;
		GolemData.save(p.golem, p.data);
		p.food.setItem(0, new ItemStack(Items.CARROT, 1));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		helper.runAfterDelay(400, () -> {
			helper.assertTrue(count(p.food, Items.CARROT) == 1, "the last carrot was taken");
			helper.assertTrue(GolemData.get(p.golem).items.stream().noneMatch(s -> s.is(Items.CARROT)), "the golem holds carrots");
			helper.succeed();
		});
	}
}
