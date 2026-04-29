package uni.post.grpc;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import uni.grpc.user.UserServiceGrpc;
import uni.grpc.user.GetGeneralTopicRequest;
import uni.grpc.user.ResolvePostTargetRequest;
import uni.grpc.user.ResolvePostTargetResponse;
import uni.grpc.user.ValidateTopicRequest;
import uni.grpc.user.ValidateTopicResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserGrpcClient {

	@GrpcClient("user")
	private UserServiceGrpc.UserServiceBlockingStub userStub;

	public ValidateResult validateTopicForUniversity(Long universityId, Long topicId) {
		try {
			ValidateTopicResponse resp = userStub.validateTopicForUniversity(
					ValidateTopicRequest.newBuilder().setUniversityId(universityId).setTopicId(topicId).build());
			return new ValidateResult(resp.getSuccess(), resp.hasParentTopicId() ? resp.getParentTopicId() : null);
		} catch (StatusRuntimeException e) {
			log.warn("UserService validation failed: {}", e.getStatus().getDescription());
			throw new IllegalStateException("Topic validation failed", e);
		}
	}

	public Long getUserUniversityId(String userId) {
		try {
			uni.grpc.user.UserResponse resp = userStub
					.getUserById(uni.grpc.user.GetUserByIdRequest.newBuilder().setId(userId).build());
			return resp.hasUniversity() ? resp.getUniversity().getId() : null;
		} catch (io.grpc.StatusRuntimeException e) {
			log.warn("Failed to get user university for {}: {}", userId, e.getStatus().getDescription());
			return null;
		}
	}

	public PostTarget resolvePostTarget(String authorId, Long topicId) {
		try {
			ResolvePostTargetRequest.Builder builder = ResolvePostTargetRequest.newBuilder().setAuthorId(authorId);
			if (topicId != null) {
				builder.setTopicId(topicId);
			}
			ResolvePostTargetResponse resp = userStub.resolvePostTarget(builder.build());
			return new PostTarget(resp.getSuccess(), resp.hasUniversityId() ? resp.getUniversityId() : null,
					resp.hasFacultyId() ? resp.getFacultyId() : null, resp.hasProgramId() ? resp.getProgramId() : null,
					resp.hasTopicId() ? resp.getTopicId() : null,
					resp.hasParentTopicId() ? resp.getParentTopicId() : null, resp.getError());
		} catch (StatusRuntimeException e) {
			log.warn("Failed to resolve post target for {}: {}", authorId, e.getStatus().getDescription());
			throw new IllegalStateException("Post target resolution failed", e);
		}
	}

	public Long getGeneralTopicId(Long universityId) {
		try {
			return userStub.getGeneralTopicForUniversity(
					GetGeneralTopicRequest.newBuilder().setUniversityId(universityId).build()).getTopicId();
		} catch (StatusRuntimeException e) {
			log.warn("Failed to get general topic for university {}: {}", universityId, e.getStatus().getDescription());
			throw new IllegalStateException("Failed to get general topic", e);
		}
	}

	public record ValidateResult(boolean valid, Long parentTopicId) {
	}

	public record PostTarget(boolean success, Long universityId, Long facultyId, Long programId, Long topicId,
			Long parentTopicId, String error) {
	}
}
