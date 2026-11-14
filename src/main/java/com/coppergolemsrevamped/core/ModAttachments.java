package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.CopperGolemsRevamped;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public final class ModAttachments {
	public static final AttachmentType<GolemData> GOLEM_DATA = AttachmentRegistry.create(
		CopperGolemsRevamped.id("golem"),
		b -> b.persistent(GolemData.CODEC).initializer(GolemData::new));

	private ModAttachments() {}

	public static void init() {}
}
