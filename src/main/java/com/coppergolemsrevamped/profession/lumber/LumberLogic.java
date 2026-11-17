package com.coppergolemsrevamped.profession.lumber;

import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.ToolSwing;
import com.coppergolemsrevamped.ai.TransferBudget;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemMending;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lumber work cycle, an explicit phase machine ({@link Phase}): PLANT, BONEMEAL, FELL, PICKUP, DELIVER, then PLANT again.
 * One assigned tree spot T (the clicked block: sapling or bottom trunk). The golem detects the whole tree (every
 * connected log of the same kind, 2x2 trunks and branches included), walks to a ground spot next to the trunk
 * ({@link Interact#approach}) and fells it from the ground: it never climbs and never moves into the trunk. It never
 * stands in the footprint of a growing tree ({@link #standOutside}). Every break goes
 * through {@link #breakBlock}. See superdoc/features/lumber.md.
 */
public final class LumberLogic {
	private LumberLogic() {}

	static final int RETRY_TICKS = 40;
	static final int SCAN_TICKS = 20;
	/** A log takes at least this many swings (each {@link ToolSwing#PERIOD} ticks), however fast the axe is. */
	static final int MIN_SWINGS = 2;
	/** Strip logs: ticks the stripped log stays visible before its break starts (see lumber.md "Strip logs"). */
	public static final int STRIP_TICKS = 8;
	static final int AFTER_ACTION_TICKS = 5;
	static final int REPATH_TICKS = 20;
	static final double SPEED = 1.0;
	/** Walking to one target this long without getting closer = unreachable (10 s). */
	static final int GIVE_UP_TICKS = 200;
	/** How long a given-up tree, chest or item is ignored (1 min). */
	static final int SKIP_TICKS = 1200;
	static final double PROGRESS_EPS = 0.1;
	/** The sapling slot is topped up from the input chest when it holds fewer than this. */
	static final int SAPLING_LOW = 16;
	/** Bone meal is fetched when the golem holds fewer than this, up to {@link #BONE_TARGET}. */
	static final int BONE_LOW = 4;
	static final int BONE_TARGET = 16;
	/** Ticks between two bone meal uses. */
	static final int BONE_DELAY = 10;
	/**
	 * Bone meal uses in a row that leave the sapling unchanged before the golem stops bone-mealing it (an obstructed sapling:
	 * no room to grow). A free sapling changes on 45% of the uses, so 16 misses in a row happen by chance 0.55^16 = 7e-5.
	 */
	static final int BONE_MAX_FAILS = 16;
	/** While walking during FELL the tree found last is reused for this many ticks instead of a flood fill per tick. */
	static final int REDETECT_TICKS = 20;
	/** A drop still under its pickup delay counts as coming (waited for, not taken) only while younger than this. */
	static final int PENDING_AGE = 60;

	/** Tree detection bounds: horizontal radius around T, height above T, block count. */
	static final int TREE_RADIUS = 8;
	public static final int TREE_HEIGHT = 32;
	static final int TREE_MAX_LOGS = 256;
	/** The golem may break a log whose horizontal distance to it is at most this (it reaches up, not sideways). */
	public static final double REACH_H = 5.5;
	/** Logs within this horizontal distance of T are worked on from T's stand spot, farther ones from a spot nearer to them. */
	static final double NEAR_TREE = 3.5;
	/** Ground-drop area around T: horizontal radius, blocks below T, blocks above T. */
	static final int ITEM_RADIUS = 8;
	static final int ITEM_BELOW = 1;
	static final int ITEM_ABOVE = 4;
	static final double PICKUP_RANGE_SQR = 1.5 * 1.5;
	/**
	 * Pickup range for a drop in the danger area (inside the sapling footprint, or behind it with no safe way round), taken from
	 * a safe cell outside the footprint. Smallest round value that covers the worst case: the far corner cell of a 2x2
	 * footprint seen from the opposite corner of the adjacent ring (2 * sqrt(2) = 2.83 blocks, squared 8, plus a little height).
	 */
	static final double DANGER_PICKUP_RANGE_SQR = 3.0 * 3.0;

	/** Ticks without a drop in the pickup box that end PICKUP (leaves decay slowly, see lumber.md). */
	static final int PICKUP_QUIET = 150;
	/** PICKUP ends after this long even if drops keep appearing (1 min). */
	static final int PICKUP_MAX = 1200;
	/** Ticks without progress towards a stand spot before that spot is dropped (3 s). */
	static final int SPOT_STUCK_TICKS = 60;
	/** Chebyshev distance (blocks) from the grow footprint to plant and bone-meal from, and to wait at. */
	static final int WORK_MIN = 1, WORK_MAX = 2;

	public enum Result { BROKEN, FULL, DENIED }

	/** The work cycle: PLANT, BONEMEAL, FELL, PICKUP, DELIVER, then PLANT again. See lumber.md. */
	public enum Phase { PLANT, BONEMEAL, FELL, PICKUP, DELIVER }

	/** Test hook: called on every phase change (transient state, a reload restarts at PICKUP or FELL). */
	public static @Nullable BiConsumer<CopperGolem, Phase> phaseListener;

	public static @Nullable Phase phaseOf(CopperGolem golem) {
		State st = STATES.get(golem);
		return st == null ? null : st.phase;
	}

	private static final class State {
		/** A supply fetch was cut short by the transfer budget: it goes on to its full target. */
		boolean topUp;
		/** The tree spot this state was built for; a different spot (reassigned tree) starts a fresh state. */
		@Nullable BlockPos tree;
		int cooldown;
		final Interact.Approach walk = new Interact.Approach();
		/** Walk progress tracking (the target being walked to, best squared distance, ticks without progress). */
		@Nullable Object goalKey;
		double bestDist;
		int stuck;
		@Nullable BlockPos breakPos;
		/** Strip logs: the log that was just stripped and the ticks left until its break starts. */
		@Nullable BlockPos stripPos;
		int stripLeft;
		int breakLeft;
		/** Length of the current break in ticks (whole {@link ToolSwing} swings). */
		int breakTotal;
		/** The next drop did not fit into the carry slots: deliver before felling on. */
		boolean mustDeliver;
		/** The golem has walked to its stand spot for the current felling round. */
		boolean anchored;
		/** Logs seen at the last detection: seeds for the next one (T itself may already be gone). */
		Set<BlockPos> known = new HashSet<>();
		@Nullable Set<BlockPos> logs;
		/** Game time of the last {@link #detectTree} flood (reused while walking, see {@link #REDETECT_TICKS}). */
		long detectedAt;
		/** Bone meal uses in a row that left the sapling unchanged ({@link #BONE_MAX_FAILS}: it cannot grow, stop). */
		int boneFails;
		/** Bottom layer of the felled tree (4 cells for a 2x2 trunk), else empty: where to replant. */
		List<BlockPos> footprint = List.of();
		/** Logs this golem could not reach from a spot it tried (tried again once the tree is gone and a new one grows). */
		final Set<BlockPos> unreach = new HashSet<>();
		int boneCooldown;
		/** Given-up targets (tree spot, chest or item UUID) -> game time until which they are ignored. */
		final Map<Object, Long> skip = new HashMap<>();
		/** Item chase tracking. */
		@Nullable ItemEntity chasing;
		int chaseRepath;
		/** Current phase of the cycle; null until the first tick decides it. */
		@Nullable Phase phase;
		/** DELIVER: the input chest (surplus saplings / bone meal) and the output chest were each visited once. */
		boolean inputTried, outputTried;
		/** PICKUP: when it began and when a drop was last seen in the box. */
		long pickupStart, lastSeen;
		/** Stand spot outside the grow footprint being walked to (see standOutside). */
		@Nullable BlockPos spot;
		int spotKey, spotStuck, spotRepath;
		double spotBest;
		final Set<BlockPos> spotBad = new HashSet<>();
	}

	private static final Map<CopperGolem, State> STATES = new WeakHashMap<>();

	/** Drops this golem's transient state ({@link com.coppergolemsrevamped.profession.Profession.Forget}). */
	public static void forget(CopperGolem golem) {
		STATES.remove(golem);
	}

	// ---- pure rules (public for tests) ----------------------------------------------------------------------------

	/**
	 * THE break rule: a golem at ({@code gx}, {@code gz}) may break {@code pos} only when it is a log of the detected
	 * {@code tree} and at most {@link #REACH_H} away horizontally. Height is free: the golem reaches up the trunk.
	 */
	public static boolean breakAllowed(double gx, double gz, Set<BlockPos> tree, BlockPos pos) {
		if (!tree.contains(pos)) return false;
		double dx = gx - (pos.getX() + 0.5), dz = gz - (pos.getZ() + 0.5);
		return dx * dx + dz * dz <= REACH_H * REACH_H;
	}

	/**
	 * Ticks to break a block with a tool: the vanilla formula {@code ceil(hardness * 30 / speed)} (30 = correct tool; no
	 * enchantments, no on-ground/in-water modifiers), at least 1.
	 */
	public static int breakTicks(float hardness, float speed) {
		return Math.max(1, (int) Math.ceil(hardness * 30.0F / Math.max(speed, 0.01F)));
	}

	public static boolean isSapling(ItemStack s) {
		return s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem;
	}

	static boolean isAxe(ItemStack s) {
		return s.is(ItemTags.AXES);
	}

	static boolean isBone(ItemStack s) {
		return s.is(Items.BONE_MEAL);
	}

	/** Saplings that only grow into a tree as a 2x2 group of four. */
	static boolean needs2x2(ItemStack s) {
		return s.is(Items.DARK_OAK_SAPLING) || s.is(Items.PALE_OAK_SAPLING);
	}

	/** Saplings that can grow a 2x2 mega tree (spruce, jungle) or must (dark oak, pale oak). */
	static boolean canBe2x2(ItemStack s) {
		return needs2x2(s) || s.is(Items.SPRUCE_SAPLING) || s.is(Items.JUNGLE_SAPLING);
	}

	/**
	 * The tree around T: every log of one kind connected (26-neighbourhood) to a seed (T if it is a log, plus the
	 * {@code known} logs of the previous scan that still stand). Never below T's Y, horizontally at most
	 * {@link #TREE_RADIUS} from T, at most {@link #TREE_HEIGHT} above it, at most {@link #TREE_MAX_LOGS} blocks. Logs of
	 * another wood kind are not part of the tree, so a neighbouring tree or a house of other wood is left alone.
	 */
	public static Set<BlockPos> detectTree(Level level, BlockPos t, Collection<BlockPos> known) {
		Set<BlockPos> found = new LinkedHashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> seeds = new ArrayList<>();
		if (level.getBlockState(t).is(BlockTags.LOGS)) seeds.add(t);
		for (BlockPos k : known) if (!k.equals(t) && level.getBlockState(k).is(BlockTags.LOGS)) seeds.add(k);
		if (seeds.isEmpty()) return found;
		Block wood = woodKind(level, seeds.getFirst());
		for (BlockPos sd : seeds) {
			if (woodKind(level, sd) == wood && found.add(sd)) queue.add(sd);
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty() && found.size() < TREE_MAX_LOGS) {
			BlockPos p = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dy == 0 && dz == 0) continue;
				m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
				if (m.getY() < t.getY() || m.getY() > t.getY() + TREE_HEIGHT) continue;
				if (Math.abs(m.getX() - t.getX()) > TREE_RADIUS || Math.abs(m.getZ() - t.getZ()) > TREE_RADIUS) continue;
				if (!level.getBlockState(m).is(BlockTags.LOGS) || woodKind(level, m) != wood) continue;
				BlockPos q = m.immutable();
				if (found.add(q)) queue.add(q);
				if (found.size() >= TREE_MAX_LOGS) return found;
			}
		}
		return found;
	}

	/**
	 * The wood kind of the log at {@code pos}: its stripped block (vanilla's axe mapping), or the block itself when it has none
	 * (already stripped). A plain log and its stripped form therefore are the same kind, so a log stripped before its break
	 * stays part of its tree (see lumber.md "Strip logs").
	 */
	static Block woodKind(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (defaultAxe == null) defaultAxe = Items.IRON_AXE.getDefaultInstance(); // lazy: item components are bound after mod init
		Holder<BlockTransformer> h = defaultAxe.get(DataComponents.BLOCK_TRANSFORMER);
		if (h == null) return st.getBlock();
		if (h.value() != woodKindOf) { // a data reload replaced the transformer: the cached mapping may be stale
			WOOD_KIND.clear();
			woodKindOf = h.value();
		}
		Block cached = WOOD_KIND.get(st.getBlock());
		if (cached != null) return cached;
		Block kind = st.getBlock();
		for (BlockTransformer.BlockTransformData t : h.value().transforms()) {
			BlockState r = t.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
			if (r != null && r.is(BlockTags.LOGS) && r.getBlock() != st.getBlock()) {
				kind = r.getBlock();
				break;
			}
		}
		WOOD_KIND.put(st.getBlock(), kind);
		return kind;
	}

	/** The axe whose transformer defines the wood kinds, created once (see {@link #woodKind}). */
	private static @Nullable ItemStack defaultAxe;
	/**
	 * {@link #woodKind} per block: the axe mapping is a fixed block-to-block table, so one evaluation per block type is enough
	 * (detectTree asks for every log of up to 256 per flood). Keyed to the transformer it was computed from.
	 */
	private static final Map<Block, Block> WOOD_KIND = new IdentityHashMap<>();
	private static @Nullable BlockTransformer woodKindOf;

	/** The 2x2 square of {@code logs} at T's level that contains T (its 4 cells), or just T. */
	static List<BlockPos> bottomSquare(Set<BlockPos> logs, BlockPos t) {
		for (int a = -1; a <= 0; a++) for (int b = -1; b <= 0; b++) {
			List<BlockPos> sq = square(t.getX() + a, t.getY(), t.getZ() + b);
			if (logs.containsAll(sq)) return sq;
		}
		return List.of(t);
	}

	private static List<BlockPos> square(int x, int y, int z) {
		return List.of(new BlockPos(x, y, z), new BlockPos(x + 1, y, z), new BlockPos(x, y, z + 1), new BlockPos(x + 1, y, z + 1));
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	public static void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		State s = STATES.computeIfAbsent(golem, g -> new State());
		if (s.cooldown > 0) {
			s.cooldown--;
			return;
		}
		BlockPos t = data.assignments.get(LumberProfession.TREE);
		BlockPos outPos = data.assignments.get(LumberProfession.OUTPUT);
		Container out = outPos == null ? null : TransporterLogic.containerAt(level, outPos);
		if (t == null || out == null || !Interact.loaded(level, t)) { // an unloaded tree spot is not available right now: wait
			golem.getNavigation().stop();
			s.cooldown = RETRY_TICKS;
			return;
		}
		if (!t.equals(s.tree)) { // tree reassigned (or first tick): drop stale progress, skips and delivery flag
			s = new State();
			s.tree = t;
			STATES.put(golem, s);
		}
		BlockPos mark = data.assignments.get(LumberProfession.SITE_2X2);
		if (mark != null && !mark.equals(t)) { // the tree spot was reassigned: the learned size is void
			data.assignments.remove(LumberProfession.SITE_2X2);
			GolemData.save(golem, data);
		}
		long now = level.getGameTime();
		s.skip.values().removeIf(until -> until <= now);
		boolean treeSkipped = s.skip.containsKey(t);

		Set<BlockPos> logs;
		if (treeSkipped) {
			logs = Set.of();
		} else if (s.breakPos != null && s.logs != null && level.getBlockState(s.breakPos).is(BlockTags.LOGS)) {
			logs = s.logs; // mid break: the tree does not change under us, no need to flood again
		} else if (s.logs != null && !s.logs.isEmpty() && s.phase == Phase.FELL && now - s.detectedAt < REDETECT_TICKS
			&& (!s.anchored || s.stripLeft > 0 && s.stripPos != null && level.getBlockState(s.stripPos).is(BlockTags.LOGS))) {
			logs = s.logs; // walking (or the strip pause): reuse the last flood; arrival (anchored) or REDETECT_TICKS floods again
		} else {
			logs = detectTree(level, t, s.known);
			s.detectedAt = now;
			if (s.known.isEmpty() && !logs.isEmpty()) { // a tree appears: remember its footprint for replanting
				s.footprint = bottomSquare(logs, t);
				s.unreach.clear();
				s.anchored = false;
				s.boneFails = 0;
				if (s.footprint.size() == 4) markSite2x2(data, t); // saved by the next GolemData.save
			}
			s.known = new HashSet<>(logs);
			s.logs = logs;
		}
		boolean felling = !logs.isEmpty();
		BlockPos inPos = data.assignments.get(LumberProfession.INPUT);
		Container in = inPos == null || s.skip.containsKey(inPos) ? null : TransporterLogic.containerAt(level, inPos);

		if (s.phase == null) { // fresh state: a standing tree is felled, else clean up around it first
			if (felling) {
				setPhase(s, golem, Phase.FELL, now);
			} else {
				setPhase(s, golem, Phase.PICKUP, now);
				s.lastSeen = now - PICKUP_QUIET; // nothing to wait for if nothing lies there
			}
		}
		Phase ph = s.phase;
		if (felling && (ph == Phase.PLANT || ph == Phase.BONEMEAL)) { // a tree is there (grown, or from the start)
			setPhase(s, golem, Phase.FELL, now);
		} else if (treeSkipped && (ph == Phase.PLANT || ph == Phase.BONEMEAL || ph == Phase.FELL)) {
			if (carryEmpty(data)) { // given up on the tree: only the carry is still worth a trip
				idle(s, golem);
				return;
			}
			setPhase(s, golem, Phase.DELIVER, now);
		}
		switch (s.phase) {
			case PLANT -> tickPlant(s, level, golem, data, t, in, inPos, now);
			case BONEMEAL -> tickBoneMeal(s, level, golem, data, t, in, inPos, now);
			case FELL -> tickFell(s, level, golem, data, t, logs, in, inPos, outPos, out, now);
			case PICKUP -> tickPickup(s, level, golem, data, t, now);
			case DELIVER -> {
				if (deliverStep(s, level, golem, data, inPos, in, outPos, out, now)) {
					s.anchored = false;
					setPhase(s, golem, Phase.PLANT, now);
				}
			}
		}
	}

	private static void setPhase(State s, CopperGolem golem, Phase p, long now) {
		if (s.phase == p) return;
		s.phase = p;
		s.inputTried = false;
		s.outputTried = false;
		s.topUp = false;
		s.chasing = null;
		s.spot = null;
		s.spotBad.clear();
		if (p == Phase.PICKUP || p == Phase.BONEMEAL) {
			s.pickupStart = now;
			s.lastSeen = now;
		}
		BiConsumer<CopperGolem, Phase> l = phaseListener;
		if (l != null) l.accept(golem, p);
	}

	/** PLANT: nothing to do when a sapling stands or a tree is there; else fetch saplings, plant, or look for drops first. */
	private static void tickPlant(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, @Nullable Container in, @Nullable BlockPos inPos, long now) {
		if (isGrowing(level, t, s) && !partial(level, s)) {
			setPhase(s, golem, Phase.BONEMEAL, now);
			return;
		}
		if (in != null && wantsFetch(level, data, in, false, true, false, s.topUp)) {
			fetchVisit(s, level, golem, data, in, inPos, now, false, true, false);
			return;
		}
		if (plant(s, level, golem, data, t, now)) return;
		// cannot plant (no saplings, a 2x2 site waiting for 4, blocked): drops (saplings from leaves) may help, then deliver
		setPhase(s, golem, Phase.PICKUP, now);
	}

	/** BONEMEAL: collect drops, then bone meal on the sapling(s) until the tree grows (FELL is entered by the tick); else wait for natural growth. */
	private static void tickBoneMeal(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, @Nullable Container in, @Nullable BlockPos inPos, long now) {
		if (!isGrowing(level, t, s)) { // the sapling is gone: back through pickup and delivery to planting
			setPhase(s, golem, Phase.PICKUP, now);
			return;
		}
		boolean bone = boneTarget(level, t, s) != null; // no bone meal fetched for a sapling that cannot grow
		if (in != null && bone && wantsFetch(level, data, in, false, false, true, s.topUp)) {
			fetchVisit(s, level, golem, data, in, inPos, now, false, false, true);
			return;
		}
		List<BlockPos> fp = footprintCells(level, t, s);
		// 1. every reachable drop first: with bone meal the tree is up in seconds and decaying leaves would otherwise drop their saplings unseen
		if (collect(s, level, golem, data, t, fp, now)) return;
		// 2. low sapling stock: do not out-run the leaves, wait for natural-speed drops (until a spare sapling is there or they stop)
		boolean starving = saplingStock(data, in) <= (fp.size() == 4 ? 4 : 1)
			&& now - s.lastSeen < PICKUP_QUIET && now - s.pickupStart < PICKUP_MAX;
		if (!starving && boneMeal(s, level, golem, data, t, now)) return;
		if (mendVisit(s, level, golem, data, in, inPos, now)) return; // the idle time repairs Mending axes, however little they miss
		if (!standOutside(s, level, golem, t, fp, WORK_MIN, WORK_MAX, now)) return;
		idle(s, golem);
	}

	/** FELL: axe fetch, then the felling steps; a full carry delivers mid-tree. */
	private static void tickFell(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, Set<BlockPos> logs, @Nullable Container in,
		@Nullable BlockPos inPos, BlockPos outPos, Container out, long now) {
		if (logs.isEmpty()) {
			s.breakPos = null;
			s.anchored = false;
			s.mustDeliver = false;
			ToolSwing.end(level, golem);
			setPhase(s, golem, Phase.DELIVER, now);
			return;
		}
		boolean fetch = in != null && wantsFetch(level, data, in, true, false, false, false);
		boolean bare = axeSlot(data) < 0 && mendingBlocked(data, in);
		if (fetch || (axeSlot(data) < 0 && !bare) || s.mustDeliver) ToolSwing.end(level, golem); // not chopping now: back to IDLE at once
		if (fetch) {
			fetchVisit(s, level, golem, data, in, inPos, now, true, false, false);
			return;
		}
		if (axeSlot(data) < 0 && !bare) { // no axe, no work (a resting enchanted axe: bare hands, see mending.md)
			idle(s, golem);
			return;
		}
		if (s.mustDeliver) {
			if (deliverStep(s, level, golem, data, inPos, in, outPos, out, now)) {
				s.mustDeliver = false;
				s.anchored = false;
			}
			return;
		}
		fell(s, level, golem, data, t, logs, bare, now);
	}

	/**
	 * PICKUP: collect the drops around the tree. Ends when no drop was seen for {@link #PICKUP_QUIET} ticks or after
	 * {@link #PICKUP_MAX} ticks; in between the golem waits.
	 */
	private static void tickPickup(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		if (now - s.pickupStart >= PICKUP_MAX) {
			setPhase(s, golem, Phase.DELIVER, now);
			return;
		}
		if (collect(s, level, golem, data, t, isGrowing(level, t, s) ? footprintCells(level, t, s) : null, now)) return;
		if (now - s.lastSeen >= PICKUP_QUIET) {
			setPhase(s, golem, Phase.DELIVER, now);
			return;
		}
		if (isGrowing(level, t, s) && !standOutside(s, level, golem, t, footprintCells(level, t, s), WORK_MIN, WORK_MAX, now)) return;
		idle(s, golem);
	}

	private static void idle(State s, CopperGolem golem) {
		golem.getNavigation().stop();
		s.cooldown = SCAN_TICKS;
	}

	private static void done(State s, CopperGolem golem, GolemData data, int cooldown) {
		golem.getNavigation().stop();
		s.cooldown = cooldown;
		GolemData.save(golem, data);
	}

	/** Walk to the input chest and fetch what the phase needs (axe, saplings, bone meal). */
	private static void fetchVisit(State s, ServerLevel level, CopperGolem golem, GolemData data, Container in, BlockPos inPos, long now,
		boolean axe, boolean saplings, boolean bone) {
		if (!reach(s, level, golem, inPos, inPos, now)) return;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
		int took = 0;
		if (axe) {
			// Mending: bottles from the input chest repair the axe (and Mending axes lying there), 1 item per bottle
			int b = GolemMending.repair(level, data, data.items.get(LumberProfession.AXE_SLOT), in, LumberLogic::isAxe, TransferBudget.remaining(level, golem));
			TransferBudget.spend(level, golem, b);
			took += b;
			if (TransferBudget.remaining(level, golem) >= 1 && fetchAxe(in, data)) {
				took++; // a swap counts as one item
				TransferBudget.spend(level, golem, 1);
			} else if (TransferBudget.remaining(level, golem) >= 1 && GolemMending.mustPark(data.items.get(LumberProfession.AXE_SLOT))
				&& GolemMending.park(in, data.items.get(LumberProfession.AXE_SLOT))) {
				data.items.set(LumberProfession.AXE_SLOT, ItemStack.EMPTY); // no other axe: it rests in the chest, the golem chops by hand
				took++;
				TransferBudget.spend(level, golem, 1);
			}
		}
		if (saplings) {
			int n = fetchSupply(in, data, LumberLogic::isSapling, 64, TransferBudget.remaining(level, golem));
			TransferBudget.spend(level, golem, n);
			took += n;
		}
		if (bone) {
			int n = fetchSupply(in, data, LumberLogic::isBone, BONE_TARGET, TransferBudget.remaining(level, golem));
			TransferBudget.spend(level, golem, n);
			took += n;
		}
		s.anchored = false;
		// budget used up while the golem still wants more: stay, keep the lid open, go on next window (not "chest empty")
		boolean pending = TransferBudget.exhausted(level, golem) && wantsFetch(level, data, in, axe, saplings, bone, true);
		s.topUp = pending;
		if (took > 0 || pending) Interact.openChest(level, golem, inPos, Interact.Action.TAKE);
		if (!pending) s.walk.reset();
		done(s, golem, data, pending ? TransferBudget.waitTicks(level) : took > 0 ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
	}

	// ---- Mending while waiting -----------------------------------------------------------------------------------

	private static boolean wantsTopUp(ServerLevel level, GolemData data, Container in) {
		return GolemMending.canRepair(level, data, data.items.get(LumberProfession.AXE_SLOT), in, LumberLogic::isAxe, true);
	}

	/** While waiting for growth: walk to the input chest and top up damaged Mending axes. True when this tick was used. */
	private static boolean mendVisit(State s, ServerLevel level, CopperGolem golem, GolemData data, @Nullable Container in, @Nullable BlockPos inPos, long now) {
		if (in == null || inPos == null || !wantsTopUp(level, data, in)) return false;
		if (!reach(s, level, golem, inPos, inPos, now)) return true;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
		int used = GolemMending.repair(level, data, data.items.get(LumberProfession.AXE_SLOT), in, LumberLogic::isAxe, TransferBudget.remaining(level, golem), true);
		TransferBudget.spend(level, golem, used);
		s.anchored = false;
		boolean pending = TransferBudget.exhausted(level, golem) && wantsTopUp(level, data, in);
		if (used > 0 || pending) Interact.openChest(level, golem, inPos, Interact.Action.TAKE);
		if (!pending) s.walk.reset();
		done(s, golem, data, pending ? TransferBudget.waitTicks(level) : used > 0 ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
		return true;
	}

	// ---- delivery -------------------------------------------------------------------------------------------------

	/** Saplings and bone meal: they top up the supply slots, the surplus belongs to the input chest. */
	static boolean isSurplusKind(ItemStack s) {
		return isSapling(s) || isBone(s);
	}

	/** Moves carried saplings / bone meal into the supply slots where they fit (same item, or a free slot for a new kind). */
	static void topUpSupplies(GolemData data) {
		for (int c = LumberProfession.CARRY_FROM; c < LumberProfession.CARRY_FROM + LumberProfession.CARRY_SLOTS; c++) {
			ItemStack d = data.items.get(c);
			if (d.isEmpty() || !isSurplusKind(d)) continue;
			for (int k = LumberProfession.SUPPLY_FROM; k < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS && !d.isEmpty(); k++) {
				ItemStack sup = data.items.get(k);
				if (sup.isEmpty() || !ItemStack.isSameItemSameComponents(sup, d)) continue;
				int n = Math.min(d.getCount(), Math.min(64, sup.getMaxStackSize()) - sup.getCount());
				sup.grow(n);
				d.shrink(n);
			}
			Predicate<ItemStack> kind = isSapling(d) ? LumberLogic::isSapling : LumberLogic::isBone;
			if (!d.isEmpty() && supplySlot(data, kind) < 0) {
				int free = emptySupplySlot(data);
				if (free >= 0) {
					int n = Math.min(d.getCount(), Math.min(64, d.getMaxStackSize()));
					data.items.set(free, d.copyWithCount(n));
					d.shrink(n);
				}
			}
			if (d.isEmpty()) data.items.set(c, ItemStack.EMPTY);
		}
	}

	private static boolean hasSurplus(GolemData data) {
		for (int i = 0; i < LumberProfession.CARRY_SLOTS; i++) if (isSurplusKind(data.items.get(LumberProfession.CARRY_FROM + i))) return true;
		return false;
	}

	/** Inserts only the carry stacks matching {@code only} into {@code chest}; true when something moved. */
	private static int insertMatching(ServerLevel level, CopperGolem golem, GolemData data, Container chest, Predicate<ItemStack> only) {
		NonNullList<ItemStack> view = NonNullList.withSize(LumberProfession.CARRY_SLOTS, ItemStack.EMPTY);
		boolean[] mask = new boolean[view.size()];
		for (int i = 0; i < view.size(); i++) {
			ItemStack st = data.items.get(LumberProfession.CARRY_FROM + i);
			mask[i] = only.test(st);
			if (mask[i]) view.set(i, st);
		}
		int moved = TransferBudget.insert(level, golem, view, chest, data.keepLayout);
		for (int i = 0; i < view.size(); i++) if (mask[i]) data.items.set(LumberProfession.CARRY_FROM + i, view.get(i));
		return moved;
	}

	/**
	 * One tick of delivering the carry. Saplings and bone meal first top up the supply slots; the surplus goes to the
	 * input chest (one visit), everything else and whatever the input chest could not take goes to the output chest (one
	 * visit). Leftovers (chest full) stay in the carry and are retried next cycle. True when the delivery is over.
	 * Chest visits are limited to one per chest per delivery so a full chest cannot trap the golem.
	 */
	private static boolean deliverStep(State s, ServerLevel level, CopperGolem golem, GolemData data, @Nullable BlockPos inPos, @Nullable Container in,
		BlockPos outPos, Container out, long now) {
		topUpSupplies(data);
		if (in != null && inPos != null && !s.inputTried && hasSurplus(data)) {
			if (!reach(s, level, golem, inPos, inPos, now)) return false;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(inPos));
			boolean moved = insertMatching(level, golem, data, in, LumberLogic::isSurplusKind) > 0;
			if (moved) Interact.openChest(level, golem, inPos, Interact.Action.INSERT);
			s.anchored = false;
			if (TransferBudget.exhausted(level, golem) && hasSurplus(data)) { // budget, not "chest full": stay and go on next window
				Interact.openChest(level, golem, inPos, Interact.Action.INSERT);
				done(s, golem, data, TransferBudget.waitTicks(level));
				return false;
			}
			s.inputTried = true;
			s.walk.reset();
			done(s, golem, data, moved ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
			return false;
		}
		if (!carryEmpty(data) && !s.outputTried && !s.skip.containsKey(outPos)) {
			if (!reach(s, level, golem, outPos, outPos, now)) return false;
			golem.getLookControl().setLookAt(Vec3.atCenterOf(outPos));
			NonNullList<ItemStack> carry = carryView(data);
			boolean moved = TransferBudget.insert(level, golem, carry, out, data.keepLayout) > 0;
			writeCarry(data, carry);
			if (moved) Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
			s.anchored = false;
			if (TransferBudget.exhausted(level, golem) && !carryEmpty(data)) { // budget, not "chest full": stay and go on next window
				Interact.openChest(level, golem, outPos, Interact.Action.INSERT);
				done(s, golem, data, TransferBudget.waitTicks(level));
				return false;
			}
			s.outputTried = true;
			s.walk.reset();
			done(s, golem, data, moved ? Interact.OPEN_TICKS : AFTER_ACTION_TICKS);
			return false;
		}
		s.inputTried = false;
		s.outputTried = false;
		if (!carryEmpty(data)) s.cooldown = RETRY_TICKS; // something did not fit: wait before the next try
		GolemData.save(golem, data);
		return true;
	}

	// ---- felling --------------------------------------------------------------------------------------------------

	private static double hdist(CopperGolem golem, BlockPos p) {
		double dx = golem.getX() - (p.getX() + 0.5), dz = golem.getZ() - (p.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double hdist(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Next log to break: the lowest one the golem can reach from where it stands, else the lowest one overall. */
	private static @Nullable BlockPos pickNext(State s, CopperGolem golem, Set<BlockPos> logs) {
		Comparator<BlockPos> byHeight = Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> hdist(golem, p));
		BlockPos best = null;
		if (s.anchored) {
			for (BlockPos p : logs)
				if (!s.unreach.contains(p) && hdist(golem, p) <= REACH_H && (best == null || byHeight.compare(p, best) < 0)) best = p;
			if (best != null) return best;
		}
		for (BlockPos p : logs)
			if (!s.unreach.contains(p) && (best == null || byHeight.compare(p, best) < 0)) best = p;
		return best;
	}

	private static void fell(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, Set<BlockPos> logs, boolean bare, long now) {
		if (s.stripPos != null && !logs.contains(s.stripPos)) {
			s.stripPos = null;
			s.stripLeft = 0;
		}
		BlockPos cur = s.breakPos != null ? s.breakPos : s.stripPos; // a stripped log is felled next, even after a delivery trip
		BlockPos target = cur != null && logs.contains(cur) ? cur : null;
		if (target == null) {
			s.breakPos = null;
			target = pickNext(s, golem, logs);
			if (target == null) { // every remaining log was out of reach from every spot tried
				s.skip.put(t, now + SKIP_TICKS);
				s.unreach.clear();
				s.anchored = false;
				return;
			}
		}
		if (!s.anchored || hdist(golem, target) > REACH_H) {
			s.breakPos = null;
			BlockPos spot = hdist(target, t) <= NEAR_TREE ? t : standCell(level, golem, target, t);
			if (spot == null) {
				s.unreach.add(target);
				return;
			}
			if (!reach(s, level, golem, spot, t, now)) return;
			s.walk.reset();
			s.anchored = true;
			if (!level.getBlockState(target).is(BlockTags.LOGS)) return; // the walk reused an older flood: the next tick floods again
			if (hdist(golem, target) > REACH_H) {
				s.unreach.add(target);
				s.anchored = false;
				return;
			}
		}
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (!target.equals(s.breakPos) && !target.equals(s.stripPos)) {
			int sa = axeSlot(data);
			ItemStack stool = sa < 0 ? ItemStack.EMPTY : data.items.get(sa);
			BlockState ts = strippedState(level, target, data, stool);
			if (ts != null) { // strip first and leave the stripped log standing for STRIP_TICKS (see lumber.md "Strip logs")
				if (!fitsAll(data, Block.getDrops(ts, level, target, null, null, stool))) { // checked before the strip: no unfellable stripped log
					s.mustDeliver = true;
					return;
				}
				strip(level, target, ts, stool, data);
				GolemData.save(golem, data);
				s.stripPos = target;
				s.stripLeft = STRIP_TICKS;
			}
		}
		if (s.stripLeft > 0 && target.equals(s.stripPos)) {
			s.stripLeft--;
			ToolSwing.tick(level, golem, target, STRIP_TICKS - s.stripLeft - 1, STRIP_TICKS, false); // arms up, no crack, no hit sound
			return;
		}
		if (!target.equals(s.breakPos)) {
			s.stripPos = null;
			s.breakPos = target;
			BlockState st = level.getBlockState(target);
			// whole swings, at least MIN_SWINGS: a fast axe still visibly chops (see lumber.md "Chopping animation")
			int axe = axeSlot(data);
			if (axe < 0 && !bare) return; // axe gone since the last check; the next tick idles or fetches
			ItemStack tool = axe < 0 ? ItemStack.EMPTY : data.items.get(axe); // bare hands: destroy speed 1
			int base = breakTicks(st.getDestroySpeed(level, target), tool.getDestroySpeed(st));
			s.breakTotal = ToolSwing.swingTicks(base, MIN_SWINGS);
			s.breakLeft = s.breakTotal;
		}
		// held, not pulsed: one continuous interaction loop for the whole felling (see lumber.md "Chopping animation")
		ToolSwing.hold(level, golem, target, s.breakTotal - s.breakLeft, s.breakTotal, true);
		if (--s.breakLeft > 0) return;
		s.breakPos = null;
		ToolSwing.clearCrack(level, golem); // the state carries over to the next log; a longer walk ends it via the hook
		switch (breakBlock(level, golem, data, logs, target, bare)) {
			case BROKEN -> GolemData.save(golem, data);
			case FULL -> s.mustDeliver = true;
			case DENIED -> s.unreach.add(target);
		}
	}

	/** A ground spot within about 3 blocks of {@code log}'s column to work on it from, nearest to the column; null if none. */
	private static @Nullable BlockPos standCell(ServerLevel level, CopperGolem golem, BlockPos log, BlockPos t) {
		BlockPos best = null;
		int bestD = Integer.MAX_VALUE;
		int[] ys = { 0, 1, -1, -2 };
		for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
			int d = dx * dx + dz * dz;
			if (d >= bestD) continue;
			for (int dy : ys) {
				BlockPos p = new BlockPos(log.getX() + dx, t.getY() + dy, log.getZ() + dz);
				if (Interact.canStand(level, golem, p)) {
					best = p;
					bestD = d;
					break;
				}
			}
		}
		return best;
	}

	/**
	 * The ONLY place that breaks a block. Refuses unless {@link #breakAllowed} and the block is a log; refuses (FULL)
	 * when the drops would not fit, so nothing is ever dropped on the ground or lost. Leaves are never broken.
	 */
	public static Result breakBlock(ServerLevel level, CopperGolem golem, GolemData data, Set<BlockPos> tree, BlockPos pos) {
		return breakBlock(level, golem, data, tree, pos, false);
	}

	/** {@code bare}: no usable axe because an enchanted axe is resting; the log is then broken by hand (no tool, no wear). */
	static Result breakBlock(ServerLevel level, CopperGolem golem, GolemData data, Set<BlockPos> tree, BlockPos pos, boolean bare) {
		if (!breakAllowed(golem.getX(), golem.getZ(), tree, pos)) return Result.DENIED;
		BlockState st = level.getBlockState(pos);
		// capture the slot once: after the axe breaks axeSlot() finds nothing and returns -1 (crash on 2026-11-15)
		int slot = axeSlot(data);
		if (!st.is(BlockTags.LOGS) || (slot < 0 && !bare)) return Result.DENIED;
		ItemStack tool = slot < 0 ? ItemStack.EMPTY : data.items.get(slot);
		List<ItemStack> drops = Block.getDrops(st, level, pos, null, null, tool);
		if (!fitsAll(data, drops)) return Result.FULL; // a refused break leaves the log whole
		level.destroyBlock(pos, false, golem);
		storeAll(data, drops);
		// breaking empties the stack (vanilla behaviour); the item is gone, never duplicated
		if (slot >= 0) {
			GolemMending.damage(level, tool, 1); // a protected enchanted axe is never broken (axeSlot only offers a usable one)
			if (tool.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		}
		return Result.BROKEN;
	}

	// ---- stripping ------------------------------------------------------------------------------------------------

	/**
	 * The stripped form of the log at {@code pos} when the golem should strip it first: setting on, a usable axe that can
	 * take the strip hit and still the break hit (2+ points left), and a vanilla stripping result that is
	 * a log. Null otherwise (the log is broken as it is). The result comes from the axe's own {@code BLOCK_TRANSFORMER}
	 * component, so every wood vanilla strips is covered (logs, wood, stems, hyphae) and nothing is hardcoded.
	 */
	public static @Nullable BlockState strippedState(ServerLevel level, BlockPos pos, GolemData data, ItemStack tool) {
		if (!data.stripLogs || tool.isEmpty() || !isAxe(tool) || !GolemMending.canUse(tool) || GolemMending.remaining(tool) < 2) return null; // at the last point the log is just broken (the strip hit would break the axe before the break hit)
		Holder<BlockTransformer> h = tool.get(DataComponents.BLOCK_TRANSFORMER);
		if (h == null) return null;
		for (BlockTransformer.BlockTransformData t : h.value().transforms()) {
			BlockState r = t.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
			if (r != null && r.is(BlockTags.LOGS) && !r.is(level.getBlockState(pos).getBlock())) return r;
		}
		return null;
	}

	/** Sets the stripped state, plays the transformer's strip sound and wears the axe by 1 like a player's strip. */
	private static void strip(ServerLevel level, BlockPos pos, BlockState stripped, ItemStack tool, GolemData data) {
		SoundEvent sound = SoundEvents.AXE_STRIP.value();
		Holder<BlockTransformer> h = tool.get(DataComponents.BLOCK_TRANSFORMER);
		if (h != null) for (BlockTransformer.BlockTransformData t : h.value().transforms()) {
			BlockState r = t.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
			if (stripped.equals(r)) sound = t.sound().value();
		}
		level.setBlock(pos, stripped, 11);
		level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
		GolemMending.damage(level, tool, 1);
	}

	// ---- walking with give-up -------------------------------------------------------------------------------------

	/**
	 * True when the golem is in reach of {@code target} ({@link Interact#approach}). Otherwise keeps walking; with no
	 * progress for {@link #GIVE_UP_TICKS} the {@code skipKey} (tree spot or chest) is ignored for {@link #SKIP_TICKS}.
	 * A null path or airborne wait is no progress, so the timeout bounds it.
	 */
	private static boolean reach(State s, ServerLevel level, CopperGolem golem, BlockPos target, BlockPos skipKey, long now) {
		if (Interact.approach(level, golem, target, s.walk, SPEED)) {
			s.goalKey = null;
			s.stuck = 0;
			return true;
		}
		double dist = golem.distanceToSqr(Vec3.atCenterOf(target));
		if (!target.equals(s.goalKey) || dist < s.bestDist - PROGRESS_EPS) {
			s.goalKey = target;
			s.bestDist = dist;
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(skipKey, now + SKIP_TICKS);
			s.goalKey = null;
			s.stuck = 0;
			s.breakPos = null;
			s.walk.reset();
			golem.getNavigation().stop();
		}
		return false;
	}

	/** Test hook: is {@code pos} currently ignored by this golem. */
	public static boolean isSkipped(CopperGolem golem, BlockPos pos, long now) {
		State st = STATES.get(golem);
		Long until = st == null ? null : st.skip.get(pos);
		return until != null && until > now;
	}

	// ---- standing outside the grow footprint ----------------------------------------------------------------------

	/** Largest horizontal (Chebyshev) distance from a cell of {@code f} to {@code p}'s column: 0 = inside the footprint. */
	static int footDist(BlockPos p, List<BlockPos> f) {
		int best = Integer.MAX_VALUE;
		for (BlockPos c : f) best = Math.min(best, Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())));
		return best;
	}

	/** Is the golem's feet block inside the sapling / trunk volume (a footprint column, around T's height)? */
	public static boolean inVolume(BlockPos feet, BlockPos t, List<BlockPos> f) {
		return footDist(feet, f) == 0 && feet.getY() >= t.getY() - 1 && feet.getY() <= t.getY() + 2;
	}

	private static @Nullable BlockPos pickSpot(ServerLevel level, CopperGolem golem, BlockPos t, List<BlockPos> f, int minD, int maxD, Set<BlockPos> bad) {
		record Cand(BlockPos p, int d, double dist) {}
		List<Cand> cands = new ArrayList<>();
		int r = maxD + 1;
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy : new int[] { 0, 1, -1, -2 }) {
			BlockPos p = new BlockPos(t.getX() + dx, t.getY() + dy, t.getZ() + dz);
			int d = footDist(p, f);
			if (d < minD || d > maxD || bad.contains(p) || !Interact.canStand(level, golem, p)) continue;
			cands.add(new Cand(p, d, golem.distanceToSqr(Vec3.atBottomCenterOf(p))));
		}
		cands.sort(Comparator.<Cand>comparingInt(Cand::d).thenComparingDouble(Cand::dist));
		for (int i = 0; i < Math.min(6, cands.size()); i++) {
			Path path = golem.getNavigation().createPath(cands.get(i).p, 0);
			if (path != null && path.canReach()) return cands.get(i).p;
		}
		return null;
	}

	/**
	 * Walk to a ground cell {@code minD}..{@code maxD} blocks (horizontal, Chebyshev) from the footprint {@code f}: never in
	 * it, so a growing tree cannot grow around the golem. True when it stands on such a cell. Out of reach of a spot it
	 * widens the range; with no spot at all it accepts its place unless it is inside the footprint (then it gives up the tree
	 * after {@link #GIVE_UP_TICKS}).
	 */
	private static boolean standOutside(State s, ServerLevel level, CopperGolem golem, BlockPos t, List<BlockPos> f, int minD, int maxD, long now) {
		BlockPos feet = golem.blockPosition();
		int fd = footDist(feet, f);
		if (fd >= minD && fd <= maxD && Math.abs(feet.getY() - t.getY()) <= 2 && golem.onGround()) {
			golem.getNavigation().stop();
			s.spot = null;
			s.spotStuck = 0;
			s.spotBad.clear();
			return true;
		}
		int key = minD * 100 + maxD;
		if (s.spot == null || s.spotKey != key) {
			s.spot = pickSpot(level, golem, t, f, minD, maxD, s.spotBad);
			if (s.spot == null && !inVolume(feet, t, f)) return true; // no such cell: stay out of the footprint where we are
			if (s.spot == null) s.spot = pickSpot(level, golem, t, f, 1, maxD + 3, s.spotBad);
			s.spotKey = key;
			s.spotRepath = 0;
			if (s.spot != null) {
				s.spotBest = golem.distanceToSqr(Vec3.atBottomCenterOf(s.spot));
				s.spotStuck = 0;
			}
		}
		if (s.spot == null) { // inside the footprint and nowhere to step: bounded wait
			if (++s.spotStuck >= GIVE_UP_TICKS) {
				s.skip.put(t, now + SKIP_TICKS);
				s.spotStuck = 0;
			}
			return false;
		}
		double d = golem.distanceToSqr(Vec3.atBottomCenterOf(s.spot));
		if (d < s.spotBest - PROGRESS_EPS) {
			s.spotBest = d;
			s.spotStuck = 0;
		} else if (++s.spotStuck >= SPOT_STUCK_TICKS) {
			s.spotBad.add(s.spot);
			s.spot = null;
			s.spotStuck = 0;
			return false;
		}
		if (--s.spotRepath <= 0 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(s.spot, 0);
			if (path != null) golem.getNavigation().moveTo(path, SPEED);
			s.spotRepath = REPATH_TICKS;
		}
		return false;
	}

	// ---- planting -------------------------------------------------------------------------------------------------

	private static boolean isSaplingBlock(Level level, BlockPos p) {
		return level.getBlockState(p).is(BlockTags.SAPLINGS);
	}

	/** A sapling stands on T or on a cell of the remembered footprint. */
	private static boolean isGrowing(Level level, BlockPos t, State s) {
		if (isSaplingBlock(level, t)) return true;
		for (BlockPos p : s.footprint) if (isSaplingBlock(level, p)) return true;
		return false;
	}

	/** A 2x2 footprint that is only partly planted (some cell is still free). */
	private static boolean partial(Level level, State s) {
		if (s.footprint.size() != 4) return false;
		boolean sapling = false, free = false;
		for (BlockPos p : s.footprint) {
			if (isSaplingBlock(level, p)) sapling = true;
			else if (level.getBlockState(p).isAir()) free = true;
		}
		return sapling && free;
	}

	private static boolean plantable(Level level, BlockPos p, BlockState sap) {
		BlockState cur = level.getBlockState(p);
		return (cur.isAir() || (cur.canBeReplaced() && cur.getFluidState().isEmpty())) && sap.canSurvive(level, p);
	}

	private static boolean cellOk(Level level, BlockPos p, BlockState sap) {
		return level.getBlockState(p).is(sap.getBlock()) || plantable(level, p, sap);
	}

	/**
	 * The cells to plant {@code sap} on: one at T, or four (a 2x2 square) for saplings that need it (dark oak, pale oak)
	 * or when a spruce/jungle 2x2 trunk was just felled and enough saplings are at hand. Empty = cannot plant now.
	 */
	static List<BlockPos> plantCells(Level level, BlockPos t, State s, ItemStack sap, boolean site2x2) {
		BlockState sapState = ((BlockItem) sap.getItem()).getBlock().defaultBlockState();
		if (site2x2 && !canBe2x2(sap)) return List.of(); // a 2x2 site only takes saplings that can grow 2x2, never a single one
		int missingFootprint = 0;
		if (s.footprint.size() == 4) for (BlockPos p : s.footprint) if (!level.getBlockState(p).is(sapState.getBlock())) missingFootprint++;
		boolean four = site2x2 || needs2x2(sap) || (canBe2x2(sap) && s.footprint.size() == 4 && sap.getCount() >= missingFootprint);
		if (!four) return cellOk(level, t, sapState) ? List.of(t) : List.of();
		List<List<BlockPos>> candidates = new ArrayList<>();
		if (s.footprint.size() == 4) candidates.add(s.footprint);
		for (int a = -1; a <= 0; a++) for (int b = -1; b <= 0; b++) candidates.add(square(t.getX() + a, t.getY(), t.getZ() + b));
		for (List<BlockPos> sq : candidates) {
			boolean ok = true;
			for (BlockPos p : sq) if (!cellOk(level, p, sapState)) ok = false;
			if (ok) return sq;
		}
		return List.of();
	}

	/** The tree spot was learned to be a 2x2 site (persisted, see {@link LumberProfession#SITE_2X2}). */
	static boolean site2x2(GolemData data, BlockPos t) {
		return t.equals(data.assignments.get(LumberProfession.SITE_2X2));
	}

	/** Marks the tree spot as a 2x2 site; the caller saves. */
	private static void markSite2x2(GolemData data, BlockPos t) {
		data.assignments.put(LumberProfession.SITE_2X2, t.immutable());
	}

	/** The cells a growing tree will occupy: the remembered 2x2 footprint, a 2x2 square of saplings containing T, else T. */
	private static List<BlockPos> footprintCells(Level level, BlockPos t, State s) {
		if (s.footprint.size() == 4) return s.footprint;
		for (int a = -1; a <= 0; a++) for (int b = -1; b <= 0; b++) {
			List<BlockPos> sq = square(t.getX() + a, t.getY(), t.getZ() + b);
			boolean all = true;
			for (BlockPos p : sq) if (!isSaplingBlock(level, p)) all = false;
			if (all) return sq;
		}
		return List.of(t);
	}

	/** One replant step: stand next to the footprint (never in it), plant one sapling per call. True when this tick was used. */
	private static boolean plant(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		int slot = supplySlot(data, LumberLogic::isSapling);
		if (slot < 0) return false;
		ItemStack sap = data.items.get(slot);
		List<BlockPos> cells = plantCells(level, t, s, sap, site2x2(data, t));
		List<BlockPos> missing = new ArrayList<>();
		BlockState sapState = ((BlockItem) sap.getItem()).getBlock().defaultBlockState();
		for (BlockPos p : cells) if (!level.getBlockState(p).is(sapState.getBlock())) missing.add(p);
		if (missing.isEmpty() || sap.getCount() < missing.size()) return false;
		if (!standOutside(s, level, golem, t, cells, WORK_MIN, WORK_MAX, now)) return true;
		if (cells.size() == 4) markSite2x2(data, t);
		BlockPos cell = missing.getFirst();
		golem.getLookControl().setLookAt(Vec3.atCenterOf(cell));
		level.setBlock(cell, sapState, 3);
		level.playSound(null, cell, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
		sap.shrink(1);
		if (sap.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		s.footprint = cells.size() == 4 ? cells : List.of(); // a 1x1 sapling is found at T
		s.boneFails = 0;
		if (missing.size() == 1) s.walk.reset();
		done(s, golem, data, AFTER_ACTION_TICKS);
		return true;
	}

	/**
	 * The sapling cell to bone-meal, or null when bone meal is pointless: no sapling, vanilla's
	 * {@link BonemealableBlock#isValidBonemealTarget} says no (a lone dark oak sapling, no room below the build limit), or the
	 * last {@link #BONE_MAX_FAILS} uses changed nothing (an obstructed sapling: vanilla still accepts it, consumes the bone meal,
	 * tries to place the tree and puts the sapling back). See lumber.md "Bone meal".
	 */
	private static @Nullable BlockPos boneTarget(ServerLevel level, BlockPos t, State s) {
		if (s.boneFails >= BONE_MAX_FAILS) return null;
		BlockPos target = null;
		for (BlockPos p : footprintCells(level, t, s)) if (isSaplingBlock(level, p)) target = p;
		if (target == null) return null;
		BlockState st = level.getBlockState(target);
		return st.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, target, st, BonemealSource.INTERACTION) ? target : null;
	}

	/** Bone meal on a growing sapling, one use every {@link #BONE_DELAY} ticks. True when this tick was used. */
	private static boolean boneMeal(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, long now) {
		int slot = supplySlot(data, LumberLogic::isBone);
		if (slot < 0) return false;
		BlockPos target = boneTarget(level, t, s);
		if (target == null) return false;
		if (!standOutside(s, level, golem, t, footprintCells(level, t, s), WORK_MIN, WORK_MAX, now)) return true;
		golem.getLookControl().setLookAt(Vec3.atCenterOf(target));
		ItemStack bone = data.items.get(slot);
		BlockState before = level.getBlockState(target);
		if (BoneMealItem.growCrop(bone, level, target)) {
			level.levelEvent(1505, target, 15);
			// unchanged = no progress (failed roll, or a tree that had no room); a stage step or the tree resets the count
			s.boneFails = level.getBlockState(target) == before ? s.boneFails + 1 : 0;
		}
		if (bone.isEmpty()) data.items.set(slot, ItemStack.EMPTY);
		done(s, golem, data, BONE_DELAY);
		return true;
	}

	// ---- ground drops ---------------------------------------------------------------------------------------------

	/**
	 * Walk to a drop lying around T and pick it up (the Cleaner's chase and give-up pattern). True when this tick was used.
	 * While a sapling stands, a drop inside the footprint (or one it cannot reach without crossing the sapling) is picked up
	 * from a safe cell outside the footprint at {@link #DANGER_PICKUP_RANGE_SQR}; the golem never enters the footprint.
	 */
	private static boolean collect(State s, ServerLevel level, CopperGolem golem, GolemData data, BlockPos t, @Nullable List<BlockPos> growing, long now) {
		AABB box = new AABB(t.getX() - ITEM_RADIUS, t.getY() - ITEM_BELOW, t.getZ() - ITEM_RADIUS,
			t.getX() + ITEM_RADIUS + 1, t.getY() + ITEM_ABOVE, t.getZ() + ITEM_RADIUS + 1);
		// fresh drops still under their pickup delay (a player's throw, a leaf drop) count as coming: waited for, not taken;
		// an old one still delayed (never-pickup display items) is ignored
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
			e -> e.isAlive() && (!e.hasPickUpDelay() || e.getAge() >= 0 && e.getAge() < PENDING_AGE)
				&& !s.skip.containsKey(e.getUUID()) && fitsSome(data, e.getItem()));
		if (items.isEmpty()) {
			s.chasing = null;
			return false;
		}
		s.lastSeen = now; // a drop is there: PICKUP does not end yet
		// a takeable drop first, the nearest of them
		ItemEntity target = items.stream().min(Comparator.<ItemEntity>comparingInt(e -> e.hasPickUpDelay() ? 1 : 0)
			.thenComparingDouble(golem::distanceToSqr)).get();
		if (growing != null && inVolume(golem.blockPosition(), t, growing)) {
			// in the sapling volume every path starts inside and "crosses" it (no detour, the drop was skipped): step out to the ring first
			standOutside(s, level, golem, t, growing, WORK_MIN, WORK_MAX, now);
			return true;
		}
		boolean danger = growing != null && footDist(target.blockPosition(), growing) == 0;
		if (golem.distanceToSqr(target) <= (danger ? DANGER_PICKUP_RANGE_SQR : PICKUP_RANGE_SQR)) {
			if (target.hasPickUpDelay()) { // close enough: wait for the delay to run out (CleanerLogic never takes it either)
				golem.getNavigation().stop();
				return true;
			}
			pickUp(s, golem, data, target);
			return true;
		}
		if (!target.equals(s.chasing)) {
			s.chasing = target;
			s.goalKey = target.getUUID();
			s.bestDist = golem.distanceToSqr(target);
			s.stuck = 0;
			s.chaseRepath = 0;
		} else if (golem.distanceToSqr(target) < s.bestDist - PROGRESS_EPS) {
			s.bestDist = golem.distanceToSqr(target);
			s.stuck = 0;
		} else if (++s.stuck >= GIVE_UP_TICKS) {
			s.skip.put(target.getUUID(), now + SKIP_TICKS);
			s.chasing = null;
			golem.getNavigation().stop();
			return true;
		}
		if (--s.chaseRepath <= 0 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(target, 0);
			if (path != null && growing != null && crosses(path, t, growing)) { // never walk over a growing sapling: go round it
				path = detour(level, golem, target, t, growing);
				if (path == null && golem.distanceToSqr(target) <= DANGER_PICKUP_RANGE_SQR) { // no way round: suck it up from here
					if (target.hasPickUpDelay()) golem.getNavigation().stop(); // not yet: wait here for the delay
					else pickUp(s, golem, data, target);
					return true;
				}
				if (path == null) { // no way round: skip this drop for now
					s.skip.put(target.getUUID(), now + SKIP_TICKS);
					s.chasing = null;
					golem.getNavigation().stop();
					return true;
				}
			}
			// a null path is transient (spawn tick, mid-air): no progress, so the timeout decides
			if (path != null) golem.getNavigation().moveTo(path, SPEED);
			s.chaseRepath = REPATH_TICKS;
		}
		return true;
	}

	private static void pickUp(State s, CopperGolem golem, GolemData data, ItemEntity target) {
		golem.getNavigation().stop();
		s.chasing = null;
		List<ItemStack> rest = new ArrayList<>(List.of(target.getItem().copy()));
		storeInto(data, rest);
		if (rest.isEmpty()) target.discard(); else target.setItem(rest.getFirst());
		done(s, golem, data, AFTER_ACTION_TICKS);
	}

	/**
	 * A path towards {@code target} that stays out of the sapling volume: the pathfinder walks straight through a sapling
	 * cell, so a drop on the far side is approached over a waypoint, a standable cell 1..2 blocks from the footprint that
	 * is closer to the drop than the golem and reachable without crossing; from there the next repath goes on round the ring.
	 */
	private static @Nullable Path detour(ServerLevel level, CopperGolem golem, ItemEntity target, BlockPos t, List<BlockPos> f) {
		List<BlockPos> cands = new ArrayList<>();
		for (int dx = -(WORK_MAX + 1); dx <= WORK_MAX + 1; dx++) for (int dz = -(WORK_MAX + 1); dz <= WORK_MAX + 1; dz++) for (int dy = -1; dy <= 1; dy++) {
			BlockPos p = new BlockPos(t.getX() + dx, golem.getBlockY() + dy, t.getZ() + dz); // +-1 Y: uneven ground round the sapling
			int d = footDist(p, f);
			if (d < WORK_MIN || d > WORK_MAX || !Interact.canStand(level, golem, p)) continue;
			if (target.distanceToSqr(Vec3.atBottomCenterOf(p)) >= golem.distanceToSqr(target) - 1 || p.equals(golem.blockPosition())) continue;
			cands.add(p);
		}
		cands.sort(Comparator.comparingDouble(p -> target.distanceToSqr(Vec3.atBottomCenterOf(p))));
		for (int i = 0; i < Math.min(8, cands.size()); i++) {
			Path path = golem.getNavigation().createPath(cands.get(i), 0);
			if (path != null && path.canReach() && !crosses(path, t, f)) return path;
		}
		return null;
	}

	/** Does the path pass through the sapling / trunk volume of the footprint {@code f}? */
	private static boolean crosses(Path path, BlockPos t, List<BlockPos> f) {
		for (int i = 0; i < path.getNodeCount(); i++) if (inVolume(path.getNode(i).asBlockPos(), t, f)) return true;
		return false;
	}

	// ---- chest fetching -------------------------------------------------------------------------------------------

	static int axeSlot(GolemData data) {
		ItemStack a = data.items.get(LumberProfession.AXE_SLOT);
		return isAxe(a) && GolemMending.canUse(a) ? LumberProfession.AXE_SLOT : -1; // a resting enchanted axe is not an axe to use
	}

	/**
	 * Bare hands are allowed only because of a protected enchanted axe: one resting in the slot, or (empty slot) lying unusable in the
	 * input chest. Without such an axe a golem with no axe still does no work, as before.
	 */
	static boolean mendingBlocked(GolemData data, @Nullable Container in) {
		ItemStack cur = data.items.get(LumberProfession.AXE_SLOT);
		if (GolemMending.mustPark(cur)) return true;
		if (!cur.isEmpty() || in == null) return false;
		for (int i = 0; i < in.getContainerSize(); i++) if (isAxe(in.getItem(i)) && GolemMending.mustPark(in.getItem(i))) return true;
		return false;
	}

	/** Chest slot of an axe that may be taken: fills the empty axe slot or replaces a protected resting one; -1 if none. */
	static int fetchableAxe(Container chest, GolemData data) {
		ItemStack cur = data.items.get(LumberProfession.AXE_SLOT);
		if (!cur.isEmpty() && GolemMending.canUse(cur)) return -1;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (isAxe(st) && st.getCount() == 1 && GolemMending.canUse(st)) return i;
		}
		return -1;
	}

	/** Empty: take one. Protected enchanted axe resting: swap, the old one goes into the chest slot (nothing lost). */
	static boolean fetchAxe(Container chest, GolemData data) {
		int i = fetchableAxe(chest, data);
		if (i < 0) return false;
		ItemStack fresh = chest.removeItem(i, 1);
		ItemStack old = data.items.get(LumberProfession.AXE_SLOT);
		if (!old.isEmpty()) chest.setItem(i, old); // fetchableAxe only offers single-item slots, so slot i is empty now
		data.items.set(LumberProfession.AXE_SLOT, fresh);
		chest.setChanged();
		return true;
	}

	/** Anything to fetch right now? Mirrors the fetch methods' preconditions exactly (no endless walking to the chest). */
	private static boolean wantsFetch(ServerLevel level, GolemData data, Container in, boolean felling, boolean wantSap, boolean growing, boolean topUp) {
		if (felling && fetchableAxe(in, data) >= 0) return true;
		// Mending: bottles to use, or a resting Mending axe the chest can take back
		ItemStack held = data.items.get(LumberProfession.AXE_SLOT);
		if (felling && (GolemMending.canRepair(level, data, held, in, LumberLogic::isAxe)
			|| GolemMending.mustPark(held) && GolemMending.canPark(in, held))) return true;
		// topUp: a fetch the transfer budget cut short goes on up to its full target, not just above the low mark
		if (wantSap && countOf(data, LumberLogic::isSapling) < (topUp ? 64 : SAPLING_LOW) && supplySource(in, data, LumberLogic::isSapling) >= 0) return true;
		return growing && countOf(data, LumberLogic::isBone) < (topUp ? BONE_TARGET : BONE_LOW) && supplySource(in, data, LumberLogic::isBone) >= 0;
	}

	/** Saplings the next planting can draw on: supply slots, carry (not yet delivered) and the input chest. */
	private static int saplingStock(GolemData data, @Nullable Container in) {
		int n = 0;
		for (int i = LumberProfession.SUPPLY_FROM; i < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; i++)
			if (isSapling(data.items.get(i))) n += data.items.get(i).getCount();
		for (int i = 0; i < LumberProfession.CARRY_SLOTS; i++)
			if (isSapling(data.items.get(LumberProfession.CARRY_FROM + i))) n += data.items.get(LumberProfession.CARRY_FROM + i).getCount();
		if (in != null) for (int i = 0; i < in.getContainerSize(); i++) if (isSapling(in.getItem(i))) n += TransporterLogic.takeable(in, in.getItem(i), data.keepLayout);
		return n;
	}

	static int supplySlot(GolemData data, Predicate<ItemStack> is) {
		for (int i = LumberProfession.SUPPLY_FROM; i < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; i++)
			if (is.test(data.items.get(i))) return i;
		return -1;
	}

	private static int emptySupplySlot(GolemData data) {
		for (int i = LumberProfession.SUPPLY_FROM; i < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; i++)
			if (data.items.get(i).isEmpty()) return i;
		return -1;
	}

	private static int countOf(GolemData data, Predicate<ItemStack> is) {
		int slot = supplySlot(data, is);
		return slot < 0 ? 0 : data.items.get(slot).getCount();
	}

	/**
	 * Slot in {@code chest} to take supplies from: the first slot with the item already in the golem's slot, or (no such
	 * slot yet, a free supply slot) the first matching item. -1 when nothing can be taken.
	 */
	static int supplySource(Container chest, GolemData data, Predicate<ItemStack> is) {
		int slot = supplySlot(data, is);
		if (slot < 0 && emptySupplySlot(data) < 0) return -1;
		ItemStack cur = slot < 0 ? ItemStack.EMPTY : data.items.get(slot);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || !is.test(st) || TransporterLogic.takeable(chest, st, data.keepLayout) <= 0) continue;
			if (cur.isEmpty() || ItemStack.isSameItemSameComponents(cur, st)) return i;
		}
		return -1;
	}

	/** Takes supplies of one kind up to {@code target} items in the golem's slot, at most {@code limit}; returns how many. */
	static int fetchSupply(Container chest, GolemData data, Predicate<ItemStack> is, int target, int limit) {
		int first = supplySource(chest, data, is);
		if (first < 0 || limit <= 0) return 0;
		int slot = supplySlot(data, is);
		if (slot < 0) slot = emptySupplySlot(data);
		ItemStack cur = data.items.get(slot);
		ItemStack kind = chest.getItem(first).copyWithCount(1);
		int have = cur.getCount();
		int max = Math.min(Math.min(target, Math.min(64, kind.getMaxStackSize())), have + limit);
		int took = 0;
		for (int i = first; i < chest.getContainerSize() && have + took < max; i++) {
			ItemStack st = chest.getItem(i);
			if (st.isEmpty() || !ItemStack.isSameItemSameComponents(kind, st)) continue;
			int n = Math.min(max - have - took, TransporterLogic.takeable(chest, st, data.keepLayout));
			if (n <= 0) continue;
			took += n;
			st.shrink(n);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
		}
		if (took == 0) return 0;
		data.items.set(slot, kind.copyWithCount(have + took));
		chest.setChanged();
		return took;
	}

	// ---- carry helpers --------------------------------------------------------------------------------------------

	static NonNullList<ItemStack> carryView(GolemData data) {
		NonNullList<ItemStack> carry = NonNullList.withSize(LumberProfession.CARRY_SLOTS, ItemStack.EMPTY);
		for (int i = 0; i < carry.size(); i++) carry.set(i, data.items.get(LumberProfession.CARRY_FROM + i));
		return carry;
	}

	static void writeCarry(GolemData data, NonNullList<ItemStack> carry) {
		for (int i = 0; i < carry.size(); i++) data.items.set(LumberProfession.CARRY_FROM + i, carry.get(i));
	}

	static boolean carryEmpty(GolemData data) {
		return TransporterLogic.isEmpty(carryView(data));
	}

	/**
	 * Stores items: merge into a supply slot that holds the same item; a sapling also goes into a free supply slot when
	 * the golem holds no sapling yet; the rest into carry. {@code drops} becomes the leftover.
	 */
	private static void storeInto(GolemData data, List<ItemStack> drops) {
		SimpleContainer tmp = new SimpleContainer(Math.max(1, drops.size()));
		for (int i = 0; i < drops.size(); i++) {
			ItemStack d = drops.get(i).copy();
			for (int k = LumberProfession.SUPPLY_FROM; k < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS && !d.isEmpty(); k++) {
				ItemStack sup = data.items.get(k);
				if (!sup.isEmpty() && ItemStack.isSameItemSameComponents(sup, d)) {
					int n = Math.min(d.getCount(), Math.min(64, sup.getMaxStackSize()) - sup.getCount());
					sup.grow(n);
					d.shrink(n);
				}
			}
			if (!d.isEmpty() && isSapling(d) && supplySlot(data, LumberLogic::isSapling) < 0) {
				int free = emptySupplySlot(data);
				if (free >= 0) {
					int n = Math.min(d.getCount(), Math.min(64, d.getMaxStackSize()));
					data.items.set(free, d.copyWithCount(n));
					d.shrink(n);
				}
			}
			tmp.setItem(i, d);
		}
		NonNullList<ItemStack> carry = carryView(data);
		TransporterLogic.takeAll(tmp, carry);
		writeCarry(data, carry);
		drops.clear();
		for (int i = 0; i < tmp.getContainerSize(); i++) if (!tmp.getItem(i).isEmpty()) drops.add(tmp.getItem(i));
	}

	private static GolemData copyOf(GolemData data) {
		GolemData copy = new GolemData();
		copy.items = NonNullList.withSize(data.items.size(), ItemStack.EMPTY);
		for (int i = 0; i < data.items.size(); i++) copy.items.set(i, data.items.get(i).copy());
		return copy;
	}

	/** Would all drops fit (supply slots + carry)? Works on a copy of the inventory. */
	private static boolean fitsAll(GolemData data, List<ItemStack> drops) {
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(copyOf(data), rest);
		return rest.isEmpty();
	}

	/**
	 * Some part of {@code stack} would be taken: the {@link #storeInto} rules checked in place, without copying the inventory
	 * (it runs per item entity in the pickup box every tick).
	 */
	private static boolean fitsSome(GolemData data, ItemStack stack) {
		if (stack.isEmpty()) return false;
		for (int k = LumberProfession.SUPPLY_FROM; k < LumberProfession.SUPPLY_FROM + LumberProfession.SUPPLY_SLOTS; k++) {
			ItemStack sup = data.items.get(k);
			if (!sup.isEmpty() && ItemStack.isSameItemSameComponents(sup, stack) && sup.getCount() < Math.min(64, sup.getMaxStackSize())) return true;
		}
		if (isSapling(stack) && supplySlot(data, LumberLogic::isSapling) < 0 && emptySupplySlot(data) >= 0) return true;
		int max = Math.min(64, stack.getMaxStackSize());
		for (int c = LumberProfession.CARRY_FROM; c < LumberProfession.CARRY_FROM + LumberProfession.CARRY_SLOTS; c++) {
			ItemStack cur = data.items.get(c);
			if (cur.isEmpty() || ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < max) return true;
		}
		return false;
	}

	private static void storeAll(GolemData data, List<ItemStack> drops) {
		List<ItemStack> rest = new ArrayList<>(drops);
		storeInto(data, rest);
		// cannot happen: fitsAll proved the drops fit and nothing ran in between
		if (!rest.isEmpty()) throw new IllegalStateException("lumber drops did not fit: " + rest);
	}
}
