package com.gtnhspeedrun.audit.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class JsonUtil {

    /**
     * The chain hashes exact on-disk bytes, so every writer must serialize identically. One shared instance,
     * HTML escaping off — {@code toString()} on a JsonObject preserves insertion order, which is what pins the
     * envelope field order.
     */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonUtil() {}

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return toHex(sha256(bytes));
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String toHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Hash of an item's NBT that is stable across JVMs and sessions. NBTTagCompound is HashMap-backed, so both
     * its write order and {@code toString()} order vary per launch — hash a canonical rendering instead:
     * compound keys sorted, lists in order, primitives via toString (fixed formats in vanilla NBT).
     */
    public static String canonicalNbtHash(NBTTagCompound tag) {
        final StringBuilder sb = new StringBuilder(256);
        appendCanonical(sb, tag);
        return sha256Hex(sb.toString());
    }

    private static void appendCanonical(StringBuilder sb, NBTBase nbt) {
        if (nbt instanceof NBTTagCompound compound) {
            @SuppressWarnings("unchecked")
            final List<String> keys = new ArrayList<>((java.util.Set<String>) compound.func_150296_c());
            Collections.sort(keys);
            sb.append('{');
            for (String key : keys) {
                sb.append(key).append(':');
                appendCanonical(sb, compound.getTag(key));
                sb.append(',');
            }
            sb.append('}');
        } else if (nbt instanceof NBTTagList list) {
            sb.append('[');
            // NBTTagList hides its backing list; tagCount/getCompoundTagAt only covers compounds, so render
            // via copy-and-remove on a copy to stay type-agnostic.
            final NBTTagList copy = (NBTTagList) list.copy();
            final int n = copy.tagCount();
            for (int i = 0; i < n; i++) {
                appendCanonical(sb, copy.removeTag(0));
                sb.append(',');
            }
            sb.append(']');
        } else {
            sb.append(nbt.toString());
        }
    }
}
