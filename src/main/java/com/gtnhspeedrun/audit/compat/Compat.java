package com.gtnhspeedrun.audit.compat;

import cpw.mods.fml.common.Loader;

/**
 * Presence cache for every optionally-integrated mod. Resolved lazily because Loader state isn't final until
 * mod loading ends, then cached — isModLoaded walks a list every call.
 */
public enum Compat {

    NEI("NotEnoughItems"),
    AE2("appliedenergistics2"),
    BETTER_QUESTING("betterquesting"),
    BAUBLES("Baubles"),
    GT5("gregtech"),
    RAILCRAFT("Railcraft");

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
