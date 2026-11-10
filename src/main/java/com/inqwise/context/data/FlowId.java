package com.inqwise.context.data;

import java.time.Instant;
import java.util.Optional;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import com.google.common.base.MoreObjects;

/**
 * Identifies a flow with an optional identifier and timestamp. JSON transport
 * uses epoch milliseconds for the timestamp and omits null fields.
 */
@DataObject
public class FlowId implements ContextChainDataItem {
    /**
     * Field names used in the JSON representation.
     */
	public static class Keys {
        /** The flow identifier field. */
		public static final String ID = "id";
        /** The timestamp field, expressed in epoch milliseconds. */
		public static final String TIMESTAMP = "timestamp";

        /**
         * Creates a field-name container, retained for source compatibility.
         */
        public Keys() {
        }
	}
	
    /**
     * Reads a flow identifier from JSON. Missing fields become null.
     *
     * @param json the flow representation
     */
	public FlowId(JsonObject json) {
		id = json.getString(Keys.ID);
		timestamp = Optional.ofNullable(json.getLong(Keys.TIMESTAMP)).map(Instant::ofEpochMilli).orElse(null);
	}
	
	private final String id;
	private final Instant timestamp;

	private FlowId(Builder builder) {
		this.id = builder.id;
		this.timestamp = builder.timestamp;
	}
	
    /**
     * Returns the identifier.
     *
     * @return the identifier, or null if unspecified
     */
	public String getId() {
		return id;
	}
	
    /**
     * Returns the flow timestamp.
     *
     * @return the timestamp, or null if unspecified
     */
	public Instant getTimestamp() {
		return timestamp;
	}
	
    /**
     * Creates the transport representation, omitting null fields.
     *
     * @return a new JSON object with the timestamp in epoch milliseconds
     */
	public JsonObject toJson() {
		var json = new JsonObject();
		if(null != id) {
			json.put(Keys.ID, id);
		}
		
		if(null != timestamp) {
			json.put(Keys.TIMESTAMP, timestamp.toEpochMilli());
		}
		
		return json;
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this).add("id", id).add("timestamp", timestamp).toString();
	}

    /**
     * Creates a builder with no identifier or timestamp.
     *
     * @return a new builder
     */
	public static Builder builder() {
		return new Builder();
	}

    /**
     * Creates a builder initialized from an existing value.
     *
     * @param flowId the value to copy
     * @return a new builder containing the supplied fields
     */
	public static Builder builderFrom(FlowId flowId) {
		return new Builder(flowId);
	}

    /**
     * Builds flow identifiers without changing previously built values.
     */
	public static final class Builder {
		private String id;
		private Instant timestamp;

		private Builder() {
		}

		private Builder(FlowId flowId) {
			this.id = flowId.id;
			this.timestamp = flowId.timestamp;
		}

        /**
         * Sets the identifier.
         *
         * @param id the identifier, or null to omit it
         * @return this builder
         */
		public Builder withId(String id) {
			this.id = id;
			return this;
		}

        /**
         * Sets the timestamp. JSON serialization retains millisecond precision.
         *
         * @param timestamp the timestamp, or null to omit it
         * @return this builder
         */
		public Builder withTimestamp(Instant timestamp) {
			this.timestamp = timestamp;
			return this;
		}

        /**
         * Creates a value from the current builder fields.
         *
         * @return a new flow identifier
         */
		public FlowId build() {
			return new FlowId(this);
		}
	}
}
