package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.core.DisplayItems;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemHandDisplay;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.menu.Tab;
import com.coppergolemsrevamped.profession.crafter.CrafterProfession;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;

/** The tabbed golem menu: layout per tab, in-place tab switching, confirm flow, targets, remembering grid, display copies. */
public class MenuTest {
	private static final Identifier TRANSPORTER = CopperGolemsRevamped.id("transporter");
	private static final Identifier SORTER = CopperGolemsRevamped.id("chest_sorter");
	private static final Identifier CLEANER = CopperGolemsRevamped.id("cleaner");
	private static final Identifier FARMER = CopperGolemsRevamped.id("farmer");
	private static final Identifier LUMBER = CopperGolemsRevamped.id("lumber");
	private static final Identifier CRAFTER = CrafterProfession.ID;
	private static final int IN = CrafterProfession.FIRST_INPUT;
	private static final int OUT = CrafterProfession.OUTPUT_SLOT;

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static CopperGolem golem(GameTestHelper helper, Identifier profession) {
		floor(helper);
		CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		if (profession != null) GolemService.changeProfession(helper.getLevel(), g, profession);
		return g;
	}

	/** A mock player standing on the golem, so every range check passes. */
	@SuppressWarnings("removal")
	private static ServerPlayer player(GameTestHelper helper, CopperGolem golem) {
		ServerPlayer p = helper.makeMockServerPlayerInLevel();
		p.setPos(golem.position());
		return p;
	}

	private static Slot slot(GolemMenu menu, int row, int col) {
		return menu.slots.get(row * 9 + col);
	}

	private static ItemStack at(GolemMenu menu, int row, int col) {
		return slot(menu, row, col).getItem();
	}

	private static String name(ItemStack s) {
		return s.getHoverName().getString();
	}

	private static String lore(ItemStack s) {
		StringBuilder b = new StringBuilder();
		for (var line : s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) b.append(line.getString()).append('|');
		return b.toString();
	}

	private static boolean glint(ItemStack s) {
		return Boolean.TRUE.equals(s.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
	}

	private static boolean orange(ItemStack s) {
		return s.is(Items.STAINED_GLASS_PANE.orange());
	}

	private static void click(GolemMenu menu, ServerPlayer player, int row, int col) {
		menu.clicked(row * 9 + col, 0, ContainerInput.PICKUP, player);
	}

	private static int count(ServerPlayer player, Item item) {
		int n = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		return n;
	}

	/** Display copies must never sit in the player's inventory or cursor. */
	private static boolean hasDisplay(ServerPlayer player, GolemMenu menu) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (DisplayItems.is(inv.getItem(i))) return true;
		return DisplayItems.is(menu.getCarried());
	}

	/** Real items the click may move: the player's inventory, the cursor and the golem's input and output slots. */
	private static int realTotal(ServerPlayer player, GolemMenu menu, CopperGolem golem) {
		int n = menu.getCarried().getCount();
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) n += inv.getItem(i).getCount();
		GolemData d = GolemData.get(golem);
		for (int i = IN; i <= OUT && i < d.items.size(); i++) n += d.items.get(i).getCount();
		return n;
	}

	// ---- opening, bar, tabs ----

	@GameTest
	public void opensOnStatusWithTabBar(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g);
		helper.assertTrue(menu.tab() == Tab.STATUS && !menu.confirming(), "tab " + menu.tab());
		helper.assertTrue(menu.slots.size() == 54 + 36, "slots " + menu.slots.size());
		helper.assertTrue(at(menu, 0, 0).is(Items.ARROW) && name(at(menu, 0, 0)).equals("Back"), "back " + at(menu, 0, 0));
		helper.assertTrue(at(menu, 0, 1).is(Items.STAINED_GLASS_PANE.black()) && at(menu, 0, 7).is(Items.STAINED_GLASS_PANE.black()),
			"black panes beside the tab bar");
		Item[] bases = {Items.WRITABLE_BOOK, Items.TARGET, Items.CHEST, Items.COMPARATOR, Items.IRON_AXE};
		for (int k = 0; k < 5; k++) {
			ItemStack t = at(menu, 0, 2 + k);
			helper.assertTrue(t.is(bases[k]), "tab " + k + " base " + t);
			helper.assertTrue(glint(t) == (k == 0), "tab " + k + " glint " + glint(t));
		}
		helper.assertTrue(at(menu, 0, 8).is(Items.BOOK) && name(at(menu, 0, 8)).equals("Help"), "help " + at(menu, 0, 8));
		helper.assertTrue(orange(at(menu, 1, 0)) && orange(at(menu, 5, 8)), "filler is orange");
		helper.assertTrue(name(at(menu, 2, 4)).startsWith("Lumber — "), "status title " + name(at(menu, 2, 4)));
		helper.succeed();
	}

	@GameTest
	public void tabSwitchKeepsTheMenuInstance(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g);
		player.containerMenu = menu;
		click(menu, player, 0, 4); // Inventory
		helper.assertTrue(player.containerMenu == menu && menu.tab() == Tab.INVENTORY, "inventory tab: " + menu.tab());
		helper.assertTrue(slot(menu, 2, 3).mayPlace(new ItemStack(Items.IRON_AXE)), "the axe slot is shown");
		helper.assertTrue(glint(at(menu, 0, 4)) && !glint(at(menu, 0, 2)), "glint follows the active tab");
		click(menu, player, 0, 3); // Targets
		helper.assertTrue(player.containerMenu == menu && menu.tab() == Tab.TARGETS && !slot(menu, 2, 3).mayPlace(new ItemStack(Items.IRON_AXE)),
			"targets tab: " + menu.tab());
		click(menu, player, 0, 6); // Profession
		helper.assertTrue(player.containerMenu == menu && menu.tab() == Tab.PROFESSION, "profession tab: " + menu.tab());
		helper.assertTrue(glint(at(menu, 3, 7)) && lore(at(menu, 3, 7)).contains("Current profession"), "current profession: " + at(menu, 3, 7));
		click(menu, player, 0, 0); // Back closes
		helper.assertTrue(player.containerMenu != menu, "back must close the menu");
		helper.succeed();
	}

	@GameTest
	public void controlCellsSwallowEveryOtherClick(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		ServerPlayer player = player(helper, g);
		player.getAbilities().instabuild = true;
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g);
		player.getInventory().setItem(0, new ItemStack(Items.STICK, 5));
		int[] cells = {0 * 9 + 2, 0 * 9 + 8, 1 * 9 + 1, 2 * 9 + 4};
		for (int cell : cells) {
			for (ContainerInput in : new ContainerInput[] {ContainerInput.CLONE, ContainerInput.SWAP, ContainerInput.THROW, ContainerInput.PICKUP_ALL}) {
				menu.clicked(cell, 0, in, player);
				helper.assertTrue(menu.getCarried().isEmpty() && menu.tab() == Tab.STATUS, in + " on cell " + cell + " must do nothing");
			}
			menu.clicked(cell, 0, ContainerInput.PICKUP, player);
			helper.assertTrue(!hasDisplay(player, menu), "no icon may reach the cursor");
			menu.showTab(Tab.STATUS);
		}
		helper.assertTrue(count(player, Items.STICK) == 5, "inventory untouched");
		helper.succeed();
	}

	// ---- profession tab and confirm ----

	@GameTest
	public void noneSwitchesWithoutConfirm(GameTestHelper helper) {
		CopperGolem g = golem(helper, null);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.PROFESSION);
		helper.assertTrue(glint(at(menu, 3, 1)) && lore(at(menu, 3, 1)).contains("Current profession"), "None is current");
		click(menu, player, 3, 2); // Transporter
		helper.assertTrue(!menu.confirming() && TRANSPORTER.equals(GolemData.get(g).profession), "profession " + GolemData.get(g).profession);
		helper.succeed();
	}

	@GameTest
	public void confirmFlowAndCancel(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		GolemData data = GolemData.get(g);
		data.items.set(0, new ItemStack(Items.IRON_AXE));
		data.items.set(3, new ItemStack(Items.OAK_LOG, 12));
		data.assignments.put("tree", helper.absolutePos(new BlockPos(5, 1, 5)));
		GolemData.save(g, data);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.PROFESSION);
		click(menu, player, 3, 2); // Transporter: asks first
		helper.assertTrue(menu.confirming() && LUMBER.equals(GolemData.get(g).profession), "must ask before switching");
		helper.assertTrue(!at(menu, 0, 0).is(Items.ARROW) && orange(at(menu, 0, 0)), "the confirm screen has no tab bar");
		helper.assertTrue(at(menu, 2, 2).is(Items.IRON_AXE) && name(at(menu, 2, 2)).equals("Lumber"), "current " + at(menu, 2, 2));
		helper.assertTrue(at(menu, 2, 4).is(Items.ARROW) && name(at(menu, 2, 4)).equals("Switch to Transporter"), "arrow " + at(menu, 2, 4));
		helper.assertTrue(at(menu, 2, 6).is(Items.CHEST_MINECART), "new " + at(menu, 2, 6));
		helper.assertTrue(at(menu, 4, 3).is(Items.APPLE) && name(at(menu, 4, 3)).equals("Cancel"), "cancel " + at(menu, 4, 3));
		helper.assertTrue(at(menu, 4, 5).is(Items.DYE.lime()) && name(at(menu, 4, 5)).equals("Confirm")
			&& lore(at(menu, 4, 5)).contains("Items go to your inventory") && lore(at(menu, 4, 5)).contains("Targets are cleared"), "confirm " + at(menu, 4, 5));
		click(menu, player, 4, 3); // Cancel
		helper.assertTrue(!menu.confirming() && menu.tab() == Tab.PROFESSION && LUMBER.equals(GolemData.get(g).profession)
			&& GolemData.get(g).items.get(0).is(Items.IRON_AXE) && GolemData.get(g).assignments.containsKey("tree"), "cancel changes nothing");
		click(menu, player, 3, 2);
		click(menu, player, 4, 5); // Confirm
		GolemData after = GolemData.get(g);
		helper.assertTrue(TRANSPORTER.equals(after.profession) && after.assignments.isEmpty(), "switched, targets cleared: " + after.profession);
		helper.assertTrue(count(player, Items.IRON_AXE) == 1 && count(player, Items.OAK_LOG) == 12, "items went to the player");
		helper.assertTrue(player.containerMenu != menu, "confirm reopens the menu (the title carries the profession)");
		helper.succeed();
	}

	// ---- targets ----

	@GameTest
	public void targetsShowAndClear(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		helper.setBlock(new BlockPos(5, 1, 5), Blocks.OAK_LOG);
		GolemData data = GolemData.get(g);
		data.assignments.put("tree", helper.absolutePos(new BlockPos(5, 1, 5)));
		GolemData.save(g, data);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.TARGETS);
		// three specs: columns 2, 4, 6; row 2 assign, row 3 Show, row 4 Clear
		helper.assertTrue(name(at(menu, 2, 2)).equals("Assign Tree") && lore(at(menu, 2, 2)).contains("Current: "), "tree " + lore(at(menu, 2, 2)));
		helper.assertTrue(lore(at(menu, 2, 4)).contains("Not set"), "input " + lore(at(menu, 2, 4)));
		helper.assertTrue(at(menu, 3, 2).is(Items.SPYGLASS) && at(menu, 4, 2).is(Items.BARRIER), "show and clear icons");
		click(menu, player, 3, 4); // Show an unset target
		helper.assertTrue(!WalkTo.active(g), "an unset target starts no walk");
		click(menu, player, 3, 2);
		helper.assertTrue(WalkTo.active(g), "Show must start a WalkTo");
		WalkTo.cancel(g);
		click(menu, player, 4, 2); // Clear
		helper.assertTrue(!GolemData.get(g).assignments.containsKey("tree"), "assignment cleared");
		helper.assertTrue(lore(at(menu, 2, 2)).contains("Not set"), "the view is refreshed: " + lore(at(menu, 2, 2)));
		helper.succeed();
	}

	@GameTest
	public void targetsAreaClearAndColumns(GameTestHelper helper) {
		CopperGolem g = golem(helper, FARMER);
		GolemData data = GolemData.get(g);
		data.assignments.put("field_a", helper.absolutePos(new BlockPos(1, 1, 1)));
		data.assignments.put("field_b", helper.absolutePos(new BlockPos(4, 1, 3)));
		GolemData.save(g, data);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.TARGETS);
		for (int col : new int[] {1, 3, 5, 7}) helper.assertTrue(at(menu, 2, col).getCount() == 1 && !orange(at(menu, 2, col)), "4 specs use columns 1,3,5,7: " + col);
		helper.assertTrue(lore(at(menu, 2, 1)).contains("Max 9x9") && lore(at(menu, 2, 1)).contains("(4x3)"), "area lore " + lore(at(menu, 2, 1)));
		click(menu, player, 3, 1);
		helper.assertTrue(WalkTo.active(g), "Show on an area walks its outline");
		WalkTo.cancel(g);
		click(menu, player, 4, 1);
		helper.assertTrue(!GolemData.get(g).assignments.containsKey("field_a") && !GolemData.get(g).assignments.containsKey("field_b"), "both corners cleared");
		// no specs
		CopperGolem none = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemMenu m2 = new GolemMenu(2, player.getInventory(), none, Tab.TARGETS);
		helper.assertTrue(at(m2, 3, 4).is(Items.BOOK) && name(at(m2, 3, 4)).equals("This profession needs no targets"), "no targets: " + at(m2, 3, 4));
		helper.succeed();
	}

	// ---- inventory layouts ----

	@GameTest
	public void lumberAndFarmerLayouts(GameTestHelper helper) {
		CopperGolem lumber = golem(helper, LUMBER);
		ServerPlayer player = player(helper, lumber);
		GolemMenu m = new GolemMenu(1, player.getInventory(), lumber, Tab.INVENTORY);
		ItemStack axe = new ItemStack(Items.IRON_AXE);
		ItemStack sapling = new ItemStack(Items.OAK_SAPLING);
		ItemStack stick = new ItemStack(Items.STICK);
		helper.assertTrue(name(at(m, 1, 3)).equals("Axe") && name(at(m, 1, 5)).equals("Supplies") && name(at(m, 4, 4)).equals("Carry"), "labels");
		helper.assertTrue(slot(m, 2, 3).mayPlace(axe) && !slot(m, 2, 3).mayPlace(sapling), "axe slot at (2,3)");
		helper.assertTrue(slot(m, 2, 5).mayPlace(sapling) && slot(m, 2, 6).mayPlace(new ItemStack(Items.BONE_MEAL)) && !slot(m, 2, 5).mayPlace(axe), "supplies at (2,5),(2,6)");
		helper.assertTrue(orange(at(m, 2, 4)) && orange(at(m, 2, 7)), "filler around the slots");
		for (int c = 0; c < 9; c++) helper.assertTrue(slot(m, 5, c).mayPlace(stick), "carry slot " + c);
		helper.assertTrue(!slot(m, 4, 3).mayPlace(stick), "row 4 is not a slot");
		helper.assertTrue(((GolemMenu) m).slots.size() == 90, "slots");

		CopperGolem farmer = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		GolemService.changeProfession(helper.getLevel(), farmer, FARMER);
		GolemMenu f = new GolemMenu(2, player.getInventory(), farmer, Tab.INVENTORY);
		helper.assertTrue(name(at(f, 1, 3)).equals("Tool") && name(at(f, 1, 5)).equals("Seeds") && name(at(f, 4, 4)).equals("Carry"), "farmer labels");
		helper.assertTrue(slot(f, 2, 3).mayPlace(new ItemStack(Items.IRON_HOE)) && slot(f, 2, 3).mayPlace(axe) && !slot(f, 2, 3).mayPlace(stick), "tool slot");
		helper.assertTrue(slot(f, 2, 5).mayPlace(new ItemStack(Items.WHEAT_SEEDS)) && !slot(f, 2, 5).mayPlace(stick), "seed slot");
		for (int c = 0; c < 9; c++) helper.assertTrue(slot(f, 5, c).mayPlace(stick), "farmer carry slot " + c);

		for (Identifier id : new Identifier[] {TRANSPORTER, CLEANER}) {
			CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
			GolemService.changeProfession(helper.getLevel(), g, id);
			GolemMenu t = new GolemMenu(3, player.getInventory(), g, Tab.INVENTORY);
			helper.assertTrue(name(at(t, 2, 4)).equals("Carry"), id + " label");
			for (int c = 0; c < 9; c++) helper.assertTrue(slot(t, 3, c).mayPlace(stick), id + " carry slot " + c);
			helper.assertTrue(!slot(t, 4, 0).mayPlace(stick), id + " has exactly nine slots");
		}
		for (Identifier id : new Identifier[] {null, SORTER}) {
			CopperGolem g = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
			if (id != null) GolemService.changeProfession(helper.getLevel(), g, id);
			GolemMenu t = new GolemMenu(4, player.getInventory(), g, Tab.INVENTORY);
			helper.assertTrue(at(t, 3, 4).is(Items.BOOK) && name(at(t, 3, 4)).equals("No inventory"), id + " has no inventory");
		}
		helper.succeed();
	}

	@GameTest
	public void settingsFollowToggle(GameTestHelper helper) {
		CopperGolem g = golem(helper, TRANSPORTER);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.SETTINGS);
		helper.assertTrue(at(menu, 3, 4).is(Items.LEAD) && !glint(at(menu, 3, 4)) && name(at(menu, 3, 4)).equals("Follow: off"), "off " + at(menu, 3, 4));
		click(menu, player, 3, 4);
		helper.assertTrue(GolemData.get(g).following && glint(at(menu, 3, 4)) && name(at(menu, 3, 4)).equals("Follow: on"), "on " + at(menu, 3, 4));
		click(menu, player, 3, 4);
		helper.assertTrue(!GolemData.get(g).following && !glint(at(menu, 3, 4)), "off again");
		helper.succeed();
	}

	// ---- status ----

	@GameTest
	public void statusShowsToolCarriedAndHolding(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		GolemData data = GolemData.get(g);
		ItemStack axe = new ItemStack(Items.IRON_AXE);
		axe.setDamageValue(10);
		data.items.set(0, axe);
		data.items.set(3, new ItemStack(Items.OAK_LOG, 5));
		GolemData.save(g, data);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g);
		helper.assertTrue(name(at(menu, 3, 3)).equals("Carrying 5 items") && at(menu, 3, 3).is(Items.BUNDLE), "carrying " + name(at(menu, 3, 3)));
		helper.assertTrue(at(menu, 3, 4).is(Items.IRON_AXE) && lore(at(menu, 3, 4)).contains("Durability 240/250"), "tool " + lore(at(menu, 3, 4)));
		helper.assertTrue(at(menu, 3, 5).is(Items.COMPASS) && name(at(menu, 3, 5)).startsWith("Next: "), "next " + name(at(menu, 3, 5)));
		data.items.set(0, ItemStack.EMPTY);
		GolemData.save(g, data);
		menu.showTab(Tab.STATUS);
		helper.assertTrue(name(at(menu, 3, 4)).equals("No tool"), "no tool: " + name(at(menu, 3, 4)));

		CopperGolem sorter = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), sorter, SORTER);
		sorter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE));
		GolemMenu sm = new GolemMenu(2, player.getInventory(), sorter);
		helper.assertTrue(name(at(sm, 3, 3)).equals("Holding Apple") && name(at(sm, 3, 5)).startsWith("Next: "), "holding " + name(at(sm, 3, 3)));
		helper.succeed();
	}

	// ---- crafter memory, ghost output ----

	private static CopperGolem sticksCrafter(GameTestHelper helper) {
		CopperGolem g = golem(helper, CRAFTER);
		GolemData data = GolemData.get(g);
		data.items.set(0, new ItemStack(Items.OAK_PLANKS));
		data.items.set(3, new ItemStack(Items.OAK_PLANKS));
		GolemData.save(g, data);
		return g;
	}

	@GameTest
	public void emptyHandClickForgetsARememberedCell(GameTestHelper helper) {
		CopperGolem g = sticksCrafter(helper);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.INVENTORY);
		int cell = 2 * 9 + 1; // grid cell 0
		helper.assertTrue(DisplayItems.is(menu.slots.get(cell).getItem()), "remembered, empty");
		menu.clicked(cell, 0, ContainerInput.PICKUP, player);
		helper.assertTrue(GolemData.get(g).items.get(0).isEmpty() && menu.slots.get(cell).getItem().isEmpty(), "memory forgotten");
		helper.assertTrue(GolemData.get(g).items.get(3).is(Items.OAK_PLANKS), "other cells keep theirs");
		helper.assertTrue(lore(at(menu, 3, 5)).contains("Oak Button"), "the arrow follows (one plank left = a button): " + lore(at(menu, 3, 5)));
		// an empty cell without memory learns the item placed into it; a full cell keeps its memory when emptied
		player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 7));
		menu.setCarried(player.getInventory().removeItem(0, 7));
		menu.clicked(cell, 0, ContainerInput.PICKUP, player);
		helper.assertTrue(GolemData.get(g).items.get(IN).is(Items.COBBLESTONE) && GolemData.get(g).items.get(IN).getCount() == 7
			&& GolemData.get(g).items.get(0).is(Items.COBBLESTONE) && GolemData.get(g).items.get(0).getCount() == 1, "placing sets the memory");
		menu.clicked(cell, 0, ContainerInput.PICKUP, player); // take the stack out again
		helper.assertTrue(menu.getCarried().getCount() == 7 && GolemData.get(g).items.get(0).is(Items.COBBLESTONE), "memory stays after taking the stack out");
		helper.assertTrue(!hasDisplay(player, menu), "no display copy in hand");
		helper.succeed();
	}

	/** Every kind of click on the ghost output and on a faded cell: no display copy escapes, no item appears or vanishes. */
	@GameTest
	public void ghostCellsCannotBeTaken(GameTestHelper helper) {
		CopperGolem g = sticksCrafter(helper);
		ServerPlayer player = player(helper, g);
		player.getAbilities().instabuild = true;
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.INVENTORY);
		int ghostOut = 3 * 9 + 7;
		int faded = 3 * 9 + 1; // grid cell 3 remembers planks
		helper.assertTrue(DisplayItems.is(menu.slots.get(ghostOut).getItem()) && DisplayItems.is(menu.slots.get(faded).getItem()), "both show copies");
		player.getInventory().setItem(0, new ItemStack(Items.STICK, 5));
		player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 9));
		GolemData before = GolemData.get(g);
		List<ItemStack> snapshot = before.items.stream().map(ItemStack::copy).toList();
		int total = realTotal(player, menu, g);
		for (int cell : new int[] {ghostOut, faded}) {
			for (ItemStack cursor : new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.STICK, 3)}) {
				for (ContainerInput in : new ContainerInput[] {ContainerInput.PICKUP, ContainerInput.QUICK_MOVE, ContainerInput.CLONE,
					ContainerInput.PICKUP_ALL, ContainerInput.SWAP, ContainerInput.THROW}) {
					for (int button : new int[] {0, 1}) {
						// an empty-handed click on a faded cell forgets its memory on purpose (emptyHandClickForgetsARememberedCell)
						if (cell == faded && in == ContainerInput.PICKUP && cursor.isEmpty()) continue;
						// a fresh state each time: the sticks are the only thing on the cursor, nothing else was moved
						menu.setCarried(cursor.copy());
						int expected = total + cursor.getCount();
						int cellMemoryBefore = GolemData.get(g).items.get(3).getCount();
						menu.clicked(cell, button, in, player);
						helper.assertTrue(!hasDisplay(player, menu), in + "/" + button + " on cell " + cell + " leaked a display copy");
						helper.assertTrue(realTotal(player, menu, g) == expected, in + "/" + button + " on cell " + cell + " changed the item count");
						for (int i = 0; i < snapshot.size(); i++) {
							helper.assertTrue(ItemStack.matches(snapshot.get(i), GolemData.get(g).items.get(i)), in + "/" + button + " changed golem slot " + i);
						}
						helper.assertTrue(GolemData.get(g).items.get(3).getCount() == cellMemoryBefore, "memory kept");
					}
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void realOutputIsStillTakeOnly(GameTestHelper helper) {
		CopperGolem g = sticksCrafter(helper);
		GolemData data = GolemData.get(g);
		data.items.set(OUT, new ItemStack(Items.STICK, 20));
		GolemData.save(g, data);
		ServerPlayer player = player(helper, g);
		GolemMenu menu = new GolemMenu(1, player.getInventory(), g, Tab.INVENTORY);
		int out = 3 * 9 + 7;
		helper.assertTrue(!DisplayItems.is(menu.slots.get(out).getItem()) && menu.slots.get(out).mayPickup(player), "a real output is shown and takeable");
		menu.clicked(out, 0, ContainerInput.QUICK_MOVE, player);
		helper.assertTrue(count(player, Items.STICK) == 20 && GolemData.get(g).items.get(OUT).isEmpty(), "shift-click takes the output");
		helper.assertTrue(DisplayItems.is(menu.slots.get(out).getItem()), "the ghost returns once it is empty");
		helper.succeed();
	}

	// ---- hand display ----

	@GameTest
	public void handToolIsDisplayOnlyAndNeverDrops(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		GolemData data = GolemData.get(g);
		data.items.set(0, new ItemStack(Items.IRON_AXE));
		GolemData.save(g, data);
		GolemHandDisplay.sync(g);
		ItemStack hand = g.getMainHandItem();
		helper.assertTrue(hand.is(Items.IRON_AXE) && DisplayItems.is(hand), "the axe is shown in the hand: " + hand);
		helper.assertTrue(g.getDropChances().byEquipment(EquipmentSlot.MAINHAND) == 0.0F, "the display never drops");
		g.kill(helper.getLevel());
		helper.runAfterDelay(2, () -> {
			int axes = 0;
			for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, g.getBoundingBox().inflate(4))) {
				helper.assertTrue(!DisplayItems.is(e.getItem()), "a display copy dropped");
				if (e.getItem().is(Items.IRON_AXE)) axes += e.getItem().getCount();
			}
			helper.assertTrue(axes == 1, "exactly the stored axe drops: " + axes);
			helper.succeed();
		});
	}

	/** Vanilla's empty-hand "throw the held item at the player" must not fire for a display copy (it would duplicate it). */
	@GameTest
	public void vanillaThrowNeverSeesTheDisplayCopy(GameTestHelper helper) {
		CopperGolem g = golem(helper, LUMBER);
		GolemData data = GolemData.get(g);
		data.items.set(0, new ItemStack(Items.IRON_AXE));
		GolemData.save(g, data);
		GolemHandDisplay.sync(g);
		ServerPlayer player = player(helper, g);
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STICK)); // main hand passes, then the empty off hand asks
		for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
			g.mobInteract(player, hand);
		}
		helper.assertTrue(DisplayItems.is(g.getMainHandItem()), "the display copy must stay in the hand");
		helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, g.getBoundingBox().inflate(6)).isEmpty(), "nothing may be thrown");
		// a real carried item is still thrown back to the player as in vanilla
		CopperGolem sorter = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), sorter, SORTER);
		sorter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE));
		sorter.mobInteract(player, net.minecraft.world.InteractionHand.OFF_HAND);
		helper.assertTrue(sorter.getMainHandItem().isEmpty(), "vanilla behaviour for a real carried item is unchanged");
		helper.succeed();
	}

	@GameTest
	public void handDisplayRules(GameTestHelper helper) {
		// Transporter shows the first carried stack as one item, follows changes and clears when empty
		CopperGolem t = golem(helper, TRANSPORTER);
		GolemData data = GolemData.get(t);
		data.items.set(2, new ItemStack(Items.COBBLESTONE, 40));
		GolemData.save(t, data);
		GolemHandDisplay.sync(t);
		helper.assertTrue(t.getMainHandItem().is(Items.COBBLESTONE) && t.getMainHandItem().getCount() == 1 && DisplayItems.is(t.getMainHandItem()), "carried stack shown");
		data.items.set(2, ItemStack.EMPTY);
		data.items.set(5, new ItemStack(Items.STICK, 2));
		GolemHandDisplay.sync(t);
		helper.assertTrue(t.getMainHandItem().is(Items.STICK), "follows the first non-empty stack: " + t.getMainHandItem());
		data.items.set(5, ItemStack.EMPTY);
		GolemHandDisplay.sync(t);
		helper.assertTrue(t.getMainHandItem().isEmpty(), "cleared when nothing is carried");

		// a real held item always wins
		data.items.set(1, new ItemStack(Items.COBBLESTONE, 4));
		t.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE));
		GolemHandDisplay.sync(t);
		helper.assertTrue(t.getMainHandItem().is(Items.APPLE) && !DisplayItems.is(t.getMainHandItem()), "real hand item is never overwritten");

		// profession change: the real item goes to the player, the display copy is just cleared, only real items arrive
		ServerPlayer player = player(helper, t);
		GolemService.changeProfession(helper.getLevel(), t, LUMBER, player);
		helper.assertTrue(t.getMainHandItem().isEmpty() && count(player, Items.APPLE) == 1 && count(player, Items.COBBLESTONE) == 4, "hand released to the player");
		GolemData ld = GolemData.get(t);
		ld.items.set(0, new ItemStack(Items.IRON_AXE));
		GolemData.save(t, ld);
		GolemHandDisplay.sync(t);
		helper.assertTrue(DisplayItems.is(t.getMainHandItem()), "axe shown");
		GolemService.changeProfession(helper.getLevel(), t, SORTER, player);
		helper.assertTrue(t.getMainHandItem().isEmpty() && count(player, Items.IRON_AXE) == 1 && !hasDisplay(player, new GolemMenu(9, player.getInventory(), t)),
			"the display copy is cleared and never handed over");
		// a vanilla-brain profession shows nothing
		GolemHandDisplay.sync(t);
		helper.assertTrue(t.getMainHandItem().isEmpty(), "sorter keeps its hand to itself");
		helper.succeed();
	}
}
