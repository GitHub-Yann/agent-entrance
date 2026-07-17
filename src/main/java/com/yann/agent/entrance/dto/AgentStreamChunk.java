package com.yann.agent.entrance.dto;

public record AgentStreamChunk(
		String text,
		boolean terminal,
		String rawPayload
) {
	public static AgentStreamChunk delta(String text) {
		return new AgentStreamChunk(text, false, text);
	}
}
