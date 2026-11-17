package com.coppergolemsrevamped.mixin.oxidation;

import com.coppergolemsrevamped.core.GolemService;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Revival (axe on an unaffected statue): the new golem gets the data stored on the statue back. See superdoc/features/oxidation.md. */
@Mixin(CopperGolemStatueBlockEntity.class)
public abstract class CopperGolemStatueBlockEntityMixin {
	@Inject(method = "removeStatue", at = @At("RETURN"))
	private void cgr$revive(BlockState state, CallbackInfoReturnable<CopperGolem> cir) {
		CopperGolem golem = cir.getReturnValue();
		if (golem != null) GolemService.fromStatue((CopperGolemStatueBlockEntity) (Object) this, golem);
	}
}
