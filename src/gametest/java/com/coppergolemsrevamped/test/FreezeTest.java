package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Freeze (Settings tab): a frozen golem does nothing and stands still, keeps its state and resumes on unfreeze. */
public class FreezeTest {
	private static final BlockPos A = new BlockPos(1, 1, 3);
	private static final BlockPos B = new BlockPos(6, 1, 3);

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static int total(ChestBlockEntity chest) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
		return n;
	}

	private static void freeze(CopperGolem golem, boolean on) {
		GolemData d = GolemData.get(golem);
		d.frozen = on;
		GolemData.save(golem, d);
	}

	/** A Transporter with a full setup (two chests, 128 items in A), frozen from the start. */
	private static CopperGolem frozenTransporter(GameTestHelper helper, ChestBlockEntity[] chests) {
		floor(helper);
		helper.setBlock(A, Blocks.CHEST);
		helper.setBlock(B, Blocks.CHEST);
		chests[0] = helper.getBlockEntity(A, ChestBlockEntity.class);
		chests[1] = helper.getBlockEntity(B, ChestBlockEntity.class);
		chests[0].setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		chests[0].setItem(1, new ItemStack(Items.OAK_LOG, 64));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("transporter"));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(A));
		data.assignments.put("output", helper.absolutePos(B));
		data.frozen = true;
		GolemData.save(golem, data);
		return golem;
	}

	@GameTest(maxTicks = 400)
	public void frozenTransporterMovesNothing(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = frozenTransporter(helper, c);
		Vec3 start = golem.position();
		helper.runAfterDelay(200, () -> {
			helper.assertTrue(total(c[0]) == 128 && total(c[1]) == 0, "chests changed: A " + total(c[0]) + ", B " + total(c[1]));
			helper.assertTrue(GolemData.get(golem).items.stream().allMatch(ItemStack::isEmpty), "the golem picked something up");
			helper.assertTrue(golem.position().distanceTo(start) <= 0.5, "moved " + golem.position().distanceTo(start) + " blocks");
			helper.assertTrue(!Interact.isOpen(golem), "no lid may be held open");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 2400)
	public void unfreezingResumesTheWork(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = frozenTransporter(helper, c);
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(total(c[1]) == 0, "frozen: B holds " + total(c[1]));
			freeze(golem, false);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(total(c[0]) == 0 && total(c[1]) == 128, "after unfreeze: A " + total(c[0]) + ", B " + total(c[1]));
		});
	}

	/** Freezing mid-work stops the golem within a tick or two and releases the lid it holds. */
	@GameTest(maxTicks = 400)
	public void freezingClosesAnOpenLidAndStops(GameTestHelper helper) {
		ChestBlockEntity[] c = new ChestBlockEntity[2];
		CopperGolem golem = frozenTransporter(helper, c);
		freeze(golem, false);
		BlockPos abs = helper.absolutePos(A);
		Interact.openChest(helper.getLevel(), golem, abs, Interact.Action.TAKE);
		helper.assertTrue(Interact.isOpen(golem), "setup: the lid is open");
		freeze(golem, true);
		helper.runAfterDelay(3, () -> {
			helper.assertTrue(!Interact.isOpen(golem) && ChestBlockEntity.getOpenCount(helper.getLevel(), abs) == 0, "the lid must be closed");
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE, "state " + golem.getState());
			helper.succeed();
		});
	}

	/** Full entity save, then load into a fresh golem: `frozen` survives; old data without the field loads as not frozen. */
	@GameTest
	public void frozenSurvivesSaveLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, golem, CopperGolemsRevamped.id("transporter"));
		freeze(golem, true);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		golem.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		helper.assertTrue(copy != null, "could not create a golem to load into");
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		helper.assertTrue(GolemData.get(copy).frozen, "frozen lost");

		freeze(golem, false);
		TagValueOutput out2 = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		golem.saveWithoutId(out2);
		CopperGolem copy2 = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		copy2.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out2.buildResult()));
		helper.assertTrue(!GolemData.get(copy2).frozen, "unfrozen must stay unfrozen");
		helper.assertTrue(CopperGolemsRevamped.id("transporter").equals(GolemData.get(copy2).profession), "other data intact");
		helper.succeed();
	}

	/** The vanilla-brain Chest Sorter is frozen too: nothing is sorted while frozen, and it sorts after the unfreeze. */
	@GameTest(maxTicks = 1500)
	public void frozenChestSorterDoesNotSort(GameTestHelper helper) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos dst = new BlockPos(6, 1, 3);
		helper.setBlock(src, Blocks.COPPER_CHEST.weathering().unaffected());
		helper.setBlock(dst, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity to = helper.getBlockEntity(dst, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, ChestSorterProfession.ID);
		freeze(golem, true);
		Vec3 start = golem.position();
		helper.runAfterDelay(200, () -> {
			helper.assertTrue(total(from) == 10 && total(to) == 0, "sorted while frozen: source " + total(from) + ", destination " + total(to));
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "carries " + golem.getMainHandItem());
			helper.assertTrue(golem.position().distanceTo(start) <= 0.5, "moved " + golem.position().distanceTo(start));
			freeze(golem, false);
		});
		helper.succeedWhen(() -> helper.assertTrue(total(from) == 0 && total(to) == 10, "after unfreeze: source " + total(from) + ", destination " + total(to)));
	}
}
