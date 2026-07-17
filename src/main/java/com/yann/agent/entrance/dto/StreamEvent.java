package com.yann.agent.entrance.dto;

public record StreamEvent(
		String messageId,
		String text,
		String status,
		String reason
) {
	public static StreamEvent messageStart(String messageId) {
		return new StreamEvent(messageId, null, null, null);
	}

	public static StreamEvent delta(String text) {
		return new StreamEvent(null, text, null, null);
	}

	public static StreamEvent messageEnd(String messageId, String status) {
		return new StreamEvent(messageId, null, status, null);
	}

	public static StreamEvent error(String messageId, String status, String reason) {
		return new StreamEvent(messageId, null, status, reason);
	}
}
