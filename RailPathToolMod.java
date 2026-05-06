package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
import com.moulberry.axiom.tools.ToolManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailPathToolMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("bte_railpathtool");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            LOGGER.warn("[RailPath] Axiom non trouvé, le mod ne se chargera pas.");
            return;
        }
        ToolManager.addTool(new RailPathTool());
        LOGGER.info("[RailPath] Chargé avec succès.");
    }
}