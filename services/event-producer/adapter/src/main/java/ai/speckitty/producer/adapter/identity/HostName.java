package ai.speckitty.producer.adapter.identity;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * {@code gethostname} through the Foreign Function and Memory API ({@code ws2_32}, after {@code WSAStartup}, on Windows). The
 * same call as Python's {@code socket.gethostname()}, with no process spawned and no name service asked.
 */
final class HostName {

    private static final int BUFFER_BYTES = 256;
    /** Winsock version 2.2. */
    private static final short WINSOCK_VERSION = 0x0202;
    /** sizeof(WSADATA) on 64-bit Windows. */
    private static final int WSADATA_BYTES = 408;

    private HostName() {}

    static String get() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = windows ? SymbolLookup.libraryLookup("ws2_32", arena) : linker.defaultLookup();
            if (windows) {
                MethodHandle startup = linker.downcallHandle(
                        lookup.find("WSAStartup").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
                MemorySegment data = arena.allocate(WSADATA_BYTES);
                int started = (int) startup.invokeExact(WINSOCK_VERSION, data);
                if (started != 0) {
                    throw new IllegalStateException("WSAStartup failed: " + started);
                }
            }
            MethodHandle gethostname = linker.downcallHandle(
                    lookup.find("gethostname").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, windows ? ValueLayout.JAVA_INT : ValueLayout.JAVA_LONG));
            MemorySegment buffer = arena.allocate(BUFFER_BYTES);
            int status = windows
                    ? (int) gethostname.invokeExact(buffer, BUFFER_BYTES)
                    : (int) gethostname.invokeExact(buffer, (long) BUFFER_BYTES);
            if (status != 0) {
                throw new IllegalStateException("gethostname failed: " + status);
            }
            return buffer.getString(0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("gethostname downcall failed", t);
        }
    }
}
