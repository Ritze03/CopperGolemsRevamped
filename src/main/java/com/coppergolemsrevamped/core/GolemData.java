package com.coppergolemsrevamped.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/** All per-golem mod state; stored as one persistent attachment. Always call {@link #save} after mutating. */
public class GolemData {
	public static final Codec<GolemData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Identifier.CODEC.optionalFieldOf("profession").forGetter(d -> Optional.ofNullable(d.profession)),
		Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).optionalFieldOf("assignments", Map.of()).forGetter(d -> d.assignments),
		ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(d -> d.items),
		Codec.BOOL.optionalFieldOf("following", false).forGetter(d -> d.following),
		UUIDUtil.CODEC.optionalFieldOf("follow_player").forGetter(d -> Optional.ofNullable(d.followPlayer))
	).apply(i, (p, a, it, f, u) -> {
		GolemData d = new GolemData();
		d.profession = p.orElse(null);
		d.assignments.putAll(a);
		d.items = NonNullList.of(ItemStack.EMPTY, it.toArray(new ItemStack[0]));
		d.following = f;
		d.followPlayer = u.orElse(null);
		return d;
	}));

	/** Null = no profession (vanilla behaviour). */
	public @Nullable Identifier profession;
	/** Keys are assign-button keys, e.g. "input"; AREA specs use key+"_a" / key+"_b". */
	public Map<String, BlockPos> assignments = new HashMap<>();
	/** The profession's inventory, sized from its declaration. */
	public NonNullList<ItemStack> items = NonNullList.create();
	public boolean following;
	public @Nullable UUID followPlayer;

	public static GolemData get(CopperGolem golem) {
		return golem.getAttachedOrCreate(ModAttachments.GOLEM_DATA);
	}

	public static void save(CopperGolem golem, GolemData data) {
		golem.setAttached(ModAttachments.GOLEM_DATA, data);
	}
}
