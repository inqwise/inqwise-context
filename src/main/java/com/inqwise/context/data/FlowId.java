package com.inqwise.context.data;

import java.time.Instant;
import java.util.Optional;

import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import com.google.common.base.MoreObjects;

@DataObject
public class FlowId implements ContextChainDataItem {
	public static class Keys {
		public static final String ID = "id";
		public static final String TIMESTAMP = "timestamp";
	}
	
	public FlowId(JsonObject json) {
		id = json.getString(Keys.ID);
		timestamp = Optional.ofNullable(json.getLong(Keys.TIMESTAMP)).map(Instant::ofEpochMilli).orElse(null);
	}
	
	private String id;
	private Instant timestamp;

	private FlowId(Builder builder) {
		this.id = builder.id;
		this.timestamp = builder.timestamp;
	}
	
	public String getId() {
		return id;
	}
	
	public Instant getTimestamp() {
		return timestamp;
	}
	
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

	public static Builder builder() {
		return new Builder();
	}

	public static Builder builderFrom(FlowId flowId) {
		return new Builder(flowId);
	}

	public static final class Builder {
		private String id;
		private Instant timestamp;

		private Builder() {
		}

		private Builder(FlowId flowId) {
			this.id = flowId.id;
			this.timestamp = flowId.timestamp;
		}

		public Builder withId(String id) {
			this.id = id;
			return this;
		}

		public Builder withTimestamp(Instant timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		public FlowId build() {
			return new FlowId(this);
		}
	}
}
