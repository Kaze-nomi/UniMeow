package uni.post.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

	@Mock
	OutboxEventRepository outboxEventRepository;

	@Spy
	ObjectMapper objectMapper = new ObjectMapper();

	@InjectMocks
	OutboxService outboxService;

	@Test
	void enqueuePostEvent_saves_event_with_correct_structure() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		Map<String, Object> payload = Map.of("postId", "post-123", "authorId", "author-456");

		outboxService.enqueuePostEvent("POST_CREATED", "key-1", "post-123", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		OutboxEvent event = captor.getValue();
		assertThat(event.getTopic()).isEqualTo("post-events");
		assertThat(event.getEventKey()).isEqualTo("key-1");
		assertThat(event.getPayload()).contains("eventType", "POST_CREATED");
		assertThat(event.getPayload()).contains("eventId");
		assertThat(event.getPayload()).contains("occurredAt");
		assertThat(event.getPayload()).contains("aggregateId", "post-123");
	}

	@Test
	void enqueuePostEvent_includes_payload_in_event() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		Map<String, Object> payload = Map.of("content", "test post", "mediaUrls", new Object[0]);

		outboxService.enqueuePostEvent("POST_CREATED", "key-1", "post-123", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		OutboxEvent event = captor.getValue();
		assertThat(event.getPayload()).contains("\"content\"").contains("\"test post\"");
	}

	@Test
	void enqueuePostEvent_generates_unique_event_ids() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		Map<String, Object> payload = Map.of();

		outboxService.enqueuePostEvent("POST_CREATED", "key-1", "post-1", payload);
		outboxService.enqueuePostEvent("POST_CREATED", "key-2", "post-2", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository, times(2)).save(captor.capture());

		var events = captor.getAllValues();
		assertThat(events.get(0).getPayload()).isNotEqualTo(events.get(1).getPayload());
	}

	@Test
	void enqueuePostEvent_sets_version_to_one() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		outboxService.enqueuePostEvent("POST_CREATED", "key-1", "post-123", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getPayload()).contains("\"version\":1");
	}

	@Test
	void enqueuePostEvent_creates_outbox_event_with_no_published_at() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		outboxService.enqueuePostEvent("POST_DELETED", "key-1", "post-123", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getPublishedAt()).isNull();
	}

	@Test
	void enqueuePostEvent_throws_when_serialization_fails() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		OutboxService service = new OutboxService(outboxEventRepository, new ObjectMapper() {
			@Override
			public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
				throw new com.fasterxml.jackson.core.JsonProcessingException("Test error") {
				};
			}
		});

		assertThatThrownBy(() -> service.enqueuePostEvent("POST_CREATED", "key", "post-1", Map.of()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to serialize");
	}

	@Test
	void enqueuePostEvent_uses_correct_topic_from_config() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "custom-post-topic");

		outboxService.enqueuePostEvent("POST_CREATED", "key", "post-1", Map.of());

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		assertThat(captor.getValue().getTopic()).isEqualTo("custom-post-topic");
	}

	@Test
	void enqueuePostEvent_preserves_payload_data() {
		ReflectionTestUtils.setField(outboxService, "postEventsTopic", "post-events");

		Map<String, Object> payload = Map.of("postId", "p1", "authorId", "a1", "content", "Hello", "likesCount", 42);

		outboxService.enqueuePostEvent("POST_CREATED", "key-1", "p1", payload);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxEventRepository).save(captor.capture());

		String eventPayload = captor.getValue().getPayload();
		assertThat(eventPayload).contains("\"postId\"").contains("\"p1\"").contains("\"authorId\"").contains("\"a1\"")
				.contains("\"Hello\"").contains("42");
	}
}
