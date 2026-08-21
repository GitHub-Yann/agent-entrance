package com.yann.agent.entrance.service;

import com.yann.agent.entrance.adapter.AgentAdapter;
import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.AgentStreamChunk;
import com.yann.agent.entrance.dto.StreamMessageRequest;
import com.yann.agent.entrance.model.AgentProtocol;
import com.yann.agent.entrance.model.MessageRole;
import com.yann.agent.entrance.model.MessageStatus;
import com.yann.agent.entrance.model.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProxyServiceTest {

	private final Clock clock = Clock.systemUTC();
	private final AgentService agentService = new AgentService();
	private final TenantAuthorizationService tenantAuthorizationService = new TenantAuthorizationService();
	private final ConversationService conversationService = new ConversationService(clock);
	private final AuditService auditService = new AuditService(clock);

	@Test
	void streamsAgentDeltasAndSavesMessagesAfterComplete() {
		AgentProxyService service = newService(Flux.just(
				AgentStreamChunk.delta("hello "),
				AgentStreamChunk.delta("world")
		));

		List<String> events = service.stream(user("portal"), "c-1", new StreamMessageRequest("dev-assistant", "hi", null))
				.map(ServerSentEvent::event)
				.collectList()
				.block();

		assertThat(events).containsExactly("message_start", "delta", "delta", "message_end");
		assertThat(conversationService.listMessages())
				.extracting(message -> message.role() + ":" + message.content() + ":" + message.status())
				.containsExactly(
						MessageRole.USER + ":hi:" + MessageStatus.SUCCESS,
						MessageRole.ASSISTANT + ":hello world:" + MessageStatus.SUCCESS
				);
		assertThat(conversationService.listMessages().get(1).id()).isEqualTo("c-1-1");
		assertThat(auditService.list()).hasSize(1);
	}

	@Test
	void savesPartialAssistantMessageWhenAgentStreamFails() {
		AgentProxyService service = newService(Flux.concat(
				Flux.just(AgentStreamChunk.delta("partial")),
				Flux.error(new IllegalStateException("downstream closed"))
		));

		StepVerifier.create(service.stream(user("portal"), "c-1", new StreamMessageRequest("dev-assistant", "hi", null))
						.map(ServerSentEvent::event))
				.expectNext("message_start", "delta", "error")
				.verifyComplete();

		assertThat(conversationService.listMessages())
				.filteredOn(message -> message.role() == MessageRole.ASSISTANT)
				.singleElement()
				.satisfies(message -> {
					assertThat(message.content()).isEqualTo("partial");
					assertThat(message.status()).isEqualTo(MessageStatus.FAILED);
				});
	}

	@Test
	void rejectsUnauthorizedTenantBeforeCallingAgent() {
		AgentProxyService service = newService(Flux.just(AgentStreamChunk.delta("ignored")));

		assertThatThrownBy(() -> service.stream(user("tenant-b"), "c-1", new StreamMessageRequest("dev-assistant", "hi", null)))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("AGENT_NOT_AUTHORIZED");
	}

	private AgentProxyService newService(Flux<AgentStreamChunk> chunks) {
		AgentAdapter adapter = new AgentAdapter() {
			@Override
			public Flux<AgentStreamChunk> stream(AgentInvokeRequest request) {
				return chunks;
			}

			@Override
			public boolean supports(AgentProtocol protocol) {
				return protocol == AgentProtocol.SSE;
			}
		};
		return new AgentProxyService(agentService, tenantAuthorizationService, conversationService, auditService, List.of(adapter));
	}

	private UserContext user(String tenantId) {
		return new UserContext("u-1", "Yann Chen", tenantId, tenantId, "token", "correlation-test");
	}
}

