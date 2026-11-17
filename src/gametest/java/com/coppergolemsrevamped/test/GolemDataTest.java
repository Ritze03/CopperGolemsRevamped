package com.coppergolemsrevamped.test;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.FilterMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;
import com.mojang.serialization.DataResult;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class GolemDataTest {
	/** Full entity save, then load into a fresh unspawned golem: proves the attachment is persistent. */
	@GameTest
	public void golemDataSurvivesSaveLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(level, golem, CopperGolemsRevamped.id("transporter"));
		UUID player = UUID.randomUUID();
		BlockPos input = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos output = helper.absolutePos(new BlockPos(5, 1, 6));
		GolemData data = GolemData.get(golem);
		data.assignments.put("input", input);
		data.assignments.put("output", output);
		data.items.set(0, new ItemStack(Items.COBBLESTONE, 64));
		data.items.set(4, new ItemStack(Items.DIAMOND, 3));
		data.following = true;
		data.followPlayer = player;
		data.spawnChest = input;
		data.autoName = "Transporter";
		data.frozen = true;
		data.xp = 13;
		data.filterMode = FilterMode.BLACKLIST;
		data.stripLogs = true;
		data.keepLayout = true;
		data.saveTools = false; // default is true
		GolemData.save(golem, data);

		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		golem.saveWithoutId(out);
		CompoundTag tag = out.buildResult();

		CopperGolem copy = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.LOAD);
		helper.assertTrue(copy != null, "could not create a golem to load into");
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));

		GolemData d = GolemData.get(copy);
		helper.assertTrue(CopperGolemsRevamped.id("transporter").equals(d.profession), "profession: " + d.profession);
		helper.assertTrue(d.assignments.size() == 2, "assignments: " + d.assignments);
		helper.assertTrue(input.equals(d.assignments.get("input")), "input: " + d.assignments.get("input"));
		helper.assertTrue(output.equals(d.assignments.get("output")), "output: " + d.assignments.get("output"));
		helper.assertTrue(d.items.size() == 18, "items size: " + d.items.size());
		helper.assertTrue(ItemStack.matches(d.items.get(0), new ItemStack(Items.COBBLESTONE, 64)), "slot 0: " + d.items.get(0));
		helper.assertTrue(ItemStack.matches(d.items.get(4), new ItemStack(Items.DIAMOND, 3)), "slot 4: " + d.items.get(4));
		helper.assertTrue(input.equals(d.spawnChest), "spawnChest: " + d.spawnChest);
		helper.assertTrue("Transporter".equals(d.autoName), "autoName: " + d.autoName);
		helper.assertTrue(d.following, "following lost");
		helper.assertTrue(player.equals(d.followPlayer), "followPlayer: " + d.followPlayer);
		helper.assertTrue(d.frozen, "frozen lost");
		helper.assertTrue(d.xp == 13, "xp: " + d.xp);
		helper.assertTrue(d.filterMode == FilterMode.BLACKLIST, "filter_mode: " + d.filterMode);
		helper.assertTrue(d.stripLogs, "strip_logs lost");
		helper.assertTrue(d.keepLayout, "keep_layout lost");
		helper.assertTrue(!d.saveTools, "save_tools reset to its default");
		helper.succeed();
	}

	private static RegistryOps<Tag> ops(GameTestHelper helper) {
		return helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}

	private static CompoundTag stack(String id, int count) {
		CompoundTag t = new CompoundTag();
		t.putString("id", id);
		t.putInt("count", count);
		return t;
	}

	private static IntArrayTag pos(int x, int y, int z) {
		return new IntArrayTag(new int[] {x, y, z});
	}

	/**
	 * A save from before the newer fields (only profession, assignments, a short items list, following): every missing
	 * field gets its default, save_tools on, and the items list is padded to the profession's slot count.
	 */
	@GameTest
	public void oldSaveGetsDefaults(GameTestHelper helper) {
		CompoundTag tag = new CompoundTag();
		tag.putString("profession", "coppergolemsrevamped:lumber");
		CompoundTag assignments = new CompoundTag();
		assignments.put("tree", pos(1, 2, 3));
		tag.put("assignments", assignments);
		ListTag items = new ListTag();
		items.add(stack("minecraft:iron_axe", 1));
		tag.put("items", items);
		tag.putBoolean("following", false);

		GolemData d = GolemData.CODEC.parse(ops(helper), tag).getOrThrow();
		Profession lumber = Professions.get(CopperGolemsRevamped.id("lumber"));
		helper.assertTrue(lumber != null && lumber.id().equals(d.profession), "profession: " + d.profession);
		helper.assertTrue(new BlockPos(1, 2, 3).equals(d.assignments.get("tree")), "assignments: " + d.assignments);
		helper.assertTrue(d.items.size() == lumber.inventorySize(), "items not padded: " + d.items.size() + " of " + lumber.inventorySize());
		helper.assertTrue(d.items.get(0).is(Items.IRON_AXE), "slot 0: " + d.items.get(0));
		helper.assertTrue(!d.frozen, "frozen default");
		helper.assertTrue(d.xp == 0, "xp default: " + d.xp);
		helper.assertTrue(d.filterMode == FilterMode.OFF, "filter_mode default: " + d.filterMode);
		helper.assertTrue(!d.stripLogs, "strip_logs default");
		helper.assertTrue(!d.keepLayout, "keep_layout default");
		helper.assertTrue(d.saveTools, "save_tools default must be on");
		helper.assertTrue(d.autoName == null && d.spawnChest == null && d.followPlayer == null, "nullable defaults");
		helper.succeed();
	}

	/**
	 * One undecodable stack (an item of a removed mod), one malformed field and one malformed assignment do not wipe
	 * the golem: the bad stack becomes empty in place (other slots keep their indices), the bad field gets its default,
	 * the good assignment stays.
	 */
	@GameTest
	public void badStackAndFieldAreTolerated(GameTestHelper helper) {
		CompoundTag tag = new CompoundTag();
		tag.putString("profession", "coppergolemsrevamped:transporter");
		CompoundTag assignments = new CompoundTag();
		assignments.put("input", pos(1, 2, 3));
		assignments.putString("output", "nowhere");
		tag.put("assignments", assignments);
		ListTag items = new ListTag();
		items.add(stack("minecraft:cobblestone", 64));
		items.add(stack("removedmod:gizmo", 5));
		items.add(stack("minecraft:emerald", 7));
		tag.put("items", items);
		tag.putString("xp", "lots");
		tag.putBoolean("keep_layout", true);
		tag.putString("save_tools", "maybe");

		DataResult<GolemData> r = GolemData.CODEC.parse(ops(helper), tag);
		helper.assertTrue(r.isSuccess(), "decode failed: " + r.error().map(e -> e.message()).orElse(""));
		GolemData d = r.getOrThrow();
		helper.assertTrue(CopperGolemsRevamped.id("transporter").equals(d.profession), "profession: " + d.profession);
		helper.assertTrue(new BlockPos(1, 2, 3).equals(d.assignments.get("input")), "good assignment lost: " + d.assignments);
		helper.assertTrue(!d.assignments.containsKey("output"), "bad assignment kept: " + d.assignments);
		helper.assertTrue(d.items.size() == 18, "items size: " + d.items.size());
		helper.assertTrue(ItemStack.matches(d.items.get(0), new ItemStack(Items.COBBLESTONE, 64)), "slot 0: " + d.items.get(0));
		helper.assertTrue(d.items.get(1).isEmpty(), "bad stack not empty: " + d.items.get(1));
		helper.assertTrue(ItemStack.matches(d.items.get(2), new ItemStack(Items.EMERALD, 7)), "slot 2 shifted: " + d.items.get(2));
		helper.assertTrue(d.xp == 0, "bad xp: " + d.xp);
		helper.assertTrue(d.keepLayout, "keep_layout lost");
		helper.assertTrue(d.saveTools, "bad save_tools must fall back to on");
		helper.succeed();
	}

	/** A stored list longer than the profession declares keeps its extra stacks (never deleted silently). */
	@GameTest
	public void longerItemsListKeepsExtras(GameTestHelper helper) {
		Profession lumber = Professions.get(CopperGolemsRevamped.id("lumber"));
		int n = lumber.inventorySize();
		CompoundTag tag = new CompoundTag();
		tag.putString("profession", "coppergolemsrevamped:lumber");
		ListTag items = new ListTag();
		for (int i = 0; i < n; i++) items.add(new CompoundTag());
		items.add(stack("minecraft:diamond", 2));
		tag.put("items", items);
		GolemData d = GolemData.CODEC.parse(ops(helper), tag).getOrThrow();
		helper.assertTrue(d.items.size() == n + 1, "items size: " + d.items.size());
		helper.assertTrue(ItemStack.matches(d.items.get(n), new ItemStack(Items.DIAMOND, 2)), "extra stack: " + d.items.get(n));
		helper.succeed();
	}

	/** A short list on a live golem is padded on access, so the menu stays open and the tick does not throw. */
	@GameTest
	public void shortItemsListIsPaddedOnAccess(GameTestHelper helper) {
		CopperGolem golem = helper.spawnWithNoFreeWill(EntityTypes.COPPER_GOLEM, new BlockPos(2, 1, 2));
		GolemService.changeProfession(helper.getLevel(), golem, CopperGolemsRevamped.id("cleaner"));
		GolemData d = GolemData.get(golem);
		d.items = NonNullList.of(ItemStack.EMPTY, new ItemStack(Items.STICK, 3));
		GolemData.save(golem, d);
		GolemData again = GolemData.get(golem);
		int want = Professions.get(CopperGolemsRevamped.id("cleaner")).inventorySize();
		helper.assertTrue(again.items.size() == want, "items size: " + again.items.size() + " of " + want);
		helper.assertTrue(ItemStack.matches(again.items.get(0), new ItemStack(Items.STICK, 3)), "slot 0: " + again.items.get(0));
		helper.succeed();
	}

	@GameTest
	public void assignAreaHelpers(GameTestHelper helper) {
		helper.assertTrue(AssignMode.areaFits(new BlockPos(0, 0, 0), new BlockPos(4, 9, 4), 5), "5x5 must fit 5");
		helper.assertTrue(!AssignMode.areaFits(new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), 5), "6 wide must not fit 5");
		helper.assertTrue(AssignMode.inRange(new Vec3(0.5, 0, 0.5), new BlockPos(31, 0, 0)), "31 blocks is in range");
		helper.assertTrue(!AssignMode.inRange(new Vec3(0.5, 0, 0.5), new BlockPos(40, 0, 0)), "40 blocks is out of range");
		helper.succeed();
	}
}
