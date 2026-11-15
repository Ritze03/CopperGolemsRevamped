package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.core.GolemData;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.pattern.BlockPattern;

/**
 * Remembers the chest the copper-golem build pattern leaves where the copper block was (pattern cell 0,1,0, the
 * block under the pumpkin) in {@link GolemData#spawnChest}. Golems made any other way never get one.
 */
@Mixin(CarvedPumpkinBlock.class)
public abstract class GolemSpawnMixin {
	@WrapOperation(method = "trySpawnGolem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/CarvedPumpkinBlock;replaceCopperBlockWithChest(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/pattern/BlockPattern$BlockPatternMatch;)V"))
	private void cgr$rememberChest(CarvedPumpkinBlock self, Level level, BlockPattern.BlockPatternMatch match,
		Operation<Void> original, @Local CopperGolem golem) {
		original.call(self, level, match);
		GolemData data = GolemData.get(golem);
		data.spawnChest = match.getBlock(0, 1, 0).getPos().immutable();
		GolemData.save(golem, data);
	}
}
