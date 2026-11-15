package com.coppergolemsrevamped.mixin;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Exposes the furnace's stored-XP map. The Smelter pops the XP with {@code getRecipesToAwardAndPopExperience}, which
 * (unlike the player variant) does not clear the map; without clearing, the XP would pop again on every collect.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {
	@Accessor("recipesUsed")
	Reference2IntOpenHashMap<ResourceKey<Recipe<?>>> cgr$recipesUsed();
}
