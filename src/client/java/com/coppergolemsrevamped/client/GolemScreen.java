package com.coppergolemsrevamped.client;

import com.coppergolemsrevamped.menu.GolemMenu;
import com.coppergolemsrevamped.network.GolemPayloads.AssignTarget;
import com.coppergolemsrevamped.network.GolemPayloads.SelectProfession;
import com.coppergolemsrevamped.network.GolemPayloads.ToggleFollow;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Generic golem screen: everything comes from the menu (derived from the profession declaration). */
public class GolemScreen extends AbstractContainerScreen<GolemMenu> {
	private static final int TEXT = 0xFF404040;
	private Button followButton;

	public GolemScreen(GolemMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GolemMenu.WIDTH, menu.imageHeight());
		this.titleLabelX = 8;
		this.titleLabelY = 6;
		this.inventoryLabelX = 8;
		this.inventoryLabelY = menu.invLabelY();
	}

	@Override
	protected void init() {
		super.init();
		int x = leftPos + 4;
		int y = topPos + GolemMenu.TOP_ROW_Y;
		addRenderableWidget(Button.builder(professionLabel(), b -> cycleProfession()).bounds(x, y, 106, 20).build());
		followButton = addRenderableWidget(Button.builder(followLabel(), b ->
			ClientPlayNetworking.send(new ToggleFollow(menu.golemId()))).bounds(x + 108, y, 60, 20).build());

		Profession p = menu.profession();
		if (p == null) return;
		int ay = topPos + menu.assignY();
		for (Profession.AssignSpec spec : p.assigns()) {
			addRenderableWidget(Button.builder(Component.empty().append(spec.label()).append(": " + value(spec)),
				b -> ClientPlayNetworking.send(new AssignTarget(menu.golemId(), spec.key()))).bounds(x, ay, 168, 20).build());
			ay += GolemMenu.ASSIGN_ROW_H;
		}
	}

	private Component professionLabel() {
		Profession p = menu.profession();
		return Component.literal("Profession: ").append(p == null ? Component.literal("None") : p.name());
	}

	private Component followLabel() {
		return Component.literal(menu.isFollowing() ? "Follow: On" : "Follow: Off");
	}

	/** Cycles None, then every registered profession in registration order. */
	private void cycleProfession() {
		List<Identifier> ids = new ArrayList<>();
		for (Profession p : Professions.all()) ids.add(p.id());
		Profession cur = menu.profession();
		int i = cur == null ? -1 : ids.indexOf(cur.id());
		Optional<Identifier> next = i + 1 < ids.size() ? Optional.of(ids.get(i + 1)) : Optional.empty();
		ClientPlayNetworking.send(new SelectProfession(menu.golemId(), next));
	}

	private String value(Profession.AssignSpec spec) {
		var map = menu.data().assignments();
		if (spec.kind() == Profession.Kind.AREA) {
			BlockPos a = map.get(spec.key() + "_a");
			BlockPos b = map.get(spec.key() + "_b");
			if (a == null && b == null) return "unset";
			return (a == null ? "?" : fmt(a)) + " to " + (b == null ? "?" : fmt(b));
		}
		BlockPos pos = map.get(spec.key());
		return pos == null ? "unset" : fmt(pos);
	}

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		if (followButton != null) followButton.setMessage(followLabel());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = leftPos;
		int y = topPos;
		graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF000000);
		graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFC6C6C6);
		for (Slot s : menu.slots) {
			graphics.fill(x + s.x - 1, y + s.y - 1, x + s.x + 17, y + s.y + 17, 0xFF373737);
			graphics.fill(x + s.x, y + s.y, x + s.x + 16, y + s.y + 16, 0xFF8B8B8B);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		graphics.text(font, title, titleLabelX, titleLabelY, TEXT, false);
		graphics.text(font, "Held item", 30, menu.heldY() + 5, TEXT, false);
		for (GolemMenu.Heading h : menu.headings()) graphics.text(font, h.text(), 8, h.y(), TEXT, false);
		graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
	}
}
