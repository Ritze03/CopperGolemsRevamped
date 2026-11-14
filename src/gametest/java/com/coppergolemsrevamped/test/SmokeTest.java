package com.coppergolemsrevamped.test;

import net.minecraft.gametest.framework.GameTestHelper;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

public class SmokeTest {
	@GameTest
	public void smoke(GameTestHelper helper) {
		helper.succeed();
	}
}
