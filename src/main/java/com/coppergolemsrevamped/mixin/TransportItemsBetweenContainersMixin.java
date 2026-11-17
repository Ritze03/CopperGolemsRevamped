package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
	@Shadow
	protected abstract void clearMemoriesAfterMatchingTargetFound(PathfinderMob body);

	@Shadow
	protected abstract void stopTargetingCurrentTarget(PathfinderMob body);

	@Shadow
	@Final
	private Predicate<BlockState> destinationBlockType;

	@Shadow
	private AABB getTargetSearchArea(PathfinderMob mob) {
		throw new AssertionError();
	}

	/** The bare literal 10 in {@code visitedPositions.size() > 10}; the only 10 in this method. */
	@ModifyConstant(method = "setVisitedBlockPos", constant = @Constant(intValue = 10))
	private int cgr$visitedLimit(int vanilla, PathfinderMob body, Level level, BlockPos target) {
		return ChestSorterProfession.visitedLimit(body);
	}

	private static boolean cgr$keepLayout(PathfinderMob body) {
		return body instanceof CopperGolem golem && GolemData.get(golem).keepLayout;
	}

	/**
	 * Keep layout (superdoc/features/keep-layout.md): the vanilla "can pick up / can put down" test looks at whole containers
	 * ({@code !isEmpty()}, {@code isEmpty() || same item}); with the rule on, a source needs a slot with items to spare that
	 * some destination in range accepts ({@link #cgr$pickSlot}), and a destination must accept the held item
	 * ({@link #cgr$accepts}).
	 */
	@Inject(method = "doReachedTargetInteraction", at = @At("HEAD"), cancellable = true)
	private void cgr$layoutInteraction(PathfinderMob body, Container container,
			BiConsumer<PathfinderMob, Container> onPickupSuccess, BiConsumer<PathfinderMob, Container> onPickupFailure,
			BiConsumer<PathfinderMob, Container> onPlaceSuccess, BiConsumer<PathfinderMob, Container> onPlaceFailure, CallbackInfo ci) {
		if (!cgr$keepLayout(body)) return;
		ItemStack hand = body.getMainHandItem();
		if (hand.isEmpty()) {
			(cgr$pickSlot(body, container) >= 0 ? onPickupSuccess : onPickupFailure).accept(body, container);
		} else {
			(cgr$accepts(container, hand) ? onPlaceSuccess : onPlaceFailure).accept(body, container);
		}
		ci.cancel();
	}

	/**
	 * Keep layout pick-up: the first slot {@link #cgr$pickSlot} names gives up to 16 items, leaving 1 behind (unstackables
	 * move whole).
	 */
	@Inject(method = "pickUpItems", at = @At("HEAD"), cancellable = true)
	private void cgr$layoutPickUp(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!cgr$keepLayout(body)) return;
		int slot = cgr$pickSlot(body, container);
		ItemStack taken = slot < 0 ? ItemStack.EMPTY
			: container.removeItem(slot, Math.min(TransporterLogic.takeable(container, container.getItem(slot), true), 16));
		body.setItemSlot(EquipmentSlot.MAINHAND, taken);
		body.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		container.setChanged();
		clearMemoriesAfterMatchingTargetFound(body);
		ci.cancel();
	}

	/** Keep layout put-down: merge into existing same-item stacks only; unstackables go to an empty slot (vanilla). */
	@Inject(method = "putDownItem", at = @At("HEAD"), cancellable = true)
	private void cgr$layoutPutDown(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!cgr$keepLayout(body)) return;
		ItemStack left = TransporterLogic.insertStack(body.getMainHandItem().copy(), container, true);
		container.setChanged();
		body.setItemSlot(EquipmentSlot.MAINHAND, left);
		if (left.isEmpty()) clearMemoriesAfterMatchingTargetFound(body);
		else stopTargetingCurrentTarget(body);
		ci.cancel();
	}

	/**
	 * Keep layout destination test. Stackable: a non-full stack of the same item to merge into. Unstackable (exempt from the
	 * layout rule): vanilla's {@code matchesLeavingItemsRequirement}, i.e. the container is empty or holds the same item
	 * ({@link ItemStack#isSameItem}), plus a free slot.
	 */
	private static boolean cgr$accepts(Container c, ItemStack stack) {
		if (stack.getMaxStackSize() == 1 && !c.isEmpty()) {
			boolean same = false;
			for (int i = 0; i < c.getContainerSize() && !same; i++) same = ItemStack.isSameItem(c.getItem(i), stack);
			if (!same) return false;
		}
		return TransporterLogic.canInsert(c, stack, true);
	}

	/**
	 * Keep layout source slot: the first slot with items to spare whose item some destination in the search area accepts,
	 * or -1. Why: vanilla never gives a held item back, it cycles destinations forever (140-tick cooldowns), so taking a
	 * type no destination accepts would block every later slot of the copper chest. "In the area" stands in for
	 * "reachable"; a real path check per chest would be too costly here.
	 */
	private int cgr$pickSlot(PathfinderMob body, Container container) {
		List<Container> dests = null;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack s = container.getItem(i);
			if (s.isEmpty() || TransporterLogic.takeable(container, s, true) <= 0) continue;
			if (dests == null) dests = cgr$destinations(body);
			for (Container d : dests) if (d != container && cgr$accepts(d, s)) return i;
		}
		return -1;
	}

	/**
	 * Destination containers the golem could pick next, filtered like vanilla's {@code getTransportTarget}: chest block
	 * entities in loaded chunks inside the search area, matching the destination predicate, not locked, not blocked. A
	 * double chest is one combined container (listed once per half, harmless).
	 */
	private List<Container> cgr$destinations(PathfinderMob body) {
		List<Container> out = new ArrayList<>();
		if (!(body.level() instanceof ServerLevel level)) return out;
		AABB area = getTargetSearchArea(body);
		for (int cx = Mth.floor(area.minX) >> 4; cx <= Mth.floor(area.maxX) >> 4; cx++) {
			for (int cz = Mth.floor(area.minZ) >> 4; cz <= Mth.floor(area.maxZ) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos p = be.getBlockPos();
					if (!(be instanceof ChestBlockEntity chest) || !area.contains(p.getX(), p.getY(), p.getZ())) continue;
					if (!destinationBlockType.test(be.getBlockState()) || chest.isLocked() || ChestBlock.isChestBlockedAt(level, p)) continue;
					Container c = TransporterLogic.containerAt(level, p);
					if (c != null) out.add(c);
				}
			}
		}
		return out;
	}
}
