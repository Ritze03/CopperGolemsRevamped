package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.profession.butcher.ButcherLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.LivingEntity;

/** See {@link EntityPushMixin}: a Butcher is not pushable while an animal pushes it. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPushMixin {
	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void cgr$butcherNotPushed(CallbackInfoReturnable<Boolean> cir) {
		if (ButcherLogic.pushImmune == (Object) this) cir.setReturnValue(false);
	}
}
