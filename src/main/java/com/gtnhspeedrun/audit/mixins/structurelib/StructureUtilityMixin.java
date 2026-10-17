package com.gtnhspeedrun.audit.mixins.structurelib;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.gtnhspeedrun.audit.trackers.StructureBounds;

/**
 * Feeds StructureBounds every block position a structure check visits. iterateV2 is the one method every
 * StructureLib walk goes through on both packs: GT 5.09.51 (pack 2.8.4) reaches it through IStructureDefinition.check,
 * GT 5.09.54 through IStructureDefinition.iterate with its own walker. It calls World.blockExists once per visited
 * position, loaded or not, so that call is the per-position hook. A Redirect allocates nothing there. The handlers
 * name no StructureLib type: IStructureWalker is package-private in StructureLib 1.4.23.
 *
 * <p>
 * Base config, because StructureLib is a coremod (the same reason as the NEI mixins). AuditMixinPlugin applies it
 * only when StructureLib is on the classpath and speedrunaudit.noStructureBounds is not set.
 */
@Mixin(value = StructureUtility.class, remap = false)
public abstract class StructureUtilityMixin {

    @Inject(method = "iterateV2", at = @At("HEAD"), require = 1)
    private static void audit$iterateStarted(CallbackInfoReturnable<Boolean> cir) {
        StructureBounds.iterateStarted();
    }

    @Redirect(
        method = "iterateV2",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;blockExists(III)Z", remap = true),
        require = 1)
    private static boolean audit$visited(World world, int x, int y, int z) {
        StructureBounds.visited(x, y, z);
        return world.blockExists(x, y, z);
    }

    @Inject(method = "iterateV2", at = @At("RETURN"), require = 1)
    private static void audit$iterateFinished(CallbackInfoReturnable<Boolean> cir) {
        StructureBounds.iterateFinished(cir.getReturnValue());
    }
}
