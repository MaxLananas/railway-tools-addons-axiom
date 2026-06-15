package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
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
            Class<?> registryClass = Class.forName("com.moulberry.axiomclientapi.service.ToolRegistryService");
            ServiceLoader<?> loader = ServiceLoader.load(registryClass);
            for (Object service : loader) {
                registryClass.getMethod("register", Class.forName("com.moulberry.axiomclientapi.CustomTool"))
                        .invoke(service, new RailPathTool());
                break;
            }
            LOGGER.info("[RailPath] Charge avec succes.");
        } catch (Exception e) {
            LOGGER.error("[RailPath] Erreur d'enregistrement : " + e.getMessage());
        }
    }
}
