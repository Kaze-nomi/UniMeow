package uni.gateway.grpc;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uni.grpc.feed.FeedServiceGrpc;
import uni.grpc.feed.FeedType;
import uni.grpc.feed.GetFeedRequest;
import uni.grpc.feed.GetFeedResponse;

@Service
public class FeedGrpcClient {

	@GrpcClient("feed-service")
	private FeedServiceGrpc.FeedServiceBlockingStub stub;

	public Mono<GetFeedResponse> getFeed(FeedType feedType, String userId, Long cursor, int size) {
		GetFeedRequest.Builder builder = GetFeedRequest.newBuilder().setFeedType(feedType).setSize(size);

		if (userId != null && !userId.isBlank()) {
			builder.setUserId(userId);
		}
		if (cursor != null) {
			builder.setCursor(cursor);
		}

		return Mono.fromCallable(() -> stub.getFeed(builder.build())).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<GetFeedResponse> getUniversityFeed(FeedType feedType, String userId, Long cursor, int size,
			long universityId, Long facultyId, Long programId) {
		GetFeedRequest.Builder builder = GetFeedRequest.newBuilder().setFeedType(feedType).setSize(size)
				.setUniversityId(universityId);

		if (facultyId != null && facultyId > 0)
			builder.setFacultyId(facultyId);
		if (programId != null && programId > 0)
			builder.setProgramId(programId);
		if (userId != null && !userId.isBlank())
			builder.setUserId(userId);
		if (cursor != null)
			builder.setCursor(cursor);

		return Mono.fromCallable(() -> stub.getFeed(builder.build())).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}
}
