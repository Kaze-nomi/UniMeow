package uni.post.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

	@Mock
	OutboxEventRepository outboxEventRepository;

	@Mock
	KafkaTemplate<String, String> kafkaTemplate;

	@Spy
	ObjectMapper objectMapper = new ObjectMapper();

	@InjectMocks
	OutboxPublisher outboxPublisher;

	private OutboxEvent buildOutboxEvent(UUID id, String topic, String eventKey, String payload) {
		return OutboxEvent.builder().id(id).topic(topic).eventKey(eventKey).payload(payload)
				.createdAt(LocalDateTime.now()).publishedAt(null).build();
	}

	@Test
	void publishPendingEvents_sends_unpublished_events_to_kafka() {
		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1",
				"{\"eventId\": \"evt-1\", \"eventType\": \"POST_CREATED\"}");

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		verify(kafkaTemplate).send("post-events", "key-1", event.getPayload());
	}

	@Test
	void publishPendingEvents_marks_event_as_published_after_sending() {
		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", "{\"eventId\": \"evt-1\"}");

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(anyString(), anyString(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		assertThat(event.getPublishedAt()).isNotNull();
	}

	@Test
	void publishPendingEvents_sends_to_dlq_on_kafka_failure() {
		ReflectionTestUtils.setField(outboxPublisher, "postEventsDlqTopic", "post-events.dlq");

		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", "{\"eventId\": \"evt-1\"}");

		RuntimeException kafkaError = new RuntimeException("Kafka unavailable");
		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())).thenThrow(kafkaError);
		when(kafkaTemplate.send(eq("post-events.dlq"), eq(event.getEventKey()), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		verify(kafkaTemplate).send(eq("post-events.dlq"), eq("key-1"), anyString());
		assertThat(event.getPublishedAt()).isNotNull();
	}

	@Test
	void publishPendingEvents_stops_on_dlq_failure() {
		ReflectionTestUtils.setField(outboxPublisher, "postEventsDlqTopic", "post-events.dlq");

		OutboxEvent event1 = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", "{}");
		OutboxEvent event2 = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-2", "{}");

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
				.thenReturn(List.of(event1, event2));
		when(kafkaTemplate.send(event1.getTopic(), event1.getEventKey(), event1.getPayload()))
				.thenThrow(new RuntimeException("Kafka error"));
		when(kafkaTemplate.send(eq("post-events.dlq"), anyString(), anyString()))
				.thenThrow(new RuntimeException("DLQ error"));

		outboxPublisher.publishPendingEvents();

		assertThat(event1.getPublishedAt()).isNull();
		assertThat(event2.getPublishedAt()).isNull();
	}

	@Test
	void publishPendingEvents_processes_multiple_events() {
		OutboxEvent event1 = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", "{}");
		OutboxEvent event2 = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-2", "{}");
		OutboxEvent event3 = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-3", "{}");

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
				.thenReturn(List.of(event1, event2, event3));
		when(kafkaTemplate.send(anyString(), anyString(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		assertThat(event1.getPublishedAt()).isNotNull();
		assertThat(event2.getPublishedAt()).isNotNull();
		assertThat(event3.getPublishedAt()).isNotNull();
	}

	@Test
	void publishPendingEvents_handles_no_pending_events() {
		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

		outboxPublisher.publishPendingEvents();

		verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void publishPendingEvents_includes_error_details_in_dlq_message() {
		ReflectionTestUtils.setField(outboxPublisher, "postEventsDlqTopic", "post-events.dlq");

		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", "{\"eventId\": \"evt-1\"}");

		RuntimeException kafkaError = new RuntimeException("Connection timeout");
		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())).thenThrow(kafkaError);
		when(kafkaTemplate.send(eq("post-events.dlq"), eq("key-1"), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		ArgumentCaptor<String> dlqPayloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(kafkaTemplate).send(eq("post-events.dlq"), eq("key-1"), dlqPayloadCaptor.capture());

		String dlqPayload = dlqPayloadCaptor.getValue();
		assertThat(dlqPayload).contains("Connection timeout");
		assertThat(dlqPayload).contains("java.lang.RuntimeException");
		assertThat(dlqPayload).contains("post-events");
	}

	@Test
	void publishPendingEvents_preserves_original_payload_in_dlq() {
		ReflectionTestUtils.setField(outboxPublisher, "postEventsDlqTopic", "post-events.dlq");

		String originalPayload = "{\"eventId\": \"evt-1\", \"postId\": \"p1\"}";
		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", originalPayload);

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
				.thenThrow(new RuntimeException("Error"));
		when(kafkaTemplate.send(eq("post-events.dlq"), eq("key-1"), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		ArgumentCaptor<String> dlqPayloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(kafkaTemplate).send(eq("post-events.dlq"), eq("key-1"), dlqPayloadCaptor.capture());

		assertThat(dlqPayloadCaptor.getValue()).contains("evt-1").contains("p1");
	}

	@Test
	void publishPendingEvents_handles_invalid_json_in_dlq_message() {
		ReflectionTestUtils.setField(outboxPublisher, "postEventsDlqTopic", "post-events.dlq");

		String invalidPayload = "not a json";
		OutboxEvent event = buildOutboxEvent(UUID.randomUUID(), "post-events", "key-1", invalidPayload);

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
		when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
				.thenThrow(new RuntimeException("Error"));
		when(kafkaTemplate.send(eq("post-events.dlq"), eq("key-1"), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		ArgumentCaptor<String> dlqPayloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(kafkaTemplate).send(eq("post-events.dlq"), eq("key-1"), dlqPayloadCaptor.capture());

		assertThat(dlqPayloadCaptor.getValue()).contains("not a json");
	}

	@Test
	void publishPendingEvents_limits_to_100_events() {
		List<OutboxEvent> events = new java.util.ArrayList<>();
		for (int i = 0; i < 100; i++) {
			events.add(buildOutboxEvent(UUID.randomUUID(), "post-events", "key-" + i, "{}"));
		}

		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(events);
		when(kafkaTemplate.send(anyString(), anyString(), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();

		verify(kafkaTemplate, times(100)).send(anyString(), anyString(), anyString());
	}
}
