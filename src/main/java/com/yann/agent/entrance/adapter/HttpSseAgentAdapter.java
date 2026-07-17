package com.yann.agent.entrance.adapter;

import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.AgentStreamChunk;
import com.yann.agent.entrance.model.AgentProtocol;
import com.yann.agent.entrance.support.JsonLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class HttpSseAgentAdapter implements AgentAdapter {

	private static final Logger log = LoggerFactory.getLogger(HttpSseAgentAdapter.class);

	private final WebClient webClient;

	public HttpSseAgentAdapter(WebClient.Builder webClientBuilder) {
		this.webClient = webClientBuilder.build();
	}

	@Override
	public Flux<AgentStreamChunk> stream(AgentInvokeRequest request) {
		long startedAt = System.currentTimeMillis();
		Map<String, String> body = Map.of(
				"agentId", request.agentId(),
				"tenantId", request.tenantId(),
				"userId", request.userId(),
				"conversationId", request.conversationId(),
				"message", request.message()
		);
		JsonLog.info(log, "agent.adapter.sse.request.started",
				"agentId", request.agentId(),
				"correlation", request.correlation(),
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
				})
				.accept(MediaType.TEXT_EVENT_STREAM)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.exchangeToFlux(response -> {
					JsonLog.info(log, "agent.adapter.sse.response.received",
							"agentId", request.agentId(),
							"correlation", request.correlation(),
							"conversationId", request.conversationId(),
							"endpoint", request.endpoint(),
							"statusCode", response.statusCode().value()
					);
					if (response.statusCode().isError()) {
						return response.createException().flatMapMany(Mono::error);
					}
					return response.bodyToFlux(String.class);
				})
				.map(AgentStreamChunk::delta)
				.doOnError(ex -> JsonLog.error(log, "agent.adapter.sse.request.failed", ex,
						"agentId", request.agentId(),
						"correlation", request.correlation(),
						"conversationId", request.conversationId(),
						"endpoint", request.endpoint(),
						"durationMs", elapsedSince(startedAt)
				))
				.doOnComplete(() -> JsonLog.info(log, "agent.adapter.sse.request.completed",
						"agentId", request.agentId(),
						"correlation", request.correlation(),
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
}

