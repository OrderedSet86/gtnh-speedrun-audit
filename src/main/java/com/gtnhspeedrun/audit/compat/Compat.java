package com.gtnhspeedrun.audit.compat;

import cpw.mods.fml.common.Loader;

/**
 * Cache whether each optional mod that the audit integrates with is loaded. The lookup is lazy, because the
 * {@code Loader} state is final only after mod loading ends. The result is cached, because
 * {@code Loader.isModLoaded()} does a map lookup and a mod-state lookup on every call.
 */
public enum Compat {

    NEI("NotEnoughItems"),
    AE2("appliedenergistics2"),
    BETTER_QUESTING("betterquesting"),
    BAUBLES("Baubles"),
    GT5("gregtech"),
    RAILCRAFT("Railcraft"),
    SERVER_UTILITIES("serverutilities"),
    WORLDEDIT("worldedit");

    private final String modId;
    private Boolean loaded;

    Compat(String modId) {
        this.modId = modId;
    }

    public boolean isLoaded() {
        if (loaded == null) {
            loaded = Loader.isModLoaded(modId);
        }
        return loaded;
    }
}
