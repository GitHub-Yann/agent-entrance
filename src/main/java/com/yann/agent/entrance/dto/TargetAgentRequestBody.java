package com.yann.agent.entrance.dto;

import java.util.List;

public record TargetAgentRequestBody(
		String agentId,
		String tenantId,
		String userId,
		String conversationId,
		String message,
		List<AgentAttachmentRef> attachments,
		String clientMessageId
) {
}
