package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemData;

import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/** Screen opening data (server to client): everything the client menu needs to build its layout. */
public record GolemMenuData(int golemId, Optional<Identifier> profession, boolean following, Map<String, BlockPos> assignments) {
	public static final StreamCodec<ByteBuf, GolemMenuData> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GolemMenuData::golemId,
		ByteBufCodecs.optional(Identifier.STREAM_CODEC), GolemMenuData::profession,
		ByteBufCodecs.BOOL, GolemMenuData::following,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.stringUtf8(64), BlockPos.STREAM_CODEC), GolemMenuData::assignments,
		GolemMenuData::new);

	public static GolemMenuData of(CopperGolem golem) {
		GolemData d = GolemData.get(golem);
		return new GolemMenuData(golem.getId(), Optional.ofNullable(d.profession), d.following, new HashMap<>(d.assignments));
	}
}
