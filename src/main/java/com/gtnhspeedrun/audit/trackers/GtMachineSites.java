package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTUtility;

/**
 * Read the {@link MachineSite} of a GT machine for the GT mixins.
 *
 * <p>
 * The GT types stay in this class. {@link MachineSite} also loads on a server without GT, for the Railcraft and Blood
 * Magic mixins and the self-test.
 */
public final class GtMachineSites {

    private static final String NO_META_TILE_ENTITY = "?";

    private GtMachineSites() {}

    /**
     * Return whether {@code stack} is in GT's wrench list. GT fills the list with GT's wrenches, and with every item of
     * another mod that implements the BuildCraft {@code IToolWrench}.
     */
    public static boolean isWrench(ItemStack stack) {
        return GTUtility.isStackInList(stack, GregTechAPI.sWrenchList);
    }

    /** Read the site, with the position of the base tile of {@code mte}. */
    public static MachineSite readSite(IMetaTileEntity mte) {
        return readSite(mte, mte.getBaseMetaTileEntity());
    }

    /** Read the site of the MetaTileEntity of {@code base}, or a site with the class name "?" for no MetaTileEntity. */
    public static MachineSite readSite(IGregTechTileEntity base) {
        final IMetaTileEntity mte = base.getMetaTileEntity();
        if (mte == null) {
            return atBase(NO_META_TILE_ENTITY, null, base);
        }
        return readSite(mte, base);
    }

    /** Read the class and the local name from {@code mte}, and the position from {@code base}. */
    public static MachineSite readSite(IMetaTileEntity mte, IGregTechTileEntity base) {
        return atBase(
            mte.getClass()
                .getSimpleName(),
            readLocalName(mte),
            base);
    }

    // GT 5.09.54.x (dailies) has getLocalNameKey(), and GT 5.09.51.x (pack 2.8.4) has only getLocalName(). A call
    // compiled against getLocalNameKey() throws NoSuchMethodError on 5.09.51.x, so the getter is found by reflection.
    // In both versions, IMetaTileEntity declares the getter. Look the getter up there, not on the class of one machine:
    // a
    // Method of a machine class that overrides the getter throws IllegalArgumentException on every other class.
    private static final @Nullable Method LOCAL_NAME_GETTER = findLocalNameGetter();

    /** Return whether this GT has getLocalNameKey(), which is new in GT 5.09.54.x. */
    public static boolean hasLocalNameKeyGetter() {
        return LOCAL_NAME_GETTER != null && LOCAL_NAME_GETTER.getName()
            .equals("getLocalNameKey");
    }

    /** Return the local name key of {@code mte}, or null when GT has neither getter. */
    private static @Nullable String readLocalName(IMetaTileEntity mte) {
        if (LOCAL_NAME_GETTER == null) {
            return null;
        }
        try {
            return String.valueOf(LOCAL_NAME_GETTER.invoke(mte));
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, reflection into GT code
            return null;
        }
    }

    private static @Nullable Method findLocalNameGetter() {
        try {
            return IMetaTileEntity.class.getMethod("getLocalNameKey");
        } catch (NoSuchMethodException e) {
            try {
                return IMetaTileEntity.class.getMethod("getLocalName");
            } catch (NoSuchMethodException oldNameMissing) {
                return null;
            }
        }
    }

    private static MachineSite atBase(String className, String localName, IGregTechTileEntity base) {
        return new MachineSite(
            className,
            localName,
            base.getWorld().provider.dimensionId,
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord());
    }
}
