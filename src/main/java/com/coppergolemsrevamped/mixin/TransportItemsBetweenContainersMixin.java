package com.coppergolemsrevamped.mixin;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
	@Shadow
	protected abstract void clearMemoriesAfterMatchingTargetFound(PathfinderMob body);

	@Shadow
	protected abstract void stopTargetingCurrentTarget(PathfinderMob body);

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
	 * ({@code !isEmpty()}, {@code isEmpty() || same item}); with the rule on, a source needs a slot with more than 1 item
	 * and a destination needs a non-empty slot of the same item with room.
	 */
	@Inject(method = "doReachedTargetInteraction", at = @At("HEAD"), cancellable = true)
	private void cgr$layoutInteraction(PathfinderMob body, Container container,
			BiConsumer<PathfinderMob, Container> onPickupSuccess, BiConsumer<PathfinderMob, Container> onPickupFailure,
			BiConsumer<PathfinderMob, Container> onPlaceSuccess, BiConsumer<PathfinderMob, Container> onPlaceFailure, CallbackInfo ci) {
		if (!cgr$keepLayout(body)) return;
		ItemStack hand = body.getMainHandItem();
		boolean ok;
		if (hand.isEmpty()) {
			ok = false;
			for (int i = 0; i < container.getContainerSize() && !ok; i++) {
				ItemStack s = container.getItem(i);
				ok = !s.isEmpty() && TransporterLogic.takeable(container, s, true) > 0;
			}
			(ok ? onPickupSuccess : onPickupFailure).accept(body, container);
		} else {
			ok = TransporterLogic.canInsert(container, hand, true);
			(ok ? onPlaceSuccess : onPlaceFailure).accept(body, container);
		}
		ci.cancel();
	}

	/** Keep layout pick-up: the first slot with something to spare gives up to 16 items, leaving 1 behind. */
	@Inject(method = "pickUpItems", at = @At("HEAD"), cancellable = true)
	private void cgr$layoutPickUp(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!cgr$keepLayout(body)) return;
		ItemStack taken = ItemStack.EMPTY;
		for (int i = 0; i < container.getContainerSize() && taken.isEmpty(); i++) {
			ItemStack s = container.getItem(i);
			if (s.isEmpty()) continue;
			int n = Math.min(TransporterLogic.takeable(container, s, true), 16);
			if (n > 0) taken = container.removeItem(i, n);
		}
		body.setItemSlot(EquipmentSlot.MAINHAND, taken);
		body.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		container.setChanged();
		clearMemoriesAfterMatchingTargetFound(body);
		ci.cancel();
	}

	/** Keep layout put-down: merge into existing same-item stacks only (unstackable items keep vanilla's rule). */
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
}
