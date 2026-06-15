package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.service.ToolRegistryService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

public class RailPathToolMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("bte_railpathtool");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            LOGGER.warn("[RailPath] Axiom introuvable — le mod est desactive.");
            return;
        }
        try {
            ServiceLoader.load(ToolRegistryService.class)
                         .findFirst()
                         .ifPresentOrElse(
                             svc -> {
                                 svc.register(new RailPathTool());
                                 LOGGER.info("[RailPath] Charge avec succes.");
                             },
                             () -> LOGGER.error("[RailPath] ToolRegistryService introuvable.")
                         );
        } catch (Exception e) {
            LOGGER.error("[RailPath] Erreur d'enregistrement : " + e.getMessage());
        }
    }
}
