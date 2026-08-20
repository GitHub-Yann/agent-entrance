package com.yann.agent.entrance.dto;

public record AgentStreamChunk(
		String event,
		String text,
		String blockType,
		String blockTitle,
		String blockFormat,
		String blockPayload,
		boolean terminal,
		String rawPayload
) {
	public static AgentStreamChunk delta(String text) {
		return new AgentStreamChunk("delta", text, null, null, null, null, false, text);
	}

	public static AgentStreamChunk contentBlock(String blockType, String blockTitle, String blockFormat, String blockPayload) {
		return new AgentStreamChunk("content_block", null, blockType, blockTitle, blockFormat, blockPayload, false, blockPayload);
	}
}
