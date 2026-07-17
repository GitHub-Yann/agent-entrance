package com.yann.agent.entrance.service;

import com.yann.agent.entrance.model.Agent;
import com.yann.agent.entrance.model.AgentProtocol;
import com.yann.agent.entrance.model.AgentStatus;
import com.yann.agent.entrance.support.JsonLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AgentService {

	private static final Logger log = LoggerFactory.getLogger(AgentService.class);

	private final Map<String, Agent> agents = new ConcurrentHashMap<>();

	public AgentService() {
		agents.put("dev-assistant", new Agent(
				"dev-assistant",
				"开发助手",
				"默认 SSE Agent",
				URI.create("http://localhost:9090/agent/stream"),
				AgentStatus.ENABLED,
				AgentProtocol.SSE
		));
		agents.put("disabled-agent", new Agent(
				"disabled-agent",
				"停用 Agent",
				"用于校验停用状态",
				URI.create("http://localhost:9090/agent/disabled"),
				AgentStatus.DISABLED,
				AgentProtocol.SSE
		));
	}

	public Agent requireEnabled(String agentId) {
		Agent agent = agents.get(agentId);
		if (agent == null) {
			JsonLog.warn(log, "agent.lookup.failed",
					"agentId", agentId,
					"reason", "not_found"
			);
			throw new ResponseStatusException(NOT_FOUND, "AGENT_NOT_FOUND");
		}
		if (agent.status() != AgentStatus.ENABLED) {
			JsonLog.warn(log, "agent.lookup.failed",
					"agentId", agentId,
					"status", agent.status(),
					"reason", "disabled"
			);
			throw new ResponseStatusException(BAD_GATEWAY, "AGENT_DISABLED");
		}
		JsonLog.info(log, "agent.lookup.succeeded",
				"agentId", agent.id(),
				"protocol", agent.protocol(),
				"endpoint", agent.endpoint()
		);
		return agent;
	}
}

