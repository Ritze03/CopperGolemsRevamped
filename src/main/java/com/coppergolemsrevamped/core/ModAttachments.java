package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.CopperGolemsRevamped;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.world.item.ItemStack;

public final class ModAttachments {
	/**
	 * Per-golem state on the golem entity. The same type also sits on a copper golem statue block entity while the
	 * golem is a statue (see superdoc/features/oxidation.md): Fabric saves persistent block entity attachments with the
	 * chunk, so the data survives unloads and statue weathering/waxing/scraping (the block entity is kept).
	 */
	public static final AttachmentType<GolemData> GOLEM_DATA = AttachmentRegistry.create(
		CopperGolemsRevamped.id("golem"),
		b -> b.persistent(GolemData.CODEC).initializer(GolemData::new));

	/**
	 * Statue block entity only: the real item the golem held in its main hand (vanilla Chest Sorter carry) when it
	 * turned into a statue; given back on revival, dropped when the statue is mined. Never a display copy.
	 */
	public static final AttachmentType<ItemStack> STATUE_HELD = AttachmentRegistry.create(
		CopperGolemsRevamped.id("statue_held"),
		b -> b.persistent(GolemData.LENIENT_STACK));

	private ModAttachments() {}

	public static void init() {}
}
