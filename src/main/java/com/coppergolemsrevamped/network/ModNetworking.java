package com.coppergolemsrevamped.network;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.menu.GolemMenuOpener;
import com.coppergolemsrevamped.network.GolemPayloads.AssignTarget;
import com.coppergolemsrevamped.network.GolemPayloads.SelectProfession;
import com.coppergolemsrevamped.network.GolemPayloads.ToggleFollow;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import java.util.Objects;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class ModNetworking {
	private ModNetworking() {}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(SelectProfession.TYPE, SelectProfession.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ToggleFollow.TYPE, ToggleFollow.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AssignTarget.TYPE, AssignTarget.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SelectProfession.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			CopperGolem golem = resolve(player, payload.golemId());
			if (golem == null || !(golem.level() instanceof ServerLevel level)) return;
			var id = payload.profession().orElse(null);
			if (id != null && Professions.get(id) == null) return;
			if (Objects.equals(id, GolemData.get(golem).profession)) return;
			GolemService.changeProfession(level, golem, id);
			// the slot layout depends on the profession, so rebuild the menu
			GolemMenuOpener.open(player, golem);
		}));

		ServerPlayNetworking.registerGlobalReceiver(ToggleFollow.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			CopperGolem golem = resolve(player, payload.golemId());
			if (golem == null) return;
			GolemData data = GolemData.get(golem);
			data.following = !data.following;
			data.followPlayer = data.following ? player.getUUID() : null;
			GolemData.save(golem, data);
		}));

		ServerPlayNetworking.registerGlobalReceiver(AssignTarget.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
			ServerPlayer player = ctx.player();
			CopperGolem golem = resolve(player, payload.golemId());
			if (golem == null) return;
			Profession p = GolemService.profession(golem);
			if (p == null || p.assigns().stream().noneMatch(a -> a.key().equals(payload.key()))) return;
			player.closeContainer();
			AssignMode.arm(player, golem, payload.key());
		}));
	}

	/** Trust boundary: the player must have this golem's menu open and the golem must be alive and in range. */
	private static @Nullable CopperGolem resolve(ServerPlayer player, int golemId) {
		if (!(player.containerMenu instanceof GolemMenu menu) || menu.golemId() != golemId) return null;
		if (!(player.level().getEntity(golemId) instanceof CopperGolem golem)) return null;
		return GolemMenu.inRange(player, golem) ? golem : null;
	}
}
