package com.yann.agent.entrance.model;

import java.time.Instant;

public record AgentAccessAudit(
		String userId,
		String username,
		String tenantId,
		String tenantName,
		String agentId,
		String agentName,
		Instant accessTime
) {
}
