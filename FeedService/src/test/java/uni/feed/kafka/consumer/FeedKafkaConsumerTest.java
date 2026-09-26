package uni.feed.kafka.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import uni.feed.kafka.producer.FeedDlqProducer;
import uni.feed.service.FeedEventService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedKafkaConsumerTest {

	@Mock
	FeedEventService feedEventService;

	@Mock
	FeedDlqProducer feedDlqProducer;

	@Mock
	Acknowledgment acknowledgment;

	@InjectMocks
	FeedKafkaConsumer feedKafkaConsumer;

	private static final String POST_EVENTS_TOPIC = "post-events";
	private static final String USER_EVENTS_TOPIC = "user-events";
	private static final String MESSAGE_KEY = "event-key";
	private static final int PARTITION = 0;
	private static final long OFFSET = 100L;

	@Test
	void onPostEvent_processes_message_and_acknowledges() {
		String eventJson = """
				{
				    "eventId": "event-1",
				    "eventType": "POST_CREATED",
				    "occurredAt": "2024-01-01T10:00:00Z",
				    "payload": {"postId": "post-1", "authorId": "author-1"}
				}
				""";

		feedKafkaConsumer.onPostEvent(eventJson, acknowledgment, POST_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);

		verify(feedEventService).processRaw(eventJson);
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onUserEvent_processes_message_and_acknowledges() {
		String eventJson = """
				{
				    "eventId": "event-2",
				    "eventType": "USER_FOLLOWED",
				    "occurredAt": "2024-01-01T10:00:00Z",
				    "payload": {"subscriberId": "user-1", "targetUserId": "user-2"}
				}
				""";

		feedKafkaConsumer.onUserEvent(eventJson, acknowledgment, USER_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);

		verify(feedEventService).processRaw(eventJson);
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_sends_to_dlq_when_processing_fails() {
		String eventJson = "invalid json";
		RuntimeException exception = new RuntimeException("Parse error");

		doThrow(exception).when(feedEventService).processRaw(eventJson);
		when(feedDlqProducer.publishConsumerFailure(POST_EVENTS_TOPIC, MESSAGE_KEY, eventJson, PARTITION, OFFSET,
				exception)).thenReturn(true);

		feedKafkaConsumer.onPostEvent(eventJson, acknowledgment, POST_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);

		verify(feedDlqProducer).publishConsumerFailure(eq(POST_EVENTS_TOPIC), eq(MESSAGE_KEY), eq(eventJson),
				eq(PARTITION), eq(OFFSET), any(RuntimeException.class));
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_throws_when_dlq_publish_fails() {
		String eventJson = "invalid json";
		RuntimeException processError = new RuntimeException("Parse error");

		doThrow(processError).when(feedEventService).processRaw(eventJson);
		when(feedDlqProducer.publishConsumerFailure(eq(POST_EVENTS_TOPIC), any(), any(), anyInt(), anyLong(), any()))
				.thenReturn(false);

		try {
			feedKafkaConsumer.onPostEvent(eventJson, acknowledgment, POST_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);
		} catch (RuntimeException e) {
		}

		verify(acknowledgment, never()).acknowledge();
	}

	@Test
	void onUserEvent_sends_to_dlq_on_processing_failure() {
		String eventJson = "malformed";
		RuntimeException exception = new RuntimeException("Invalid event");

		doThrow(exception).when(feedEventService).processRaw(eventJson);
		when(feedDlqProducer.publishConsumerFailure(USER_EVENTS_TOPIC, MESSAGE_KEY, eventJson, PARTITION, OFFSET,
				exception)).thenReturn(true);

		feedKafkaConsumer.onUserEvent(eventJson, acknowledgment, USER_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);

		verify(feedDlqProducer).publishConsumerFailure(eq(USER_EVENTS_TOPIC), eq(MESSAGE_KEY), eq(eventJson),
				eq(PARTITION), eq(OFFSET), any(RuntimeException.class));
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_passes_correct_metadata_to_dlq_producer() {
		String eventJson = "event data";
		RuntimeException exception = new RuntimeException("Error");

		doThrow(exception).when(feedEventService).processRaw(eventJson);
		when(feedDlqProducer.publishConsumerFailure(anyString(), anyString(), anyString(), anyInt(), anyLong(), any()))
				.thenReturn(true);

		feedKafkaConsumer.onPostEvent(eventJson, acknowledgment, "topic-x", "key-y", 5, 999L);

		verify(feedDlqProducer).publishConsumerFailure("topic-x", "key-y", eventJson, 5, 999L, exception);
	}

	@Test
	void onPostEvent_only_acknowledges_after_dlq_success() {
		String eventJson = "event";
		RuntimeException exception = new RuntimeException("Error");

		doThrow(exception).when(feedEventService).processRaw(eventJson);
		when(feedDlqProducer.publishConsumerFailure(anyString(), anyString(), anyString(), anyInt(), anyLong(), any()))
				.thenReturn(true);

		feedKafkaConsumer.onPostEvent(eventJson, acknowledgment, POST_EVENTS_TOPIC, MESSAGE_KEY, PARTITION, OFFSET);

		verify(acknowledgment).acknowledge();
	}

	@Test
	void onUserEvent_handles_null_message_key() {
		String eventJson = "event with no key";

		feedKafkaConsumer.onUserEvent(eventJson, acknowledgment, USER_EVENTS_TOPIC, null, PARTITION, OFFSET);

		verify(feedEventService).processRaw(eventJson);
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_successfully_processes_valid_event() {
		String validEvent = """
				{
				    "eventId": "evt-123",
				    "eventType": "POST_CREATED",
				    "occurredAt": "2024-01-01T12:00:00Z",
				    "payload": {"postId": "p1", "authorId": "a1"}
				}
				""";

		feedKafkaConsumer.onPostEvent(validEvent, acknowledgment, POST_EVENTS_TOPIC, "k1", PARTITION, OFFSET);

		verify(feedEventService).processRaw(validEvent);
		verify(acknowledgment).acknowledge();
		verifyNoInteractions(feedDlqProducer);
	}
}
