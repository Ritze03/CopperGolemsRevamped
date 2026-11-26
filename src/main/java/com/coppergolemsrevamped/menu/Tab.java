package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.profession.Profession;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The golem menu's tabs, in tab-bar order. {@code icon == null && plain == null}: the current profession's icon is shown.
 * {@link #RECIPE} exists only for a profession with a recipe group, {@link #FILTER} only with a filter group ({@link #forProfession}).
 */
public enum Tab {
	STATUS("Status", "What the golem is doing now", GolemIcon.STATUS, List.of(
		"Read-only overview of the golem.",
		"Hover each icon: current job, carried items, tool durability and the next step.",
		"Values update while the menu is open.")),
	TARGETS("Targets", "Where the golem works", GolemIcon.ASSIGN_BLOCK, List.of(
		"Each profession has its own targets: chests, blocks or an area.",
		"Click a target icon, then right-click a block in the world to set it.",
		"Show makes the golem walk to the target; Clear removes it.",
		"Chest Sorter and None have no targets.")),
	RECIPE("Recipe", "The recipe the golem follows", null, Items.KNOWLEDGE_BOOK, List.of(
		"The grid is the recipe. Click a cell with an item to set it (the item is not used up), click with an empty hand to clear it.",
		"Nothing here is a real item; the ingredients live on the Inventory tab.")),
	FILTER("Filter", "Which items the golem moves", null, Items.HOPPER, List.of(
		"Put items in the nine slots (the item stays here, one per slot); only the item type counts.",
		"The button picks the mode: Off moves everything, Whitelist moves only these items, Blacklist moves everything except them.",
		"An empty whitelist moves nothing.")),
	INVENTORY("Inventory", "The golem's slots", GolemIcon.INVENTORY, List.of(
		"These are the golem's own slots; the layout depends on the profession.",
		"Tool, seed and supply slots only take matching items.",
		"Shift-click moves items from your inventory into the golem's working slots and back.")),
	SETTINGS("Settings", "Follow, Freeze and other options", GolemIcon.SETTINGS, List.of(
		"Follow: when on, the golem walks along with you instead of working.",
		"Freeze: the golem stops all work and stands still, so you can safely change its inventory. Turning it on turns Follow off.",
		"Keep layout (taking): the golem never empties a stack; it leaves 1 item in every slot it takes from.",
		"Keep layout (storing): the golem only tops up stacks that already hold the same item and never puts items into empty slots.",
		"Together they keep a chest you laid out by hand as it is.",
		"Click a toggle to switch it; it glows while on.")),
	PROFESSION("Profession", "Choose what the golem does", null, List.of(
		"Pick what this golem does.",
		"While it has none, a click switches it right away.",
		"Changing a working golem asks you to confirm first.",
		"Its items go to your inventory and its targets are cleared."));

	public final String label;
	public final String lore;
	public final @Nullable GolemIcon icon;
	/** A plain vanilla item used instead of a mod icon (no custom model data), or null. */
	public final @Nullable Item plain;
	/** Lines for the Help button; the menu adds profession-specific lines (assign sizes, slot explanations). */
	public final List<String> help;

	Tab(String label, String lore, @Nullable GolemIcon icon, List<String> help) {
		this(label, lore, icon, null, help);
	}

	Tab(String label, String lore, @Nullable GolemIcon icon, @Nullable Item plain, List<String> help) {
		this.label = label;
		this.lore = lore;
		this.icon = icon;
		this.plain = plain;
		this.help = help;
	}

	/** The tabs of the tab bar for a profession (null = None): all of them, {@link #RECIPE} only with a recipe group. */
	public static List<Tab> forProfession(@Nullable Profession p) {
		List<Tab> out = new ArrayList<>();
		for (Tab t : values()) {
			if (t == RECIPE && !(p != null && p.hasRecipeTab())) continue;
			if (t == FILTER && !(p != null && p.hasFilterTab())) continue;
			out.add(t);
		}
		return out;
	}
}
