package com.yann.agent.entrance.controller;

import com.yann.agent.entrance.dto.TargetAgentRequestBody;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map;

@Controller
public class TestPageController {

	@ResponseBody
	@GetMapping(value = "/test/chat", produces = MediaType.TEXT_HTML_VALUE)
	public Resource chatPage() {
		return new ClassPathResource("static/test-chat.html");
	}

	@ResponseBody
	@PostMapping(value = "/test/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> mockTargetAgentChat(@RequestBody TargetAgentRequestBody request) {
		String messageId = "mock-mixed-" + System.currentTimeMillis();
		String prompt = request == null ? "" : request.message();
		return Flux.interval(Duration.ofSeconds(1))
				.take(8)
				.index()
				.map(step -> mixedEvent(messageId, step.getT1().intValue(), request, prompt))
				.concatWithValues(toSse("message_end", messageEndData(messageId, request)));
	}

	@ResponseBody
	@PostMapping(value = "/test/v2/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> mockTargetAgentRichChat(@RequestBody TargetAgentRequestBody request) {
		return mockTargetAgentChat(request);
	}

	@ResponseBody
	@GetMapping(value = "/test/v3/submit", produces = MediaType.TEXT_PLAIN_VALUE)
	public String submitName(@RequestParam String name) {
		return "hello " + name;
	}

	private ServerSentEvent<Map<String, Object>> mixedEvent(String messageId, int step, TargetAgentRequestBody request, String prompt) {
		String agentId = request == null ? null : request.agentId();
		String conversationId = request == null ? null : request.conversationId();
		String summarySuffix = conversationId == null ? "" : "（会话：" + conversationId + "）";
		return switch (step) {
			case 0 -> toSse("message_start", messageStartData(messageId, agentId, conversationId));
			case 1 -> toSse("delta", Map.of("text", "下面先看一个 HTML 图表，再看一个富文本卡片。当前消息：" + prompt));
			case 2 -> toSse("content_block", htmlChartBlock(request, "block-html-chart-1", "本周订单量"));
			case 3 -> toSse("content_block", htmlBlock(request));
			case 4 -> toSse("delta", Map.of("text", "再看一个富文本卡片。"));
			case 5 -> toSse("content_block", htmlChartBlock(request, "block-html-chart-2", "渠道订单分布"));
			case 6 -> toSse("content_block", htmlBlock2(request));
			default -> toSse("delta", Map.of("text", "最后再补一句总结：整体趋势向上。" + summarySuffix));
		};
	}

	private Map<String, Object> htmlChartBlock(TargetAgentRequestBody request, String blockId, String title) {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", blockId);
		block.put("type", "html");
		block.put("title", titleWithAgent(title, request));
		block.put("format", "sandbox_iframe");
		block.put("payload", chartHtml(title));
		return block;
	}

	private String chartHtml(String title) {
		return """
				<!doctype html>
				<html lang="zh-CN">
				<head>
				  <meta charset="utf-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1">
				  <style>
				    :root { color: #1f2937; font-family: Arial, "Microsoft YaHei", sans-serif; }
				    body { margin: 0; padding: 16px; background: #ffffff; }
				    .chart-card { padding: 16px 18px; border: 1px solid #99f6e4; border-radius: 10px; background: #f0fdfa; }
				    h3 { margin: 0 0 14px; color: #115e59; font-size: 18px; }
				    .chart { display: flex; align-items: end; gap: 12px; min-height: 180px; padding: 8px 8px 0; border-bottom: 1px solid #cbd5e1; }
				    .item { display: flex; flex: 1; min-width: 32px; flex-direction: column; align-items: center; gap: 6px; }
				    .value, .label { font-size: 12px; color: #475569; }
				    .bar { width: min(42px, 100%%); min-height: 4px; border-radius: 4px 4px 0 0; background: #0f766e; }
				  </style>
				</head>
				<body>
				  <div class="chart-card">
				    <h3>%s</h3>
				    <div class="chart">
				      <div class="item"><span class="value">12</span><div class="bar" style="height: 74px"></div><span class="label">周一</span></div>
				      <div class="item"><span class="value">18</span><div class="bar" style="height: 110px"></div><span class="label">周二</span></div>
				      <div class="item"><span class="value">9</span><div class="bar" style="height: 55px"></div><span class="label">周三</span></div>
				      <div class="item"><span class="value">23</span><div class="bar" style="height: 140px"></div><span class="label">周四</span></div>
				      <div class="item"><span class="value">17</span><div class="bar" style="height: 104px"></div><span class="label">周五</span></div>
				    </div>
				  </div>
				</body>
				</html>
				""".formatted(title);
	}

	private Map<String, Object> htmlBlock(TargetAgentRequestBody request) {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-html-2");
		block.put("type", "html");
		block.put("title", titleWithAgent("结论卡片", request));
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

	private Map<String, Object> htmlBlock2(TargetAgentRequestBody request) {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("blockId", "block-html-1");
		block.put("type", "html");
		block.put("title", titleWithAgent("结论卡片", request));
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
				    .form-card {
				      max-width: 420px;
				      padding: 18px;
				      border: 1px solid #bfdbfe;
				      border-radius: 10px;
				      background: #eff6ff;
				    }
				    h3 { margin: 0 0 12px; color: #1d4ed8; font-size: 18px; }
				    label { display: block; margin-bottom: 8px; font-weight: 700; }
				    .row { display: flex; gap: 10px; }
				    input {
				      flex: 1;
				      min-width: 0;
				      border: 1px solid #93c5fd;
				      border-radius: 6px;
				      padding: 9px 10px;
				      font: inherit;
				    }
				    button {
				      border: 0;
				      border-radius: 6px;
				      padding: 9px 14px;
				      background: #2563eb;
				      color: #ffffff;
				      font: inherit;
				      font-weight: 700;
				      cursor: pointer;
				    }
				    .hint { margin: 10px 0 0; color: #475569; font-size: 13px; }
				  </style>
				</head>
				<body>
				  <div class="form-card">
				    <h3>问候表单</h3>
				    <form id="helloForm" action="javascript:void(0)" method="get" autocomplete="off">
				      <label for="name">Name</label>
				      <div class="row">
				        <input id="name" name="name" autocomplete="off" placeholder="请输入 name">
				        <button id="submitButton" type="button" onclick="submitName()">提交</button>
				      </div>
				    </form>
				    <p class="hint">提交后会请求 /test/v3/submit，并在控制台打印返回值。</p>
				    <p id="submitResult" class="hint" aria-live="polite"></p>
				  </div>
				  <script>
				    async function submitName() {
				      const button = document.getElementById("submitButton");
				      const result = document.getElementById("submitResult");
				      const name = document.getElementById("name").value.trim();
				      button.disabled = true;
				      result.textContent = "提交中...";
				      try {
				        const response = await fetch("/test/v3/submit?name=" + encodeURIComponent(name));
				        const text = await response.text();
				        console.log(text);
				        result.textContent = text;
				      } catch (error) {
				        console.error(error);
				        result.textContent = "提交失败：" + error.message;
				      } finally {
				        button.disabled = false;
				      }
				    }
				    document.getElementById("helloForm").addEventListener("submit", event => {
				      event.preventDefault();
				      submitName();
				    });
				  </script>
				</body>
				</html>
				""");
		return block;
	}

	private String titleWithAgent(String baseTitle, TargetAgentRequestBody request) {
		if (request == null || request.agentId() == null || request.agentId().isBlank()) {
			return baseTitle;
		}
		return baseTitle + " - " + request.agentId();
	}

	private Map<String, Object> messageStartData(String messageId, String agentId, String conversationId) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("messageId", messageId);
		putIfPresent(data, "agentId", agentId);
		putIfPresent(data, "conversationId", conversationId);
		return data;
	}

	private Map<String, Object> messageEndData(String messageId, TargetAgentRequestBody request) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("messageId", messageId);
		data.put("status", "SUCCESS");
		if (request != null) {
			putIfPresent(data, "conversationId", request.conversationId());
		}
		return data;
	}

	private void putIfPresent(Map<String, Object> target, String key, String value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private ServerSentEvent<Map<String, Object>> toSse(String event, Map<String, Object> data) {
		return ServerSentEvent.<Map<String, Object>>builder()
				.event(event)
				.data(Objects.requireNonNull(data))
				.build();
	}

}
