package com.coppergolemsrevamped.mixin.oxidation;

import com.coppergolemsrevamped.core.GolemService;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A statue block entity removed without a revival (mined, exploded, replaced) drops the golem's stored items, XP and
 * held item, like a container drops its contents. {@code preRemoveSideEffects} is the hook vanilla containers use: it
 * runs when the block entity goes away, not when the statue only weathers, is waxed or scraped (the block entity is
 * kept then). {@code CopperGolemStatueBlockEntity} does not override it, so the hook sits on {@link BlockEntity}.
 * See superdoc/features/oxidation.md.
 */
@Mixin(BlockEntity.class)
public abstract class StatueRemovalMixin {
	@Inject(method = "preRemoveSideEffects", at = @At("HEAD"))
	private void cgr$statueRemoved(BlockPos pos, BlockState state, CallbackInfo ci) {
		if ((Object) this instanceof CopperGolemStatueBlockEntity statue && statue.getLevel() instanceof ServerLevel server) {
			GolemService.statueRemoved(server, pos, statue);
		}
	}
}
