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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Read the coil and structure tiers of a formed multiblock, such as the EBF's coil, a Space Elevator's motor tier or
 * a glass tier gate, with no binding to any of the hundreds of controller classes.
 *
 * <p>
 * GT has no common interface for the tiers, so the readable fields of a class are chosen by field shape, once per
 * class, and cached:
 *
 * <ul>
 * <li>any instance field of an enum type named {@code HeatingCoilLevel}, with the enum constant name as the
 * value</li>
 * <li>{@code mHeatingCapacity}, the heat of the EBF family in kelvin</li>
 * <li>instance {@code int}, {@code short} and {@code byte} fields with "tier" as a word of the name, such as
 * {@code glassTier} or {@code mTierCasing}. A name with a bookkeeping word of {@link #NOT_STRUCTURE}, such as
 * render, recipe, max or count, is skipped.</li>
 * <li>instance {@code int} fields named {@code ...Metadata}, the three field tiers of the Eye of Harmony</li>
 * <li>the few tiers that are not numbers or need a path, listed by class in {@link #EXTRA_PATHS}</li>
 * </ul>
 *
 * Only fields declared below GT's shared base classes are read, so the line has no per-hatch bookkeeping field of
 * those bases. The values are what the structure check left at RETURN.
 *
 * <p>
 * {@code getDeclaredFields()} loads the type of every field of a class. On a dedicated server, the type of a
 * client-only field cannot load, such as the {@code kubatech.client.effect.EntityRenderer} field of the Extreme
 * Entity Crusher. So for such a class the fields are found by name in its class file, and each field is read
 * through a method handle. The handle resolves only that field.
 */
public final class StructureTiers {

    private static final int MAX_FIELDS = 12;
    // static: the mixins call read() and unreadable() with no instance. A class's plan is fixed for the JVM's lifetime
    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();
    /** Controller simple class name to the dotted field paths that are read on top of the name rules. */
    private static final Map<String, String[]> EXTRA_PATHS = Map.of(
        // the Plasma Forge's convergence upgrade
        "MTEPlasmaForge",
        new String[] { "convergence" },
        // the Exo-Foundry's tier (1 UEV, 2 UIV, 3 UXV) and its module slots (heliocast, hypercooler, …)
        "MTEExoFoundry",
        new String[] { "foundryData.tier", "foundryData.modules" });

    private StructureTiers() {}

    /**
     * Return the tier values by key. The {@link #EXTRA_PATHS} entries of the class come first, then the fields in
     * declaration order, with the concrete class first. The map is empty when the class has no tier fields.
     */
    public static Map<String, String> read(Object controller) {
        final List<Getter> plan = PLANS.computeIfAbsent(controller.getClass(), StructureTiers::buildPlan)
            .getters();
        if (plan.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (Getter getter : plan) {
            try {
                out.put(getter.key(), format(getter.get(controller)));
            } catch (ReflectiveOperationException | RuntimeException ignored) { // noqa: NAR142, GT controller fields
                // a field that can't be read is left out, and the other fields are still read
            }
        }
        return out;
    }

    /**
     * Return the simple names of the classes, from the controller's class up to GT's shared bases, whose fields
     * {@link #read} skips on this side. The list is empty when there is no such class.
     *
     * <p>
     * The fields of such a class can't all be listed on this side, and {@link #addByName} failed too: on a JVM before
     * 9, with no class file, or with a class file that this ASM can't read. The class is then listed in
     * {@code structureUnreadable} on the formation line.
     */
    public static List<String> unreadable(Class<?> type) {
        return PLANS.computeIfAbsent(type, StructureTiers::buildPlan)
            .unreadable();
    }

    private static String format(Object value) {
        if (value instanceof Enum<?>constant) {
            return constant.name();
        }
        if (value instanceof Object[]array) {
            final StringBuilder formatted = new StringBuilder("[");
            for (Object element : array) {
                formatted.append(formatted.length() == 1 ? "" : ",")
                    .append(format(element));
            }
            return formatted.append("]")
                .toString();
        }
        return String.valueOf(value);
    }

    /** Return the keys of {@link #read} for a controller class. */
    public static List<String> planKeys(Class<?> type) {
        final List<String> keys = new ArrayList<>();
        for (Getter getter : PLANS.computeIfAbsent(type, StructureTiers::buildPlan)
            .getters()) {
            keys.add(getter.key());
        }
        return keys;
    }

    /**
     * Choose the getters for a controller class: the class's {@link #EXTRA_PATHS} entries, then every field that
     * {@link #isWanted} accepts, up to {@link #MAX_FIELDS}, from the concrete class up to GT's shared bases.
     */
    private static Plan buildPlan(Class<?> type) {
        final List<Getter> getters = new ArrayList<>();
        final List<String> unreadable = new ArrayList<>();
        final String[] extra = EXTRA_PATHS.get(type.getSimpleName());
        if (extra != null) {
            for (String dotted : extra) {
                final List<Field> path = resolve(type, dotted);
                if (path != null) {
                    getters.add(new FieldPath(path));
                }
            }
        }
        for (Class<?> declaring = type; declaring != null
            && !isSharedBase(declaring); declaring = declaring.getSuperclass()) {
            final Field[] declared;
            try {
                declared = declaring.getDeclaredFields();
            } catch (LinkageError e) {
                // a field type can't load on this side. The class's other fields are still found by name
                if (!addByName(declaring, getters)) {
                    unreadable.add(declaring.getSimpleName());
                }
                continue;
            }
            for (Field field : declared) {
                if (getters.size() >= MAX_FIELDS) {
                    return new Plan(getters, unreadable);
                }
                final boolean isInstanceField = !Modifier.isStatic(field.getModifiers());
                if (!(isInstanceField && isWanted(field.getName(), field.getType()))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    getters.add(new FieldPath(List.of(field)));
                } catch (RuntimeException ignored) { // noqa: NAR142, setAccessible() on another mod's field
                    // the field is inaccessible under this JVM's module rules. Skip the field
                }
            }
        }
        return new Plan(getters, unreadable);
    }

    /**
     * Add the wanted fields of a class whose fields can't all be listed. The names and types come from the class
     * file, and each value comes from a getter handle.
     *
     * @return false when the fields can't be added here: a class of the bootstrap loader, no class file, a class file
     *         that this ASM can't read, or a JVM before 9, without {@code MethodHandles.privateLookupIn}. The
     *         privateLookupIn call is reflective, so this class loads on Java 8.
     */
    private static boolean addByName(Class<?> declaring, List<Getter> getters) {
        final ClassLoader loader = declaring.getClassLoader();
        if (loader == null) {
            return false;
        }
        final List<ClassFileField> fields;
        try (InputStream in = loader.getResourceAsStream(
            declaring.getName()
                .replace('.', '/') + ".class")) {
            if (in == null) {
                return false;
            }
            fields = readInstanceFields(in);
        } catch (IOException | RuntimeException e) { // noqa: NAR142, ASM on another mod's class file
            return false;
        }
        final MethodHandles.Lookup lookup;
        try {
            lookup = (MethodHandles.Lookup) MethodHandles.class
                .getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class)
                .invoke(null, declaring, MethodHandles.lookup());
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, lookup into another mod
            return false;
        }
        for (ClassFileField field : fields) {
            if (getters.size() >= MAX_FIELDS) {
                break;
            }
            final Class<?> type = loadWantedType(field.descriptor(), loader);
            if (!(type != null && isWanted(field.name(), type))) {
                continue;
            }
            try {
                getters.add(new HandleGetter(field.name(), lookup.findGetter(declaring, field.name(), type)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { // noqa: NAR142, another
                                                                                               // mod
                // the field can't be read after all. The class's other fields are still read
            }
        }
        return true;
    }

    /** Return each instance field in declaration order. */
    private static List<ClassFileField> readInstanceFields(InputStream classFile) throws IOException {
        final List<ClassFileField> fields = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM5) {

            /** Add each instance field. ASM calls this method once per field, in declaration order. */
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    fields.add(new ClassFileField(name, desc));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return fields;
    }

    /**
     * Return the type that {@link #isWanted} could accept for a field descriptor, loaded without initialization.
     * Return null for every other type, so no other field type is loaded.
     */
    private static Class<?> loadWantedType(String descriptor, ClassLoader loader) {
        return switch (descriptor) {
            case "I" -> int.class;
            case "S" -> short.class;
            case "B" -> byte.class;
            case "J" -> long.class;
            default -> loadCoilLevelType(descriptor, loader);
        };
    }

    private static Class<?> loadCoilLevelType(String descriptor, ClassLoader loader) {
        if (!descriptor.endsWith("/HeatingCoilLevel;")) {
            return null;
        }
        try {
            return Class.forName(
                descriptor.substring(1, descriptor.length() - 1)
                    .replace('/', '.'),
                false,
                loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Return the field chain for a dotted path, or null when a field of the path is absent. Each field after the first
     * is looked up in the previous field's declared type and its superclasses.
     */
    private static List<Field> resolve(Class<?> type, String dotted) {
        final String[] names = dotted.split("\\.");
        final List<Field> path = new ArrayList<>(names.length);
        Class<?> owner = type;
        for (String name : names) {
            final Field field = findField(owner, name);
            if (field == null) {
                return null;
            }
            path.add(field);
            owner = field.getType();
        }
        return path;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> declaring = type; declaring != null; declaring = declaring.getSuperclass()) {
            try {
                final Field field = declaring.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | RuntimeException | LinkageError ignored) { // noqa: NAR142, another mod's
                                                                                       // class
                // look in the superclass. A LinkageError comes from a client-only field type on a dedicated server
            }
        }
        return null;
    }

    private static boolean isWanted(String name, Class<?> type) {
        if (type.isEnum() && "HeatingCoilLevel".equals(type.getSimpleName())) {
            return true;
        }
        if ("mHeatingCapacity".equals(name)) {
            return type == int.class || type == long.class;
        }
        if (type == int.class && name.endsWith("Metadata")) {
            return true;
        }
        return (type == int.class || type == short.class || type == byte.class) && isTierName(name);
    }

    /** Words that make a tier-named field bookkeeping, such as a limit, a lookup or a count, and not a tier. */
    private static final Set<String> NOT_STRUCTURE = Set.of(
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
        "amount");

    /**
     * Return true when "tier" is a whole camelCase word of the name, for example {@code glassTier} or
     * {@code mTierCasing}, and the name has no bookkeeping word.
     */
    public static boolean isTierName(String name) {
        boolean tier = false;
        // splits `mTierCasing` into `m`, `Tier`, `Casing`, and `UHVTier` into `UHV`, `Tier`
        for (String word : name.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            final String lower = word.toLowerCase(Locale.ROOT);
            if (NOT_STRUCTURE.contains(lower)) {
                return false;
            }
            tier |= "tier".equals(lower);
        }
        return tier;
    }

    /**
     * Return true for a multiblock base class of GT or a GT addon. The fields from that class up are shared plumbing.
     */
    public static boolean isSharedBase(Class<?> type) {
        final String className = type.getName();
        return className.startsWith("gregtech.api.") || className.startsWith("java.")
            || className.endsWith(".TTMultiblockBase")
            || className.endsWith(".GTPPMultiBlockBase")
            || className.endsWith(".KubaTechGTMultiBlockBase");
    }

    /** The getters of {@link #read} for one controller class, chosen once. */
    private record Plan(List<Getter> getters, List<String> unreadable) {

        private Plan {
            getters = List.copyOf(getters);
            unreadable = List.copyOf(unreadable);
        }
    }

    /** One value of the plan, under its key in the formation line. */
    private interface Getter {

        String key();

        Object get(Object controller) throws ReflectiveOperationException;
    }

    /** A field path: one field, or a field of a field's value. */
    private record FieldPath(List<Field> path) implements Getter {

        private FieldPath {
            path = List.copyOf(path);
        }

        @Override
        public String key() {
            final StringBuilder key = new StringBuilder();
            for (Field field : path) {
                key.append(key.length() == 0 ? "" : ".")
                    .append(field.getName());
            }
            return key.toString();
        }

        @Override
        public Object get(Object controller) throws IllegalAccessException {
            Object value = controller;
            for (Field field : path) {
                value = value == null ? null : field.get(value);
            }
            return value;
        }
    }

    /** A field found by name in the class file, read through a getter handle. */
    private record HandleGetter(String key, MethodHandle getter) implements Getter {

        @Override
        public Object get(Object controller) throws InvocationTargetException {
            try {
                return getter.invoke(controller);
            } catch (Throwable t) { // noqa: NAR142, invoke() declares Throwable. Another mod's field
                throw new InvocationTargetException(t);
            }
        }
    }

    /** The name and type descriptor of an instance field in a class file. */
    private record ClassFileField(String name, String descriptor) {}
}
