package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.ai.Interact;
import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.core.GolemTicker;

import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.ProfessionHelp;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Core behaviour feedback: spawn chest, item hand-over, None standing still, profession name, walk-to. */
public class CoreFeedbackTest {
	private static final Identifier TRANSPORTER = CopperGolemsRevamped.id("transporter");
	static {
		// All test classes are loaded at startup, so this covers every test: a throwing profession fails the test.
		GolemTicker.rethrow = true;
	}

	private static final Identifier LUMBER = CopperGolemsRevamped.id("lumber");

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static double horizontal(CopperGolem golem, BlockPos block) {
		double dx = golem.getX() - (block.getX() + 0.5), dz = golem.getZ() - (block.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	// ---- 1. spawn chest ----

	@GameTest
	public void spawnChestFromBuildPattern(GameTestHelper helper) {
		floor(helper);
		BlockPos copper = new BlockPos(3, 1, 3);
		helper.setBlock(copper, Blocks.COPPER_BLOCK.weathering().unaffected());
		helper.setBlock(copper.above(), Blocks.CARVED_PUMPKIN); // onPlace builds the golem
		List<CopperGolem> golems = helper.getEntities(EntityTypes.COPPER_GOLEM);
		helper.assertTrue(golems.size() == 1, "golems built: " + golems.size());
		CopperGolem golem = golems.get(0);
		GolemData data = GolemData.get(golem);
		helper.assertTrue(helper.absolutePos(copper).equals(data.spawnChest), "spawn chest: " + data.spawnChest);
		helper.assertTrue(helper.getBlockState(copper).getBlock() instanceof net.minecraft.world.level.block.ChestBlock, "the pattern must leave a chest");
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		BlockPos input = GolemData.get(golem).assignments.get("input");
		helper.assertTrue(helper.absolutePos(copper).equals(input), "input prefilled with the spawn chest: " + input);
		helper.assertTrue(!GolemData.get(golem).assignments.containsKey("output"), "only the first chest assign is prefilled");
		helper.succeed();
	}

	@GameTest
	public void spawnChestPrefillRules(GameTestHelper helper) {
		BlockPos chest = new BlockPos(1, 1, 1);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem eggGolem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), eggGolem, TRANSPORTER);
		helper.assertTrue(GolemData.get(eggGolem).assignments.isEmpty(), "no spawn chest: nothing is prefilled");

		CopperGolem built = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 3));
		GolemData data = GolemData.get(built);
		data.spawnChest = helper.absolutePos(chest);
		GolemData.save(built, data);
		GolemService.changeProfession(helper.getLevel(), built, TRANSPORTER);
		helper.assertTrue(helper.absolutePos(chest).equals(GolemData.get(built).assignments.get("input")), "prefilled");
		// a profession without a chest assign gets nothing
		GolemService.changeProfession(helper.getLevel(), built, LUMBER);
		helper.assertTrue(!GolemData.get(built).assignments.containsValue(helper.absolutePos(chest))
			|| GolemData.get(built).assignments.size() <= 1, "unexpected assignments " + GolemData.get(built).assignments);
		// chest broken: no prefill
		helper.setBlock(chest, Blocks.AIR);
		GolemService.changeProfession(helper.getLevel(), built, TRANSPORTER);
		helper.assertTrue(GolemData.get(built).assignments.isEmpty(), "broken chest must not be prefilled");
		helper.succeed();
	}

	// ---- 2. items go to the player ----

	@SuppressWarnings("removal")
	@GameTest
	public void switchMovesItemsToPlayer(GameTestHelper helper) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		GolemData data = GolemData.get(golem);
		data.items.set(0, new ItemStack(Items.COBBLESTONE, 10));
		data.items.set(5, new ItemStack(Items.DIAMOND, 3));
		GolemData.save(golem, data);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GolemService.changeProfession(helper.getLevel(), golem, null, player);
		int cobble = 0, diamonds = 0;
		for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
			if (s.is(Items.COBBLESTONE)) cobble += s.getCount();
			if (s.is(Items.DIAMOND)) diamonds += s.getCount();
		}
		helper.assertTrue(cobble == 10 && diamonds == 3, "player inventory: cobble " + cobble + ", diamonds " + diamonds);
		helper.assertTrue(GolemData.get(golem).items.isEmpty(), "golem items: " + GolemData.get(golem).items);
		helper.succeed();
	}

	@SuppressWarnings("removal")
	@GameTest
	public void switchDropsRestAtPlayerWhenFull(GameTestHelper helper) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		GolemData data = GolemData.get(golem);
		data.items.set(0, new ItemStack(Items.DIAMOND, 3));
		GolemData.save(golem, data);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)))); // the mock starts at the world origin, an unloaded chunk
		for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++) {
			player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		}
		GolemService.changeProfession(helper.getLevel(), golem, null, player);
		int dropped = 0;
		for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3))) {
			if (e.getItem().is(Items.DIAMOND)) dropped += e.getItem().getCount();
		}
		helper.assertTrue(dropped == 3, "diamonds dropped at the player: " + dropped);
		helper.succeed();
	}

	// ---- 3. None stands still ----

	@GameTest(maxTicks = 200)
	public void noneProfessionStandsStill(GameTestHelper helper) {
		floor(helper);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		Vec3[] start = new Vec3[1];
		helper.runAtTickTime(10, () -> start[0] = golem.position());
		helper.runAtTickTime(150, () -> {
			double d = golem.position().distanceTo(start[0]);
			helper.assertTrue(d < 0.3, "a golem without profession moved " + d + " blocks");
			helper.succeed();
		});
	}

	// ---- 4. profession name ----

	@GameTest
	public void professionShownAsName(GameTestHelper helper) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		helper.assertTrue(golem.getCustomName() != null && "Transporter".equals(golem.getCustomName().getString()),
			"name: " + golem.getCustomName());
		helper.assertTrue(!golem.isCustomNameVisible(), "name must only show when looked at");
		GolemService.changeProfession(helper.getLevel(), golem, LUMBER);
		helper.assertTrue("Lumber".equals(golem.getCustomName().getString()), "name after switch: " + golem.getCustomName());
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.assertTrue(golem.getCustomName() == null, "name must clear on None: " + golem.getCustomName());
		helper.succeed();
	}

	@GameTest
	public void playerNameTagIsKept(GameTestHelper helper) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		golem.setCustomName(Component.literal("Rusty")); // what a name tag does
		GolemService.changeProfession(helper.getLevel(), golem, LUMBER);
		helper.assertTrue("Rusty".equals(golem.getCustomName().getString()), "renamed golem lost its name: " + golem.getCustomName());
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.assertTrue(golem.getCustomName() != null && "Rusty".equals(golem.getCustomName().getString()), "name cleared: " + golem.getCustomName());

		CopperGolem named = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 2));
		named.setCustomName(Component.literal("Copper"));
		GolemService.changeProfession(helper.getLevel(), named, TRANSPORTER);
		helper.assertTrue("Copper".equals(named.getCustomName().getString()), "pre-named golem got renamed: " + named.getCustomName());
		helper.succeed();
	}

	// ---- 5 / 6. walk-to ----

	@GameTest(maxTicks = 900)
	public void walkToStopsNextToBlock(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(7, 1, 7);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(0, 1, 0));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		WalkTo.toBlock(helper.getLevel(), golem, helper.absolutePos(chest));
		helper.assertTrue(WalkTo.active(golem), "walk must start");
		helper.succeedWhen(() -> {
			helper.assertTrue(!WalkTo.active(golem), "still walking");
			BlockPos abs = helper.absolutePos(chest);
			helper.assertTrue(Interact.inReach(golem, abs) || Interact.nearReach(golem, abs),
				"golem must end in Interact reach of the chest, distance " + horizontal(golem, abs) + " at " + golem.position() + " chest " + abs + " tick " + helper.getTick());
		});
	}

	/** After arriving the golem stands still for about 3 s (HOLD_TICKS) before the walk ends. */
	@GameTest(maxTicks = 900)
	public void walkToHoldsAtTargetThreeSeconds(GameTestHelper helper) {
		floor(helper);
		BlockPos chest = new BlockPos(5, 1, 5);
		helper.setBlock(chest, Blocks.CHEST);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(1, 1, 1));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		BlockPos abs = helper.absolutePos(chest);
		WalkTo.toBlock(helper.getLevel(), golem, abs);
		long[] arrivedAt = {-1};
		net.minecraft.world.phys.Vec3[] arrivedPos = {null};
		helper.onEachTick(() -> {
			if (arrivedAt[0] < 0) {
				if (WalkTo.active(golem) && Interact.inReach(golem, abs)) {
					arrivedAt[0] = helper.getTick();
					arrivedPos[0] = golem.position();
				}
				return;
			}
			long since = helper.getTick() - arrivedAt[0];
			if (since < 55) {
				helper.assertTrue(WalkTo.active(golem), "hold ended too early after " + since + " ticks");
				helper.assertTrue(golem.position().distanceTo(arrivedPos[0]) < 0.3, "golem moved while holding: " + golem.position() + " vs " + arrivedPos[0]);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(arrivedAt[0] >= 0, "not arrived yet");
			helper.assertTrue(!WalkTo.active(golem), "still holding");
			helper.assertTrue(helper.getTick() - arrivedAt[0] >= 55, "hold ended too early");
			helper.assertTrue(helper.getTick() - arrivedAt[0] <= 70, "hold lasted too long: " + (helper.getTick() - arrivedAt[0]));
		});
	}

	/** Area assign stores the area but starts no walk; a block assign still starts one. */
	@GameTest
	public void areaAssignDoesNotWalkBlockAssignDoes(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
		helper.setBlock(new BlockPos(7, 0, 6), Blocks.STONE);
		helper.setBlock(new BlockPos(7, 1, 7), Blocks.CHEST);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("cleaner"));
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		AssignMode.arm(player, golem, "area");
		helper.assertTrue(AssignMode.onClick(player, helper.absolutePos(new BlockPos(2, 0, 2))), "first corner click");
		AssignMode.onClick(player, helper.absolutePos(new BlockPos(7, 0, 6)));
		helper.assertTrue(GolemData.get(golem).assignments.containsKey("area_b"), "area must be stored");
		helper.assertTrue(!WalkTo.active(golem), "an area assign must start no WalkTo");
		AssignMode.arm(player, golem, "output");
		AssignMode.onClick(player, helper.absolutePos(new BlockPos(7, 1, 7)));
		helper.assertTrue(WalkTo.active(golem), "a block assign must still start a WalkTo");
		WalkTo.cancel(golem);
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void unreachableWaypointIsSkipped(GameTestHelper helper) {
		floor(helper);
		// a corner walled in by obstacles: stand positions are computed on the floor, the walk must still finish
		BlockPos a = helper.absolutePos(new BlockPos(1, 0, 1));
		BlockPos b = helper.absolutePos(new BlockPos(5, 0, 5));
		for (int y = 1; y <= 3; y++) {
			for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) {
				boolean ring = x == 4 || x == 6 || z == 4 || z == 6;
				if (ring) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
			}
		}
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(1, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		WalkTo.outline(helper.getLevel(), golem, a, b);
		helper.succeedWhen(() -> helper.assertTrue(!WalkTo.active(golem), "walk must end despite an unreachable corner"));
	}

	// ---- area clamp and help ----

	@GameTest
	public void areaCornerIsCappedNotRejected(GameTestHelper helper) {
		BlockPos first = new BlockPos(0, 5, 0);
		helper.assertTrue(new BlockPos(15, 7, -3).equals(AssignMode.clampCorner(first, new BlockPos(30, 7, -3), 16)),
			"positive overshoot: " + AssignMode.clampCorner(first, new BlockPos(30, 7, -3), 16));
		helper.assertTrue(new BlockPos(-8, 0, 8).equals(AssignMode.clampCorner(first, new BlockPos(-40, 0, 40), 9)),
			"negative/positive overshoot: " + AssignMode.clampCorner(first, new BlockPos(-40, 0, 40), 9));
		BlockPos inside = new BlockPos(-4, 9, 15);
		helper.assertTrue(inside.equals(AssignMode.clampCorner(first, inside, 16)), "a fitting corner must stay");
		BlockPos c = AssignMode.clampCorner(first, new BlockPos(100, 0, 100), 16);
		helper.assertTrue(AssignMode.areaFits(first, c, 16) && c.getX() == 15 && c.getZ() == 15, "capped corner fits exactly: " + c);
		helper.succeed();
	}

	@GameTest
	public void everyProfessionHasHelp(GameTestHelper helper) {
		helper.assertTrue(Professions.all().size() >= 6, "professions: " + Professions.all().size());
		for (Profession p : Professions.all()) {
			helper.assertTrue(!p.help().isEmpty(), p.id() + " has no help text");
			helper.assertTrue(p.help().size() <= 6, p.id() + " help is too long: " + p.help().size());
			helper.assertTrue(p.assigns().size() <= 4, p.id() + " has more assigns than the Targets tab has columns");
			for (Profession.AssignSpec a : p.assigns()) {
				helper.assertTrue(!a.help().isEmpty(), p.id() + "/" + a.key() + " has no help text");
				String prompt = ProfessionHelp.prompt(a);
				helper.assertTrue(a.kind() != Profession.Kind.AREA || prompt.contains("max " + a.maxSize() + "x" + a.maxSize()),
					"area prompt must state the max size: " + prompt);
			}
			helper.assertTrue(ProfessionHelp.lines(p).size() >= p.help().size() + p.assigns().size(), p.id() + " help lines");
		}
		helper.succeed();
	}

	// ---- safety net ----

	/** A throwing tick is swallowed (no exception reaches the server) and the golem is paused, not retried each tick. */
	@GameTest
	public void throwingGolemBacksOff(GameTestHelper helper) {
		floor(helper);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("chest_sorter"));
		int[] calls = { 0 };
		Runnable boom = () -> {
			calls[0]++;
			throw new IllegalStateException("test failure");
		};
		GolemTicker.rethrow = false; // same server thread, nothing interleaves before it is restored
		try {
			GolemTicker.tick(helper.getLevel(), golem, boom);
			helper.assertTrue(calls[0] == 1, "tick ran once: " + calls[0]);
			helper.assertTrue(GolemTicker.coolingDown(golem), "golem backs off");
			for (int i = 0; i < 50; i++) GolemTicker.tick(helper.getLevel(), golem, boom);
			helper.assertTrue(calls[0] == 1, "skipped while cooling down: " + calls[0]);
		} finally {
			GolemTicker.rethrow = true;
		}
		helper.succeed();
	}
}
