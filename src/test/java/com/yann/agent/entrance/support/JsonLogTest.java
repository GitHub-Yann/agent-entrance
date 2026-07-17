package com.yann.agent.entrance.support;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;

class JsonLogTest {

	@Test
	void acceptsStructuredFieldsWithoutManualJsonRendering() {
		assertThatNoException().isThrownBy(() -> JsonLog.info(
				LoggerFactory.getLogger(JsonLogTest.class),
				"agent.stream.completed",
				"status", TestStatus.SUCCESS,
				"accessTime", Instant.parse("2026-07-16T00:00:00Z"),
				"durationMs", 42
		));
	}

	@Test
	void acceptsCorrelationContext() {
		assertThatNoException().isThrownBy(() -> CorrelationContext.run("correlation-test", () -> JsonLog.info(
				LoggerFactory.getLogger(JsonLogTest.class),
				"agent.stream.request.received",
				"conversationId", "c-1"
		)));
	}

	private enum TestStatus {
		SUCCESS
	}
}
