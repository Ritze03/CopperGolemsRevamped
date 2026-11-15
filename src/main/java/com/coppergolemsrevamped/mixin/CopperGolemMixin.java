package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.core.DisplayItems;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.core.GolemTicker;
import com.coppergolemsrevamped.menu.GolemMenuOpener;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin {
	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void cgr$emptyHand(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
			if (player instanceof ServerPlayer sp) GolemMenuOpener.open(sp, (CopperGolem) (Object) this);
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		// Vanilla throws the golem's main-hand item at an empty-handed player (e.g. off-hand pass with a block in the
		// main hand). The hand shows a display copy of a stored tool or carried stack: throwing it would duplicate it.
		if (player.getItemInHand(hand).isEmpty() && DisplayItems.is(((CopperGolem) (Object) this).getMainHandItem())) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
	private void cgr$gateBrain(Brain<CopperGolem> brain, ServerLevel level, LivingEntity body, Operation<Void> original) {
		GolemTicker.tick(level, (CopperGolem) (Object) this, () -> original.call(brain, level, body));
	}

	@Inject(method = "dropEquipment", at = @At("TAIL"))
	private void cgr$dropStored(ServerLevel level, CallbackInfo ci) {
		GolemService.dropAll(level, (CopperGolem) (Object) this);
	}

	@Inject(method = "turnToStatue", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/animal/golem/CopperGolem;dropPreservedEquipment(Lnet/minecraft/server/level/ServerLevel;)V"))
	private void cgr$statueDrops(ServerLevel level, CallbackInfo ci) {
		GolemService.dropAll(level, (CopperGolem) (Object) this);
	}
}
