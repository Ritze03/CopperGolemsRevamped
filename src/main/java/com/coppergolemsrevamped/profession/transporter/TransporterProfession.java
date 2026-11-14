package com.coppergolemsrevamped.profession.transporter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;

public final class TransporterProfession {
	private TransporterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			CopperGolemsRevamped.id("transporter"),
			Component.literal("Transporter"),
			List.of(new SlotGroup("carry", 9, 64, s -> true)),
			List.of(
				new AssignSpec("input", Component.literal("Assign Chest A"), Kind.CHEST, 0),
				new AssignSpec("output", Component.literal("Assign Chest B"), Kind.CHEST, 0)),
			false,
			// stub: filled by I6
			(level, golem, data) -> {}));
	}
}
