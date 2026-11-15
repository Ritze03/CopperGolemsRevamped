package com.coppergolemsrevamped.profession;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;

public final class Professions {
	private static final Map<Identifier, Profession> REGISTRY = new LinkedHashMap<>();

	private Professions() {}

	public static void register(Profession p) {
		p = ProfessionHelp.apply(p);
		p.validateLayout();
		if (REGISTRY.putIfAbsent(p.id(), p) != null) throw new IllegalStateException("Duplicate profession " + p.id());
	}

	public static @Nullable Profession get(@Nullable Identifier id) {
		return id == null ? null : REGISTRY.get(id);
	}

	/** In registration order. */
	public static Collection<Profession> all() {
		return REGISTRY.values();
	}
}
