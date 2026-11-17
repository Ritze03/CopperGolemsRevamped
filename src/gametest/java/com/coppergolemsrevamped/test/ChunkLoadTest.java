package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Chunk loading: a profession golem holds a ticket at its chunk, which follows it and goes with its profession or life. */
public class ChunkLoadTest {
	private static final Identifier TRANSPORTER = CopperGolemsRevamped.id("transporter");

	private static TicketStorage storage(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
	}

	private static boolean ticketAt(ServerLevel level, ChunkPos pos) {
		for (Ticket t : storage(level).getTickets(pos.pack())) if (t.getType() == GolemChunkLoader.TYPE) return true;
		return false;
	}

	/** Other tests' golems may share the chunk: the ticket exists exactly while some golem still holds it. */
	private static void assertConsistent(GameTestHelper helper, ChunkPos pos) {
		ServerLevel level = helper.getLevel();
		boolean has = ticketAt(level, pos), used = GolemChunkLoader.users(level, pos) > 0;
		helper.assertTrue(has == used, "ticket at " + pos + ": " + has + ", holders: " + GolemChunkLoader.users(level, pos));
	}

	private static CopperGolem working(GameTestHelper helper) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, TRANSPORTER);
		return golem;
	}

	@GameTest
	public void workingGolemHoldsTicket(GameTestHelper helper) {
		CopperGolem golem = working(helper);
		ChunkPos pos = golem.chunkPosition();
		helper.assertTrue(GolemChunkLoader.holds(golem), "golem must hold a ticket");
		helper.assertTrue(ticketAt(helper.getLevel(), pos), "no ticket at " + pos);
		// level 30 at the centre, so the 8 neighbours are 31 = entity ticking
		int level = storage(helper.getLevel()).getTicketLevelAt(pos.pack(), true);
		helper.assertTrue(level <= 30, "ticket level at the golem's chunk: " + level);
		helper.succeed();
	}

	/**
	 * Why the golem is teleported 40 blocks up (with no gravity): GameTest structures sit packed side by side, so the
	 * next chunk is inside another test's bounds, and that test's {@code succeed()} discards every entity in its
	 * bounds, including a golem teleported in (flaky "no ticket at ..." with a golem that was discarded before its
	 * first tick). Above the structures nothing reaches it.
	 * <p>
	 * Why maxTicks 3000: GameTests tick as fast as the server can, but the next chunk is generated on worker
	 * threads, in a batch of 50 parallel tests that can take longer than 200 ticks (~1 s) of wall time. The wait is
	 * for a condition, so the bound is only a safety net.
	 */
	@GameTest(maxTicks = 3000)
	public void ticketMovesWithGolem(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = working(helper);
		ChunkPos from = golem.chunkPosition();
		ChunkPos to = new ChunkPos(from.x() + 1, from.z());
		helper.assertTrue(ticketAt(level, from), "no ticket at start");
		BlockPos target = new BlockPos(to.getMiddleBlockX(), (int) golem.getY() + 40, to.getMiddleBlockZ());
		boolean[] moved = {false};
		helper.succeedWhen(() -> {
			if (!moved[0]) {
				// our own ticket (radius 3) loads and ticks the next chunk; a golem teleported into a chunk that is
				// not entity-ticking yet would not tick either
				helper.assertTrue(level.isPositionEntityTicking(target), "waiting for the next chunk to tick: " + to
					+ ", ticket level there: " + storage(level).getTicketLevelAt(to.pack(), true) + ", golem holds: " + GolemChunkLoader.holds(golem));
				golem.setNoGravity(true);
				golem.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
				moved[0] = true;
			}
			helper.assertTrue(!golem.isRemoved(), "golem removed: " + golem.getRemovalReason());
			helper.assertTrue(golem.chunkPosition().equals(to), "golem chunk: " + golem.chunkPosition());
			helper.assertTrue(ticketAt(level, to), "no ticket at " + to + ", golem ticks: " + golem.tickCount);
			assertConsistent(helper, from); // the old spot is released unless another test's golem still holds it
			golem.discard();
		});
	}

	@GameTest
	public void releasedOnNone(GameTestHelper helper) {
		CopperGolem golem = working(helper);
		ChunkPos pos = golem.chunkPosition();
		GolemService.changeProfession(helper.getLevel(), golem, null);
		helper.assertTrue(!GolemChunkLoader.holds(golem), "ticket must be released");
		assertConsistent(helper, pos);
		helper.succeed();
	}

	@GameTest
	public void releasedOnDiscard(GameTestHelper helper) {
		CopperGolem golem = working(helper);
		ChunkPos pos = golem.chunkPosition();
		golem.discard();
		helper.succeedWhen(() -> {
			helper.assertTrue(!GolemChunkLoader.holds(golem), "ticket must be released");
			assertConsistent(helper, pos);
		});
	}

	@GameTest
	public void sharedTicketStaysForOtherGolem(GameTestHelper helper) {
		CopperGolem a = working(helper);
		CopperGolem b = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		b.teleportTo(a.getX(), a.getY(), a.getZ()); // the test area may straddle a chunk border
		GolemService.changeProfession(helper.getLevel(), b, TRANSPORTER);
		ChunkPos pos = a.chunkPosition();
		helper.assertTrue(b.chunkPosition().equals(pos), "both golems must share a chunk");
		GolemService.changeProfession(helper.getLevel(), a, null);
		helper.assertTrue(ticketAt(helper.getLevel(), pos), "the other golem still needs the ticket");
		GolemService.changeProfession(helper.getLevel(), b, null);
		assertConsistent(helper, pos);
		helper.succeed();
	}

	/** What a restart does: the ticket is written with the level's ticket storage and read back (persistent type). */
	@GameTest
	public void ticketSurvivesSaveLoad(GameTestHelper helper) {
		helper.assertTrue(GolemChunkLoader.TYPE.persist(), "ticket type must be persistent");
		ChunkPos pos = new ChunkPos(12345, -678); // far from anything else
		TicketStorage out = new TicketStorage();
		out.addTicketWithRadius(GolemChunkLoader.TYPE, pos, GolemChunkLoader.RADIUS);
		Tag tag = TicketStorage.CODEC.encodeStart(NbtOps.INSTANCE, out).getOrThrow();
		TicketStorage in = TicketStorage.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		in.activateAllDeactivatedTickets(); // loaded tickets start deactivated, the level activates them on load
		boolean found = false;
		for (Ticket t : in.getTickets(pos.pack())) {
			if (t.getType() == GolemChunkLoader.TYPE && t.getTicketLevel() == 30) found = true;
		}
		helper.assertTrue(found, "ticket lost in the save/load round trip: " + tag);
		helper.succeed();
	}

	/**
	 * Unloaded targets (chunk-loading.md): a Transporter whose chest B lies in an unloaded chunk far away never loads it.
	 * It takes from A (B's room is checked once B is loaded) and walks towards B; walled in, it cannot leave the test.
	 * The old code read B every retry, which loaded its chunk synchronously.
	 */
	@GameTest(maxTicks = 200)
	public void unloadedTargetIsNeverLoaded(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
			if (x != 1 && x != 5 && z != 1 && z != 5) continue;
			for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
		}
		BlockPos a = new BlockPos(2, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		ChestBlockEntity chestA = helper.getBlockEntity(a, ChestBlockEntity.class);
		chestA.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
		BlockPos far = helper.absolutePos(new BlockPos(3, 1, 3)).offset(4096, 0, 4096);
		helper.assertFalse(level.hasChunkAt(far), "the far chunk is loaded before the test even starts");
		helper.assertTrue(TransporterLogic.containerAt(level, far) == null, "containerAt must report an unloaded chest as missing");
		helper.assertFalse(level.hasChunkAt(far), "containerAt loaded the far chunk");

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(level, golem, TRANSPORTER);
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", far);
		GolemData.save(golem, data);

		helper.onEachTick(() -> helper.assertFalse(level.hasChunkAt(far), "the golem loaded the far chunk"));
		helper.runAfterDelay(120, () -> {
			helper.assertTrue(TransporterLogic.carry(GolemData.get(golem)).stream().anyMatch(s -> s.is(Items.COBBLESTONE)),
				"the golem should have taken from A to carry it towards the far chest B");
			String status = TransporterLogic.status(golem);
			helper.assertTrue(status != null && status.contains("too far"), "B is 4096 blocks away: expected the give-up status, got: " + status);
			helper.succeed();
		});
	}

	/**
	 * Far-walk give-up (chunk-loading.md, "Unloaded targets"): chest B lies in an unloaded chunk within the 256-block
	 * cap but the golem is walled in, so the distance never improves. It stops walking after ~200 ticks, shows the status
	 * line and waits instead of walking on.
	 */
	@GameTest(maxTicks = 500)
	public void unreachableFarTargetIsGivenUp(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
			if (x != 1 && x != 5 && z != 1 && z != 5) continue;
			for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
		}
		BlockPos a = new BlockPos(2, 1, 3);
		helper.setBlock(a, Blocks.CHEST);
		helper.getBlockEntity(a, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 8));
		BlockPos far = helper.absolutePos(new BlockPos(3, 1, 3)).offset(150, 0, 150);
		helper.assertFalse(level.hasChunkAt(far), "the far chunk is loaded before the test even starts");

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
		GolemService.changeProfession(level, golem, TRANSPORTER);
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", helper.absolutePos(a));
		data.assignments.put("output", far);
		GolemData.save(golem, data);

		helper.runAfterDelay(450, () -> {
			String status = TransporterLogic.status(golem);
			helper.assertTrue(status != null && status.contains("no path"), "expected the give-up status, got: " + status);
			helper.assertFalse(level.hasChunkAt(far), "the golem loaded the far chunk");
			helper.succeed();
		});
	}

	/**
	 * No portals: a golem standing in an end portal and one in a nether portal for 100 ticks stays in its dimension (a
	 * Following golem may travel; not tested). The end portal block is stable without a frame, the nether portal block
	 * may vanish without one, so it only adds to the check.
	 */
	@GameTest(maxTicks = 130)
	public void golemsDoNotUsePortals(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x < 6; x++) for (int z = 0; z < 3; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.END_PORTAL);
		helper.setBlock(new BlockPos(4, 1, 1), Blocks.NETHER_PORTAL);
		CopperGolem inEnd = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(1, 1, 1));
		CopperGolem inNether = helper.spawn(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 1));
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(inEnd.isAlive() && inEnd.level() == level, "the golem in the end portal left the dimension");
			helper.assertTrue(inNether.isAlive() && inNether.level() == level, "the golem in the nether portal left the dimension");
			helper.succeed();
		});
	}
}
