package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.profession.butcher.ButcherLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;

/**
 * One-sided push between a Butcher and an animal: {@code Entity:push(Entity)} pushes both entities, each only if its
 * {@code isPushable()} holds. Around the call the Butcher is marked ({@link ButcherLogic#pushImmune}) and
 * {@code LivingEntity:isPushable} (see {@link LivingEntityPushMixin}) reports false for it, so only the animal moves.
 * Covers both directions (the animal's {@code pushEntities} and the golem's), since both end in this method.
 */
@Mixin(Entity.class)
public abstract class EntityPushMixin {
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void cgr$pushStart(Entity other, CallbackInfo ci) {
		ButcherLogic.pushImmune = ButcherLogic.butcherAmongAnimal((Entity) (Object) this, other);
	}

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
	private void cgr$pushEnd(Entity other, CallbackInfo ci) {
		ButcherLogic.pushImmune = null;
	}
}
