package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.FilterMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.core.ModAttachments;
import com.coppergolemsrevamped.profession.transporter.TransporterProfession;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Oxidation into a statue keeps everything: the golem's data and held item ride on the statue block entity and come
 * back on revival; a mined statue drops the items instead. See superdoc/features/oxidation.md.
 */
public class OxidationTest {
	private static final BlockPos AT = new BlockPos(2, 1, 2);
	private static Item statueItem() {
		return ((Block) Blocks.COPPER_GOLEM_STATUE.weathering().oxidized()).asItem();
	}

	private static final UUID FOLLOWED = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** A Transporter with assignments, every setting off its default, items in carry and filter, XP and a real held item. */
	private static CopperGolem workingGolem(GameTestHelper helper) {
		floor(helper);
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, AT);
		GolemService.changeProfession(level, golem, TransporterProfession.ID);
		GolemData d = GolemData.get(golem);
		d.assignments.put("input", helper.absolutePos(new BlockPos(0, 1, 0)));
		d.assignments.put("output", helper.absolutePos(new BlockPos(5, 1, 5)));
		d.items.set(0, new ItemStack(Items.COBBLESTONE, 64));
		d.items.set(4, new ItemStack(Items.DIAMOND, 3));
		d.items.set(TransporterProfession.FILTER_FROM, new ItemStack(Items.DIAMOND));
		d.xp = 20;
		d.frozen = true;
		d.following = true;
		d.followPlayer = FOLLOWED;
		d.filterMode = FilterMode.WHITELIST;
		d.stripLogs = true;
		d.keepLayout = true;
		GolemData.save(golem, d);
		golem.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD, 5));
		golem.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		return golem;
	}

	/** Vanilla's private {@code CopperGolem#turnToStatue}, the end of full oxidation (normally a random roll per tick). */
	private static void turnToStatue(CopperGolem golem, ServerLevel level) {
		try {
			Method m = CopperGolem.class.getDeclaredMethod("turnToStatue", ServerLevel.class);
			m.setAccessible(true);
			m.invoke(golem, level);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot call turnToStatue", e);
		}
	}

	private static void setNextWeatheringTick(CopperGolem golem, long value) {
		try {
			java.lang.reflect.Field f = CopperGolem.class.getDeclaredField("nextWeatheringTick");
			f.setAccessible(true);
			f.setLong(golem, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot set nextWeatheringTick", e);
		}
	}

	private static void updateWeathering(CopperGolem golem, ServerLevel level) {
		try {
			Method m = CopperGolem.class.getDeclaredMethod("updateWeathering", ServerLevel.class, net.minecraft.util.RandomSource.class, long.class);
			m.setAccessible(true);
			m.invoke(golem, level, level.getRandom(), level.getGameTime());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot call updateWeathering", e);
		}
	}

	/**
	 * No oxidation: a golem whose next stage is due stays at its stage, and a fully oxidized one never turns into a
	 * statue (vanilla rolls 0.58% per tick; 3000 rolls make a statue practically certain without the mixin).
	 */
	@GameTest
	public void golemsNeverOxidize(GameTestHelper helper) {
		floor(helper);
		ServerLevel level = helper.getLevel();
		CopperGolem exposed = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, AT);
		exposed.setWeatherState(net.minecraft.world.level.block.WeatheringCopper.WeatherState.EXPOSED);
		setNextWeatheringTick(exposed, 1L);
		CopperGolem oxidized = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(4, 1, 4));
		oxidized.setWeatherState(net.minecraft.world.level.block.WeatheringCopper.WeatherState.OXIDIZED);
		setNextWeatheringTick(oxidized, 0L);
		for (int i = 0; i < 3000; i++) {
			updateWeathering(exposed, level);
			updateWeathering(oxidized, level);
		}
		helper.assertTrue(exposed.getWeatherState() == net.minecraft.world.level.block.WeatheringCopper.WeatherState.EXPOSED, "advanced: " + exposed.getWeatherState());
		helper.assertTrue(!oxidized.isRemoved(), "oxidized golem turned into a statue");
		helper.assertTrue(level.getBlockState(oxidized.blockPosition()).isAir(), "statue block placed");
		helper.succeed();
	}

	private static int itemCount(GameTestHelper helper, Item item) {
		int n = 0;
		for (ItemEntity e : helper.getEntities(EntityTypes.ITEM, AT, 4)) if (e.getItem().is(item)) n += e.getItem().getCount();
		return n;
	}

	private static void assertRestored(GameTestHelper helper, GolemData d) {
		helper.assertTrue(TransporterProfession.ID.equals(d.profession), "profession: " + d.profession);
		helper.assertTrue(helper.absolutePos(new BlockPos(0, 1, 0)).equals(d.assignments.get("input")), "input: " + d.assignments);
		helper.assertTrue(helper.absolutePos(new BlockPos(5, 1, 5)).equals(d.assignments.get("output")), "output: " + d.assignments);
		helper.assertTrue(d.items.size() == TransporterProfession.CARRY_SLOTS + TransporterProfession.FILTER_SLOTS, "items size: " + d.items.size());
		helper.assertTrue(ItemStack.matches(d.items.get(0), new ItemStack(Items.COBBLESTONE, 64)), "slot 0: " + d.items.get(0));
		helper.assertTrue(ItemStack.matches(d.items.get(4), new ItemStack(Items.DIAMOND, 3)), "slot 4: " + d.items.get(4));
		helper.assertTrue(ItemStack.matches(d.items.get(TransporterProfession.FILTER_FROM), new ItemStack(Items.DIAMOND)), "filter: " + d.items.get(TransporterProfession.FILTER_FROM));
		helper.assertTrue(d.xp == 20, "xp: " + d.xp);
		helper.assertTrue(d.frozen, "frozen lost");
		helper.assertTrue(d.following && FOLLOWED.equals(d.followPlayer), "follow: " + d.following + " " + d.followPlayer);
		helper.assertTrue(d.filterMode == FilterMode.WHITELIST, "filter mode: " + d.filterMode);
		helper.assertTrue(d.stripLogs, "strip_logs lost");
		helper.assertTrue(d.keepLayout, "keep_layout lost");
		helper.assertTrue("Transporter".equals(d.autoName), "autoName: " + d.autoName);
	}

	/**
	 * Full cycle: statue (nothing drops), block entity save/load (chunk unload), three axe scrapes (block changes, the
	 * block entity is kept), revival with the axe: the new golem has everything back.
	 */
	@GameTest
	public void statueKeepsEverythingAndRevivalRestoresIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = workingGolem(helper);
		BlockPos pos = golem.blockPosition();
		turnToStatue(golem, level);

		helper.assertTrue(golem.isRemoved(), "golem was not removed");
		BlockState statueState = level.getBlockState(pos);
		helper.assertTrue(statueState.is((Block) Blocks.COPPER_GOLEM_STATUE.weathering().oxidized()), "not an oxidized statue: " + statueState);
		helper.assertTrue(helper.getEntities(EntityTypes.ITEM, AT, 4).isEmpty(), "items dropped: " + helper.getEntities(EntityTypes.ITEM, AT, 4));
		helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, AT, 4).isEmpty(), "xp orbs dropped");
		CopperGolemStatueBlockEntity statue = (CopperGolemStatueBlockEntity) level.getBlockEntity(pos);
		helper.assertTrue(statue != null, "no statue block entity");
		helper.assertTrue(statue.components().get(DataComponents.CUSTOM_NAME) == null, "auto name left on the statue");

		// chunk unload / reload: the block entity's own save, loaded into a fresh block entity
		CompoundTag tag = statue.saveWithFullMetadata(level.registryAccess());
		BlockEntity loaded = BlockEntity.loadStatic(pos, statueState, tag, level.registryAccess());
		helper.assertTrue(loaded != null, "statue block entity did not load");
		GolemData reloaded = loaded.getAttached(ModAttachments.GOLEM_DATA);
		helper.assertTrue(reloaded != null, "golem data not saved with the statue");
		assertRestored(helper, reloaded);
		ItemStack heldReloaded = loaded.getAttached(ModAttachments.STATUE_HELD);
		helper.assertTrue(heldReloaded != null && ItemStack.matches(heldReloaded, new ItemStack(Items.EMERALD, 5)), "held item not saved: " + heldReloaded);

		// scrape oxidized -> weathered -> exposed -> unaffected, then revive; the way a player does it
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_AXE));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		for (int i = 0; i < 6 && level.getBlockState(pos).is(BlockTags.COPPER_GOLEM_STATUES); i++) {
			ItemStack axe = player.getMainHandItem();
			InteractionResult r = level.getBlockState(pos).useItemOn(axe, level, player, InteractionHand.MAIN_HAND, hit);
			if (!r.consumesAction()) axe.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
		}
		helper.assertTrue(level.getBlockState(pos).isAir(), "statue still there: " + level.getBlockState(pos));

		List<CopperGolem> golems = helper.getEntities(EntityTypes.COPPER_GOLEM, AT, 2);
		helper.assertTrue(golems.size() == 1, "revived golems: " + golems.size());
		CopperGolem revived = golems.get(0);
		helper.assertTrue(revived != golem, "the old golem is still there");
		assertRestored(helper, GolemData.get(revived));
		helper.assertTrue(ItemStack.matches(revived.getMainHandItem(), new ItemStack(Items.EMERALD, 5)), "held item: " + revived.getMainHandItem());
		Component name = revived.getCustomName();
		helper.assertTrue(name != null && name.getString().equals("Transporter"), "name: " + name);
		helper.assertTrue(helper.getEntities(EntityTypes.ITEM, AT, 4).isEmpty(), "items dropped on revival: " + helper.getEntities(EntityTypes.ITEM, AT, 4));
		helper.succeed();
	}

	/** Vanilla keeps only the custom name in a mined statue item, so the stored items, XP and held item drop instead. */
	@GameTest
	public void minedStatueDropsEverything(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = workingGolem(helper);
		BlockPos pos = golem.blockPosition();
		turnToStatue(golem, level);
		helper.assertTrue(helper.getEntities(EntityTypes.ITEM, AT, 4).isEmpty(), "items dropped at statue conversion");

		level.destroyBlock(pos, true);
		helper.assertTrue(itemCount(helper, Items.COBBLESTONE) == 64, "cobblestone: " + itemCount(helper, Items.COBBLESTONE));
		helper.assertTrue(itemCount(helper, Items.DIAMOND) == 4, "diamonds (carry + filter): " + itemCount(helper, Items.DIAMOND));
		helper.assertTrue(itemCount(helper, Items.EMERALD) == 5, "held emeralds: " + itemCount(helper, Items.EMERALD));
		helper.assertTrue(itemCount(helper, Items.EXPERIENCE_BOTTLE) == 2, "xp bottles (20 XP = 2 bottles + 6 XP): " + itemCount(helper, Items.EXPERIENCE_BOTTLE));
		helper.assertTrue(itemCount(helper, statueItem()) == 1, "statue item: " + itemCount(helper, statueItem()));
		for (ItemEntity e : helper.getEntities(EntityTypes.ITEM, AT, 4)) {
			if (e.getItem().is(statueItem())) {
				helper.assertTrue(e.getItem().get(DataComponents.CUSTOM_NAME) == null, "statue item carries the auto name");
			}
		}
		helper.succeed();
	}

	/** A golem the mod never touched stays vanilla: no data on the statue, none on the revived golem, the name kept. */
	@GameTest
	public void plainGolemStatueStaysVanilla(GameTestHelper helper) {
		floor(helper);
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, AT);
		golem.setCustomName(Component.literal("Rusty"));
		BlockPos pos = golem.blockPosition();
		turnToStatue(golem, level);
		CopperGolemStatueBlockEntity statue = (CopperGolemStatueBlockEntity) level.getBlockEntity(pos);
		helper.assertTrue(statue != null && statue.getAttached(ModAttachments.GOLEM_DATA) == null, "data on a plain statue");
		helper.assertTrue(statue.components().get(DataComponents.CUSTOM_NAME) != null, "player name taken off the statue");
		CopperGolem revived = statue.removeStatue(level.getBlockState(pos));
		helper.assertTrue(revived != null, "no revived golem");
		helper.assertTrue(revived.getAttached(ModAttachments.GOLEM_DATA) == null, "data on a plain revived golem");
		helper.assertTrue("Rusty".equals(revived.getCustomName().getString()), "name: " + revived.getCustomName());
		helper.succeed();
	}
}
