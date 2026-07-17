package com.yann.agent.entrance.model;

public record UserContext(
		String userId,
		String username,
		String tenantId,
		String tenantName,
		String accessToken,
		String correlation
) {
}
