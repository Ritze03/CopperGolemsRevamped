package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.core.GolemXp;
import com.coppergolemsrevamped.profession.butcher.ButcherProfession;
import com.coppergolemsrevamped.profession.smelter.SmelterProfession;
import com.coppergolemsrevamped.test.ButcherTest.Pen;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Stored golem XP and the XP bottles (superdoc/features/xp-bottles.md): Smelter, Butcher, full chest, profession change, persistence. */
public class XpTest {
	private static int bottles(ChestBlockEntity chest) {
		return SmelterTest.count(chest, Items.EXPERIENCE_BOTTLE);
	}

	private static double orbs(GameTestHelper helper, AABB box) {
		double v = 0;
		for (ExperienceOrb o : helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, box)) v += o.getValue();
		return v;
	}

	private static AABB around(GameTestHelper helper) {
		return new AABB(helper.absoluteVec(new Vec3(-16, -4, -16)), helper.absoluteVec(new Vec3(24, 12, 24)));
	}

	/**
	 * 14 gold ore (exactly 14 XP: recipe XP 1.0, no random rounding): 2 bottles in the output, no orbs anywhere, nothing left stored.
	 * Why gold and not iron ore (0.7 XP): the fraction is rounded up at random for every furnace collection, so the total
	 * varied from run to run and sometimes stayed under the 7 XP of one bottle (flaky "bottles in the output: 0").
	 */
	@GameTest(maxTicks = 3800)
	public void smelterMakesBottlesFromFurnaceXp(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = SmelterTest.base(helper, 1, c);
		AbstractFurnaceBlockEntity[] f = {SmelterTest.furnace(helper, new BlockPos(1, 1, 5), Blocks.FURNACE),
			SmelterTest.furnace(helper, new BlockPos(3, 1, 5), Blocks.BLAST_FURNACE)};
		c[0].setItem(0, new ItemStack(Items.GOLD_ORE, 14));
		SmelterTest.addFuel(c[1], 64);
		AABB box = around(helper);
		helper.onEachTick(() -> helper.assertTrue(orbs(helper, box) == 0, "an experience orb appeared"));
		helper.succeedWhen(() -> {
			helper.assertTrue(SmelterTest.count(c[2], Items.GOLD_INGOT) == 14, "ingots: " + SmelterTest.count(c[2], Items.GOLD_INGOT));
			helper.assertTrue(bottles(c[2]) == 2, "bottles in the output: " + bottles(c[2]));
			helper.assertTrue(GolemData.get(golem).xp < GolemXp.XP_PER_BOTTLE, "stored XP: " + GolemData.get(golem).xp);
			int xp = GolemData.get(golem).xp + 7 * (bottles(c[2]));
			helper.assertTrue(xp == 14, "XP total (14 ore x 1.0): " + xp);
			for (var fu : f) {
				helper.assertTrue(((com.coppergolemsrevamped.mixin.AbstractFurnaceAccessor) fu).cgr$recipesUsed().isEmpty(), "furnace XP map not emptied");
			}
		});
	}

	/** Stored XP fills the 9th slot with bottles, which DELIVER moves out; an item an older version left in that slot is delivered first, not lost. */
	@GameTest(maxTicks = 1500)
	public void smelterXpSlotDeliversAndKeepsOldItems(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = SmelterTest.base(helper, 1, c);
		GolemData d = GolemData.get(golem);
		d.items.set(SmelterProfession.XP_SLOT, new ItemStack(Items.GOLD_INGOT, 5));
		d.xp = 20;
		GolemData.save(golem, d);
		helper.succeedWhen(() -> {
			helper.assertTrue(SmelterTest.count(c[2], Items.GOLD_INGOT) == 5, "the old item was lost: " + SmelterTest.count(c[2], Items.GOLD_INGOT));
			helper.assertTrue(bottles(c[2]) == 2, "bottles: " + bottles(c[2]));
			helper.assertTrue(GolemData.get(golem).xp == 6, "remainder: " + GolemData.get(golem).xp);
		});
	}

	/** Full output chest: the XP stays stored (as bottles in the XP slot, nothing lost), the chest is untouched. */
	@GameTest(maxTicks = 600)
	public void smelterFullOutputKeepsTheXp(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[3];
		CopperGolem golem = SmelterTest.base(helper, 1, c);
		for (int i = 0; i < c[2].getContainerSize(); i++) c[2].setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		GolemData d = GolemData.get(golem);
		d.xp = 20;
		GolemData.save(golem, d);
		helper.runAfterDelay(400, () -> {
			GolemData n = GolemData.get(golem);
			int held = n.items.get(SmelterProfession.XP_SLOT).getCount();
			helper.assertTrue(n.xp + 7 * held == 20, "XP lost: " + n.xp + " + 7 x " + held);
			helper.assertTrue(bottles(c[2]) == 0 && SmelterTest.total(c[2]) == 27 * 64, "the full chest changed");
			helper.succeed();
		});
	}

	/** Every kill adds XP (1 to 3 for a cow); enough kills put a bottle into the output chest; no orbs are spawned. */
	@GameTest(maxTicks = 7000)
	public void butcherKillsFillTheStoreAndMakeBottles(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		ButcherTest.setSlot(p, ButcherProfession.TOOL_SLOT, new ItemStack(Items.IRON_SWORD));
		for (int i = 0; i < 16; i++) ButcherTest.animal(helper, p, EntityTypes.COW, false);
		boolean[] storeSeen = new boolean[1];
		AABB box = around(helper);
		helper.onEachTick(() -> {
			storeSeen[0] |= GolemData.get(p.golem).xp > 0;
			helper.assertTrue(orbs(helper, box) == 0, "an experience orb appeared");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(storeSeen[0], "a kill never added XP to the store");
			helper.assertTrue(bottles(p.out) >= 1, "bottles in the output: " + bottles(p.out) + ", stored XP " + GolemData.get(p.golem).xp);
		});
	}

	/** The breeding orb of a pair the Butcher fed goes to the store: a baby appears, no orb does. */
	@GameTest(maxTicks = 3000)
	public void butcherTakesTheBreedingXp(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, true);
		p.food.setItem(0, new ItemStack(Items.CARROT, 8));
		for (int i = 0; i < 4; i++) ButcherTest.animal(helper, p, EntityTypes.PIG, false);
		AABB box = around(helper);
		helper.onEachTick(() -> helper.assertTrue(orbs(helper, box) == 0, "an experience orb appeared"));
		helper.succeedWhen(() -> {
			helper.assertTrue(ButcherTest.animals(helper, p, Pig.class).stream().anyMatch(Pig::isBaby), "no baby yet");
			int xp = GolemData.get(p.golem).xp + 7 * bottles(p.out);
			helper.assertTrue(xp >= 1, "breeding XP not stored: " + xp);
		});
	}

	/** Butcher with a full output chest and 20 stored XP: nothing is delivered, the XP stays. */
	@GameTest(maxTicks = 600)
	public void butcherFullOutputKeepsTheXp(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		for (int i = 0; i < p.out.getContainerSize(); i++) p.out.setItem(i, new ItemStack(Items.DIRT, 64));
		p.data.xp = 20;
		GolemData.save(p.golem, p.data);
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(GolemData.get(p.golem).xp == 20, "stored XP: " + GolemData.get(p.golem).xp);
			helper.assertTrue(bottles(p.out) == 0 && ButcherTest.total(p.out) == 27 * 64, "the full chest changed");
			helper.succeed();
		});
	}

	/** Butcher with room: the whole bottles arrive in the output chest, the remainder stays stored. */
	@GameTest(maxTicks = 900)
	public void butcherDeliversBottles(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		p.data.xp = 20;
		GolemData.save(p.golem, p.data);
		helper.succeedWhen(() -> {
			helper.assertTrue(bottles(p.out) == 2, "bottles: " + bottles(p.out));
			helper.assertTrue(GolemData.get(p.golem).xp == 6, "remainder: " + GolemData.get(p.golem).xp);
		});
	}

	/** A profession change gives the whole bottles to the player and the remainder as orbs at the player. */
	@GameTest(maxTicks = 100)
	public void professionChangeGivesTheBottles(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setPos(helper.absoluteVec(new Vec3(3.5, 1, 3.5)));
		p.data.xp = 20;
		GolemData.save(p.golem, p.data);
		GolemService.changeProfession(helper.getLevel(), p.golem, SmelterProfession.ID, player);
		int n = 0;
		for (ItemStack s : player.getInventory().getNonEquipmentItems()) if (s.is(Items.EXPERIENCE_BOTTLE)) n += s.getCount();
		helper.assertTrue(n == 2, "bottles given: " + n);
		helper.assertTrue(GolemData.get(p.golem).xp == 0, "store not emptied: " + GolemData.get(p.golem).xp);
		helper.assertTrue(orbs(helper, around(helper)) == 6, "remainder as orbs: " + orbs(helper, around(helper)));
		helper.succeed();
	}

	/** The stored XP survives a full entity save and load. */
	@GameTest
	public void xpSurvivesSaveLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemData data = GolemData.get(golem);
		data.xp = 13;
		GolemData.save(golem, data);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		golem.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		helper.assertTrue(copy != null, "could not create a golem to load into");
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		helper.assertTrue(GolemData.get(copy).xp == 13, "xp after load: " + GolemData.get(copy).xp);
		helper.succeed();
	}

	/** Keep layout: bottles merge into the output chest's 1-stack of bottles, empty slots stay empty. */
	@GameTest(maxTicks = 900)
	public void keepLayoutBottlesMergeOnly(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		p.data.keepLayout = true;
		p.data.xp = 20;
		GolemData.save(p.golem, p.data);
		p.out.setItem(0, new ItemStack(Items.EXPERIENCE_BOTTLE, 1));
		helper.succeedWhen(() -> {
			helper.assertTrue(p.out.getItem(0).getCount() == 3, "merged bottles: " + p.out.getItem(0).getCount());
			helper.assertTrue(ButcherTest.total(p.out) == 3, "an empty slot was used: " + ButcherTest.total(p.out));
			helper.assertTrue(GolemData.get(p.golem).xp == 6, "remainder: " + GolemData.get(p.golem).xp);
		});
	}

	/** Keep layout, no bottle stack in the output chest: nothing is delivered, the XP stays stored. */
	@GameTest(maxTicks = 600)
	public void keepLayoutNoMatchingStackKeepsTheXp(GameTestHelper helper) {
		Pen p = ButcherTest.pen(helper, false);
		p.data.keepLayout = true;
		p.data.xp = 20;
		GolemData.save(p.golem, p.data);
		p.out.setItem(0, new ItemStack(Items.DIRT, 1));
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(GolemData.get(p.golem).xp == 20, "stored XP: " + GolemData.get(p.golem).xp);
			helper.assertTrue(ButcherTest.total(p.out) == 1, "the chest changed");
			helper.succeed();
		});
	}
}
