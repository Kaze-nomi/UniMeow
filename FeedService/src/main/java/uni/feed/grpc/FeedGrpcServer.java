package uni.feed.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.feed.record.FeedPageResult;
import uni.feed.service.FeedReadService;
import uni.grpc.feed.FeedServiceGrpc;
import uni.grpc.feed.GetFeedRequest;
import uni.grpc.feed.GetFeedResponse;

@GrpcService
@RequiredArgsConstructor
public class FeedGrpcServer extends FeedServiceGrpc.FeedServiceImplBase {

	private final FeedReadService feedReadService;

	@Override
	public void getFeed(GetFeedRequest req, StreamObserver<GetFeedResponse> obs) {
		try {
			Long cursor = req.getCursor() != 0 ? req.getCursor() : null;
			Integer size = req.getSize() > 0 ? req.getSize() : null;
			Long universityId = req.getUniversityId() > 0 ? req.getUniversityId() : null;
			Long facultyId = req.getFacultyId() > 0 ? req.getFacultyId() : null;
			Long programId = req.getProgramId() > 0 ? req.getProgramId() : null;
			Long topicId = req.getTopicId() > 0 ? req.getTopicId() : null;
			if (programId == null) {
				programId = topicId;
			}

			FeedPageResult result = switch (req.getFeedType()) {
				case FOLLOWING -> {
					String userId = req.getUserId();
					if (userId == null || userId.isBlank()) {
						obs.onError(Status.UNAUTHENTICATED.withDescription("userId required for FOLLOWING feed")
								.asRuntimeException());
						yield null;
					}
					yield feedReadService.getFollowingFeed(userId, cursor, size, universityId, facultyId, programId);
				}
				case TRENDING -> feedReadService.getTrendingFeed(cursor, size, universityId, facultyId, programId);
				default -> feedReadService.getGlobalFeed(cursor, size, universityId, facultyId, programId);
			};

			if (result == null)
				return;

			GetFeedResponse.Builder builder = GetFeedResponse.newBuilder().addAllPostIds(result.postIds())
					.setHasMore(result.hasMore());

			if (result.nextCursor() != null) {
				builder.setNextCursor(result.nextCursor());
			}

			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}
}
