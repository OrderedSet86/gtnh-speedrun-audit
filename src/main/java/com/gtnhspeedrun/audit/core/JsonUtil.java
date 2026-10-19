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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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

    private JsonUtil() {}

    /**
     * Hash NBT the same way in every JVM and session. NBTTagCompound is backed by a HashMap, so the write order and the
     * toString() order of two equal tags can differ. The hash is over a canonical rendering instead: compound keys
     * sorted, lists in order, and primitives by toString(), whose vanilla formats are fixed.
     */
    public static String canonicalNbtHash(NBTTagCompound tag) {
        final StringBuilder rendering = new StringBuilder(256);
        appendCanonical(rendering, tag);
        return sha256Hex(rendering.toString());
    }

    /** Append {@code nbt} in the canonical form that {@link #canonicalNbtHash} describes. */
    private static void appendCanonical(StringBuilder rendering, NBTBase nbt) {
        if (nbt instanceof NBTTagCompound compound) {
            @SuppressWarnings("unchecked")
            final List<String> keys = new ArrayList<>((Set<String>) compound.func_150296_c());
            Collections.sort(keys);
            rendering.append('{');
            for (String key : keys) {
                rendering.append(key)
                    .append(':');
                appendCanonical(rendering, compound.getTag(key));
                rendering.append(',');
            }
            rendering.append('}');
        } else if (nbt instanceof NBTTagList list) {
            rendering.append('[');
            // In 1.7.10, NBTTagList has no getter that returns an element of any type, but removeTag() returns the
            // removed element. The loop empties a copy.
            final NBTTagList copy = (NBTTagList) list.copy();
            final int count = copy.tagCount();
            for (int i = 0; i < count; i++) {
                appendCanonical(rendering, copy.removeTag(0));
                rendering.append(',');
            }
            rendering.append(']');
        } else {
            rendering.append(nbt.toString());
        }
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
