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
 * Passes each block position that a structure check visits to StructureBounds.
 *
 * <p>
 * Every StructureLib walk on both packs goes through iterateV2(). GT 5.09.51 (pack 2.8.4) calls iterateV2() through
 * IStructureDefinition.check(), and GT 5.09.54 calls iterateV2() through IStructureDefinition.iterate() with a GT
 * walker. iterateV2() calls World.blockExists() once per visited position, loaded or not, so the redirect of that call
 * is the hook for each position. A Redirect allocates nothing per call. The handlers take no StructureLib type as a
 * parameter, because IStructureWalker is package-private in StructureLib 1.4.23.
 *
 * <p>
 * The mixin is in the base config because StructureLib is a coremod, the same reason as for the NEI mixins.
 * AuditMixinPlugin.shouldApplyMixin() returns true for this mixin only when StructureLib is on the classpath and
 * speedrunaudit.noStructureBounds is not set.
 */
@Mixin(value = StructureUtility.class, remap = false)
public abstract class StructureUtilityMixin { // noqa: NAR106

    @Inject(method = "iterateV2", at = @At("HEAD"), require = 1)
    private static void audit$iterateStarted(CallbackInfoReturnable<Boolean> cir) { // noqa: NAR130
        StructureBounds.iterateStarted();
    }

    /** Passes the position to StructureBounds, then makes the original blockExists() call. */
    @Redirect(
        method = "iterateV2",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;blockExists(III)Z", remap = true),
        require = 1)
    private static boolean audit$visited(World world, int x, int y, int z) { // noqa: NAR130, NAR131
        StructureBounds.visited(x, y, z);
        return world.blockExists(x, y, z);
    }

    @Inject(method = "iterateV2", at = @At("RETURN"), require = 1)
    private static void audit$iterateFinished(CallbackInfoReturnable<Boolean> cir) { // noqa: NAR130
        StructureBounds.iterateFinished(cir.getReturnValue());
    }
}
