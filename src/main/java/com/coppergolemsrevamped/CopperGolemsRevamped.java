package com.coppergolemsrevamped;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.ModAttachments;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;
import com.coppergolemsrevamped.profession.cleaner.CleanerProfession;
import com.coppergolemsrevamped.profession.crafter.CrafterProfession;
import com.coppergolemsrevamped.profession.farmer.FarmerProfession;
import com.coppergolemsrevamped.profession.transporter.TransporterProfession;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopperGolemsRevamped implements ModInitializer {
	public static final String MOD_ID = "coppergolemsrevamped";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModAttachments.init();
		TransporterProfession.init();
		ChestSorterProfession.init();
		CleanerProfession.init();
		CrafterProfession.init();
		FarmerProfession.init();
		AssignMode.init();
		LOGGER.info("Copper Golems Revamped initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
