package com.gtnhspeedrun.audit.trackers;

/**
 * Shaped like the Extreme Entity Crusher: a controller with a field typed by a client-only class. StructureTiersTest
 * defines this class and ClientFieldBase in a loader that refuses ClientOnly, as FML's SideTransformer refuses
 * client classes on a dedicated server. Top-level, because a nested class cannot be split from its outer class
 * across loaders.
 */
@SuppressWarnings("unused")
class ClientFieldController extends ClientFieldBase {

    private ClientOnly renderer;
    int casingTier = 2;
}

class ClientFieldBase {

    int glassTier = 4;
}

/** Stands in for a client-only class such as kubatech.client.effect.EntityRenderer. */
class ClientOnly {
}
