package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coil and structure tiers of a formed multiblock — the EBF's coil, a Space Elevator's motor tier, a glass tier
 * gate — read without binding to any of the hundreds of controller classes. GT has no common interface for them,
 * so the readable set is decided by field shape, once per class, and cached:
 *
 * <ul>
 * <li>any instance field typed as an enum named {@code HeatingCoilLevel} (value: the enum constant name);</li>
 * <li>{@code mHeatingCapacity} (the EBF-family heat in kelvin);</li>
 * <li>instance {@code int}/{@code short}/{@code byte} fields with "tier" as a word of the name ({@code glassTier},
 * {@code mTierCasing}), skipping render, recipe, limit, lookup and count bookkeeping;</li>
 * <li>instance {@code int} fields named {@code ...Metadata} (the Eye of Harmony's three field tiers);</li>
 * <li>the few tiers that need a path or are not numbers, listed by class in {@link #EXTRA_PATHS}.</li>
 * </ul>
 *
 * Only fields declared below GT's shared base classes are read, so per-hatch bookkeeping in those bases does not
 * drown the controller's own tiers. Values are whatever the structure check left behind at RETURN.
 */
public final class StructureTiers {

    private static final int MAX_FIELDS = 12;
    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();
    /** Controller simple class name → dotted field paths read on top of the name rules. */
    private static final Map<String, String[]> EXTRA_PATHS = new HashMap<>();

    static {
        // the Plasma Forge's convergence upgrade
        EXTRA_PATHS.put("MTEPlasmaForge", new String[] { "convergence" });
        // the Exo-Foundry's tier (1 UEV, 2 UIV, 3 UXV) and its module slots (heliocast, hypercooler, …)
        EXTRA_PATHS.put("MTEExoFoundry", new String[] { "foundryData.tier", "foundryData.modules" });
    }

    private StructureTiers() {}

    /** What {@link #read} reads for one controller class, decided once. */
    private static final class Plan {

        /** Each entry is a field path: one field, or a field of a field's value. */
        final List<Field[]> paths = new ArrayList<>();
        /** Simple names of the classes whose own fields this side cannot list, so none of them are read. */
        final List<String> unreadable = new ArrayList<>();
    }

    /** Field name → value, in declaration order (concrete class first). Empty, never null. */
    public static Map<String, String> read(Object controller) {
        final List<Field[]> plan = PLANS.computeIfAbsent(controller.getClass(), StructureTiers::plan).paths;
        if (plan.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (Field[] path : plan) {
            try {
                Object v = controller;
                final StringBuilder key = new StringBuilder();
                for (Field f : path) {
                    v = v == null ? null : f.get(v);
                    key.append(key.length() == 0 ? "" : ".")
                        .append(f.getName());
                }
                out.put(key.toString(), format(v));
            } catch (IllegalAccessException | RuntimeException ignored) {
                // A field that refuses to read is left out; the rest still describe the structure.
            }
        }
        return out;
    }

    /**
     * Simple names of the classes, from the controller's class up to GT's shared bases, whose own fields {@link #read}
     * skips on this side; empty when none. On a dedicated server that is a class with a field typed by a client-only
     * class, so a formation line can say its structure tiers are incomplete.
     */
    public static List<String> unreadable(Class<?> type) {
        return PLANS.computeIfAbsent(type, StructureTiers::plan).unreadable;
    }

    private static String format(Object v) {
        if (v instanceof Enum<?>e) {
            return e.name();
        }
        if (v instanceof Object[]array) {
            final StringBuilder sb = new StringBuilder("[");
            for (Object o : array) {
                sb.append(sb.length() == 1 ? "" : ",")
                    .append(format(o));
            }
            return sb.append("]")
                .toString();
        }
        return String.valueOf(v);
    }

    /** The keys {@link #read} would produce for a controller class, for the self-test to check on a real pack. */
    public static List<String> planKeys(Class<?> type) {
        final List<String> keys = new ArrayList<>();
        for (Field[] path : PLANS.computeIfAbsent(type, StructureTiers::plan).paths) {
            final StringBuilder key = new StringBuilder();
            for (Field f : path) {
                key.append(key.length() == 0 ? "" : ".")
                    .append(f.getName());
            }
            keys.add(key.toString());
        }
        return keys;
    }

    private static Plan plan(Class<?> type) {
        final Plan plan = new Plan();
        final List<Field[]> out = plan.paths;
        final String[] extra = EXTRA_PATHS.get(type.getSimpleName());
        if (extra != null) {
            for (String dotted : extra) {
                final Field[] path = resolve(type, dotted);
                if (path != null) {
                    out.add(path);
                }
            }
        }
        for (Class<?> c = type; c != null && !isSharedBase(c); c = c.getSuperclass()) {
            final Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (LinkageError e) {
                // Listing fields loads every field's type. On a dedicated server a field typed by a client-only
                // class (the Extreme Entity Crusher's kubatech.client.effect.EntityRenderer) cannot load, so this
                // class's own fields are skipped and its superclasses are still read.
                plan.unreadable.add(c.getSimpleName());
                continue;
            }
            for (Field f : declared) {
                if (out.size() >= MAX_FIELDS) {
                    return plan;
                }
                if (Modifier.isStatic(f.getModifiers()) || !wanted(f)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    out.add(new Field[] { f });
                } catch (RuntimeException ignored) {
                    // Inaccessible under this JVM's module rules; skip it.
                }
            }
        }
        return plan;
    }

    /**
     * The field chain for a dotted path, each looked up through its declaring type's superclasses; null if any is
     * missing.
     */
    static Field[] resolve(Class<?> type, String dotted) {
        final String[] names = dotted.split("\\.");
        final Field[] path = new Field[names.length];
        Class<?> owner = type;
        for (int i = 0; i < names.length; i++) {
            path[i] = find(owner, names[i]);
            if (path[i] == null) {
                return null;
            }
            owner = path[i].getType();
        }
        return path;
    }

    private static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException | RuntimeException | LinkageError ignored) {
                // keep looking in the superclass; LinkageError: a client-only field type on a server, as in plan()
            }
        }
        return null;
    }

    static boolean wanted(Field f) {
        final Class<?> t = f.getType();
        if (t.isEnum() && "HeatingCoilLevel".equals(t.getSimpleName())) {
            return true;
        }
        final String name = f.getName();
        if ("mHeatingCapacity".equals(name)) {
            return t == int.class || t == long.class;
        }
        if (t == int.class && name.endsWith("Metadata")) {
            return true;
        }
        return (t == int.class || t == short.class || t == byte.class) && isTierName(name);
    }

    /** Words that mark a tier-named field as bookkeeping (a limit, a lookup, a count), not the structure's tier. */
    private static final java.util.Set<String> NOT_STRUCTURE = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "render",
            "recipe",
            "max",
            "maximum",
            "min",
            "minimum",
            "required",
            "current",
            "last",
            "index",
            "override",
            "count",
            "amount"));

    /**
     * "tier" as a whole camelCase word ({@code glassTier}, {@code mTierCasing}), with no bookkeeping word beside it.
     */
    static boolean isTierName(String name) {
        boolean tier = false;
        for (String word : name.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            final String lower = word.toLowerCase(java.util.Locale.ROOT);
            if (NOT_STRUCTURE.contains(lower)) {
                return false;
            }
            tier |= "tier".equals(lower);
        }
        return tier;
    }

    /** GT's and its addons' multiblock base classes: everything from here up is shared plumbing. */
    static boolean isSharedBase(Class<?> c) {
        final String n = c.getName();
        return n.startsWith("gregtech.api.") || n.startsWith("java.")
            || n.endsWith(".TTMultiblockBase")
            || n.endsWith(".GTPPMultiBlockBase")
            || n.endsWith(".KubaTechGTMultiBlockBase");
    }
}
