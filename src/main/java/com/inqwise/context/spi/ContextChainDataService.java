package com.inqwise.context.spi;

public interface ContextChainDataService {
	String key();
	String marshalToString(ContextChainDataItem model);
	ContextChainDataItem unmarshalFormString(String str);
}
