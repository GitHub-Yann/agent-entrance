package com.yann.agent.entrance.dto;

import java.net.URI;

public record AgentInvokeRequest(
		String agentId,
		String tenantId,
		String userId,
		String conversationId,
		String accessToken,
		String message,
		URI endpoint,
		String correlation
) {
}
