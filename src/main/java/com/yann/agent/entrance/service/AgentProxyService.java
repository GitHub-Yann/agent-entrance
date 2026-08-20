package com.yann.agent.entrance.service;

import com.yann.agent.entrance.adapter.AgentAdapter;
import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.StreamEvent;
import com.yann.agent.entrance.dto.StreamMessageRequest;
import com.yann.agent.entrance.model.Agent;
import com.yann.agent.entrance.model.MessageStatus;
import com.yann.agent.entrance.model.UserContext;
import com.yann.agent.entrance.support.JsonLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class AgentProxyService {

	private static final Logger log = LoggerFactory.getLogger(AgentProxyService.class);

	private final AgentService agentService;
	private final TenantAuthorizationService tenantAuthorizationService;
	private final ConversationService conversationService;
	private final AuditService auditService;
	private final List<AgentAdapter> adapters;

	public AgentProxyService(
			AgentService agentService,
			TenantAuthorizationService tenantAuthorizationService,
			ConversationService conversationService,
			AuditService auditService,
			List<AgentAdapter> adapters
	) {
		this.agentService = agentService;
		this.tenantAuthorizationService = tenantAuthorizationService;
		this.conversationService = conversationService;
		this.auditService = auditService;
		this.adapters = adapters;
	}

	public Flux<ServerSentEvent<StreamEvent>> stream(UserContext user, String conversationId, StreamMessageRequest request) {
		long startedAt = System.currentTimeMillis();
		validate(conversationId, request);
		JsonLog.info(log, "agent.stream.processing.started",
				"conversationId", conversationId,
				"correlation", user.correlation(),
				"agentId", request.agentId(),
				"userId", user.userId(),
				"tenantId", user.tenantId()
		);
		Agent agent = agentService.requireEnabled(request.agentId());
		tenantAuthorizationService.requireAuthorized(user.tenantId(), agent.id());
		conversationService.saveUserMessage(conversationId, request.message().trim());
		JsonLog.info(log, "agent.stream.user.message.saved",
				"conversationId", conversationId,
				"correlation", user.correlation(),
				"agentId", agent.id(),
				"userId", user.userId(),
				"messageLength", request.message().trim().length()
		);
		auditService.recordAccess(user, agent);

		String assistantMessageId = conversationService.nextId();
		StringBuilder assistantContent = new StringBuilder();
		AtomicBoolean terminalSaved = new AtomicBoolean(false);
		AgentAdapter adapter = selectAdapter(agent);
		AgentInvokeRequest invokeRequest = new AgentInvokeRequest(
				agent.id(),
				user.tenantId(),
				user.userId(),
				conversationId,
				user.accessToken(),
				request.message().trim(),
				agent.endpoint(),
				user.correlation()
		);

		Flux<ServerSentEvent<StreamEvent>> start = Flux.just(toSse("message_start", StreamEvent.messageStart(assistantMessageId)));
		Flux<ServerSentEvent<StreamEvent>> stream = adapter.stream(invokeRequest)
				.concatMap(chunk -> switch (chunk.event()) {
					case "message_start" -> Flux.empty();
					case "delta" -> {
						if (StringUtils.hasText(chunk.text())) {
							assistantContent.append(chunk.text());
							yield Flux.just(toSse("delta", StreamEvent.delta(chunk.text())));
						}
						yield Flux.empty();
					}
					case "content_block" -> {
						if (StringUtils.hasText(chunk.blockType())) {
							assistantContent.append("[")
									.append(chunk.blockType())
									.append("]")
									.append(chunk.blockTitle() == null ? "" : chunk.blockTitle());
							yield Flux.just(toSse("content_block", StreamEvent.contentBlock(
									chunk.blockType(),
									chunk.blockTitle(),
									chunk.blockFormat(),
									chunk.blockPayload()
							)));
						}
						yield Flux.empty();
					}
					case "message_end" -> Flux.empty();
					default -> StringUtils.hasText(chunk.rawPayload())
							? Flux.just(toSse("delta", StreamEvent.delta(chunk.rawPayload())))
							: Flux.empty();
				})
				.concatWith(Flux.defer(() -> {
					terminalSaved.set(true);
					conversationService.saveAssistantMessage(
							assistantMessageId,
							conversationId,
							assistantContent.toString(),
							MessageStatus.SUCCESS
					);
					JsonLog.info(log, "agent.stream.completed",
							"conversationId", conversationId,
							"correlation", user.correlation(),
							"agentId", agent.id(),
							"assistantMessageId", assistantMessageId,
							"status", MessageStatus.SUCCESS,
							"assistantContentLength", assistantContent.length(),
							"durationMs", elapsedSince(startedAt)
					);
					return Flux.just(toSse("message_end", StreamEvent.messageEnd(assistantMessageId, MessageStatus.SUCCESS.name())));
				}))
				.onErrorResume(ex -> {
					terminalSaved.set(true);
					conversationService.saveAssistantMessage(
							assistantMessageId,
							conversationId,
							assistantContent.toString(),
							MessageStatus.FAILED
					);
					JsonLog.error(log, "agent.stream.failed", ex,
							"conversationId", conversationId,
							"correlation", user.correlation(),
							"agentId", agent.id(),
							"assistantMessageId", assistantMessageId,
							"status", MessageStatus.FAILED,
							"assistantContentLength", assistantContent.length(),
							"durationMs", elapsedSince(startedAt)
					);
					return Flux.just(toSse("error", StreamEvent.error(assistantMessageId, MessageStatus.FAILED.name(), "Agent stream interrupted")));
				})
				.doOnSubscribe(subscription -> JsonLog.info(log, "agent.stream.downstream.subscribed",
						"conversationId", conversationId,
						"correlation", user.correlation(),
						"agentId", agent.id(),
						"assistantMessageId", assistantMessageId,
						"protocol", agent.protocol(),
						"endpoint", agent.endpoint()
				))
				.doFinally(signal -> saveStopped(signal, terminalSaved, assistantMessageId, conversationId, user.correlation(), agent.id(), assistantContent, startedAt));
		return start.concatWith(stream);
	}

	private void validate(String conversationId, StreamMessageRequest request) {
		if (!StringUtils.hasText(conversationId)) {
			throw new ResponseStatusException(BAD_REQUEST, "CONVERSATION_ID_REQUIRED");
		}
		if (request == null || !StringUtils.hasText(request.agentId())) {
			throw new ResponseStatusException(BAD_REQUEST, "AGENT_ID_REQUIRED");
		}
		if (!StringUtils.hasText(request.message())) {
			throw new ResponseStatusException(BAD_REQUEST, "MESSAGE_REQUIRED");
		}
	}

	private AgentAdapter selectAdapter(Agent agent) {
		return adapters.stream()
				.filter(adapter -> adapter.supports(agent.protocol()))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(BAD_GATEWAY, "AGENT_PROTOCOL_UNSUPPORTED"));
	}

	private void saveStopped(
			SignalType signal,
			AtomicBoolean terminalSaved,
			String messageId,
			String conversationId,
			String correlation,
			String agentId,
			StringBuilder content,
			long startedAt
	) {
		if (signal == SignalType.CANCEL && terminalSaved.compareAndSet(false, true)) {
			conversationService.saveAssistantMessage(messageId, conversationId, content.toString(), MessageStatus.STOPPED);
			JsonLog.warn(log, "agent.stream.cancelled",
					"conversationId", conversationId,
					"correlation", correlation,
					"agentId", agentId,
					"assistantMessageId", messageId,
					"status", MessageStatus.STOPPED,
					"assistantContentLength", content.length(),
					"durationMs", elapsedSince(startedAt)
			);
		}
	}

	private long elapsedSince(long startedAt) {
		return System.currentTimeMillis() - startedAt;
	}

	private ServerSentEvent<StreamEvent> toSse(String event, StreamEvent data) {
		return ServerSentEvent.<StreamEvent>builder()
				.event(event)
				.data(Objects.requireNonNull(data))
				.build();
	}
}
