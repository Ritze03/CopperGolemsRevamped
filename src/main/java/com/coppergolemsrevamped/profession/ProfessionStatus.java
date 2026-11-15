package com.coppergolemsrevamped.profession;

import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.crafter.CrafterLogic;
import com.coppergolemsrevamped.profession.lumber.LumberLogic;
import com.coppergolemsrevamped.profession.lumber.LumberLogic.Phase;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/**
 * One-line status texts for the menu's Status tab: what the golem is doing now and what comes next.
 * Deliberately small (no status framework): a generic default per profession, a live value only where the logic
 * already exposes one cheaply (Lumber's phase, the Crafter's recipe). Like {@link ProfessionHelp} it lives in one
 * file so the profession declarations stay untouched.
 */
public final class ProfessionStatus {
	private ProfessionStatus() {}

	/** The first assignment that is not set yet, or null when all are. */
	public static @Nullable AssignSpec missing(Profession p, GolemData data) {
		for (AssignSpec a : p.assigns()) {
			boolean set = a.kind() == Kind.AREA
				? data.assignments.containsKey(a.key() + "_a") && data.assignments.containsKey(a.key() + "_b")
				: data.assignments.containsKey(a.key());
			if (!set) return a;
		}
		return null;
	}

	/** What the golem is doing, e.g. "Felling the tree". */
	public static String doing(ServerLevel level, CopperGolem golem, GolemData data, @Nullable Profession p) {
		if (p == null) return "Idle";
		if (data.following) return "Following you";
		if (WalkTo.active(golem)) return "Walking to a target";
		if (p.runsVanillaBrain()) return "Sorting items between chests";
		if (missing(p, data) != null) return "Waiting for targets";
		return switch (p.id().getPath()) {
			case "transporter" -> "Moving items from A to B";
			case "cleaner" -> "Picking up loose items";
			case "farmer" -> "Farming the field";
			case "crafter" -> CrafterLogic.preview(level, data).isEmpty()
				? "No valid recipe" : "Crafting " + CrafterLogic.preview(level, data).getHoverName().getString();
			case "lumber" -> lumber(LumberLogic.phaseOf(golem));
			default -> "Working";
		};
	}

	/** What comes next, e.g. "Next: deliver to the output chest". */
	public static String next(CopperGolem golem, GolemData data, @Nullable Profession p) {
		if (p == null) return "Next: wait for a profession";
		if (data.following) return "Next: work again when Follow is off";
		AssignSpec m = missing(p, data);
		if (m != null && !p.runsVanillaBrain()) return "Next: set " + m.label().getString();
		return switch (p.id().getPath()) {
			case "chest_sorter" -> "Next: sort the next item";
			case "transporter" -> "Next: deliver to chest B";
			case "cleaner" -> "Next: deliver to the output chest";
			case "farmer" -> "Next: deliver the harvest to the output chest";
			case "crafter" -> "Next: refill the recipe grid";
			case "lumber" -> {
				Phase ph = LumberLogic.phaseOf(golem);
				yield "Next: " + lower(lumber(ph == null ? Phase.FELL : Phase.values()[(ph.ordinal() + 1) % Phase.values().length]));
			}
			default -> "Next: keep working";
		};
	}

	private static String lumber(@Nullable Phase ph) {
		if (ph == null) return "Looking for the tree";
		return switch (ph) {
			case PLANT -> "Planting a sapling";
			case BONEMEAL -> "Using bone meal";
			case FELL -> "Felling the tree";
			case PICKUP -> "Picking up the drops";
			case DELIVER -> "Delivering the wood";
		};
	}

	private static String lower(String s) {
		return s.substring(0, 1).toLowerCase() + s.substring(1);
	}
}
