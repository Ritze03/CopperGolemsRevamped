package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.DisplayItems;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.ProfessionStatus;
import com.coppergolemsrevamped.profession.Professions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Builds the 54 {@link Cell}s of a {@link GolemMenu} view: the tab bar plus the current tab, or the confirm screen.
 * Cell index = row * 9 + column. Everything profession-specific comes from the {@link Profession} declaration
 * (slot groups, labels, assigns), never from a profession id (decision D6); the Status texts come from
 * {@link ProfessionStatus}.
 */
final class GolemViews {
	private GolemViews() {}

	static Cell[] build(GolemMenu m) {
		Cell[] cells = new Cell[GolemMenu.TOP];
		Arrays.fill(cells, icon(GolemIcon.filler()));
		if (m.confirming()) {
			confirm(m, cells);
			return cells;
		}
		bar(m, cells);
		switch (m.tab()) {
			case STATUS -> status(m, cells);
			case TARGETS -> targets(m, cells);
			case RECIPE -> recipe(m, cells);
			case INVENTORY -> inventory(m, cells);
			case SETTINGS -> settings(m, cells);
			case PROFESSION -> professions(m, cells);
		}
		return cells;
	}

	private static Cell.Icon icon(ItemStack stack) {
		return new Cell.Icon(stack, null);
	}

	private static Cell.Icon icon(ItemStack stack, Cell.Control c) {
		return new Cell.Icon(stack, c);
	}

	private static void set(Cell[] cells, int row, int col, Cell cell) {
		cells[row * GolemMenu.COLS + col] = cell;
	}

	private static List<Component> lore(String... lines) {
		List<Component> out = new ArrayList<>();
		for (String l : lines) out.add(GolemIcon.line(l));
		return out;
	}

	/** Columns for {@code n} icons in the Status row: centred, adjacent for 3 (simulator: [3,4,5] for 3, [3,5] for 2). */
	private static int[] statusColumns(int n) {
		return n == 3 ? new int[] {3, 4, 5} : targetColumns(n);
	}

	/** Columns for {@code n} targets: one column each, spread out (2 -> [3,5], 3 -> [2,4,6], 4 -> [1,3,5,7]). */
	private static int[] targetColumns(int n) {
		return switch (n) {
			case 1 -> new int[] {4};
			case 2 -> new int[] {3, 5};
			case 3 -> new int[] {2, 4, 6};
			case 4 -> new int[] {1, 3, 5, 7};
			default -> throw new IllegalArgumentException("columns: " + n);
		};
	}

	/** "Assign Tree" -> "Tree": the target's name for chat messages. */
	static String targetName(AssignSpec spec) {
		String l = spec.label().getString();
		return l.startsWith("Assign ") ? l.substring("Assign ".length()) : l;
	}

	// ---- tab bar (row 0) ----

	/**
	 * Row 0: black panes, the profession's tabs right-aligned to end at column 6 (5 tabs start at 2, with a Recipe tab
	 * at 1) and Help at column 8. There is no Back button: players close the menu with Esc like any chest.
	 */
	private static void bar(GolemMenu m, Cell[] cells) {
		// black panes mark the bar as another part of the UI than the orange tab content
		for (int c = 0; c < GolemMenu.COLS; c++) set(cells, 0, c, icon(GolemIcon.barFiller()));
		Profession prof = m.profession();
		List<Tab> tabs = Tab.forProfession(prof);
		int start = 7 - tabs.size();
		for (int k = 0; k < tabs.size(); k++) {
			Tab t = tabs.get(k);
			boolean active = t == m.tab();
			List<Component> lore = lore(t.lore);
			ItemStack stack = t.plain != null
				? GolemIcon.plain(t.plain, Component.literal(t.label), lore, active)
				: (t.icon != null ? t.icon : GolemIcon.profession(prof == null ? "none" : prof.id().getPath()))
					.stack(Component.literal(t.label), lore, active);
			set(cells, 0, start + k, icon(stack, (p, b) -> m.showTab(t)));
		}
		set(cells, 0, 8, icon(GolemIcon.HELP.stack(Component.literal("Help"), lore("Explains this page in chat"), false),
			(p, b) -> m.sendHelp(p)));
	}

	// ---- Status ----

	private static void status(GolemMenu m, Cell[] cells) {
		Profession prof = m.profession();
		GolemData data = GolemData.get(m.golem());
		ServerLevel level = (ServerLevel) m.golem().level();
		String pname = prof == null ? "None" : prof.name().getString();
		String path = prof == null ? "none" : prof.id().getPath();
		set(cells, 2, 4, icon(GolemIcon.profession(path).stack(
			Component.literal(pname + " — " + ProfessionStatus.doing(level, m.golem(), data, prof)), List.of(), false)));

		List<ItemStack> row = new ArrayList<>();
		if (prof != null && prof.inventorySize() > 0) {
			int n = carried(prof, data);
			row.add(GolemIcon.plain(Items.BUNDLE, Component.literal("Carrying " + n + " item" + (n == 1 ? "" : "s")), List.of(), false));
			SlotGroup toolGroup = prof.group("tool");
			if (toolGroup != null) row.add(toolIcon(prof, data, prof.firstIndex(toolGroup)));
		}
		ItemStack hand = m.golem().getMainHandItem();
		if (!hand.isEmpty() && !DisplayItems.is(hand)) {
			row.add(GolemIcon.plain(hand.getItem(), Component.literal("Holding " + hand.getHoverName().getString()),
				lore("The golem carries this itself", "Read-only"), false));
		}
		row.add(GolemIcon.plain(Items.COMPASS, Component.literal(ProfessionStatus.next(m.golem(), data, prof)), List.of(), false));
		int[] cols = statusColumns(row.size());
		for (int k = 0; k < row.size(); k++) set(cells, 3, cols[k], icon(row.get(k)));
	}

	/** Items in every group that is not a 1-stack tool group (the recipe memory is hidden and counts as no group). */
	private static int carried(Profession p, GolemData data) {
		int n = 0;
		int idx = 0;
		for (SlotGroup g : p.slots()) {
			for (int i = 0; i < g.count(); i++, idx++) {
				if (g.maxStack() > 1 && g.visible() && !g.recipe() && idx < data.items.size()) n += data.items.get(idx).getCount();
			}
		}
		return n;
	}

	private static ItemStack toolIcon(Profession p, GolemData data, int idx) {
		ItemStack tool = idx < data.items.size() ? data.items.get(idx) : ItemStack.EMPTY;
		if (tool.isEmpty()) {
			return GolemIcon.ASSIGN_TOOLS.stack(Component.literal("No tool"), lore("Put one in the tool slot"), false);
		}
		List<Component> lore = tool.isDamageableItem()
			? lore("Durability " + (tool.getMaxDamage() - tool.getDamageValue()) + "/" + tool.getMaxDamage()) : List.of();
		return GolemIcon.plain(tool.getItem(), tool.getHoverName(), lore, false);
	}

	// ---- Targets ----

	private static void targets(GolemMenu m, Cell[] cells) {
		Profession prof = m.profession();
		List<AssignSpec> specs = prof == null ? List.of() : prof.assigns();
		if (specs.isEmpty()) {
			set(cells, 3, 4, icon(GolemIcon.plain(Items.BOOK, Component.literal("This profession needs no targets"), List.of(), false)));
			return;
		}
		GolemData data = GolemData.get(m.golem());
		int[] cols = targetColumns(Math.min(specs.size(), 4));
		for (int k = 0; k < Math.min(specs.size(), 4); k++) {
			AssignSpec spec = specs.get(k);
			int c = cols[k];
			set(cells, 2, c, icon(assignIcon(spec, data), (p, b) -> {
				p.closeContainer();
				AssignMode.arm(p, m.golem(), spec.key());
			}));
			String name = targetName(spec);
			set(cells, 3, c, icon(GolemIcon.plain(Items.SPYGLASS, Component.literal("Show"), lore("Golem walks to " + name), false),
				(p, b) -> m.show(p, spec)));
			set(cells, 4, c, icon(GolemIcon.plain(Items.BARRIER, Component.literal("Clear"), lore("Unset " + name), false),
				(p, b) -> m.clear(p, spec)));
		}
	}

	private static ItemStack assignIcon(AssignSpec spec, GolemData data) {
		GolemIcon keyed = GolemIcon.byName("assign_" + spec.key());
		GolemIcon icon = keyed != null ? keyed : switch (spec.kind()) {
			case CHEST -> GolemIcon.ASSIGN_CHEST;
			case CRAFTING_TABLE -> GolemIcon.ASSIGN_CRAFTING_TABLE;
			case BLOCK -> GolemIcon.ASSIGN_BLOCK;
			case AREA -> GolemIcon.ASSIGN_AREA;
		};
		List<Component> lore = new ArrayList<>();
		lore.add(GolemIcon.line(switch (spec.kind()) {
			case CHEST -> "Click, then right-click a chest";
			case CRAFTING_TABLE -> "Click, then right-click a crafting table";
			case BLOCK -> "Click, then right-click a block";
			case AREA -> "Click, then right-click two opposite corners";
		}));
		if (spec.kind() == Kind.AREA) lore.add(GolemIcon.line("Max " + spec.maxSize() + "x" + spec.maxSize()));
		String current = currentValue(spec, data);
		lore.add(current == null ? GolemIcon.line("Not set") : GolemIcon.line("Current: " + current, ChatFormatting.WHITE));
		return icon.stack(spec.label(), lore, false);
	}

	/** "x, y, z" or "a to b (WxD)" for an area; null when not set. */
	private static @Nullable String currentValue(AssignSpec spec, GolemData data) {
		if (spec.kind() == Kind.AREA) {
			BlockPos a = data.assignments.get(spec.key() + "_a");
			BlockPos b = data.assignments.get(spec.key() + "_b");
			if (a == null || b == null) return null;
			int w = Math.abs(b.getX() - a.getX()) + 1;
			int d = Math.abs(b.getZ() - a.getZ()) + 1;
			return fmt(a) + " to " + fmt(b) + " (" + w + "x" + d + ")";
		}
		BlockPos p = data.assignments.get(spec.key());
		return p == null ? null : fmt(p);
	}

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	// ---- Inventory ----

	private static void inventory(GolemMenu m, Cell[] cells) {
		Profession prof = m.profession();
		boolean any = false;
		if (prof != null) {
			for (SlotGroup g : prof.slots()) {
				if (!g.visible() || g.recipe()) continue;
				any = true;
				int first = prof.firstIndex(g);
				for (int i = 0; i < g.count(); i++) {
					set(cells, g.row() + i / g.width(), g.col() + i % g.width(), new Cell.Store(m.golem(), m.items(), first + i, g));
				}
				Profession.Label l = g.label();
				if (l != null) set(cells, l.row(), l.col(), icon(label(l)));
			}
		}
		if (!any) set(cells, 3, 4, icon(GolemIcon.plain(Items.BOOK, Component.literal("No inventory"), List.of(), false)));
	}

	/** A group's label on the Inventory tab: icon, name and the declared lore (one line per {@code \n}). */
	private static ItemStack label(Profession.Label l) {
		return GolemIcon.label(l.icon(), l.name(), lore(l.lore().isEmpty() ? new String[0] : l.lore().split("\n")));
	}

	// ---- Recipe ----

	/**
	 * The Recipe tab: each ghost-recipe group's cells show the stored one-item memory copy (an empty cell shows nothing)
	 * and set it on a click with an item on the cursor; a previewing group adds its arrow (lore: the outcome) and the
	 * ghost preview. Every cell is an {@link Cell.Icon}, so nothing here can be taken or placed as a real item.
	 */
	private static void recipe(GolemMenu m, Cell[] cells) {
		Profession prof = m.profession();
		if (prof == null) return;
		GolemData data = GolemData.get(m.golem());
		ServerLevel level = (ServerLevel) m.golem().level();
		for (SlotGroup g : prof.slots()) {
			if (!g.visible()) continue;
			if (g.recipe()) {
				int first = prof.firstIndex(g);
				for (int i = 0; i < g.count(); i++) {
					int idx = first + i;
					ItemStack stored = idx < data.items.size() ? data.items.get(idx) : ItemStack.EMPTY;
					ItemStack shown = stored.isEmpty() ? ItemStack.EMPTY : GolemIcon.ghost(stored.copyWithCount(1), "recipe",
						lore("Click with an item to replace it", "Click with an empty hand to clear it"));
					set(cells, g.row() + i / g.width(), g.col() + i % g.width(),
						new Cell.Icon(shown, (p, b) -> m.setRecipeCell(p, g, idx), true));
				}
				if (g.label() != null) set(cells, g.label().row(), g.label().col(), icon(label(g.label())));
			} else if (g.preview() != null) {
				ItemStack o = g.preview().outcome(level, data);
				if (g.label() != null) {
					Profession.Label l = g.label();
					List<Component> lore = lore(o.isEmpty() ? "No valid recipe"
						: o.getHoverName().getString() + (o.getCount() > 1 ? " x" + o.getCount() : ""));
					set(cells, l.row(), l.col(), icon(GolemIcon.label(l.icon(), l.name(), lore)));
				}
				set(cells, g.row(), g.col(), icon(o.isEmpty() ? ItemStack.EMPTY : GolemIcon.ghost(o, "preview",
					lore("Crafted by the golem", "Cannot be taken"))));
			}
		}
	}

	// ---- Settings ----

	private static void settings(GolemMenu m, Cell[] cells) {
		GolemData data = GolemData.get(m.golem());
		boolean on = data.following;
		set(cells, 3, 3, icon((on ? GolemIcon.FOLLOW_ON : GolemIcon.FOLLOW_OFF).stack(
			Component.literal("Follow: " + (on ? "on" : "off")),
			lore(on ? "Click to stop following you" : "Click to make the golem follow you"), on),
			(p, b) -> m.toggleFollow(p)));
		boolean frozen = data.frozen;
		set(cells, 3, 5, icon(GolemIcon.plain(Items.BLUE_ICE, Component.literal("Freeze: " + (frozen ? "on" : "off")),
			lore("Golem stops all work and stands still", "Use it to safely change its inventory"), frozen),
			(p, b) -> m.toggleFreeze()));
	}

	// ---- Profession and confirm ----

	private static void professions(GolemMenu m, Cell[] cells) {
		List<@Nullable Identifier> order = new ArrayList<>();
		order.add(null);
		for (Profession p : Professions.all()) order.add(p.id());
		for (int k = 0; k < order.size(); k++) {
			@Nullable Identifier id = order.get(k);
			boolean current = Objects.equals(id, m.currentId());
			List<Component> lore = new ArrayList<>();
			lore.add(current ? GolemIcon.line("Current profession", ChatFormatting.GREEN)
				: GolemIcon.line("Click to switch to " + nameOf(id)));
			set(cells, 3, 1 + k, icon(iconOf(id).stack(Component.literal(nameOf(id)), lore, current), (p, b) -> m.choose(id, p)));
		}
	}

	private static String nameOf(@Nullable Identifier id) {
		Profession p = Professions.get(id);
		return p == null ? "None" : p.name().getString();
	}

	private static GolemIcon iconOf(@Nullable Identifier id) {
		return GolemIcon.profession(id == null ? "none" : id.getPath());
	}

	/** No tab bar: current profession, arrow, new profession, then Cancel (red apple) and Confirm (lime dye). */
	private static void confirm(GolemMenu m, Cell[] cells) {
		@Nullable Identifier cur = m.currentId();
		@Nullable Identifier nw = m.pending();
		String curName = nameOf(cur);
		String newName = nameOf(nw);
		set(cells, 2, 2, icon(iconOf(cur).stack(Component.literal(curName), lore("Current profession"), false)));
		set(cells, 2, 4, icon(GolemIcon.plain(Items.ARROW, Component.literal("Switch to " + newName), List.of(), false)));
		set(cells, 2, 6, icon(iconOf(nw).stack(Component.literal(newName), lore("New profession"), false)));
		set(cells, 4, 3, icon(GolemIcon.plain(Items.APPLE, GolemIcon.line("Cancel", ChatFormatting.RED), lore("Keep " + curName), false),
			(p, b) -> m.cancelConfirm()));
		set(cells, 4, 5, icon(GolemIcon.plain(Items.DYE.lime(), GolemIcon.line("Confirm", ChatFormatting.GREEN),
			lore("Switch to " + newName, "Items go to your inventory", "Targets are cleared"), false),
			(p, b) -> m.confirm(p)));
	}
}
