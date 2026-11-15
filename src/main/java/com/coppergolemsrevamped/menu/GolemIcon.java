package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.CopperGolemsRevamped;

import it.unimi.dsi.fastutil.objects.ReferenceSortedSets;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
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
	HELD_EMPTY("held_empty", Items.GLASS_BOTTLE);

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

	/** Filler cell: a plain vanilla gray pane with no tooltip and no custom model (it must not read as an item). */
	public static ItemStack filler() {
		ItemStack s = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, ReferenceSortedSets.emptySet()));
		return s;
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
		return Component.literal(text).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY));
	}
}
