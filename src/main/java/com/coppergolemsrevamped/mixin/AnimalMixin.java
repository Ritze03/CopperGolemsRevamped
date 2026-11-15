package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.profession.butcher.ButcherLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;

/**
 * Lets a Butcher keep the XP of the breeding it caused: vanilla spawns the orb in
 * {@code finalizeSpawnChildFromBreeding}; for a pair a Butcher fed, {@link ButcherLogic#takeBreedingXp} takes the value
 * instead and no orb appears. Inert for every other breeding.
 */
@Mixin(Animal.class)
public abstract class AnimalMixin {
	@Redirect(method = "finalizeSpawnChildFromBreeding", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean cgr$breedingOrb(ServerLevel level, Entity orb, ServerLevel level2, Animal partner, AgeableMob baby) {
		if (orb instanceof ExperienceOrb o && ButcherLogic.takeBreedingXp(level, (Animal) (Object) this, partner, o.getValue())) return true;
		return level.addFreshEntity(orb);
	}
}
