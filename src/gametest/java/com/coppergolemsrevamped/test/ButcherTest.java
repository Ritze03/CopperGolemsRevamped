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

	private static final class Pen {
		ChestBlockEntity tools, food, out;
		CopperGolem golem;
		GolemData data;
		AABB all;
		int placed;
	}

	private static Pen pen(GameTestHelper helper, boolean withFoodChest) {
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

	private static <T extends Animal> T animal(GameTestHelper helper, Pen p, EntityType<T> type, boolean baby) {
		int i = p.placed++;
		T a = helper.spawn(type, new BlockPos(1 + i % 5, 1, 1 + (i / 5) % 5));
		a.setPersistenceRequired();
		if (baby) a.setBaby(true);
		return a;
	}

	private static <T extends Animal> List<T> animals(GameTestHelper helper, Pen p, Class<T> cls) {
		return helper.getLevel().getEntitiesOfClass(cls, p.all, Animal::isAlive);
	}

	private static long adults(List<? extends Animal> l) {
		return l.stream().filter(a -> !a.isBaby()).count();
	}

	private static long babies(List<? extends Animal> l) {
		return l.stream().filter(Animal::isBaby).count();
	}

	private static int count(ChestBlockEntity chest, Item item) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) n += chest.getItem(i).getCount();
		return n;
	}

	private static int total(ChestBlockEntity chest) {
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

	private static void setSlot(Pen p, int slot, ItemStack s) {
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
			helper.assertTrue(beef + leather == total(p.out), "unexpected items in the output chest");
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

	/** A sword that breaks on its next use is swapped for a fresh one from the tool chest; the worn one goes into the chest. */
	@GameTest(maxTicks = 2000)
	public void wornWeaponIsSwappedNotLost(GameTestHelper helper) {
		Pen p = pen(helper, false);
		ItemStack worn = new ItemStack(Items.IRON_SWORD);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		setSlot(p, ButcherProfession.TOOL_SLOT, worn);
		p.tools.setItem(0, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 9; i++) animal(helper, p, EntityTypes.COW, false);
		helper.succeedWhen(() -> {
			ItemStack slot = GolemData.get(p.golem).items.get(ButcherProfession.TOOL_SLOT);
			helper.assertTrue(slot.is(Items.IRON_SWORD) && !slot.nextDamageWillBreak(), "no fresh sword in the slot: " + slot);
			ItemStack back = p.tools.getItem(0);
			helper.assertTrue(back.is(Items.IRON_SWORD) && back.getDamageValue() == back.getMaxDamage() - 1, "the worn sword is not in the chest: " + back);
			helper.assertTrue(animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class).size() == 8, "no kill yet");
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

	/** No weapon anywhere: nothing is killed, but breeding and collecting go on. */
	@GameTest(maxTicks = 3000)
	public void noWeaponStillBreedsAndCollects(GameTestHelper helper) {
		Pen p = pen(helper, true);
		p.food.setItem(0, new ItemStack(Items.CARROT, 8));
		for (int i = 0; i < 4; i++) animal(helper, p, EntityTypes.PIG, false);
		for (int i = 0; i < 10; i++) animal(helper, p, EntityTypes.COW, false);
		Vec3 at = helper.absoluteVec(new Vec3(3.5, 1.2, 3.5));
		ItemEntity feather = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.FEATHER, 5));
		feather.setNoPickUpDelay();
		helper.getLevel().addFreshEntity(feather);
		helper.onEachTick(() -> {
			var cows = animals(helper, p, net.minecraft.world.entity.animal.cow.Cow.class);
			helper.assertTrue(cows.size() == 10, "a cow was killed without a weapon: " + cows.size());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(count(p.out, Items.FEATHER) == 5, "feather in the output chest: " + count(p.out, Items.FEATHER));
			helper.assertTrue(babies(animals(helper, p, net.minecraft.world.entity.animal.pig.Pig.class)) >= 1, "no baby pig");
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
}
