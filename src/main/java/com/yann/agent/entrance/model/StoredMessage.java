package com.yann.agent.entrance.model;

import java.time.Instant;

public record StoredMessage(
		String id,
		String conversationId,
		MessageRole role,
		String content,
		MessageStatus status,
		Instant createdAt
) {
}
