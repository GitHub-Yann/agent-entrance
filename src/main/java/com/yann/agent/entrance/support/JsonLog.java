package com.yann.agent.entrance.support;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.time.temporal.TemporalAccessor;

public final class JsonLog {

	private JsonLog() {
	}

	public static void info(Logger logger, String msg, Object... fields) {
		if (logger.isInfoEnabled()) {
			addFields(logger.atInfo(), fields).log(msg);
		}
	}

	public static void warn(Logger logger, String msg, Object... fields) {
		if (logger.isWarnEnabled()) {
			addFields(logger.atWarn(), fields).log(msg);
		}
	}

	public static void error(Logger logger, String msg, Throwable throwable, Object... fields) {
		if (logger.isErrorEnabled()) {
			LoggingEventBuilder builder = addFields(logger.atError(), fields);
			if (throwable != null) {
				builder.addKeyValue("errorType", throwable.getClass().getSimpleName());
				builder.addKeyValue("errorMessage", throwable.getMessage());
			}
			builder.log(msg);
		}
	}

	static LoggingEventBuilder addFields(LoggingEventBuilder builder, Object... fields) {
		// builder.addKeyValue("event", event);
		String correlation = CorrelationContext.current();
		if (correlation != null) {
			builder.addKeyValue(CorrelationContext.KEY, correlation);
		}
		for (int index = 0; index + 1 < fields.length; index += 2) {
			builder.addKeyValue(String.valueOf(fields[index]), normalize(fields[index + 1]));
		}
		return builder;
	}

	private static Object normalize(Object value) {
		if (value == null
				|| value instanceof CharSequence
				|| value instanceof Number
				|| value instanceof Boolean) {
			return value;
		}
		if (value instanceof Enum<?> enumValue) {
			return enumValue.name();
		}
		if (value instanceof TemporalAccessor) {
			return value.toString();
		}
		return value.toString();
	}
}
