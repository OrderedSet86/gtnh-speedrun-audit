package com.gtnhspeedrun.audit.trackers;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

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
 *
 * <p>
 * Listing a class's fields loads every field's type. On a dedicated server a field typed by a client-only class
 * (the Extreme Entity Crusher's kubatech.client.effect.EntityRenderer) cannot load, so for such a class the fields
 * are found by name in its class file instead, and each one is read through a method handle, which resolves only
 * that field.
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

        final List<Getter> getters = new ArrayList<>();
        /** Simple names of the classes whose own fields could be neither listed nor found by name on this side. */
        final List<String> unreadable = new ArrayList<>();
    }

    /** One value of the plan, under its key in the formation line. */
    private interface Getter {

        String key();

        Object get(Object controller) throws ReflectiveOperationException;
    }

    /** A field path: one field, or a field of a field's value. */
    private static final class FieldPath implements Getter {

        private final Field[] path;

        FieldPath(Field[] path) {
            this.path = path;
        }

        @Override
        public String key() {
            final StringBuilder key = new StringBuilder();
            for (Field f : path) {
                key.append(key.length() == 0 ? "" : ".")
                    .append(f.getName());
            }
            return key.toString();
        }

        @Override
        public Object get(Object controller) throws IllegalAccessException {
            Object v = controller;
            for (Field f : path) {
                v = v == null ? null : f.get(v);
            }
            return v;
        }
    }

    /** A field found by name in the class file, read through a getter handle. */
    private static final class HandleGetter implements Getter {

        private final String name;
        private final MethodHandle getter;

        HandleGetter(String name, MethodHandle getter) {
            this.name = name;
            this.getter = getter;
        }

        @Override
        public String key() {
            return name;
        }

        @Override
        public Object get(Object controller) throws InvocationTargetException {
            try {
                return getter.invoke(controller);
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }
    }

    /** Field name → value, in declaration order (concrete class first). Empty, never null. */
    public static Map<String, String> read(Object controller) {
        final List<Getter> plan = PLANS.computeIfAbsent(controller.getClass(), StructureTiers::plan).getters;
        if (plan.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (Getter g : plan) {
            try {
                out.put(g.key(), format(g.get(controller)));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // A field that refuses to read is left out; the rest still describe the structure.
            }
        }
        return out;
    }

    /**
     * Simple names of the classes, from the controller's class up to GT's shared bases, whose own fields {@link #read}
     * skips on this side; empty when none. That is a class whose fields cannot all be listed here and whose class file
     * could not be read either (a JVM before 9, or no class file), so a formation line can say its structure tiers
     * are incomplete.
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
        for (Getter g : PLANS.computeIfAbsent(type, StructureTiers::plan).getters) {
            keys.add(g.key());
        }
        return keys;
    }

    private static Plan plan(Class<?> type) {
        final Plan plan = new Plan();
        final List<Getter> out = plan.getters;
        final String[] extra = EXTRA_PATHS.get(type.getSimpleName());
        if (extra != null) {
            for (String dotted : extra) {
                final Field[] path = resolve(type, dotted);
                if (path != null) {
                    out.add(new FieldPath(path));
                }
            }
        }
        for (Class<?> c = type; c != null && !isSharedBase(c); c = c.getSuperclass()) {
            final Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (LinkageError e) {
                // a field type that cannot load on this side; the class's other fields are still found by name
                if (!addByName(c, out)) {
                    plan.unreadable.add(c.getSimpleName());
                }
                continue;
            }
            for (Field f : declared) {
                if (out.size() >= MAX_FIELDS) {
                    return plan;
                }
                if (Modifier.isStatic(f.getModifiers()) || !wanted(f.getName(), f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    out.add(new FieldPath(new Field[] { f }));
                } catch (RuntimeException ignored) {
                    // Inaccessible under this JVM's module rules; skip it.
                }
            }
        }
        return plan;
    }

    /**
     * Adds the wanted fields of a class whose fields cannot all be listed: names and types come from its class
     * file, and each value from a getter handle, which resolves only that one field.
     *
     * @return false when that is not possible here: no class file, one this ASM cannot read, or a JVM before 9
     *         (no MethodHandles.privateLookupIn, called reflectively because the mod compiles against Java 8)
     */
    private static boolean addByName(Class<?> c, List<Getter> out) {
        final ClassLoader loader = c.getClassLoader();
        if (loader == null) {
            return false;
        }
        final List<String[]> fields;
        try (InputStream in = loader.getResourceAsStream(
            c.getName()
                .replace('.', '/') + ".class")) {
            if (in == null) {
                return false;
            }
            fields = instanceFields(in);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        final MethodHandles.Lookup lookup;
        try {
            lookup = (MethodHandles.Lookup) MethodHandles.class
                .getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class)
                .invoke(null, c, MethodHandles.lookup());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        for (String[] f : fields) {
            if (out.size() >= MAX_FIELDS) {
                break;
            }
            final Class<?> type = typeOf(f[1], loader);
            if (type == null || !wanted(f[0], type)) {
                continue;
            }
            try {
                out.add(new HandleGetter(f[0], lookup.findGetter(c, f[0], type)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // not readable after all; the class's other fields still are
            }
        }
        return true;
    }

    /** {name, descriptor} of each instance field, in declaration order. */
    private static List<String[]> instanceFields(InputStream classFile) throws IOException {
        final List<String[]> fields = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM5) {

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    fields.add(new String[] { name, desc });
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return fields;
    }

    /**
     * The type {@link #wanted} could accept for a field descriptor, loaded without initialising it; null for every
     * other type, so no other field type is loaded.
     */
    private static Class<?> typeOf(String desc, ClassLoader loader) {
        switch (desc) {
            case "I":
                return int.class;
            case "S":
                return short.class;
            case "B":
                return byte.class;
            case "J":
                return long.class;
            default:
                if (!desc.endsWith("/HeatingCoilLevel;")) {
                    return null;
                }
                try {
                    return Class.forName(
                        desc.substring(1, desc.length() - 1)
                            .replace('/', '.'),
                        false,
                        loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    return null;
                }
        }
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

    static boolean wanted(String name, Class<?> t) {
        if (t.isEnum() && "HeatingCoilLevel".equals(t.getSimpleName())) {
            return true;
        }
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
