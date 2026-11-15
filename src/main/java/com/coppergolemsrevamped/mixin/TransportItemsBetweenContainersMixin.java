package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.level.Level;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
	/** The bare literal 10 in {@code visitedPositions.size() > 10}; the only 10 in this method. */
	@ModifyConstant(method = "setVisitedBlockPos", constant = @Constant(intValue = 10))
	private int cgr$visitedLimit(int vanilla, PathfinderMob body, Level level, BlockPos target) {
		return ChestSorterProfession.visitedLimit(body);
	}
}
