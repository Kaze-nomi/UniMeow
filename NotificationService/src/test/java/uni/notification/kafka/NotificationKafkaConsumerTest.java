package uni.notification.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import uni.notification.kafka.consumer.NotificationKafkaConsumer;
import uni.notification.kafka.producer.NotificationDlqProducer;
import uni.notification.service.NotificationEventService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationKafkaConsumerTest {

	@Mock
	NotificationEventService notificationEventService;

	@Mock
	NotificationDlqProducer dlqProducer;

	@Mock
	Acknowledgment acknowledgment;

	@InjectMocks
	NotificationKafkaConsumer consumer;

	private static final String TOPIC = "post-events";
	private static final String RAW = "{\"eventId\":\"e1\",\"eventType\":\"POST_CREATED\",\"payload\":{}}";

	@Test
	void onPostEvent_processes_and_acknowledges_on_success() {
		consumer.onPostEvent(RAW, acknowledgment, TOPIC, "key-1", 0, 0L);

		verify(notificationEventService).processRaw(RAW);
		verify(acknowledgment).acknowledge();
		verifyNoInteractions(dlqProducer);
	}

	@Test
	void onUserEvent_processes_and_acknowledges_on_success() {
		consumer.onUserEvent(RAW, acknowledgment, "user-events", "key-2", 0, 1L);

		verify(notificationEventService).processRaw(RAW);
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_sends_to_dlq_and_acknowledges_on_failure_when_dlq_succeeds() {
		doThrow(new RuntimeException("parse error")).when(notificationEventService).processRaw(any());
		when(dlqProducer.publishConsumerFailure(any(), any(), any(), anyInt(), anyLong(), any())).thenReturn(true);

		consumer.onPostEvent(RAW, acknowledgment, TOPIC, "key-1", 0, 5L);

		verify(dlqProducer).publishConsumerFailure(eq(TOPIC), eq("key-1"), eq(RAW), eq(0), eq(5L), any());
		verify(acknowledgment).acknowledge();
	}

	@Test
	void onPostEvent_rethrows_when_dlq_also_fails() {
		doThrow(new RuntimeException("parse error")).when(notificationEventService).processRaw(any());
		when(dlqProducer.publishConsumerFailure(any(), any(), any(), anyInt(), anyLong(), any())).thenReturn(false);

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> consumer.onPostEvent(RAW, acknowledgment, TOPIC, "key-1", 0, 5L))
				.isInstanceOf(RuntimeException.class);

		verify(acknowledgment, never()).acknowledge();
	}
}
