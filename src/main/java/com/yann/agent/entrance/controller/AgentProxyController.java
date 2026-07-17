package com.yann.agent.entrance.controller;

import com.yann.agent.entrance.dto.StreamEvent;
import com.yann.agent.entrance.dto.StreamMessageRequest;
import com.yann.agent.entrance.service.AgentProxyService;
import com.yann.agent.entrance.support.CorrelationContext;
import com.yann.agent.entrance.support.JsonLog;
import com.yann.agent.entrance.support.UserContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class AgentProxyController {

	private static final Logger log = LoggerFactory.getLogger(AgentProxyController.class);

	private final AgentProxyService agentProxyService;
	private final UserContextResolver userContextResolver;

	public AgentProxyController(AgentProxyService agentProxyService, UserContextResolver userContextResolver) {
		this.agentProxyService = agentProxyService;
		this.userContextResolver = userContextResolver;
	}

	@PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<StreamEvent>> stream(
			@PathVariable String conversationId,
			@RequestBody Mono<StreamMessageRequest> request,
			ServerWebExchange exchange
	) {
		String correlation = UUID.randomUUID().toString();
		return CorrelationContext.call(correlation, () -> {
			JsonLog.info(log, "agent.stream.request.received",
					"conversationId", conversationId,
					"path", exchange.getRequest().getPath().value()
			);
			return request.flatMapMany(body -> CorrelationContext.call(correlation, () -> {
				JsonLog.info(log, "agent.stream.request.body.received",
						"conversationId", conversationId,
						"agentId", body == null ? null : body.agentId()
				);
				return Mono.fromCallable(() -> CorrelationContext.call(
								correlation,
								() -> userContextResolver.resolve(exchange, correlation)
						))
						.subscribeOn(Schedulers.boundedElastic())
						.flatMapMany(user -> CorrelationContext.call(
								correlation,
								() -> agentProxyService.stream(user, conversationId, body)
						));
			}));
		});
	}
}
