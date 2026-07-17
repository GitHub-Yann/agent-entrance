package com.yann.agent.entrance.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FlatJsonLogEncoder extends EncoderBase<ILoggingEvent> {

	private static final byte[] EMPTY_BYTES = new byte[0];
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Override
	public byte[] headerBytes() {
		return EMPTY_BYTES;
	}

	@Override
	public byte[] encode(ILoggingEvent event) {
		Map<String, Object> payload = new LinkedHashMap<>();
		// payload.put("sequenceNumber", event.getSequenceNumber());
		payload.put("timestamp", event.getTimeStamp());
		// payload.put("nanoseconds", event.getNanoseconds());
		payload.put("level", event.getLevel().toString());
		payload.put("threadName", event.getThreadName());
		payload.put("loggerName", event.getLoggerName());
		// payload.put("mdc", mdc(event));
		appendKeyValuePairs(payload, event.getKeyValuePairs());
		payload.put("message", event.getFormattedMessage());
		payload.put("throwable", throwable(event.getThrowableProxy()));
		try {
			return (OBJECT_MAPPER.writeValueAsString(payload) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException ex) {
			return fallback(event, ex);
		}
	}

	@Override
	public byte[] footerBytes() {
		return EMPTY_BYTES;
	}

	private void appendKeyValuePairs(Map<String, Object> payload, List<KeyValuePair> keyValuePairs) {
		if (keyValuePairs == null) {
			return;
		}
		for (KeyValuePair pair : keyValuePairs) {
			if (pair != null && pair.key != null && !payload.containsKey(pair.key)) {
				payload.put(pair.key, pair.value);
			}
		}
	}

	private Map<String, String> mdc(ILoggingEvent event) {
		try {
			Map<String, String> mdc = event.getMDCPropertyMap();
			return mdc == null ? Map.of() : mdc;
		} catch (NullPointerException ex) {
			return Map.of();
		}
	}

	private Object throwable(IThrowableProxy throwable) {
		if (throwable == null) {
			return null;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("className", throwable.getClassName());
		payload.put("message", throwable.getMessage());
		return payload;
	}

	private byte[] fallback(ILoggingEvent event, JsonProcessingException ex) {
		String json = "{\"level\":\"ERROR\",\"loggerName\":\""
				+ event.getLoggerName()
				+ "\",\"message\":\"log.serialization.failed\",\"errorType\":\""
				+ ex.getClass().getSimpleName()
				+ "\"}"
				+ System.lineSeparator();
		return json.getBytes(StandardCharsets.UTF_8);
	}
}
