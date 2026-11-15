package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.cleaner.CleanerLogic;
import com.coppergolemsrevamped.profession.cleaner.CleanerProfession;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CleanerTest {
	private static ItemEntity drop(GameTestHelper helper, Item item, int count, double x, double z) {
		Vec3 p = helper.absoluteVec(new Vec3(x, 1.2, z));
		ItemEntity e = new ItemEntity(helper.getLevel(), p.x, p.y, p.z, new ItemStack(item, count));
		e.setNoPickUpDelay();
		e.setDeltaMovement(Vec3.ZERO);
		helper.getLevel().addFreshEntity(e);
		return e;
	}

	/** Area x 0..3, z 0..3 (floor block clicks at y=0); chest and outside items in x 4..7. */
	@GameTest(maxTicks = 1200)
	public void cleansItemsInsideArea(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		BlockPos chestPos = new BlockPos(5, 1, 1);
		helper.setBlock(chestPos, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);

		drop(helper, Items.COBBLESTONE, 64, 0.5, 0.5);
		drop(helper, Items.COBBLESTONE, 20, 1.5, 2.5);
		drop(helper, Items.OAK_LOG, 10, 2.5, 3.5);
		drop(helper, Items.DIAMOND, 3, 3.5, 1.5);
		ItemEntity outA = drop(helper, Items.GOLD_INGOT, 7, 5.5, 5.5);
		ItemEntity outB = drop(helper, Items.COBBLESTONE, 9, 6.5, 3.5);

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, CleanerProfession.ID);
		GolemData data = GolemData.get(golem);
		data.assignments.put("area_a", helper.absolutePos(new BlockPos(0, 0, 0)));
		data.assignments.put("area_b", helper.absolutePos(new BlockPos(3, 0, 3)));
		data.assignments.put("output", helper.absolutePos(chestPos));
		GolemData.save(golem, data);
		AABB box = CleanerLogic.box(data);
		helper.assertTrue(box != null, "box unset");

		helper.succeedWhen(() -> {
			int stone = 0, logs = 0, diamonds = 0;
			for (int i = 0; i < chest.getContainerSize(); i++) {
				ItemStack s = chest.getItem(i);
				if (s.is(Items.COBBLESTONE)) stone += s.getCount();
				else if (s.is(Items.OAK_LOG)) logs += s.getCount();
				else if (s.is(Items.DIAMOND)) diamonds += s.getCount();
				else helper.assertTrue(s.isEmpty(), "unexpected item in chest: " + s);
			}
			helper.assertTrue(stone == 84, "cobblestone in chest: " + stone);
			helper.assertTrue(logs == 10, "oak logs in chest: " + logs);
			helper.assertTrue(diamonds == 3, "diamonds in chest: " + diamonds);
			List<ItemEntity> inside = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
			helper.assertTrue(inside.isEmpty(), "items still inside the box: " + inside.size());
			helper.assertTrue(outA.isAlive() && outA.getItem().getCount() == 7, "outside gold touched");
			helper.assertTrue(outB.isAlive() && outB.getItem().getCount() == 9, "outside cobblestone touched");
		});
	}

	@GameTest
	public void areaLimit16(GameTestHelper helper) {
		Profession p = Professions.get(CleanerProfession.ID);
		Profession.AssignSpec area = p.assigns().stream().filter(a -> a.key().equals("area")).findFirst().orElseThrow();
		helper.assertTrue(area.kind() == Profession.Kind.AREA, "area kind " + area.kind());
		helper.assertTrue(area.maxSize() == 16, "maxSize " + area.maxSize());
		BlockPos o = new BlockPos(0, 5, 0);
		helper.assertTrue(AssignMode.areaFits(o, new BlockPos(15, 5, 15), 16), "16x16 must fit");
		helper.assertTrue(!AssignMode.areaFits(o, new BlockPos(16, 5, 15), 16), "17x16 must not fit");
		helper.assertTrue(!AssignMode.areaFits(o, new BlockPos(15, 5, 16), 16), "16x17 must not fit");
		GolemData d = new GolemData();
		d.assignments.put("area_a", new BlockPos(2, 10, 2));
		d.assignments.put("area_b", new BlockPos(5, 13, 4));
		AABB b = CleanerLogic.box(d);
		helper.assertTrue(b != null && b.minY == 10 && b.maxY == 16 && b.minX == 2 && b.maxX == 6 && b.maxZ == 5,
			"box " + b);
		helper.assertTrue(CleanerLogic.box(new GolemData()) == null, "unset box must be null");
		helper.succeed();
	}
}
