package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailPathToolMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("bte_railpathtool");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            LOGGER.warn("[RailPath] Axiom introuvable — le mod est desactive.");
            return;
        }
        try {
            Class<?> toolManagerClass = Class.forName("com.moulberry.axiom.tools.ToolManager");
            toolManagerClass.getMethod("addTool",
                    Class.forName("com.moulberry.axiom.tools.Tool"))
                    .invoke(null, new RailPathTool());
            LOGGER.info("[RailPath] Charge avec succes.");
        } catch (Exception e) {
            LOGGER.error("[RailPath] Erreur enregistrement: " + e.getMessage());
        }
    }
}
