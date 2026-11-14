package com.coppergolemsrevamped.network;

import com.coppergolemsrevamped.CopperGolemsRevamped;

import io.netty.buffer.ByteBuf;

import java.util.Optional;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Serverbound GUI payloads (all carry the golem's entity id). */
public final class GolemPayloads {
	private GolemPayloads() {}

	/** Empty profession = none. */
	public record SelectProfession(int golemId, Optional<Identifier> profession) implements CustomPacketPayload {
		public static final Type<SelectProfession> TYPE = new Type<>(CopperGolemsRevamped.id("select_profession"));
		public static final StreamCodec<ByteBuf, SelectProfession> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SelectProfession::golemId,
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), SelectProfession::profession,
			SelectProfession::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record ToggleFollow(int golemId) implements CustomPacketPayload {
		public static final Type<ToggleFollow> TYPE = new Type<>(CopperGolemsRevamped.id("toggle_follow"));
		public static final StreamCodec<ByteBuf, ToggleFollow> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ToggleFollow::golemId, ToggleFollow::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** {@code key} is an AssignSpec key (base key for AREA specs). */
	public record AssignTarget(int golemId, String key) implements CustomPacketPayload {
		public static final Type<AssignTarget> TYPE = new Type<>(CopperGolemsRevamped.id("assign_target"));
		public static final StreamCodec<ByteBuf, AssignTarget> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AssignTarget::golemId,
			ByteBufCodecs.stringUtf8(64), AssignTarget::key,
			AssignTarget::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
