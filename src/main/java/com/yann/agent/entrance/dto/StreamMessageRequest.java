package com.yann.agent.entrance.dto;

public record StreamMessageRequest(
		String agentId,
		String message,
		String clientMessageId
) {
}
