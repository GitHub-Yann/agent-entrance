package com.yann.agent.entrance.adapter;

import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.AgentStreamChunk;
import com.yann.agent.entrance.dto.TargetAgentRequestBody;
import com.yann.agent.entrance.model.AgentProtocol;
import com.yann.agent.entrance.support.JsonLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

@Component
public class HttpSseAgentAdapter implements AgentAdapter {

	private static final Logger log = LoggerFactory.getLogger(HttpSseAgentAdapter.class);

	private final WebClient webClient;
	private final ObjectMapper objectMapper;

	public HttpSseAgentAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
		this.webClient = webClientBuilder.build();
		this.objectMapper = objectMapper;
	}

	@Override
	public Flux<AgentStreamChunk> stream(AgentInvokeRequest request) {
		long startedAt = System.currentTimeMillis();
		TargetAgentRequestBody body = new TargetAgentRequestBody(
				request.agentId(),
				request.tenantId(),
				request.userId(),
				request.conversationId(),
				request.message(),
				request.attachments(),
				request.clientMessageId()
		);
		JsonLog.info(log, "agent.adapter.sse.request.started",
				"agentId", request.agentId(),
				"correlation", request.correlationId(),
				"tenantId", request.tenantId(),
				"userId", request.userId(),
				"conversationId", request.conversationId(),
				"endpoint", request.endpoint(),
				"messageLength", request.message().length()
		);
		return webClient.post()
				.uri(request.endpoint())
				.headers(headers -> {
					if (request.accessToken() != null) {
						headers.setBearerAuth(request.accessToken());
					}
					if (request.correlationId() != null) {
						headers.set("x-correlation-id", request.correlationId());
					}
				})
				.accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.exchangeToFlux(response -> {
					JsonLog.info(log, "agent.adapter.sse.response.received",
							"agentId", request.agentId(),
							"correlation", request.correlationId(),
							"conversationId", request.conversationId(),
							"endpoint", request.endpoint(),
							"statusCode", response.statusCode().value()
					);
					if (response.statusCode().isError()) {
						return response.createException().flatMapMany(Mono::error);
					}
					return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
				})
				.flatMap(this::parseChunk)
				.doOnError(ex -> JsonLog.error(log, "agent.adapter.sse.request.failed", ex,
						"agentId", request.agentId(),
						"correlation", request.correlationId(),
						"conversationId", request.conversationId(),
						"endpoint", request.endpoint(),
						"durationMs", elapsedSince(startedAt)
				))
				.doOnComplete(() -> JsonLog.info(log, "agent.adapter.sse.request.completed",
						"agentId", request.agentId(),
						"correlation", request.correlationId(),
						"conversationId", request.conversationId(),
						"endpoint", request.endpoint(),
						"durationMs", elapsedSince(startedAt)
				));
	}

	@Override
	public boolean supports(AgentProtocol protocol) {
		return protocol == AgentProtocol.SSE;
	}

	private long elapsedSince(long startedAt) {
		return System.currentTimeMillis() - startedAt;
	}

	private Flux<AgentStreamChunk> parseChunk(ServerSentEvent<String> event) {
		String eventType = event.event();
		String dataText = event.data();
		if (eventType == null) {
			return Flux.empty();
		}
		return switch (eventType) {
			case "content_block" -> Flux.just(parseContentBlock(dataText));
			case "message_start" -> Flux.just(new AgentStreamChunk("message_start", null, null, null, null, null, false, dataText));
			case "message_end" -> Flux.just(new AgentStreamChunk("message_end", null, null, null, null, null, true, dataText));
			case "delta" -> Flux.just(AgentStreamChunk.delta(parseText(dataText)));
			default -> Flux.just(AgentStreamChunk.delta(dataText));
		};
	}

	private AgentStreamChunk parseContentBlock(String dataText) {
		try {
			JsonNode node = objectMapper.readTree(dataText);
			String type = text(node, "type");
			return AgentStreamChunk.contentBlock(
					type,
					text(node, "title"),
					text(node, "format"),
					"html".equals(type) ? text(node, "payload") : dataText
			);
		} catch (Exception ex) {
			return AgentStreamChunk.contentBlock("unknown", null, null, dataText);
		}
	}

	private String parseText(String dataText) {
		try {
			JsonNode node = objectMapper.readTree(dataText);
			JsonNode text = node.get("text");
			if (text != null && !text.isNull()) {
				return text.asText();
			}
			return dataText;
		} catch (Exception ex) {
			return dataText;
		}
	}

	private String text(JsonNode node, String fieldName) {
		JsonNode child = node.get(fieldName);
		return child == null || child.isNull() ? null : child.asText();
	}
}

