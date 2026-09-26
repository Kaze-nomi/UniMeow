package uni.post.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

	@Mock
	OutboxEventRepository outboxEventRepository;

	@Mock
	KafkaTemplate<String, String> kafkaTemplate;

	@InjectMocks
	OutboxPublisher outboxPublisher;

	private OutboxEvent event(String key) {
		return OutboxEvent.builder().id(UUID.randomUUID()).topic("post-events").eventKey(key)
				.payload("{\"eventId\":\"" + key + "\"}").createdAt(LocalDateTime.now()).build();
	}

	private void pending(OutboxEvent... events) {
		when(outboxEventRepository.tryLockPublisher()).thenReturn(true);
		when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc())
				.thenReturn(List.of(events));
	}

	@Test
	void skips_batch_when_another_publisher_holds_lock() {
		outboxPublisher.publishPendingEvents();

		verify(outboxEventRepository).tryLockPublisher();
		verifyNoMoreInteractions(outboxEventRepository);
		verifyNoInteractions(kafkaTemplate);
	}

	@Test
	void handles_empty_batch() {
		pending();

		outboxPublisher.publishPendingEvents();

		verifyNoInteractions(kafkaTemplate);
	}

	@Test
	void locks_before_reading_and_publishes_in_repository_order() {
		OutboxEvent first = event("first");
		OutboxEvent second = event("second");
		pending(first, second);
		when(kafkaTemplate.send(first.getTopic(), first.getEventKey(), first.getPayload())).thenAnswer(call -> {
			assertThat(first.getPublishedAt()).isNull();
			return CompletableFuture.completedFuture(null);
		});
		when(kafkaTemplate.send(second.getTopic(), second.getEventKey(), second.getPayload())).thenAnswer(call -> {
			assertThat(first.getPublishedAt()).isNotNull();
			assertThat(second.getPublishedAt()).isNull();
			return CompletableFuture.completedFuture(null);
		});

		outboxPublisher.publishPendingEvents();

		var order = inOrder(outboxEventRepository, kafkaTemplate);
		order.verify(outboxEventRepository).tryLockPublisher();
		order.verify(outboxEventRepository).findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc();
		order.verify(kafkaTemplate).send(first.getTopic(), first.getEventKey(), first.getPayload());
		order.verify(kafkaTemplate).send(second.getTopic(), second.getEventKey(), second.getPayload());
		order.verifyNoMoreInteractions();
		assertThat(second.getPublishedAt()).isNotNull();
	}

	@Test
	void failed_confirmation_stops_batch_and_keeps_unsent_events_pending() {
		OutboxEvent first = event("first");
		OutboxEvent failed = event("failed");
		OutboxEvent later = event("later");
		pending(first, failed, later);
		when(kafkaTemplate.send(first.getTopic(), first.getEventKey(), first.getPayload()))
				.thenReturn(CompletableFuture.completedFuture(null));
		when(kafkaTemplate.send(failed.getTopic(), failed.getEventKey(), failed.getPayload()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

		outboxPublisher.publishPendingEvents();

		assertThat(first.getPublishedAt()).isNotNull();
		assertThat(failed.getPublishedAt()).isNull();
		assertThat(later.getPublishedAt()).isNull();
		verify(kafkaTemplate).send(first.getTopic(), first.getEventKey(), first.getPayload());
		verify(kafkaTemplate).send(failed.getTopic(), failed.getEventKey(), failed.getPayload());
		verifyNoMoreInteractions(kafkaTemplate);
	}

	@Test
	void synchronous_send_failure_keeps_batch_pending() {
		OutboxEvent first = event("first");
		OutboxEvent later = event("later");
		pending(first, later);
		when(kafkaTemplate.send(first.getTopic(), first.getEventKey(), first.getPayload()))
				.thenThrow(new RuntimeException("Kafka unavailable"));

		outboxPublisher.publishPendingEvents();

		assertThat(first.getPublishedAt()).isNull();
		assertThat(later.getPublishedAt()).isNull();
		verify(kafkaTemplate).send(first.getTopic(), first.getEventKey(), first.getPayload());
		verifyNoMoreInteractions(kafkaTemplate);
	}

	@Test
	void retries_same_event_before_later_events_on_next_batch() {
		OutboxEvent first = event("first");
		OutboxEvent later = event("later");
		pending(first, later);
		when(kafkaTemplate.send(first.getTopic(), first.getEventKey(), first.getPayload()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")),
						CompletableFuture.completedFuture(null));
		when(kafkaTemplate.send(later.getTopic(), later.getEventKey(), later.getPayload()))
				.thenReturn(CompletableFuture.completedFuture(null));

		outboxPublisher.publishPendingEvents();
		outboxPublisher.publishPendingEvents();

		var order = inOrder(kafkaTemplate);
		order.verify(kafkaTemplate, times(2)).send(first.getTopic(), first.getEventKey(), first.getPayload());
		order.verify(kafkaTemplate).send(later.getTopic(), later.getEventKey(), later.getPayload());
		order.verifyNoMoreInteractions();
		verify(outboxEventRepository, times(2)).tryLockPublisher();
		assertThat(first.getPublishedAt()).isNotNull();
		assertThat(later.getPublishedAt()).isNotNull();
	}
}
