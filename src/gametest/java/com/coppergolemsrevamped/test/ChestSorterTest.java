package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import com.coppergolemsrevamped.CopperGolemsRevamped;

public class ChestSorterTest {
	/** Exposes the protected method the mixin targets. */
	private static final class Probe extends TransportItemsBetweenContainers {
		Probe() {
			super(1.0F, s -> false, s -> false, 32, 8, Map.of(), m -> {}, t -> false);
		}

		void visit(PathfinderMob body, Level level, BlockPos pos) {
			setVisitedBlockPos(body, level, pos);
		}
	}

	private static CopperGolem sorter(GameTestHelper helper, int waxed) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, ChestSorterProfession.ID);
		GolemData data = GolemData.get(golem);
		if (waxed > 0) data.items.set(0, new ItemStack(Items.COPPER_BLOCK.waxed().unaffected(), waxed));
		GolemData.save(golem, data);
		return golem;
	}

	@GameTest
	public void visitedLimitScalesWithWaxedBlocks(GameTestHelper helper) {
		CopperGolem plain = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		int l = ChestSorterProfession.visitedLimit(plain);
		helper.assertTrue(l == 10, "no profession: " + l);
		l = ChestSorterProfession.visitedLimit(sorter(helper, 0));
		helper.assertTrue(l == 10, "sorter 0 blocks: " + l);
		l = ChestSorterProfession.visitedLimit(sorter(helper, 5));
		helper.assertTrue(l == 15, "sorter 5 blocks: " + l);
		l = ChestSorterProfession.visitedLimit(sorter(helper, 64));
		helper.assertTrue(l == 74, "sorter 64 blocks: " + l);
		helper.succeed();
	}

	/** Fails without the mixin: vanilla would enter cooldown on the 11th chest. */
	@GameTest
	public void mixinAppliesLimit(GameTestHelper helper) {
		Level level = helper.getLevel();
		Probe probe = new Probe();
		CopperGolem golem = sorter(helper, 3);
		seed(golem, level, 12);
		probe.visit(golem, level, helper.absolutePos(new BlockPos(0, 1, 12)));
		helper.assertTrue(!golem.getBrain().hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS),
			"sorter(3) entered cooldown on the 13th chest, limit " + ChestSorterProfession.visitedLimit(golem));
		int size = golem.getBrain().getMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS).orElseThrow().size();
		helper.assertTrue(size == 13, "visited size: " + size);
		probe.visit(golem, level, helper.absolutePos(new BlockPos(0, 1, 13)));
		helper.assertTrue(golem.getBrain().hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS),
			"sorter(3) did not enter cooldown on the 14th chest");

		CopperGolem plain = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		seed(plain, level, 10);
		probe.visit(plain, level, helper.absolutePos(new BlockPos(0, 1, 10)));
		helper.assertTrue(plain.getBrain().hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS),
			"control golem did not enter cooldown on the 11th chest");
		helper.succeed();
	}

	private static void seed(CopperGolem golem, Level level, int n) {
		golem.getBrain().eraseMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
		Set<GlobalPos> set = new HashSet<>();
		for (int i = 0; i < n; i++) set.add(GlobalPos.of(level.dimension(), new BlockPos(1000, 1, i)));
		golem.getBrain().setMemory(MemoryModuleType.VISITED_BLOCK_POSITIONS, set);
	}

	@GameTest
	public void waxedSlotFilter(GameTestHelper helper) {
		Profession p = Professions.get(ChestSorterProfession.ID);
		helper.assertTrue(p != null, "chest_sorter not registered");
		helper.assertTrue(p.slots().size() == 1, "slot groups: " + p.slots().size());
		SlotGroup g = p.slots().get(0);
		helper.assertTrue(g.key().equals("waxed"), "key: " + g.key());
		helper.assertTrue(g.count() == 1, "count: " + g.count());
		helper.assertTrue(g.maxStack() == 64, "maxStack: " + g.maxStack());
		helper.assertTrue(p.runsVanillaBrain(), "must run the vanilla brain");
		Item[] yes = {
			Items.COPPER_BLOCK.waxed().unaffected(), Items.COPPER_BLOCK.waxed().exposed(),
			Items.COPPER_BLOCK.waxed().weathered(), Items.COPPER_BLOCK.waxed().oxidized()};
		for (Item i : yes) helper.assertTrue(g.filter().test(new ItemStack(i)), "must accept " + i);
		Item[] no = {
			Items.COPPER_BLOCK.weathering().unaffected(), Items.COPPER_BLOCK.weathering().oxidized(),
			Items.RAW_COPPER_BLOCK, Items.COPPER_INGOT, Items.COBBLESTONE};
		for (Item i : no) helper.assertTrue(!g.filter().test(new ItemStack(i)), "must reject " + i);
		helper.assertTrue(!g.filter().test(ItemStack.EMPTY), "must reject empty");
		helper.succeed();
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** Items from a copper chest end up in the wooden chest; {@code first} is a profession to hold beforehand. */
	private static void sortScenario(GameTestHelper helper, Block copper, String first, boolean destHasItem) {
		floor(helper);
		BlockPos src = new BlockPos(1, 1, 3);
		BlockPos dst = new BlockPos(6, 1, 3);
		helper.setBlock(src, copper);
		helper.setBlock(dst, Blocks.CHEST);
		ChestBlockEntity from = helper.getBlockEntity(src, ChestBlockEntity.class);
		ChestBlockEntity to = helper.getBlockEntity(dst, ChestBlockEntity.class);
		from.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		if (destHasItem) to.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		if (first != null) {
			GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id(first));
			golem.getNavigation().moveTo(golem.getX(), golem.getY(), golem.getZ() + 1, 1.0);
		}
		GolemService.changeProfession(helper.getLevel(), golem, ChestSorterProfession.ID);
		helper.succeedWhen(() -> {
			int inFrom = 0;
			for (int i = 0; i < from.getContainerSize(); i++) inFrom += from.getItem(i).getCount();
			int inTo = 0;
			for (int i = 0; i < to.getContainerSize(); i++) inTo += to.getItem(i).getCount();
			int expected = 10 + (destHasItem ? 1 : 0);
			helper.assertTrue(inFrom == 0 && inTo == expected, "source " + inFrom + ", destination " + inTo
				+ ", golem hand " + golem.getMainHandItem() + ", cooldown "
				+ golem.getBrain().getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS));
		});
	}

	@GameTest(maxTicks = 1200)
	public void sortsFreshGolem(GameTestHelper helper) {
		sortScenario(helper, Blocks.COPPER_CHEST.weathering().unaffected(), null, false);
	}

	@GameTest(maxTicks = 1200)
	public void sortsWaxedOxidizedSourceIntoMatchingChest(GameTestHelper helper) {
		sortScenario(helper, Blocks.COPPER_CHEST.waxed().oxidized(), null, true);
	}

	@GameTest(maxTicks = 1200)
	public void sortsAfterBeingTransporter(GameTestHelper helper) {
		sortScenario(helper, Blocks.COPPER_CHEST.weathering().unaffected(), "transporter", false);
	}
}
