package com.yann.agent.entrance.dto;

public record StreamEvent(
		String messageId,
		String text,
		ContentBlock block,
		String status,
		String reason
) {
	public static StreamEvent messageStart(String messageId) {
		return new StreamEvent(messageId, null, null, null, null);
	}

	public static StreamEvent delta(String text) {
		return new StreamEvent(null, text, null, null, null);
	}

	public static StreamEvent contentBlock(String blockType, String blockTitle, String blockFormat, String blockPayload) {
		return new StreamEvent(null, null, new ContentBlock(blockType, blockTitle, blockFormat, blockPayload), null, null);
	}

	public static StreamEvent messageEnd(String messageId, String status) {
		return new StreamEvent(messageId, null, null, status, null);
	}

	public static StreamEvent error(String messageId, String status, String reason) {
		return new StreamEvent(messageId, null, null, status, reason);
	}

	public record ContentBlock(
			String type,
			String title,
			String format,
			String payload
	) {}
}
