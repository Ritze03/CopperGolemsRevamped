package com.coppergolemsrevamped.chunk;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.ModAttachments;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Keeps the chunks around every golem with a profession loaded and entity-ticking, so it works with no player near.
 * See superdoc/features/chunk-loading.md for the design and its limits.
 */
public final class GolemChunkLoader {
	/**
	 * Ticket radius 3 = ticket level 30: the golem's chunk is level 30, its 8 neighbours level 31 = entity ticking
	 * ({@code ChunkLevel#ENTITY_TICKING_LEVEL}), the next ring only block ticking.
	 */
	public static final int RADIUS = 3;
	/** Ticks between ticket refreshes while the golem stays in one chunk. */
	static final int REFRESH_TICKS = 100;
	/** A ticket nobody refreshes expires after this many ticks (30 s). */
	static final long TIMEOUT_TICKS = 600;

	/**
	 * Persistent (saved natively in the level's ticket storage, so it survives a restart), loads and simulates chunks,
	 * keeps an otherwise player-less dimension ticking. Not on Fabric registry sync's explicit list of synced
	 * registries, so vanilla clients are fine.
	 * Why a timeout: a ticket whose golem vanished without our hook (world edit, crash) cleans itself up instead of
	 * pinning chunks forever; golems refresh theirs every {@link #REFRESH_TICKS}.
	 */
	public static final TicketType TYPE = Registry.register(BuiltInRegistries.TICKET_TYPE, CopperGolemsRevamped.id("golem"),
		new TicketType(TIMEOUT_TICKS, TicketType.FLAG_PERSIST | TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
			| TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

	private record Spot(ResourceKey<Level> dimension, ChunkPos chunk) {}

	/** Where each golem's ticket sits. Runtime only: after a restart the first golem tick re-adds it. */
	private static final Map<UUID, Spot> HELD = new HashMap<>();
	/**
	 * Golems per ticket spot. Why: tickets of one type and level in a chunk merge into one, so two golems in the same
	 * chunk share a ticket and it may only go when the last of them leaves.
	 */
	private static final Map<Spot, Set<UUID>> USERS = new HashMap<>();

	private GolemChunkLoader() {}

	public static void init() {
		// A removal that is not a plain chunk unload (killed, discarded, statue, dimension change) releases the ticket.
		// A chunk unload must not: the golem comes back with the chunk.
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CopperGolem golem && isGone(golem)) {
				logRemoval(level, golem);
				release(level, golem);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(GolemChunkLoader::clear);
	}

	/**
	 * INFO line for every golem that leaves the world for good. Why: golems vanished from a live server with no trace;
	 * this names the cause (RemovalReason) at the next one. See superdoc/features/golem-safety.md.
	 */
	private static void logRemoval(ServerLevel level, CopperGolem golem) {
		GolemData data = golem.getAttached(ModAttachments.GOLEM_DATA);
		CopperGolemsRevamped.LOGGER.info("Copper golem removed: name={} uuid={} profession={} dimension={} pos={} reason={}",
			golem.getName().getString(), golem.getUUID(), data == null || data.profession == null ? "none" : data.profession,
			level.dimension().identifier(), golem.blockPosition().toShortString(), golem.getRemovalReason());
	}

	private static boolean isGone(Entity e) {
		Entity.RemovalReason reason = e.getRemovalReason();
		return reason != null && !reason.shouldSave();
	}

	/** Ticks of the profession golem: (re)place the ticket at its current chunk, cheap unless the chunk changed or a refresh is due. */
	public static void keep(ServerLevel level, CopperGolem golem) {
		Spot now = new Spot(level.dimension(), golem.chunkPosition());
		Spot old = HELD.get(golem.getUUID());
		if (now.equals(old) && golem.tickCount % REFRESH_TICKS != 0) return;
		level.getChunkSource().addTicketWithRadius(TYPE, golem.chunkPosition(), RADIUS); // same type+level: refreshes the timeout
		HELD.put(golem.getUUID(), now);
		USERS.computeIfAbsent(now, k -> new HashSet<>()).add(golem.getUUID());
		if (old != null && !old.equals(now)) drop(level.getServer(), golem.getUUID(), old);
	}

	/** The golem no longer needs its chunks (profession None, died, converted, left the dimension). */
	public static void release(ServerLevel level, CopperGolem golem) {
		Spot old = HELD.remove(golem.getUUID());
		if (old != null) drop(level.getServer(), golem.getUUID(), old);
	}

	/** Test hook: is the golem registered as holding a ticket. */
	public static boolean holds(CopperGolem golem) {
		return HELD.containsKey(golem.getUUID());
	}

	/** Test hook: how many golems currently share the ticket at this chunk. */
	public static int users(ServerLevel level, ChunkPos chunk) {
		Set<UUID> users = USERS.get(new Spot(level.dimension(), chunk));
		return users == null ? 0 : users.size();
	}

	private static void drop(MinecraftServer server, UUID golem, Spot spot) {
		Set<UUID> users = USERS.get(spot);
		if (users == null) return;
		users.remove(golem);
		if (!users.isEmpty()) return;
		USERS.remove(spot);
		ServerLevel level = server.getLevel(spot.dimension());
		if (level != null) level.getChunkSource().removeTicketWithRadius(TYPE, spot.chunk(), RADIUS);
	}

	private static void clear(MinecraftServer server) {
		HELD.clear();
		USERS.clear();
	}
}
