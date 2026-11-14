package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

public final class GolemService {
	private GolemService() {}

	public static void changeProfession(ServerLevel level, CopperGolem golem, @Nullable Identifier id) {
		GolemData data = GolemData.get(golem);
		drop(level, golem, data);
		data.assignments.clear();
		data.profession = id;
		Profession p = Professions.get(id);
		data.items = NonNullList.withSize(p == null ? 0 : p.inventorySize(), ItemStack.EMPTY);
		GolemData.save(golem, data);
	}

	/** Drops every stored item (death / statue). */
	public static void dropAll(ServerLevel level, CopperGolem golem) {
		GolemData data = GolemData.get(golem);
		drop(level, golem, data);
		GolemData.save(golem, data);
	}

	public static @Nullable Profession profession(CopperGolem golem) {
		return Professions.get(GolemData.get(golem).profession);
	}

	private static void drop(ServerLevel level, CopperGolem golem, GolemData data) {
		for (int i = 0; i < data.items.size(); i++) {
			ItemStack s = data.items.get(i);
			if (!s.isEmpty()) golem.spawnAtLocation(level, s);
			data.items.set(i, ItemStack.EMPTY);
		}
	}
}
