package com.yann.agent.entrance.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestPageControllerTest {

	@Test
	void chatPageReturnsStaticTestPage() throws Exception {
		Resource page = new TestPageController().chatPage();

		assertThat(page.exists()).isTrue();
		assertThat(page.getFilename()).isEqualTo("test-chat.html");
	}

	@Test
	void mockTargetAgentChatReturnsRandomNumberEverySecondAndCompletesAfterFiveItems() {
		TestPageController controller = new TestPageController();

		StepVerifier.withVirtualTime(controller::mockTargetAgentChat)
				.expectSubscription()
				.thenAwait(Duration.ofSeconds(5))
				.expectNextMatches(event -> "message_start".equals(event.event()))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> "message_end".equals(event.event()))
				.verifyComplete();
	}

	@Test
	void mockTargetAgentRichChatDelegatesToMixedResponse() {
		StepVerifier.withVirtualTime(new TestPageController()::mockTargetAgentRichChat)
				.expectSubscription()
				.thenAwait(Duration.ofSeconds(5))
				.expectNextMatches(event -> "message_start".equals(event.event()))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> "message_end".equals(event.event()))
				.verifyComplete();
	}

	private static boolean isPayloadEvent(ServerSentEvent<Map<String, Object>> event) {
		return "delta".equals(event.event())
				|| "content_block".equals(event.event());
	}
}
