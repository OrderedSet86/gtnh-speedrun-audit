package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.jupiter.api.Test;

/**
 * Test the canonical NBT hash: equal tags hash the same in any insertion order, and any change to a tag changes the
 * hash, including the changes that the first format missed (string text that looks like structure, byte array
 * contents, tag types).
 */
class JsonUtilTest {

    private static final int CASES = 2_000;

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
    void listOrderMatters() {
        assertNotEquals(hashOf("l", stringList("a", "b")), hashOf("l", stringList("b", "a")));
    }

    @Test
    void sha256HexOfAbcIsTheFipsTestVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JsonUtil.sha256Hex("abc"));
    }

    @Test
    void tagsThatTheFirstFormatRenderedAlikeHashDifferently() {
        final NBTTagCompound injected = new NBTTagCompound();
        injected.setString("k", "a\",l:\"b");
        final NBTTagCompound twoKeys = new NBTTagCompound();
        twoKeys.setString("k", "a");
        twoKeys.setString("l", "b");
        assertNotEquals(JsonUtil.canonicalNbtHash(injected), JsonUtil.canonicalNbtHash(twoKeys));

        final NBTTagCompound bytesA = new NBTTagCompound();
        bytesA.setByteArray("b", new byte[] { 1, 2, 3 });
        final NBTTagCompound bytesB = new NBTTagCompound();
        bytesB.setByteArray("b", new byte[] { 1, 2, 4 });
        assertNotEquals(JsonUtil.canonicalNbtHash(bytesA), JsonUtil.canonicalNbtHash(bytesB));

        final NBTTagList intList = new NBTTagList();
        intList.appendTag(new NBTTagInt(1));
        intList.appendTag(new NBTTagInt(2));
        final NBTTagCompound intArray = new NBTTagCompound();
        intArray.setIntArray("v", new int[] { 1, 2 });
        assertNotEquals(hashOf("v", intList), JsonUtil.canonicalNbtHash(intArray));

        final NBTTagCompound asInt = new NBTTagCompound();
        asInt.setInteger("n", 1);
        final NBTTagCompound asLong = new NBTTagCompound();
        asLong.setLong("n", 1);
        assertNotEquals(JsonUtil.canonicalNbtHash(asInt), JsonUtil.canonicalNbtHash(asLong));

        final NBTTagCompound zero = new NBTTagCompound();
        zero.setFloat("f", 0.0f);
        final NBTTagCompound negativeZero = new NBTTagCompound();
        negativeZero.setFloat("f", -0.0f);
        assertNotEquals(JsonUtil.canonicalNbtHash(zero), JsonUtil.canonicalNbtHash(negativeZero));

        final NBTTagCompound loneSurrogate = new NBTTagCompound();
        loneSurrogate.setString("s", "\uD800");
        final NBTTagCompound questionMark = new NBTTagCompound();
        questionMark.setString("s", "?");
        assertNotEquals(JsonUtil.canonicalNbtHash(loneSurrogate), JsonUtil.canonicalNbtHash(questionMark));

        final NBTTagList emptyOfBytes = new NBTTagList();
        emptyOfBytes.appendTag(new NBTTagByte((byte) 0));
        emptyOfBytes.removeTag(0);
        assertNotEquals(hashOf("e", emptyOfBytes), hashOf("e", new NBTTagList()));
    }

    @Test
    void generatedTagsHashTheSameInReverseInsertionOrderAndDifferAfterAnyChange() {
        for (int caseNumber = 0; caseNumber < CASES; caseNumber++) {
            final NBTTagCompound tag = generateCompound(new Random(caseNumber), 0, false);
            final NBTTagCompound reversed = generateCompound(new Random(caseNumber), 0, true);
            final NBTTagCompound changed = generateCompound(new Random(caseNumber), 0, false);
            changed.setInteger("zz_changed", caseNumber);

            assertEquals(JsonUtil.canonicalNbtHash(tag), JsonUtil.canonicalNbtHash(reversed), "case " + caseNumber);
            assertNotEquals(JsonUtil.canonicalNbtHash(tag), JsonUtil.canonicalNbtHash(changed), "case " + caseNumber);
        }
    }

    @Test
    void listOfTwoMillionEmptyElementsFromAPacketHashesInUnderTwoSeconds() throws IOException {
        final int ELEMENTS = 2_000_000;
        final long BUDGET_NANOS = 2_000_000_000L;
        final NBTTagCompound tag = readPacketTag(ELEMENTS);
        final long start = System.nanoTime();
        JsonUtil.canonicalNbtHash(tag);
        final long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < BUDGET_NANOS, "hash took " + elapsed / 1_000_000 + " ms");
    }

    @Test
    void intArrayOfAMillionValuesHashesInUnderTwoSeconds() {
        final long BUDGET_NANOS = 2_000_000_000L;
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setIntArray("ints", new int[1_000_000]);
        final long start = System.nanoTime();
        JsonUtil.canonicalNbtHash(tag);
        final long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < BUDGET_NANOS, "hash took " + elapsed / 1_000_000 + " ms");
    }

    private static String hashOf(String key, NBTBase value) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(key, value);
        return JsonUtil.canonicalNbtHash(tag);
    }

    private static NBTTagList stringList(String... values) {
        final NBTTagList list = new NBTTagList();
        for (String value : values) {
            list.appendTag(new NBTTagString(value));
        }
        return list;
    }

    /**
     * Read a compound with one list of {@code elements} empty (type 0) elements, from the bytes a client can send. The
     * bytes are 14 long, whatever the count.
     */
    private static NBTTagCompound readPacketTag(int elements) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10); // compound
        out.writeUTF("");
        out.writeByte(9); // list
        out.writeUTF("l");
        out.writeByte(0); // element type: end
        out.writeInt(elements);
        out.writeByte(0); // end of compound
        return CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    /** Generate a compound from {@code random}, with keys inserted in reverse order when {@code reverse} is set. */
    private static NBTTagCompound generateCompound(Random random, int depth, boolean reverse) {
        final int MAX_DEPTH = 4;
        final int keys = random.nextInt(6);
        final List<String> names = new ArrayList<>();
        final List<NBTBase> values = new ArrayList<>();
        for (int key = 0; key < keys; key++) {
            names.add("k" + key);
            values.add(generateValue(random, depth, reverse, MAX_DEPTH));
        }
        if (reverse) {
            Collections.reverse(names);
            Collections.reverse(values);
        }
        final NBTTagCompound tag = new NBTTagCompound();
        for (int index = 0; index < names.size(); index++) {
            tag.setTag(names.get(index), values.get(index));
        }
        return tag;
    }

    private static NBTBase generateValue(Random random, int depth, boolean reverse, int maxDepth) {
        final NBTTagCompound holder = new NBTTagCompound();
        switch (random.nextInt(depth < maxDepth ? 8 : 6)) {
            case 0 -> holder.setInteger("v", random.nextInt());
            case 1 -> holder.setString("v", "s\"" + random.nextInt() + "\\,:{");
            case 2 -> holder.setIntArray("v", new int[] { random.nextInt(), random.nextInt() });
            case 3 -> holder.setByteArray("v", new byte[] { (byte) random.nextInt(), (byte) random.nextInt() });
            case 4 -> holder.setDouble("v", random.nextDouble());
            case 5 -> holder.setLong("v", random.nextLong());
            case 6 -> holder.setTag("v", generateCompound(random, depth + 1, reverse));
            default -> holder.setTag("v", stringList("a" + random.nextInt(), "b"));
        }
        return holder.getTag("v");
    }
}
