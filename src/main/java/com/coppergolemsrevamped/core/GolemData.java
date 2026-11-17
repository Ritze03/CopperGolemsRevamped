package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/**
 * All per-golem mod state; stored as one persistent attachment. Always call {@link #save} after mutating.
 * Decoding is lenient (see {@link #CODEC}) and {@link #items} is padded to the profession's slot count on load and on
 * every {@link #get} (see {@link #fitItems}).
 */
public class GolemData {
	/**
	 * {@code codec} that never fails to decode: on an error it keeps what could be read (the partial result, e.g. the
	 * good entries of a map) or falls back to {@code fallback}, and logs a warning naming {@code what}. Encoding is
	 * unchanged.
	 */
	public static <A> Codec<A> tolerant(Codec<A> codec, A fallback, String what) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
				DataResult<Pair<A, T>> r = codec.decode(ops, input);
				if (r.isSuccess()) return r;
				A kept = r.resultOrPartial().map(Pair::getFirst).orElse(fallback);
				CopperGolemsRevamped.LOGGER.warn("Golem {} could not be read fully ({}), kept: {}",
					what, r.error().map(e -> e.message()).orElse("?"), kept);
				return DataResult.success(Pair.of(kept, input));
			}

			@Override
			public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
				return codec.encode(value, ops, prefix);
			}
		};
	}

	/**
	 * One stack of {@link #items}. An undecodable stack (an item or component of a removed mod) keeps what the codec
	 * could read (partial result) or becomes empty, instead of failing the whole list.
	 * Why: a strict list codec fails on one bad element, the attachment decode fails with it and Fabric drops the
	 * whole attachment: one removed mod would wipe the golem's profession, assignments and every other item.
	 */
	public static final Codec<ItemStack> LENIENT_STACK = tolerant(ItemStack.OPTIONAL_CODEC, ItemStack.EMPTY, "item");

	/**
	 * Every field is lenient: missing (an old save) or malformed, it falls back to its default ({@code
	 * lenientOptionalFieldOf}, or {@link #tolerant} where a partial result is worth keeping: the good assignments, the
	 * good stacks). The items list is {@link #LENIENT_STACK} per element, so a bad stack never shifts the other slots.
	 * Why lenient: a strict field that fails fails the whole attachment, and Fabric then drops all of it (the golem
	 * loads as a fresh vanilla golem and its items are gone).
	 */
	public static final Codec<GolemData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Identifier.CODEC.lenientOptionalFieldOf("profession").forGetter(d -> Optional.ofNullable(d.profession)),
		tolerant(Codec.unboundedMap(Codec.STRING, BlockPos.CODEC), Map.<String, BlockPos>of(), "assignments")
			.optionalFieldOf("assignments", Map.of()).forGetter(d -> d.assignments),
		tolerant(LENIENT_STACK.listOf(), List.<ItemStack>of(), "item list").optionalFieldOf("items", List.of()).forGetter(d -> d.items),
		Codec.BOOL.lenientOptionalFieldOf("following", false).forGetter(d -> d.following),
		UUIDUtil.CODEC.lenientOptionalFieldOf("follow_player").forGetter(d -> Optional.ofNullable(d.followPlayer)),
		BlockPos.CODEC.lenientOptionalFieldOf("spawn_chest").forGetter(d -> Optional.ofNullable(d.spawnChest)),
		Codec.STRING.lenientOptionalFieldOf("auto_name").forGetter(d -> Optional.ofNullable(d.autoName)),
		Codec.BOOL.lenientOptionalFieldOf("frozen", false).forGetter(d -> d.frozen),
		Codec.INT.lenientOptionalFieldOf("xp", 0).forGetter(d -> d.xp),
		Codec.STRING.lenientOptionalFieldOf("filter_mode", FilterMode.OFF.id).forGetter(d -> d.filterMode.id),
		Codec.BOOL.lenientOptionalFieldOf("strip_logs", false).forGetter(d -> d.stripLogs),
		Codec.BOOL.lenientOptionalFieldOf("keep_layout", false).forGetter(d -> d.keepLayout),
		GlobalPos.CODEC.lenientOptionalFieldOf("home").forGetter(d -> Optional.ofNullable(d.home))
	).apply(i, (p, a, it, f, u, sc, an, fr, xp, fm, sl, kl, hm) -> {
		GolemData d = new GolemData();
		d.profession = p.orElse(null);
		d.assignments.putAll(a);
		d.items = NonNullList.of(ItemStack.EMPTY, it.toArray(new ItemStack[0]));
		d.following = f;
		d.followPlayer = u.orElse(null);
		d.spawnChest = sc.orElse(null);
		d.autoName = an.orElse(null);
		d.frozen = fr;
		d.xp = Math.max(0, xp);
		d.filterMode = FilterMode.parse(fm);
		d.stripLogs = sl;
		d.keepLayout = kl;
		d.home = hm.orElse(null);
		d.fitItems();
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
	public @Nullable UUID followPlayer;
	/**
	 * Chest Sorter home: where the golem works; it walks back when it strays too far (see superdoc/features/golem-safety.md).
	 * Null for other professions and for old saves (set on the first tick). Lenient optional in the codec.
	 */
	public @Nullable GlobalPos home;

	/** The chest the build pattern left where the copper block was; null for golems spawned any other way. */
	public @Nullable BlockPos spawnChest;
	/** The custom name this mod set (the profession name); null if none. A different current name is the player's. */
	public @Nullable String autoName;

	public static GolemData get(CopperGolem golem) {
		GolemData d = golem.getAttachedOrCreate(ModAttachments.GOLEM_DATA);
		d.fitItems();
		return d;
	}

	/**
	 * Professions whose own migration needs to see the short list: the Smelter detects an old 8/9-slot save by its size
	 * and then moves results out of the furnace buffers ({@code SmelterLogic:migrate}). Padding first would hide the old
	 * layout from it. It grows the list on its first tick (before anything else), so it never throws.
	 */
	private static final Set<Identifier> OWN_MIGRATION = Set.of(CopperGolemsRevamped.id("smelter"));

	/**
	 * Pads {@link #items} with empty slots up to the profession's declared slot count; never truncates. A longer list
	 * (a profession that lost slots in an update) keeps its extra stacks: nothing shows them, but they are handed out
	 * like every other stored item on a profession change, on death or when a statue is mined, so nothing is deleted.
	 * No-op for no profession or an unknown one (the list is kept as it is).
	 * Why central: a list shorter than the declaration made the profession tick throw (index out of bounds) and made
	 * {@code GolemMenu:stillValid} close the menu at once, so the player could not even switch profession; only some
	 * professions had their own migration.
	 * Exception: {@link #OWN_MIGRATION}.
	 */
	public void fitItems() {
		if (profession == null || OWN_MIGRATION.contains(profession)) return;
		Profession p = Professions.get(profession);
		if (p == null) return;
		int n = p.inventorySize();
		if (items.size() >= n) return;
		NonNullList<ItemStack> padded = NonNullList.withSize(n, ItemStack.EMPTY);
		for (int k = 0; k < items.size(); k++) padded.set(k, items.get(k));
		items = padded;
	}

	public static void save(CopperGolem golem, GolemData data) {
		golem.setAttached(ModAttachments.GOLEM_DATA, data);
	}
}
