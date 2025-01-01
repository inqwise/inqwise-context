package com.inqwise.context.data;

import java.util.Base64;

import com.inqwise.context.spi.ContextChainDataService;
import com.inqwise.context.spi.ContextChainDataItem;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

public class FlowIdService implements ContextChainDataService {

	@Override
	public String key() {
		return "flow_id";
	}

	@Override
	public String marshalToString(ContextChainDataItem model) {
		return Base64.getEncoder().encodeToString(((FlowId)model).toJson().toBuffer().getBytes());
	}

	@Override
	public ContextChainDataItem unmarshalFormString(String str) {
		return new FlowId(new JsonObject(Buffer.buffer(Base64.getDecoder().decode(str))));
	}
}
