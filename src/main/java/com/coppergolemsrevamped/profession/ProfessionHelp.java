package com.coppergolemsrevamped.profession;

import com.coppergolemsrevamped.profession.Profession.AssignSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Help texts of all professions in one place, attached to the {@link Profession} declaration by
 * {@link Professions#register} ({@link Profession#help()}, {@link AssignSpec#help()}).
 * Why one file: the declarations are edited by many people at once; the texts stay short and read together.
 * Sizes are not written here, {@link #lines} adds them from the {@link AssignSpec}s so they cannot drift.
 */
public final class ProfessionHelp {
	private ProfessionHelp() {}

	private static final Map<String, List<String>> LINES = Map.of(
		"transporter", List.of(
			"Carries everything from chest A to chest B, up to 9 stacks per trip.",
			"The 9 slots are what it carries right now."),
		"chest_sorter", List.of(
			"Sorts items between chests like a vanilla golem, no assignments needed.",
			"It visits up to 64 chests before it gives up and wanders."),
		"cleaner", List.of(
			"Picks up dropped items inside the area and brings them to the output chest.",
			"The 9 slots are what it carries."),
		"crafter", List.of(
			"Left 3x3 = the recipe template (never used up). Middle 3x3 = 9 input slots, one per grid cell, each holds only that cell's item. Right = 1 output slot.",
			"It fills the input slots from the input chest, crafts at the table until the output stack is full, then delivers it to the output chest."),
		"farmer", List.of(
			"Works one field with one tool: a hoe farms crops, an axe only melons and pumpkins.",
			"Put the tool in the tool slot, seeds in the seed slot. Seeds go back to the seed chest, the harvest to the output chest."),
		"lumber", List.of(
			"Fells the whole assigned tree from the ground up, branches and 2x2 trees too, and collects the drops.",
			"Then it replants a sapling and uses bone meal from the input chest. Wood goes to the output chest.",
			"Put an axe or shears in the tool slots and a sapling in the sapling slot."));

	/** Key = profession id path + "/" + assign key. */
	private static final Map<String, String> ASSIGNS = Map.ofEntries(
		Map.entry("transporter/input", "the chest the golem takes items from (chest A)"),
		Map.entry("transporter/output", "the chest the golem puts the items into (chest B)"),
		Map.entry("cleaner/area", "two opposite corners of the area to clean"),
		Map.entry("cleaner/output", "the chest the collected items go to"),
		Map.entry("crafter/input", "the chest the ingredients are taken from"),
		Map.entry("crafter/table", "the crafting table the golem crafts at"),
		Map.entry("crafter/output", "the chest the crafted items go to"),
		Map.entry("farmer/field", "two opposite corners of the field to farm"),
		Map.entry("farmer/tools", "the chest the golem takes its tool from"),
		Map.entry("farmer/seeds", "the chest seeds are taken from and surplus seeds go back to"),
		Map.entry("farmer/output", "the chest the harvest goes to"),
		Map.entry("lumber/tree", "the bottom log or sapling of the tree to fell"),
		Map.entry("lumber/input", "the chest the golem takes tools, saplings and bone meal from"),
		Map.entry("lumber/output", "the chest the wood and other drops go to"));

	/** The profession with its help texts filled in where the declaration has none. */
	static Profession apply(Profession p) {
		String path = p.id().getPath();
		List<AssignSpec> assigns = new ArrayList<>();
		for (AssignSpec a : p.assigns()) {
			String h = a.help().isEmpty() ? ASSIGNS.getOrDefault(path + "/" + a.key(), "") : a.help();
			assigns.add(new AssignSpec(a.key(), a.label(), a.kind(), a.maxSize(), h));
		}
		List<String> help = p.help().isEmpty() ? LINES.getOrDefault(path, List.of()) : p.help();
		return new Profession(p.id(), p.name(), p.slots(), assigns, p.runsVanillaBrain(), p.tick(), help);
	}

	/** Chat explanation for the Help button: the profession's lines, then one line per assignment with its size. */
	public static List<String> lines(Profession p) {
		List<String> out = new ArrayList<>(p.help());
		for (AssignSpec a : p.assigns()) out.add(a.label().getString() + ": " + prompt(a));
		return out;
	}

	/** What to click when this assignment is armed, e.g. "Right-click two opposite corners ..., max 16x16, larger areas are capped". */
	public static String prompt(AssignSpec a) {
		String what = a.help().isEmpty() ? "" : a.help();
		return switch (a.kind()) {
			case AREA -> "right-click two opposite corners" + (what.isEmpty() ? "" : " (" + what + ")") + ", max " + a.maxSize()
				+ "x" + a.maxSize() + ", larger areas are capped";
			case CHEST -> "right-click " + (what.isEmpty() ? "a chest" : what);
			case CRAFTING_TABLE -> "right-click " + (what.isEmpty() ? "a crafting table" : what);
			case BLOCK -> "right-click " + (what.isEmpty() ? "a block" : what);
		};
	}
}
