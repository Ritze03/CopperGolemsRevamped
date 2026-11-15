package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.FilterMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.menu.Tab;
import com.coppergolemsrevamped.profession.transporter.TransporterProfession;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** The Transporter Filter tab: tab and layout, persistence, mode cycling and what each mode moves. */
public class TransporterFilterTest {
	private static final Identifier TRANSPORTER = CopperGolemsRevamped.id("transporter");
	private static final int FILTER = TransporterProfession.FILTER_FROM;

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	@SuppressWarnings("removal")
	private static ServerPlayer player(GameTestHelper helper, CopperGolem golem) {
		ServerPlayer p = helper.makeMockServerPlayerInLevel();
		p.setPos(golem.position());
		return p;
	}

	private static ItemStack at(GolemMenu menu, int row, int col) {
		return menu.slots.get(row * 9 + col).getItem();
	}

	@GameTest
	public void filterTabOnlyForTransporter(GameTestHelper helper) {
		floor(helper);
		for (String id : new String[] {"transporter", "chest_sorter", "cleaner", "farmer", "lumber", "crafter", "smelter", "butcher"}) {
			CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
			GolemService.changeProfession(helper.getLevel(), g, CopperGolemsRevamped.id(id));
			ServerPlayer p = player(helper, g);
			GolemMenu menu = new GolemMenu(1, p.getInventory(), g);
			boolean has = false;
			for (int c = 0; c < 8; c++) if (at(menu, 0, c).getHoverName().getString().equals("Filter")) has = true;
			helper.assertTrue(has == id.equals("transporter"), id + ": filter tab present = " + has);
			helper.assertTrue(Tab.forProfession(com.coppergolemsrevamped.profession.Professions.get(CopperGolemsRevamped.id(id)))
				.contains(Tab.FILTER) == id.equals("transporter"), id + " tab list");
			g.discard();
		}
		helper.assertTrue(!Tab.forProfession(null).contains(Tab.FILTER), "None has no filter tab");
		helper.succeed();
	}

	@GameTest
	public void filterLayoutMatchesInventory(GameTestHelper helper) {
		floor(helper);
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), g, TRANSPORTER);
		ServerPlayer p = player(helper, g);
		GolemMenu menu = new GolemMenu(1, p.getInventory(), g);
		GolemData d = GolemData.get(g);
		d.items.set(0, new ItemStack(Items.COBBLESTONE, 5)); // carry
		d.items.set(FILTER + 2, new ItemStack(Items.DIAMOND)); // filter
		GolemData.save(g, d);
		menu.showTab(Tab.INVENTORY);
		boolean[] inv = new boolean[54];
		for (int i = 0; i < 54; i++) inv[i] = menu.slots.get(i).mayPlace(new ItemStack(Items.DIRT));
		ItemStack invLabel = at(menu, 2, 4);
		helper.assertTrue(at(menu, 3, 0).is(Items.COBBLESTONE), "carry cell shows carry: " + at(menu, 3, 0));
		menu.showTab(Tab.FILTER);
		for (int i = 0; i < 54; i++) {
			helper.assertTrue(menu.slots.get(i).mayPlace(new ItemStack(Items.DIRT)) == inv[i], "same placeable cells, slot " + i);
		}
		for (int c = 0; c < 9; c++) helper.assertTrue(menu.slots.get(27 + c).mayPlace(new ItemStack(Items.DIRT)), "row 3 col " + c + " is a slot");
		helper.assertTrue(at(menu, 3, 2).is(Items.DIAMOND), "filter item shown: " + at(menu, 3, 2));
		helper.assertTrue(at(menu, 3, 0).isEmpty(), "carry item is not shown on the filter tab: " + at(menu, 3, 0));
		helper.assertTrue(!menu.slots.get(2 * 9 + 4).mayPlace(new ItemStack(Items.DIRT)) && !at(menu, 2, 4).isEmpty() && invLabel != null,
			"(2,4) is an icon cell (the mode button)");
		helper.succeed();
	}

	@GameTest
	public void placingIntoFilterStoresOneAndKeepsIt(GameTestHelper helper) {
		floor(helper);
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), g, TRANSPORTER);
		ServerPlayer p = player(helper, g);
		GolemMenu menu = new GolemMenu(1, p.getInventory(), g);
		menu.showTab(Tab.FILTER);
		menu.setCarried(new ItemStack(Items.DIAMOND, 10));
		menu.clicked(3 * 9 + 1, 0, ContainerInput.PICKUP, p);
		GolemData d = GolemData.get(g);
		helper.assertTrue(d.items.get(FILTER + 1).is(Items.DIAMOND) && d.items.get(FILTER + 1).getCount() == 1, "stored " + d.items.get(FILTER + 1));
		helper.assertTrue(menu.getCarried().getCount() == 9, "cursor keeps the rest: " + menu.getCarried());
		helper.succeed();
	}

	@GameTest
	public void filterAndModePersist(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, g, TRANSPORTER);
		GolemData d = GolemData.get(g);
		d.items.set(FILTER, new ItemStack(Items.DIAMOND));
		d.items.set(FILTER + 8, new ItemStack(Items.GOLD_INGOT));
		d.filterMode = FilterMode.BLACKLIST;
		GolemData.save(g, d);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		g.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		GolemData c = GolemData.get(copy);
		helper.assertTrue(c.items.size() == 18, "size " + c.items.size());
		helper.assertTrue(c.items.get(FILTER).is(Items.DIAMOND) && c.items.get(FILTER + 8).is(Items.GOLD_INGOT), "filter items " + c.items);
		helper.assertTrue(c.filterMode == FilterMode.BLACKLIST, "mode " + c.filterMode);
		helper.succeed();
	}

	/** An old save has no filter_mode and only 9 items: it loads as Off, padded, and the filter slots are usable. */
	@GameTest
	public void oldSaveLoadsAsOffAndIsPadded(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, g, TRANSPORTER);
		GolemData d = GolemData.get(g);
		d.items = NonNullList.withSize(9, ItemStack.EMPTY);
		d.items.set(3, new ItemStack(Items.DIRT, 7));
		GolemData.save(g, d);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		g.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		GolemData c = GolemData.get(copy);
		TransporterProfession.migrate(c);
		helper.assertTrue(c.filterMode == FilterMode.OFF, "mode " + c.filterMode);
		helper.assertTrue(c.items.size() == 18 && c.items.get(3).is(Items.DIRT) && c.items.get(3).getCount() == 7, "padded: " + c.items);
		helper.assertTrue(FilterMode.parse("nonsense") == FilterMode.OFF, "unknown id reads as Off");
		helper.succeed();
	}

	@GameTest
	public void modeButtonCycles(GameTestHelper helper) {
		floor(helper);
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), g, TRANSPORTER);
		ServerPlayer p = player(helper, g);
		GolemMenu menu = new GolemMenu(1, p.getInventory(), g);
		menu.showTab(Tab.FILTER);
		Item[] icons = {Items.GLASS_PANE, Items.WOOL.white(), Items.WOOL.black(), Items.GLASS_PANE};
		FilterMode[] modes = {FilterMode.OFF, FilterMode.WHITELIST, FilterMode.BLACKLIST, FilterMode.OFF};
		for (int k = 0; k < 4; k++) {
			helper.assertTrue(GolemData.get(g).filterMode == modes[k], "step " + k + " mode " + GolemData.get(g).filterMode);
			helper.assertTrue(at(menu, 2, 4).is(icons[k]), "step " + k + " icon " + at(menu, 2, 4));
			helper.assertTrue(at(menu, 2, 4).getHoverName().getString().contains(modes[k].label), "step " + k + " name");
			if (k < 3) menu.clicked(2 * 9 + 4, 0, ContainerInput.PICKUP, p);
		}
		helper.succeed();
	}

	// ---- logic ----

	private record Setup(CopperGolem golem, ChestBlockEntity a, ChestBlockEntity b) {}

	/** Chest A: 64 cobblestone, 64 dirt, 64 oak logs (slots 0..2); the golem filters {cobblestone, dirt-with-name-ignored}. */
	private static Setup setup(GameTestHelper helper, FilterMode mode, Item... filter) {
		floor(helper);
		BlockPos a = new BlockPos(1, 1, 3);
		BlockPos b = new BlockPos(6, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.setBlock(b, Blocks.CHEST);
		ChestBlockEntity ca = helper.getBlockEntity(a, ChestBlockEntity.class);
		ChestBlockEntity cb = helper.getBlockEntity(b, ChestBlockEntity.class);
		ca.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		ca.setItem(1, new ItemStack(Items.DIRT, 64));
		ItemStack named = new ItemStack(Items.OAK_LOG, 64);
		named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Special"));
		ca.setItem(2, named);
		CopperGolem g = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 4));
		GolemService.changeProfession(helper.getLevel(), g, TRANSPORTER);
		GolemData d = GolemData.get(g);
		d.assignments.put("input", helper.absolutePos(a));
		d.assignments.put("output", helper.absolutePos(b));
		d.filterMode = mode;
		for (int i = 0; i < filter.length; i++) d.items.set(FILTER + i, new ItemStack(filter[i]));
		GolemData.save(g, d);
		return new Setup(g, ca, cb);
	}

	private static int count(ChestBlockEntity c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	private static int total(ChestBlockEntity c) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
		return n;
	}

	private static int carried(CopperGolem g) {
		int n = 0;
		for (int i = 0; i < TransporterProfession.CARRY_SLOTS; i++) n += GolemData.get(g).items.get(i).getCount();
		return n;
	}

	@GameTest(maxTicks = 600)
	public void whitelistMovesOnlyMatching(GameTestHelper helper) {
		Setup s = setup(helper, FilterMode.WHITELIST, Items.COBBLESTONE, Items.OAK_LOG);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(s.b, Items.COBBLESTONE) == 64, "cobblestone in B: " + count(s.b, Items.COBBLESTONE));
			helper.assertTrue(count(s.b, Items.OAK_LOG) == 64, "named log matches by item type only: " + count(s.b, Items.OAK_LOG));
			helper.assertTrue(carried(s.golem) == 0, "carry empty");
			helper.assertTrue(count(s.b, Items.DIRT) == 0 && count(s.a, Items.DIRT) == 64, "dirt stays in A");
			helper.assertTrue(total(s.a) + total(s.b) == 192, "items conserved");
		});
	}

	@GameTest(maxTicks = 600)
	public void blacklistMovesOnlyNonMatching(GameTestHelper helper) {
		Setup s = setup(helper, FilterMode.BLACKLIST, Items.COBBLESTONE);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(s.b, Items.DIRT) == 64 && count(s.b, Items.OAK_LOG) == 64, "non-matching moved");
			helper.assertTrue(count(s.b, Items.COBBLESTONE) == 0 && count(s.a, Items.COBBLESTONE) == 64, "cobblestone stays in A");
			helper.assertTrue(carried(s.golem) == 0, "carry empty");
			helper.assertTrue(total(s.a) + total(s.b) == 192, "items conserved");
		});
	}

	@GameTest(maxTicks = 600)
	public void offMovesEverythingAndIgnoresFilter(GameTestHelper helper) {
		Setup s = setup(helper, FilterMode.OFF, Items.COBBLESTONE);
		helper.succeedWhen(() -> {
			helper.assertTrue(total(s.a) == 0 && total(s.b) == 192, "all moved: A " + total(s.a) + " B " + total(s.b));
			// the filter items were never carried off
			helper.assertTrue(GolemData.get(s.golem).items.get(FILTER).is(Items.COBBLESTONE), "filter item kept");
		});
	}

	@GameTest(maxTicks = 300)
	public void emptyWhitelistMovesNothing(GameTestHelper helper) {
		Setup s = setup(helper, FilterMode.WHITELIST);
		helper.runAfterDelay(250, () -> {
			helper.assertTrue(total(s.b) == 0 && total(s.a) == 192 && carried(s.golem) == 0,
				"nothing moved: A " + total(s.a) + " B " + total(s.b) + " carry " + carried(s.golem));
			helper.assertTrue(com.coppergolemsrevamped.profession.ProfessionStatus.doing(helper.getLevel(), s.golem, GolemData.get(s.golem),
				com.coppergolemsrevamped.profession.Professions.get(TRANSPORTER)).contains("empty"), "status names the empty whitelist");
			helper.succeed();
		});
	}

	/** The filter changed mid-trip: carried items that no longer pass are still delivered, never stranded. */
	@GameTest(maxTicks = 400)
	public void carryNotPassingIsStillDelivered(GameTestHelper helper) {
		Setup s = setup(helper, FilterMode.WHITELIST, Items.COBBLESTONE);
		GolemData d = GolemData.get(s.golem);
		d.items.set(0, new ItemStack(Items.GRAVEL, 10)); // does not pass
		GolemData.save(s.golem, d);
		helper.succeedWhen(() -> {
			helper.assertTrue(count(s.b, Items.GRAVEL) == 10 && carried(s.golem) == 0, "gravel delivered: " + count(s.b, Items.GRAVEL));
			helper.assertTrue(count(s.b, Items.COBBLESTONE) == 64, "cobblestone moved too");
		});
	}

	@GameTest
	public void filterItemsDropOnProfessionChange(GameTestHelper helper) {
		floor(helper);
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), g, TRANSPORTER);
		GolemData d = GolemData.get(g);
		d.items.set(FILTER, new ItemStack(Items.DIAMOND));
		d.filterMode = FilterMode.WHITELIST;
		GolemData.save(g, d);
		ServerPlayer p = player(helper, g);
		GolemService.changeProfession(helper.getLevel(), g, CopperGolemsRevamped.id("cleaner"), p);
		boolean got = false;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.DIAMOND)) got = true;
		helper.assertTrue(got, "the filter item went to the player");
		helper.succeed();
	}
}
