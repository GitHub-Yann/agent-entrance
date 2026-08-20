package com.yann.agent.entrance.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import reactor.test.StepVerifier;

import java.time.Duration;

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
				.expectNextMatches(TestPageControllerTest::isRandomNumber)
				.expectNextMatches(TestPageControllerTest::isRandomNumber)
				.expectNextMatches(TestPageControllerTest::isRandomNumber)
				.expectNextMatches(TestPageControllerTest::isRandomNumber)
				.expectNextMatches(TestPageControllerTest::isRandomNumber)
				.verifyComplete();
	}

	private static boolean isRandomNumber(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		int number = Integer.parseInt(value);
		return number >= 0 && number < 1000;
	}
}
