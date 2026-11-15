package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.core.GolemService;

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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

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

	@GameTest
	public void ticketMovesWithGolem(GameTestHelper helper) {
		CopperGolem golem = working(helper);
		ChunkPos from = golem.chunkPosition();
		ChunkPos to = new ChunkPos(from.x() + 1, from.z());
		helper.assertTrue(ticketAt(helper.getLevel(), from), "no ticket at start");
		BlockPos target = new BlockPos(to.getMiddleBlockX(), (int) golem.getY() + 2, to.getMiddleBlockZ());
		boolean[] moved = {false};
		helper.succeedWhen(() -> {
			if (!moved[0]) {
				// our own ticket (radius 3) loads and ticks the next chunk; a golem teleported into a chunk that is
				// not entity-ticking yet would not tick either
				helper.assertTrue(helper.getLevel().isPositionEntityTicking(target), "waiting for the next chunk to tick");
				golem.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
				moved[0] = true;
			}
			helper.assertTrue(golem.chunkPosition().equals(to), "golem chunk: " + golem.chunkPosition());
			helper.assertTrue(ticketAt(helper.getLevel(), to), "no ticket at " + to);
			assertConsistent(helper, from);
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
}
