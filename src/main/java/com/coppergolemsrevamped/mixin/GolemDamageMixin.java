package com.coppergolemsrevamped.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;

/**
 * Copper golems only take damage caused by a player (superdoc/features/health.md). Why a separate mixin on
 * {@code LivingEntity}: {@code isInvulnerableTo(ServerLevel, DamageSource)} is declared there, not in
 * {@code CopperGolem}, and {@code hurtServer} checks it first.
 */
@Mixin(LivingEntity.class)
public abstract class GolemDamageMixin {
	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void cgr$onlyPlayersHurtGolems(ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof CopperGolem)) return;
		// void and /kill must still kill; otherwise only a player (directly or via arrow/trident) hurts
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.getEntity() instanceof Player) return;
		cir.setReturnValue(true);
	}
}
