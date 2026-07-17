package com.yann.agent.entrance.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

class TestPageControllerTest {

	@Test
	void chatPageReturnsStaticTestPage() throws Exception {
		Resource page = new TestPageController().chatPage();

		assertThat(page.exists()).isTrue();
		assertThat(page.getFilename()).isEqualTo("test-chat.html");
	}
}
