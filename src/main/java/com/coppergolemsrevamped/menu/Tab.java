package com.coppergolemsrevamped.menu;

import java.util.List;

import org.jspecify.annotations.Nullable;

/** The golem menu's tabs, in tab-bar order (cells 2..6). {@code icon == null}: the current profession's icon is shown. */
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
	INVENTORY("Inventory", "The golem's slots", GolemIcon.INVENTORY, List.of(
		"These are the golem's own slots; the layout depends on the profession.",
		"Tool, seed and supply slots only take matching items.",
		"Shift-click moves items from your inventory into the golem's working slots and back.")),
	SETTINGS("Settings", "Follow and other options", GolemIcon.SETTINGS, List.of(
		"Follow: when on, the golem walks along with you instead of working.",
		"Click the toggle to switch it; it glows while on.")),
	PROFESSION("Profession", "Choose what the golem does", null, List.of(
		"Pick what this golem does.",
		"While it has none, a click switches it right away.",
		"Changing a working golem asks you to confirm first.",
		"Its items go to your inventory and its targets are cleared."));

	public final String label;
	public final String lore;
	public final @Nullable GolemIcon icon;
	/** Lines for the Help button; the menu adds profession-specific lines (assign sizes, slot explanations). */
	public final List<String> help;

	Tab(String label, String lore, @Nullable GolemIcon icon, List<String> help) {
		this.label = label;
		this.lore = lore;
		this.icon = icon;
		this.help = help;
	}
}
