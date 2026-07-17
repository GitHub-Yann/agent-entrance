package com.yann.agent.entrance.model;

import java.net.URI;

public record Agent(
		String id,
		String name,
		String description,
		URI endpoint,
		AgentStatus status,
		AgentProtocol protocol
) {
}
