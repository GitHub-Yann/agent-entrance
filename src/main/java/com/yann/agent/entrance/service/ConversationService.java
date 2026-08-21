package com.yann.agent.entrance.service;

import com.yann.agent.entrance.model.MessageRole;
import com.yann.agent.entrance.model.MessageStatus;
import com.yann.agent.entrance.model.StoredMessage;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ConversationService {

	private final Clock clock;
	private final List<StoredMessage> messages = new CopyOnWriteArrayList<>();
	private final ConcurrentHashMap<String, AtomicInteger> clientMessageSequences = new ConcurrentHashMap<>();

	public ConversationService(Clock clock) {
		this.clock = clock;
	}

	public StoredMessage saveUserMessage(String conversationId, String content) {
		StoredMessage message = new StoredMessage(
				nextId(),
				conversationId,
				MessageRole.USER,
				content,
				MessageStatus.SUCCESS,
				clock.instant()
		);
		messages.add(message);
		return message;
	}

	public StoredMessage saveAssistantMessage(String messageId, String conversationId, String content, MessageStatus status) {
		StoredMessage message = new StoredMessage(
				messageId,
				conversationId,
				MessageRole.ASSISTANT,
				content,
				status,
				clock.instant()
		);
		messages.add(message);
		return message;
	}

	public List<StoredMessage> listMessages() {
		return List.copyOf(messages);
	}

	public String nextId() {
		return "m-" + UUID.randomUUID();
	}

	public String nextClientMessageId(String conversationId) {
		int sequence = clientMessageSequences
				.computeIfAbsent(conversationId, key -> new AtomicInteger())
				.incrementAndGet();
		return conversationId + "-" + sequence;
	}
}

