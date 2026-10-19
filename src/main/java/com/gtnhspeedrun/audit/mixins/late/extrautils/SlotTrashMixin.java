package com.gtnhspeedrun.audit.mixins.late.extrautils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.InventoryChanges;

/**
 * Logs items that a player puts into an Extra Utilities trash can by hand. A player reaches the can only through the
 * can's GUI slot, SlotTrash. Click-placing and shift-clicking both end in putStack(). Pipes, hoppers and conveyors
 * insert through the tile's IInventory and never touch a Slot, so trash from a machine line is not logged.
 * putStack() passes the stack to the tile with instantAdd set, and the tile voids the stack at once. Only a
 * node-upgrade filter in slot 0 stays in the can.
 *
 * <p>
 * Extra Utilities 1.2.12 is on no maven, so the target is @Pseudo with the SRG method name. The jar is the same in
 * pack 2.8.4 and 2.9.x.
 */
@Pseudo
@Mixin(targets = "com.rwtema.extrautils.gui.SlotTrash", remap = false)
public class SlotTrashMixin {

    @Inject(method = "func_75215_d(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 1)
    private void audit$put(ItemStack stack, CallbackInfo ci) { // noqa: NAR130
        if (stack == null) {
            return;
        }
        final Slot self = (Slot) (Object) this;
        EntityPlayerMP player = null;
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            for (Object entry : server.getConfigurationManager().playerEntityList) {
                final EntityPlayerMP candidate = (EntityPlayerMP) entry;
                if (candidate.openContainer != null && candidate.openContainer.inventorySlots.contains(self)) {
                    player = candidate;
                    break;
                }
            }
        }
        final TileEntity can = self.inventory instanceof TileEntity tile ? tile : null;
        if (can != null && can.getWorldObj() != null && can.getWorldObj().isRemote) {
            return;
        }
        InventoryChanges.logTrashInsert(player, can, stack);
    }
}
