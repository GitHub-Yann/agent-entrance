package com.yann.agent.entrance.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Controller
public class TestPageController {

	@ResponseBody
	@GetMapping(value = "/test/chat", produces = MediaType.TEXT_HTML_VALUE)
	public Resource chatPage() {
		return new ClassPathResource("static/test-chat.html");
	}

	@ResponseBody
	@PostMapping(value = "/test/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> mockTargetAgentChat() {
		return Flux.interval(Duration.ofSeconds(1))
				.take(5)
				.map(tick -> String.valueOf(ThreadLocalRandom.current().nextInt(0, 1000)));
	}
}
