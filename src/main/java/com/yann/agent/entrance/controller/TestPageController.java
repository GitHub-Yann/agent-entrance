package com.yann.agent.entrance.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class TestPageController {

	@ResponseBody
	@GetMapping(value = "/test/chat", produces = MediaType.TEXT_HTML_VALUE)
	public Resource chatPage() {
		return new ClassPathResource("static/test-chat.html");
	}
}
