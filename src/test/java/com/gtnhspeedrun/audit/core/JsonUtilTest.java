package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.jupiter.api.Test;

/**
 * Test the canonical NBT hash. NBTTagCompound is backed by a HashMap, so two equal tags built in different key orders
 * can serialize differently through vanilla code. If these rules break, a snapshot hash from months ago no longer
 * matches the hash computed again.
 */
class JsonUtilTest {

    @Test
    void insertionOrderDoesNotChangeHash() {
        final NBTTagCompound a = new NBTTagCompound();
        a.setInteger("damage", 3);
        a.setString("owner", "player");
        a.setLong("charge", 512_000L);

        final NBTTagCompound b = new NBTTagCompound();
        b.setLong("charge", 512_000L);
        b.setInteger("damage", 3);
        b.setString("owner", "player");

        assertEquals(JsonUtil.canonicalNbtHash(a), JsonUtil.canonicalNbtHash(b));
    }

    @Test
    void nestedCompoundsAndListsAreCovered() {
        final NBTTagCompound outerA = new NBTTagCompound();
        final NBTTagCompound innerA = new NBTTagCompound();
        innerA.setString("z", "last");
        innerA.setString("a", "first");
        outerA.setTag("inner", innerA);
        final NBTTagList listA = new NBTTagList();
        listA.appendTag(new NBTTagString("one"));
        listA.appendTag(new NBTTagString("two"));
        outerA.setTag("list", listA);

        final NBTTagCompound outerB = new NBTTagCompound();
        final NBTTagList listB = new NBTTagList();
        listB.appendTag(new NBTTagString("one"));
        listB.appendTag(new NBTTagString("two"));
        outerB.setTag("list", listB);
        final NBTTagCompound innerB = new NBTTagCompound();
        innerB.setString("a", "first");
        innerB.setString("z", "last");
        outerB.setTag("inner", innerB);

        assertEquals(JsonUtil.canonicalNbtHash(outerA), JsonUtil.canonicalNbtHash(outerB));
    }

    @Test
    void differentValuesProduceDifferentHashes() {
        final NBTTagCompound a = new NBTTagCompound();
        a.setInteger("damage", 3);
        final NBTTagCompound b = new NBTTagCompound();
        b.setInteger("damage", 4);
        assertNotEquals(JsonUtil.canonicalNbtHash(a), JsonUtil.canonicalNbtHash(b));
    }

    @Test
    void listOrderMatters() {
        final NBTTagList l1 = new NBTTagList();
        l1.appendTag(new NBTTagString("a"));
        l1.appendTag(new NBTTagString("b"));
        final NBTTagCompound c1 = new NBTTagCompound();
        c1.setTag("l", l1);

        final NBTTagList l2 = new NBTTagList();
        l2.appendTag(new NBTTagString("b"));
        l2.appendTag(new NBTTagString("a"));
        final NBTTagCompound c2 = new NBTTagCompound();
        c2.setTag("l", l2);

        assertNotEquals(JsonUtil.canonicalNbtHash(c1), JsonUtil.canonicalNbtHash(c2));
    }

    @Test
    void sha256HexOfAbcIsTheFipsTestVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JsonUtil.sha256Hex("abc"));
    }
}
