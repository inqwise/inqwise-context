package com.inqwise.context;

import com.inqwise.context.spi.ContextChainDataService;

/**
 * Reports an optional EventBus metadata propagation failure to the current
 * context's exception handler, falling back to the owning Vert.x handler or
 * default logging. Reporting does not fail message delivery.
 *
 * <p>The exception retains the failure type and stack trace, but omits its
 * message and cause chain because codecs may include sensitive payloads there.
 */
public final class ContextPropagationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The transport direction in which propagation failed. */
    public enum Direction {
        /** Decoding metadata received with a message. */
        INBOUND,
        /** Encoding metadata sent with a message. */
        OUTBOUND
    }

    /** The direction of the failed transport operation. */
    private final Direction direction;
    /** The provider class, or null for a transport-level failure. */
    private final String providerClassName;
    /** The original failure class, without its message or cause. */
    private final String failureType;

    ContextPropagationException(Direction direction, ContextChainDataService provider, Throwable failure) {
        super("Context propagation failed: direction=" + direction
            + ", provider=" + (provider == null ? "transport" : provider.getClass().getName())
            + ", exception=" + failure.getClass().getName());
        this.direction = direction;
        this.providerClassName = provider == null ? null : provider.getClass().getName();
        this.failureType = failure.getClass().getName();
        setStackTrace(failure.getStackTrace());
    }

    /**
     * Returns the failed propagation direction.
     *
     * @return the direction
     */
    public Direction getDirection() {
        return direction;
    }

    /**
     * Returns the provider responsible for the failed operation.
     *
     * @return its class name, or null for a transport-level failure such as
     *         provider discovery
     */
    public String getProviderClassName() {
        return providerClassName;
    }

    /**
     * Returns the original failure's class name without exposing its message.
     *
     * @return the failure type
     */
    public String getFailureType() {
        return failureType;
    }
}
