package com.coppergolemsrevamped.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/** Health rules: only players hurt a golem (void and /kill still kill), 1 HP regeneration per 600 ticks (health.md). */
public class HealthTest {
	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	private static CopperGolem golem(GameTestHelper helper) {
		floor(helper);
		return helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(3, 1, 3));
	}

	/** Real suffocation: the golem stands inside a solid block for 100 ticks and keeps its full health. */
	@GameTest(maxTicks = 200)
	public void suffocationDoesNothing(GameTestHelper helper) {
		CopperGolem g = golem(helper);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(g.isAlive() && g.getHealth() == g.getMaxHealth(), "health " + g.getHealth());
			helper.succeed();
		});
	}

	/** Fall, lava, fire, drowning, a mob, an explosion and lightning are all ignored. */
	@GameTest
	public void nonPlayerDamageIsIgnored(GameTestHelper helper) {
		CopperGolem g = golem(helper);
		ServerLevel level = helper.getLevel();
		Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
		var ds = level.damageSources();
		DamageSource[] sources = {
			ds.fall(), ds.lava(), ds.inFire(), ds.onFire(), ds.drown(), ds.inWall(), ds.mobAttack(zombie),
			ds.explosion(null, zombie), ds.lightningBolt(), ds.cactus(), ds.magic(), ds.generic()
		};
		for (DamageSource s : sources) {
			g.hurtServer(level, s, 6.0F);
			helper.assertTrue(g.getHealth() == g.getMaxHealth(), s.getMsgId() + " hurt the golem: " + g.getHealth());
		}
		helper.succeed();
	}

	/** A player's melee hit hurts; so does an arrow shot by a player (the attacking entity is the player). */
	@GameTest
	public void playerDamageHurts(GameTestHelper helper) {
		CopperGolem g = golem(helper);
		ServerLevel level = helper.getLevel();
		Player player = helper.makeMockServerPlayerInLevel();
		g.hurtServer(level, level.damageSources().playerAttack(player), 3.0F);
		helper.assertTrue(g.getHealth() == g.getMaxHealth() - 3.0F, "melee: health " + g.getHealth());
		CopperGolem h = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(5, 1, 5));
		h.hurtServer(level, level.damageSources().thrown(null, player), 2.0F);
		helper.assertTrue(h.getHealth() == h.getMaxHealth() - 2.0F, "projectile: health " + h.getHealth());
		helper.succeed();
	}

	/** Void damage and /kill (generic kill) bypass the immunity. */
	@GameTest
	public void voidAndKillStillKill(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem a = golem(helper);
		CopperGolem b = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(5, 1, 5));
		a.hurtServer(level, level.damageSources().fellOutOfWorld(), 1000.0F);
		b.kill(level);
		helper.assertTrue(!a.isAlive(), "void did not kill");
		helper.assertTrue(!b.isAlive(), "kill did not kill");
		helper.succeed();
	}

	/**
	 * A damaged golem gains exactly 1 HP per 600 ticks. Waits for tickCount % 600 == 1, damages the golem, then checks
	 * 300 ticks later (no gain yet) and 650 ticks later (exactly one regen tick, at tickCount % 600 == 0).
	 */
	@GameTest(maxTicks = 1800)
	public void regeneratesOneHpPerThirtySeconds(GameTestHelper helper) {
		CopperGolem g = golem(helper);
		int[] start = {-1};
		helper.onEachTick(() -> {
			if (start[0] < 0) {
				if (g.tickCount % 600 == 1) {
					g.setHealth(5.0F);
					start[0] = g.tickCount;
				}
				return;
			}
			int elapsed = g.tickCount - start[0];
			if (elapsed == 300) helper.assertTrue(g.getHealth() == 5.0F, "gained early: " + g.getHealth());
			if (elapsed == 650) {
				helper.assertTrue(g.getHealth() == 6.0F, "expected 6 HP, got " + g.getHealth());
				helper.succeed();
			}
		});
	}
}
