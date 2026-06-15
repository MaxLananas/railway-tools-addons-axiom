package com.bte.railpathtool;

import com.bte.railpathtool.tools.RailPathTool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class RailPathToolMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("bte_railpathtool");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            LOGGER.warn("[RailPath] Axiom introuvable — le mod est desactive.");
            return;
        }
        try {
            Class<?> toolInterface = Class.forName("com.moulberry.axiom.tools.Tool");
            Class<?> toolManagerClass = Class.forName("com.moulberry.axiom.tools.ToolManager");

            RailPathTool toolInstance = new RailPathTool();

            // Crée un proxy qui implémente l'interface Tool d'Axiom
            Object proxy = Proxy.newProxyInstance(
                toolInterface.getClassLoader(),
                new Class<?>[]{ toolInterface },
                new RailPathToolHandler(toolInstance)
            );

            // Cherche la méthode d'enregistrement disponible
            boolean registered = false;
            for (Method m : toolManagerClass.getMethods()) {
                if (m.getName().equals("addTool") || m.getName().equals("registerTool")) {
                    m.invoke(null, proxy);
                    registered = true;
                    LOGGER.info("[RailPath] Enregistre via methode: " + m.getName());
                    break;
                }
            }
            if (!registered) {
                LOGGER.error("[RailPath] Aucune methode d'enregistrement trouvee dans ToolManager.");
            }

        } catch (ClassNotFoundException e) {
            LOGGER.error("[RailPath] Classe Axiom introuvable: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[RailPath] Erreur enregistrement: " + e.getMessage(), e);
        }
    }

    /**
     * Handler du proxy : redirige les appels de l'interface Tool vers RailPathTool.
     */
    private static class RailPathToolHandler implements InvocationHandler {

        private final RailPathTool tool;

        RailPathToolHandler(RailPathTool tool) {
            this.tool = tool;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // Redirige vers les méthodes de RailPathTool selon le nom
            try {
                switch (name) {
                    // Identité
                    case "getName", "name", "getId", "getTranslationKey" -> {
                        return tool.name();
                    }

                    // Rendu
                    case "render", "renderTool" -> {
                        tool.render(args != null && args.length > 0 ? args[0] : null);
                        return null;
                    }

                    // Options ImGui
                    case "renderImGui", "displayImguiOptions",
                         "renderOptions", "buildImGui", "imguiOptions" -> {
                        tool.displayImguiOptions();
                        return null;
                    }

                    // Clic / utilisation
                    case "onUse", "use", "callUseTool",
                         "onRightClick", "activate" -> {
                        return tool.callUseTool();
                    }

                    // Confirmer
                    case "onConfirm", "confirm", "callConfirm",
                         "onEnter", "submit" -> {
                        return tool.callConfirm();
                    }

                    // Supprimer
                    case "onDelete", "delete", "callDelete",
                         "onRemove", "undo" -> {
                        return tool.callDelete();
                    }

                    // Réinitialiser
                    case "reset", "onReset", "clear", "onDeactivate",
                         "deactivate", "onUnselect" -> {
                        tool.reset();
                        return null;
                    }

                    // Méthodes Object standard
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return tool.name();
                    }

                    default -> {
                        // Cherche la méthode dans RailPathTool par réflexion
                        try {
                            Class<?>[] paramTypes = method.getParameterTypes();
                            Method m = RailPathTool.class.getMethod(name, paramTypes);
                            return m.invoke(tool, args);
                        } catch (NoSuchMethodException ignored) {
                            // Retourne une valeur par défaut selon le type de retour
                            Class<?> ret = method.getReturnType();
                            if (ret == boolean.class) return false;
                            if (ret == int.class)     return 0;
                            if (ret == float.class)   return 0f;
                            if (ret == double.class)  return 0d;
                            if (ret == long.class)    return 0L;
                            if (ret == void.class)    return null;
                            return null;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[RailPath] Erreur methode proxy '{}': {}", name, e.getMessage());
                // Valeur par défaut selon le type de retour
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class)     return 0;
                if (ret == void.class)    return null;
                return null;
            }
        }
    }
}
