package com.yann.agent.entrance.adapter;

import com.yann.agent.entrance.model.AgentProtocol;
import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.AgentStreamChunk;
import reactor.core.publisher.Flux;

public interface AgentAdapter {

	Flux<AgentStreamChunk> stream(AgentInvokeRequest request);

	boolean supports(AgentProtocol protocol);
}

