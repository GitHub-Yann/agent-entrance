package com.yann.agent.entrance.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
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
	public Flux<ServerSentEvent<Map<String, Object>>> mockTargetAgentChat() {
		String messageId = "mock-mixed-" + System.currentTimeMillis();
		return Flux.interval(Duration.ofSeconds(1))
				.take(8)
				.index()
				.map(step -> mixedEvent(messageId, step.getT1().intValue()))
				.concatWithValues(toSse("message_end", Map.of(
						"messageId", messageId,
						"status", "SUCCESS"
				)));
	}

	@ResponseBody
	@PostMapping(value = "/test/v2/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> mockTargetAgentRichChat() {
		return mockTargetAgentChat();
	}

	private ServerSentEvent<Map<String, Object>> mixedEvent(String messageId, int step) {
		return switch (step) {
			case 0 -> toSse("message_start", Map.of("messageId", messageId));
			case 1 -> toSse("delta", Map.of("text", "下面先看一个图表，再看一个富文本卡片。"));
			case 2 -> toSse("content_block", chartBlock());
			case 3 -> toSse("content_block", htmlBlock());
			case 4 -> toSse("delta", Map.of("text", "再看一个富文本卡片。"));
			case 5 -> toSse("content_block", chartBlock2());
			case 6 -> toSse("content_block", htmlBlock2());
			default -> toSse("delta", Map.of("text", "最后再补一句总结：整体趋势向上。"));
		};
	}

	private Map<String, Object> chartBlock() {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-chart-1");
		block.put("type", "chart");
		block.put("title", "本周订单量");
		block.put("format", "bar");
		block.put("labels", List.of("周一", "周二", "周三", "周四", "周五"));
		block.put("values", List.of(12, 18, 9, 23, 17));
		return block;
	}

	private Map<String, Object> chartBlock2() {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-chart-2");
		block.put("type", "chart");
		block.put("title", "本周订单量");
		block.put("format", "bar");
		block.put("labels", List.of("周一", "周二", "周三", "周四", "周五"));
		block.put("values", List.of(12, 18, 9, 23, 17));
		return block;
	}

	private Map<String, Object> htmlBlock() {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-html-1");
		block.put("type", "html");
		block.put("title", "结论卡片");
		block.put("format", "sandbox_iframe");
		block.put("payload", """
				<!doctype html>
				<html lang="zh-CN">
				<head>
				  <meta charset="utf-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1">
				  <style>
				    :root { color: #1f2937; font-family: Arial, "Microsoft YaHei", sans-serif; }
				    body { margin: 0; padding: 16px; background: #ffffff; }
				    .report-card {
				      padding: 16px 18px;
				      border: 1px solid #99f6e4;
				      border-radius: 10px;
				      background: #f0fdfa;
				    }
				    h3 { margin: 0 0 10px; color: #115e59; font-size: 18px; }
				    p { margin: 0 0 10px; line-height: 1.7; }
				    ul { margin: 0; padding-left: 20px; line-height: 1.7; }
				    li { margin: 4px 0; }
				  </style>
				</head>
				<body>
				  <div class="report-card">
				    <h3>结论</h3>
				    <p>本周峰值出现在周四，整体趋势向上。</p>
				    <ul>
				      <li>增长最明显：周三到周四</li>
				      <li>建议：关注高峰时段的处理能力</li>
				    </ul>
				  </div>
				</body>
				</html>
				""");
		return block;
	}

	private Map<String, Object> htmlBlock2() {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-html-1");
		block.put("type", "html");
		block.put("title", "结论卡片");
		block.put("format", "sandbox_iframe");
		block.put("payload", """
				<!doctype html>
				<html lang="zh-CN">
				<head>
				  <meta charset="utf-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1">
				  <style>
				    :root { color: #dd42f2ff; font-family: Arial, "Microsoft YaHei", sans-serif; }
				    body { margin: 0; padding: 16px; background: #ffffff; }
				    .report-card {
				      padding: 16px 18px;
				      border: 1px solid #f3c457ff;
				      border-radius: 10px;
				      background: #eaf8daff;
				    }
				    h3 { margin: 0 0 10px; color: #f53293ff; font-size: 18px; }
				    p { margin: 0 0 10px; line-height: 1.7; }
				    ul { margin: 0; padding-left: 20px; line-height: 1.7; }
				    li { margin: 4px 0; }
				  </style>
				</head>
				<body>
				  <div class="report-card">
				    <h3>结论</h3>
				    <p>本周峰值出现在周四，整体趋势向上。</p>
				    <ul>
				      <li>增长最明显：周三到周四</li>
				      <li>建议：关注高峰时段的处理能力</li>
				    </ul>
				  </div>
				</body>
				</html>
				""");
		return block;
	}

	private ServerSentEvent<Map<String, Object>> toSse(String event, Map<String, Object> data) {
		return ServerSentEvent.<Map<String, Object>>builder()
				.event(event)
				.data(Objects.requireNonNull(data))
				.build();
	}
}
