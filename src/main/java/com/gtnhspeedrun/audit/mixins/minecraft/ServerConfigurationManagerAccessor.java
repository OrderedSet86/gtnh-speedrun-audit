package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.server.management.ServerConfigurationManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read whether every player may use commands. Open to LAN with "Allow Cheats" sets the flag, and vanilla has no getter
 * for the flag.
 */
@Mixin(ServerConfigurationManager.class)
public interface ServerConfigurationManagerAccessor {

    @Accessor("commandsAllowedForAll")
    boolean audit$isCommandsAllowedForAll(); // noqa: NAR130
}
