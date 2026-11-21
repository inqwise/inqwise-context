package com.inqwise.context;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.ServiceConfigurationError;

import com.inqwise.context.ContextPropagationException.Direction;
import com.inqwise.context.data.FlowIdService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPropagationExceptionTest {
    private static final String SECRET = "private-codec-diagnostics";

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "assertion", "linkage", "service", "null", "null-element"})
    void constructor_unusableOriginalStackTrace_retainsSafeReportingTrace(String kind) {
        RuntimeException original = new RuntimeException(SECRET, new IllegalArgumentException(SECRET)) {
            @Override
            public StackTraceElement[] getStackTrace() {
                return switch (kind) {
                    case "runtime" -> throw new IllegalStateException(SECRET);
                    case "assertion" -> throw new AssertionError(SECRET);
                    case "linkage" -> throw new NoClassDefFoundError(SECRET);
                    case "service" -> throw new ServiceConfigurationError(SECRET);
                    case "null" -> null;
                    default -> new StackTraceElement[] {null};
                };
            }
        };
        ContextPropagationException failure = assertDoesNotThrow(() ->
            new ContextPropagationException(Direction.OUTBOUND, new FlowIdService(), original));

        assertEquals(Direction.OUTBOUND, failure.getDirection());
        assertEquals(FlowIdService.class.getName(), failure.getProviderClassName());
        assertEquals(original.getClass().getName(), failure.getFailureType());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(Arrays.stream(failure.getStackTrace())
            .anyMatch(frame -> frame.getClassName().equals(ContextPropagationExceptionTest.class.getName())));
        StringWriter printed = new StringWriter();
        failure.printStackTrace(new PrintWriter(printed));
        assertFalse(printed.toString().contains(SECRET));
    }

    @Test
    void constructor_validOriginalStackTrace_preservesFramesWithoutCause() {
        RuntimeException original = new RuntimeException(SECRET);
        StackTraceElement[] frames = {new StackTraceElement("Codec", "encode", "Codec.java", 12)};
        original.setStackTrace(frames);
        ContextPropagationException failure = new ContextPropagationException(Direction.INBOUND, null, original);

        assertArrayEquals(frames, failure.getStackTrace());
        assertNull(failure.getProviderClassName());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET));
    }

    @Test
    void constructor_intentionallyEmptyStackTrace_preservesEmptyTrace() {
        RuntimeException original = new RuntimeException(SECRET);
        original.setStackTrace(new StackTraceElement[0]);

        assertEquals(0, new ContextPropagationException(Direction.INBOUND, null, original).getStackTrace().length);
    }
}
