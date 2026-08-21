package com.yann.agent.entrance.dto;

public record AgentAttachmentRef(
		String attachmentId,
		String fileName,
		String contentType,
		String mediaType,
		String storageKey,
		String accessUrl
) {
}
