package com.yann.agent.entrance.controller;

import com.yann.agent.entrance.dto.TargetAgentRequestBody;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
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
	void mockTargetAgentChatConsumesUnifiedAgentRequestAndReturnsMixedEvents() {
		TestPageController controller = new TestPageController();
		TargetAgentRequestBody request = new TargetAgentRequestBody(
				"dev-assistant",
				"tenant-a",
				"u-1",
				"c-1",
				"帮我生成富文本",
				List.of(),
				"c-1-1"
		);

		StepVerifier.withVirtualTime(() -> controller.mockTargetAgentChat(request))
				.expectSubscription()
				.thenAwait(Duration.ofSeconds(8))
				.expectNextMatches(event -> "message_start".equals(event.event())
						&& "dev-assistant".equals(event.data().get("agentId")))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> "message_end".equals(event.event()))
				.verifyComplete();
	}

	@Test
	void mockTargetAgentRichChatDelegatesToMixedResponse() {
		TargetAgentRequestBody request = new TargetAgentRequestBody(
				"dev-assistant",
				"tenant-a",
				"u-1",
				"c-1",
				"帮我生成富文本",
				List.of(),
				"c-1-1"
		);

		StepVerifier.withVirtualTime(() -> new TestPageController().mockTargetAgentRichChat(request))
				.expectSubscription()
				.thenAwait(Duration.ofSeconds(8))
				.expectNextMatches(event -> "message_start".equals(event.event()))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> isPayloadEvent(event))
				.expectNextMatches(event -> "message_end".equals(event.event()))
				.verifyComplete();
	}

	@Test
	void submitNameReturnsGreetingText() {
		assertThat(new TestPageController().submitName("Yann")).isEqualTo("hello Yann");
	}

	private static boolean isPayloadEvent(ServerSentEvent<Map<String, Object>> event) {
		if ("delta".equals(event.event())) {
			return event.data() != null && event.data().containsKey("text");
		}
		return "content_block".equals(event.event())
				&& "html".equals(event.data().get("type"))
				&& "sandbox_iframe".equals(event.data().get("format"))
				&& String.valueOf(event.data().get("payload")).contains("<!doctype html>");
	}
}
