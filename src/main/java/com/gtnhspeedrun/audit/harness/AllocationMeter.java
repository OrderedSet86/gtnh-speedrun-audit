package com.gtnhspeedrun.audit.harness;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Read the bytes that the current thread has allocated, for the benches. The class references no optional mod, so a
 * bench for any mod can use the class.
 */
public final class AllocationMeter {

    // com.sun.management.ThreadMXBean is a JDK extension, absent from some JVMs. The method is looked up by
    // reflection, so on such a JVM readThreadAllocatedBytes() returns -1 and the bench still runs.
    private static Method allocatedBytesMethod; // static: the lookup result stays valid for the life of the JVM
    private static boolean allocationUnavailable; // static: the failed lookup is skipped for the life of the JVM

    private AllocationMeter() {}

    /** Bytes the current thread has allocated, or -1 when the lookup fails, as on a JVM without the extension. */
    public static long readThreadAllocatedBytes() {
        if (allocationUnavailable) {
            return -1;
        }
        try {
            if (allocatedBytesMethod == null) {
                allocatedBytesMethod = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getThreadAllocatedBytes", long.class);
            }
            return (Long) allocatedBytesMethod.invoke(
                ManagementFactory.getThreadMXBean(),
                Thread.currentThread()
                    .getId());
        } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
            allocationUnavailable = true;
            return -1;
        }
    }
}
