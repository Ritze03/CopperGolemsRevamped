package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.butcher.ButcherLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

/**
 * Lets the Butcher take the death loot of the animal it is striking straight into its carry: every other
 * {@code spawnAtLocation} overload funnels into this one. Inert unless {@link ButcherLogic#capture} claims the entity.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;",
		at = @At("HEAD"), cancellable = true)
	private void cgr$butcherCapture(ServerLevel level, ItemStack stack, Vec3 offset, CallbackInfoReturnable<ItemEntity> cir) {
		if (ButcherLogic.capture((Entity) (Object) this, stack)) cir.setReturnValue(null);
	}

	/**
	 * Copper golems never use a portal (nether portal, end portal, end gateway: all go through this method), so a
	 * golem that wanders or paths into one stays in its dimension. Only a golem in Follow mode may travel, so a player
	 * can take it along. Why: a golem that leaves the dimension silently loses its workplace.
	 */
	@Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
	private void cgr$noPortal(Portal portal, BlockPos pos, CallbackInfo ci) {
		if ((Object) this instanceof CopperGolem golem && !GolemData.get(golem).following) ci.cancel();
	}
}
