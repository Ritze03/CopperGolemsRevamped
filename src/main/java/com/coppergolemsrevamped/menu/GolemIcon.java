package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.DisplayItems;

import it.unimi.dsi.fastutil.objects.ReferenceSortedSets;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * GUI icons: a vanilla base item plus a {@code CUSTOM_MODEL_DATA} string. The base item must match the
 * {@code assets/minecraft/items/<base>.json} override that maps the string to the custom model
 * (see superdoc/features/gui.md); clients without the resource pack see the plain base item.
 */
public enum GolemIcon {
	PROFESSION_NONE("profession_none", Items.DYE.lightGray()),
	PROFESSION_TRANSPORTER("profession_transporter", Items.CHEST_MINECART),
	PROFESSION_CHEST_SORTER("profession_chest_sorter", Items.COMPARATOR),
	PROFESSION_CLEANER("profession_cleaner", Items.HOPPER),
	PROFESSION_CRAFTER("profession_crafter", Items.CRAFTING_TABLE),
	PROFESSION_FARMER("profession_farmer", Items.IRON_HOE),
	PROFESSION_LUMBER("profession_lumber", Items.IRON_AXE),
	FOLLOW_ON("follow_on", Items.LEAD),
	FOLLOW_OFF("follow_off", Items.LEAD),
	ASSIGN_CHEST("assign_chest", Items.CHEST),
	ASSIGN_CRAFTING_TABLE("assign_crafting_table", Items.CRAFTING_TABLE),
	ASSIGN_BLOCK("assign_block", Items.TARGET),
	ASSIGN_AREA("assign_area", Items.MAP),
	ASSIGN_INPUT("assign_input", Items.CHEST),
	ASSIGN_OUTPUT("assign_output", Items.CHEST),
	ASSIGN_TOOLS("assign_tools", Items.IRON_PICKAXE),
	ASSIGN_SEEDS("assign_seeds", Items.WHEAT_SEEDS),
	ASSIGN_TREE("assign_tree", Items.OAK_SAPLING),
	HELP("help", Items.BOOK),
	BACK("icon_back", Items.ARROW),
	STATUS("icon_status", Items.WRITABLE_BOOK),
	INVENTORY("icon_inventory", Items.CHEST),
	SETTINGS("icon_settings", Items.COMPARATOR),
	CRAFT_ARROW("craft_arrow", Items.SPECTRAL_ARROW);

	public final String iconName;
	public final Item base;

	GolemIcon(String iconName, Item base) {
		this.iconName = iconName;
		this.base = base;
	}

	/** Profession icon by id path ({@code profession_<path>}), {@link #PROFESSION_NONE} when there is none. */
	public static GolemIcon profession(String path) {
		for (GolemIcon i : values()) if (i.iconName.equals("profession_" + path)) return i;
		return PROFESSION_NONE;
	}

	/** Icon by exact name, or null. */
	public static GolemIcon byName(String name) {
		for (GolemIcon i : values()) if (i.iconName.equals(name)) return i;
		return null;
	}

	/** Filler cell: a plain vanilla orange pane with no tooltip and no custom model (it must not read as an item). */
	public static ItemStack filler() {
		return pane(Items.STAINED_GLASS_PANE.orange());
	}

	/** The black pane beside the tab bar: marks the top row as another part of the UI than the tab content. */
	public static ItemStack barFiller() {
		return pane(Items.STAINED_GLASS_PANE.black());
	}

	private static ItemStack pane(Item pane) {
		ItemStack s = new ItemStack(pane);
		s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, ReferenceSortedSets.emptySet()));
		return s;
	}

	/** A plain vanilla item as an icon (no custom model data): name and lore only. */
	public static ItemStack plain(Item item, Component name, List<Component> lore, boolean glint) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.ITEM_NAME, name);
		s.set(DataComponents.LORE, new ItemLore(lore));
		s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, ReferenceSortedSets.singleton(DataComponents.ATTRIBUTE_MODIFIERS)));
		if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}

	/** Icon for a {@link com.coppergolemsrevamped.profession.Profession.Label}: a mod icon name, else a vanilla item id. */
	public static ItemStack label(String icon, String name, List<Component> lore) {
		GolemIcon g = byName(icon);
		Component n = Component.literal(name);
		if (g != null) return g.stack(n, lore, false);
		return plain(BuiltInRegistries.ITEM.getValue(Identifier.parse(icon)), n, lore, false);
	}

	/**
	 * A display-only copy of {@code like} (see {@link DisplayItems}) named "&lt;item&gt; (note)" in gray, with the given
	 * lore. Never a real stack: it is marked and only ever returned from {@code Slot#getItem}, never stored.
	 */
	public static ItemStack ghost(ItemStack like, String note, List<Component> lore) {
		ItemStack s = like.copy();
		s.set(DataComponents.CUSTOM_NAME, Component.literal(like.getHoverName().getString() + " (" + note + ")")
			.withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)));
		s.set(DataComponents.LORE, new ItemLore(lore));
		return DisplayItems.mark(s);
	}

	public ItemStack stack(Component name, List<Component> lore, boolean glint) {
		ItemStack s = new ItemStack(base);
		s.set(DataComponents.CUSTOM_MODEL_DATA, model());
		s.set(DataComponents.ITEM_NAME, name);
		s.set(DataComponents.LORE, new ItemLore(lore));
		// tools and similar base items would otherwise list their attribute modifiers
		s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, ReferenceSortedSets.singleton(DataComponents.ATTRIBUTE_MODIFIERS)));
		if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}

	private CustomModelData model() {
		return new CustomModelData(List.of(), List.of(), List.of(CopperGolemsRevamped.MOD_ID + ":" + iconName), List.of());
	}

	/** One lore line: not italic, gray. */
	public static Component line(String text) {
		return line(text, ChatFormatting.GRAY);
	}

	/** One lore line or name in the given color, not italic. */
	public static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(Style.EMPTY.withItalic(false).withColor(color));
	}
}
