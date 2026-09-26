package uni.feed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uni.feed.repository.FeedProjectionRepository;
import uni.feed.repository.FeedProjectionRepository.Destination;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class FeedEventServiceTest {
	@Mock
	FeedProjectionRepository projections;
	FeedEventService service;

	@BeforeEach
	void setup() {
		service = new FeedEventService(new ObjectMapper(), projections);
		ReflectionTestUtils.setField(service, "authorWindowSize", 1000L);
		ReflectionTestUtils.setField(service, "userWindowSize", 1000L);
		ReflectionTestUtils.setField(service, "uniWindowSize", 5000L);
		ReflectionTestUtils.setField(service, "popularWindowSize", 5000L);
		ReflectionTestUtils.setField(service, "trendingLikeBoostMs", 1L);
	}

	private String event(String type, String payload) {
		return "{\"eventId\":\"e1\",\"eventType\":\"" + type + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"payload\":"
				+ payload + "}";
	}

	@Test
	void completedDeliveryIsNotAppliedAgain() {
		when(projections.begin("e1")).thenReturn(-1L);
		service.processRaw(event("POST_CREATED", "{}"));
		verify(projections, never()).complete(any());
		verify(projections, never()).projectPost(any(), any(), anyLong(), anyDouble(), anyDouble(), anyBoolean(),
				any());
	}

	@Test
	void creationKeepsAllIndependentChronologicalAndPopularProjections() {
		service.processRaw(event("POST_CREATED",
				"{\"postId\":\"p1\",\"authorId\":\"a1\",\"authorUniversityId\":7,\"topicId\":11,\"parentTopicId\":5}"));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Destination>> destinations = ArgumentCaptor.forClass(List.class);
		verify(projections).projectPost(eq("p1"), eq("a1"), eq(0L), anyDouble(), anyDouble(), eq(true),
				destinations.capture());
		assertThat(destinations.getValue()).extracting(Destination::key).containsExactly("feed:author:a1",
				"feed:popular", "feed:uni:7", "feed:uni:7:popular", "feed:uni:7:topic:5", "feed:uni:7:topic:5:popular",
				"feed:uni:7:subtopic:11", "feed:uni:7:subtopic:11:popular");
		var order = inOrder(projections);
		order.verify(projections).fanout(eq("a1"), eq("p1"), anyDouble(), eq(1000L));
		order.verify(projections).complete("e1");
	}

	@Test
	void failedFanoutLeavesReceiptUnfinishedForRetry() {
		doThrow(new IllegalStateException("Redis connection lost")).when(projections).fanout(any(), any(), anyDouble(),
				anyLong());
		assertThatThrownBy(() -> service.processRaw(event("POST_CREATED", "{\"postId\":\"p1\",\"authorId\":\"a1\"}")))
				.isInstanceOf(IllegalStateException.class);
		verify(projections, never()).complete(any());
	}

	@Test
	void likeAndUnlikeUseAbsoluteCountAndOnlyPopularityDestinations() {
		service.processRaw(
				event("POST_UNLIKED", "{\"postId\":\"p1\",\"authorId\":\"a1\",\"createdAtMs\":1000,\"likesCount\":4}"));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Destination>> destinations = ArgumentCaptor.forClass(List.class);
		verify(projections).projectPost(eq("p1"), eq("a1"), eq(0L), eq(1000.0), eq(1004.0), eq(false),
				destinations.capture());
		assertThat(destinations.getValue()).allMatch(Destination::popular);
		verify(projections, never()).fanout(any(), any(), anyDouble(), anyLong());
	}

	@Test
	void rejectsMissingIdsAndInvalidDatesWithoutCompletingReceipt() {
		assertThatThrownBy(() -> service.processRaw("null")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.processRaw("not json")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.processRaw("{\"eventType\":\"POST_CREATED\"}"))
				.hasMessageContaining("eventId");
		assertThatThrownBy(() -> service.processRaw(event("POST_CREATED", "{\"postId\":\"p1\"}")))
				.hasMessageContaining("authorId");
		assertThatThrownBy(() -> service
				.processRaw(event("USER_FOLLOWED", "{\"subscriberId\":\"same\",\"targetUserId\":\"same\"}")))
				.hasMessageContaining("invalid");
		verify(projections, never()).complete(any());
	}
}
