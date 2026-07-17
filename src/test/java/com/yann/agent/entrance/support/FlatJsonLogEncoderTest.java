package com.yann.agent.entrance.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlatJsonLogEncoderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void flattensKeyValuePairsAndOmitsContext() throws Exception {
		LoggerContext context = new LoggerContext();
		LoggingEvent event = new LoggingEvent();
		event.setLoggerContext(context);
		event.setLoggerName("com.yann.agent.entrance.controller.AgentProxyController");
		event.setLevel(Level.INFO);
		event.setThreadName("reactor-http-nio-3");
		event.setMessage("agent.stream.request.received");
		event.setKeyValuePairs(List.of(
				new KeyValuePair("conversationId", "test-conversation"),
				new KeyValuePair("path", "/api/conversations/test-conversation/messages/stream")
		));

		String json = new String(new FlatJsonLogEncoder().encode(event), StandardCharsets.UTF_8);
		JsonNode payload = objectMapper.readTree(json);

		assertThat(payload.has("context")).isFalse();
		assertThat(payload.has("kvpList")).isFalse();
		assertThat(payload.get("conversationId").asText()).isEqualTo("test-conversation");
		assertThat(payload.get("path").asText()).isEqualTo("/api/conversations/test-conversation/messages/stream");
		assertThat(payload.get("message").asText()).isEqualTo("agent.stream.request.received");
		assertThat(payload.get("throwable").isNull()).isTrue();
	}
}
