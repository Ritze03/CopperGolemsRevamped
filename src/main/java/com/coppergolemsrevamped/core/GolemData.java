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
		UUIDUtil.CODEC.optionalFieldOf("follow_player").forGetter(d -> Optional.ofNullable(d.followPlayer)),
		BlockPos.CODEC.optionalFieldOf("spawn_chest").forGetter(d -> Optional.ofNullable(d.spawnChest)),
		Codec.STRING.optionalFieldOf("auto_name").forGetter(d -> Optional.ofNullable(d.autoName)),
		Codec.BOOL.optionalFieldOf("frozen", false).forGetter(d -> d.frozen),
		Codec.INT.optionalFieldOf("xp", 0).forGetter(d -> d.xp),
		Codec.STRING.optionalFieldOf("filter_mode", FilterMode.OFF.id).forGetter(d -> d.filterMode.id),
		Codec.BOOL.optionalFieldOf("strip_logs", false).forGetter(d -> d.stripLogs),
		Codec.BOOL.optionalFieldOf("keep_layout", false).forGetter(d -> d.keepLayout),
		Codec.BOOL.optionalFieldOf("save_tools", true).forGetter(d -> d.saveTools)
	).apply(i, (p, a, it, f, u, sc, an, fr, xp, fm, sl, kl, st) -> {
		GolemData d = new GolemData();
		d.profession = p.orElse(null);
		d.assignments.putAll(a);
		d.items = NonNullList.of(ItemStack.EMPTY, it.toArray(new ItemStack[0]));
		d.following = f;
		d.followPlayer = u.orElse(null);
		d.spawnChest = sc.orElse(null);
		d.autoName = an.orElse(null);
		d.frozen = fr;
		d.xp = xp;
		d.filterMode = FilterMode.parse(fm);
		d.stripLogs = sl;
		d.keepLayout = kl;
		d.saveTools = st;
		return d;
	}));

	/** Null = no profession (vanilla behaviour). */
	public @Nullable Identifier profession;
	/** Keys are assign-button keys, e.g. "input"; AREA specs use key+"_a" / key+"_b". */
	public Map<String, BlockPos> assignments = new HashMap<>();
	/** The profession's inventory, sized from its declaration. */
	public NonNullList<ItemStack> items = NonNullList.create();
	public boolean following;
	/** Freeze: the golem does nothing and stands still (see superdoc/features/freeze.md). Cleared on a profession change. */
	public boolean frozen;
	/** Stored experience points (Butcher kills, Smelter furnaces), paid out as bottles; see superdoc/features/xp-bottles.md. */
	public int xp;
	/** Transporter filter mode; the filter items themselves are the profession's {@code filter} slot group in {@link #items}. */
	public FilterMode filterMode = FilterMode.OFF;
	/** Lumber setting: strip each log with the axe before breaking it (see superdoc/features/lumber.md). Default off. */
	public boolean stripLogs;
	/** Keep layout: never empty a stack, never fill an empty slot in a container (see superdoc/features/keep-layout.md). Default off. */
	public boolean keepLayout;
	/** Save tools (Lumber, Farmer, Butcher): enchanted tools are put away before they break (see superdoc/features/mending.md). Default on. */
	public boolean saveTools = true;
	public @Nullable UUID followPlayer;

	/** The chest the build pattern left where the copper block was; null for golems spawned any other way. */
	public @Nullable BlockPos spawnChest;
	/** The custom name this mod set (the profession name); null if none. A different current name is the player's. */
	public @Nullable String autoName;

	public static GolemData get(CopperGolem golem) {
		return golem.getAttachedOrCreate(ModAttachments.GOLEM_DATA);
	}

	public static void save(CopperGolem golem, GolemData data) {
		golem.setAttached(ModAttachments.GOLEM_DATA, data);
	}
}
