package com.gtnhspeedrun.audit.trackers;

/**
 * Stand in for the Extreme Entity Crusher: a controller with a field of a client-only type. StructureTiersTest defines
 * this class and ClientFieldBase in a loader that refuses ClientOnly. FML's SideTransformer refuses client classes on a
 * dedicated server the same way. The class is top-level, because a nested class and its outer class cannot be split
 * across two loaders.
 */
@SuppressWarnings("unused")
class ClientFieldController extends ClientFieldBase {

    private ClientOnly renderer;
    int casingTier = 2;
}

/** Give ClientFieldController a superclass with a tier field. */
class ClientFieldBase {

    int glassTier = 4;
}

/** Stand in for a client-only class such as kubatech.client.effect.EntityRenderer. */
class ClientOnly {
}
