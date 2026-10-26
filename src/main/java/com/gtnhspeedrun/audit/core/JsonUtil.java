package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.apache.commons.codec.binary.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Serialize and hash audit data the same way in every writer, JVM and session. */
public final class JsonUtil {

    /**
     * The chain hashes the bytes on disk, so every writer MUST serialize with this one instance. A JsonObject
     * iterates in insertion order, so the order of the addProperty() calls is the field order of a line.
     */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
        .create();

    /** The version of {@link #canonicalNbtHash}. A hash of one version can't be compared with a hash of another. */
    public static final int NBT_HASH_FORMAT = 2;
    // the tag ids of NBTBase.getId()
    private static final int NBT_END = 0;
    private static final int NBT_BYTE = 1;
    private static final int NBT_SHORT = 2;
    private static final int NBT_INT = 3;
    private static final int NBT_LONG = 4;
    private static final int NBT_FLOAT = 5;
    private static final int NBT_DOUBLE = 6;
    private static final int NBT_BYTE_ARRAY = 7;
    private static final int NBT_STRING = 8;
    private static final int NBT_LIST = 9;
    private static final int NBT_COMPOUND = 10;
    private static final int NBT_INT_ARRAY = 11;

    private JsonUtil() {}

    /**
     * Hash NBT the same way in every JVM and session. NBTTagCompound is backed by a HashMap, so the write order and the
     * toString() order of two equal tags can differ. The hash is over a canonical rendering instead, and two tags get
     * one rendering only when they are equal. Each value starts with its tag id. The keys of a compound are sorted, the
     * elements of a list are in list order, and strings and keys are quoted and escaped. Floats and doubles are their
     * raw bits in hex.
     */
    public static String canonicalNbtHash(NBTTagCompound tag) {
        final StringBuilder rendering = new StringBuilder(256);
        appendCanonical(rendering, tag);
        return sha256Hex(rendering.toString());
    }

    /** Append {@code nbt} in the canonical form that {@link #canonicalNbtHash} describes. */
    private static void appendCanonical(StringBuilder rendering, NBTBase nbt) {
        final int id = nbt.getId();
        rendering.append(id);
        switch (id) {
            case NBT_END -> {}
            case NBT_BYTE, NBT_SHORT, NBT_INT, NBT_LONG -> rendering.append(':')
                .append(((NBTBase.NBTPrimitive) nbt).func_150291_c());
            case NBT_FLOAT -> rendering.append(':')
                .append(Integer.toHexString(Float.floatToRawIntBits(((NBTBase.NBTPrimitive) nbt).func_150288_h())));
            case NBT_DOUBLE -> rendering.append(':')
                .append(Long.toHexString(Double.doubleToRawLongBits(((NBTBase.NBTPrimitive) nbt).func_150286_g())));
            case NBT_BYTE_ARRAY -> appendBytes(rendering, ((NBTTagByteArray) nbt).func_150292_c());
            case NBT_STRING -> appendQuoted(rendering, ((NBTTagString) nbt).func_150285_a_());
            case NBT_LIST -> appendList(rendering, (NBTTagList) nbt);
            case NBT_COMPOUND -> appendCompound(rendering, (NBTTagCompound) nbt);
            case NBT_INT_ARRAY -> appendInts(rendering, ((NBTTagIntArray) nbt).func_150302_c());
            default -> throw new IllegalArgumentException("unknown NBT tag id " + id + " (expected 0 to 11)");
        }
    }

    private static void appendCompound(StringBuilder rendering, NBTTagCompound compound) {
        @SuppressWarnings("unchecked")
        final List<String> keys = new ArrayList<>((Set<String>) compound.func_150296_c());
        Collections.sort(keys);
        rendering.append('{');
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                rendering.append(',');
            }
            appendQuoted(rendering, keys.get(index));
            rendering.append(':');
            appendCanonical(rendering, compound.getTag(keys.get(index)));
        }
        rendering.append('}');
    }

    /** Append the list's element type, then the elements, so two empty lists of different types differ. */
    private static void appendList(StringBuilder rendering, NBTTagList list) {
        rendering.append('[')
            .append(list.func_150303_d())
            .append(';');
        final NBTBase[] elements = readListElements(list);
        for (int index = 0; index < elements.length; index++) {
            if (index > 0) {
                rendering.append(',');
            }
            appendCanonical(rendering, elements[index]);
        }
        rendering.append(']');
    }

    private static void appendBytes(StringBuilder rendering, byte[] bytes) {
        rendering.append('[');
        for (byte value : bytes) {
            rendering.append(Character.forDigit((value >> 4) & 0xF, 16))
                .append(Character.forDigit(value & 0xF, 16));
        }
        rendering.append(']');
    }

    private static void appendInts(StringBuilder rendering, int[] values) {
        rendering.append('[');
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                rendering.append(',');
            }
            rendering.append(values[index]);
        }
        rendering.append(']');
    }

    /**
     * Append {@code text} in double quotes. A quote or backslash gets a backslash, and a control character or a
     * surrogate is written as {@code \}{@code uXXXX}, so a lone surrogate never turns into {@code ?} in UTF-8.
     */
    private static void appendQuoted(StringBuilder rendering, String text) {
        final int HEX_DIGITS = 4;
        rendering.append('"');
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character == '"' || character == '\\') {
                rendering.append('\\')
                    .append(character);
            } else if (character < ' ' || Character.isSurrogate(character)) {
                final String hex = Integer.toHexString(character);
                rendering.append("\\u")
                    .append("0".repeat(HEX_DIGITS - hex.length()))
                    .append(hex);
            } else {
                rendering.append(character);
            }
        }
        rendering.append('"');
    }

    /**
     * Return the elements of {@code list} in order. In 1.7.10, NBTTagList has no getter that returns an element of any
     * type, but removeTag() returns the removed element.
     */
    private static NBTBase[] readListElements(NBTTagList list) {
        final NBTTagList copy = (NBTTagList) list.copy();
        final NBTBase[] elements = new NBTBase[copy.tagCount()];
        // removeTag() shifts every later element. Remove from the end, so a list of 2,000,000 empty elements from a
        // client takes linear time
        for (int index = elements.length - 1; index >= 0; index--) {
            elements[index] = copy.removeTag(index);
        }
        return elements;
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        return toHex(sha256(bytes));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String toHex(byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Return the gzipped NBT in base64, the exact form of the evidence in snapshots and nbt_edit lines. For a tag that
     * cannot be written, the result is {@code ERROR:} and the exception.
     */
    public static String nbtToB64(NBTTagCompound tag) {
        try {
            return Base64.encodeBase64String(CompressedStreamTools.compress(tag));
        } catch (IOException | RuntimeException e) { // noqa: NAR142, NBT from the tile entities and items of other mods
            return "ERROR:" + e;
        }
    }

    /** Remove the Minecraft § formatting codes. Formatting codes don't belong in an audit record. */
    public static String stripFormatting(String text) {
        return text == null ? null : text.replaceAll("§.", "");
    }

    public static boolean hasNumber(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive()
                .isNumber();
    }

    public static boolean hasString(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive()
                .isString();
    }

    public static boolean hasObject(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonObject();
    }
}
