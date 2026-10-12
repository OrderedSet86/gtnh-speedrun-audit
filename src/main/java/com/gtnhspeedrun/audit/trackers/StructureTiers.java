package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
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
 * <li>instance {@code int}/{@code short}/{@code byte} fields named {@code ...Tier} / {@code ...tier}, skipping
 * render, recipe and limit bookkeeping.</li>
 * </ul>
 *
 * Only fields declared below GT's shared base classes are read, so per-hatch bookkeeping in those bases does not
 * drown the controller's own tiers. Values are whatever the structure check left behind at RETURN.
 */
public final class StructureTiers {

    private static final int MAX_FIELDS = 12;
    private static final Map<Class<?>, List<Field>> PLANS = new ConcurrentHashMap<>();

    private StructureTiers() {}

    /** Field name → value, in declaration order (concrete class first). Empty, never null. */
    public static Map<String, String> read(Object controller) {
        final List<Field> plan = PLANS.computeIfAbsent(controller.getClass(), StructureTiers::plan);
        if (plan.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (Field f : plan) {
            try {
                final Object v = f.get(controller);
                out.put(f.getName(), v instanceof Enum<?>e ? e.name() : String.valueOf(v));
            } catch (IllegalAccessException | RuntimeException ignored) {
                // A field that refuses to read is left out; the rest still describe the structure.
            }
        }
        return out;
    }

    static List<Field> plan(Class<?> type) {
        final List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && !isSharedBase(c); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (out.size() >= MAX_FIELDS) {
                    return out;
                }
                if (Modifier.isStatic(f.getModifiers()) || !wanted(f)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (RuntimeException ignored) {
                    // Inaccessible under this JVM's module rules; skip it.
                }
            }
        }
        return out;
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
        return (t == int.class || t == short.class || t == byte.class) && isTierName(name);
    }

    static boolean isTierName(String name) {
        if (!(name.endsWith("Tier") || name.endsWith("tier"))) {
            return false;
        }
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !(lower.contains("render") || lower.contains("recipe")
            || lower.contains("max")
            || lower.contains("min")
            || lower.contains("required")
            || lower.contains("current"));
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
