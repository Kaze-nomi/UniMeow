package uni.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import uni.gateway.dto.post.CommentDto;
import uni.gateway.dto.post.CommentPageDto;
import uni.gateway.dto.post.CreatePostInput;
import uni.gateway.dto.post.DeleteResult;
import uni.gateway.dto.post.EditPostInput;
import uni.gateway.dto.post.FeedPageDto;
import uni.gateway.dto.post.LikeResult;
import uni.gateway.dto.post.PostDto;
import uni.gateway.dto.post.PostPageDto;
import uni.gateway.dto.user.EducationLevelDto;
import uni.gateway.dto.user.FacultyDto;
import uni.gateway.dto.user.UniversityDto;
import uni.gateway.dto.user.UserDto;
import uni.gateway.grpc.FeedGrpcClient;
import uni.gateway.grpc.PostGrpcClient;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.feed.FeedType;
import uni.grpc.feed.GetFeedResponse;
import uni.grpc.post.CommentListResponse;
import uni.grpc.post.CommentResponse;
import uni.grpc.post.PostListResponse;
import uni.grpc.post.PostResponse;
import uni.grpc.user.EducationLevel;
import uni.grpc.user.UserResponse;

import javax.security.auth.login.CredentialException;
import java.nio.file.AccessDeniedException;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class PostController {

	private final FeedGrpcClient feedGrpcClient;
	private final PostGrpcClient postGrpcClient;
	private final UserGrpcClient userGrpcClient;

	@QueryMapping
	public Mono<PostDto> getPost(@Argument(name = "id") String id,
			@ContextValue(name = "userId", required = false) String userId) {
		return postGrpcClient.getPostById(id, userId).map(this::toDto);
	}

	@SchemaMapping(typeName = "Post", field = "author")
	public Mono<UserDto> author(PostDto post) {
		return userGrpcClient.getUserById(post.authorId()).map(this::toUserDto);
	}

	@QueryMapping
	public Mono<PostPageDto> getUserPosts(@Argument(name = "userId") String userId,
			@Argument(name = "page") Integer page, @Argument(name = "size") Integer size,
			@ContextValue(name = "userId", required = false) String currentUserId) {
		int p = page != null ? page : 0;
		int s = size != null ? size : 20;
		return postGrpcClient.getPostsByUser(userId, currentUserId, p, s).map(this::toPageDto);
	}

	@QueryMapping
	public Mono<CommentPageDto> getComments(@Argument(name = "postId") String postId,
			@Argument(name = "page") Integer page, @Argument(name = "size") Integer size,
			@ContextValue(name = "userId", required = false) String currentUserId) {
		int p = page != null ? page : 0;
		int s = size != null ? size : 20;
		return postGrpcClient.getComments(postId, p, s, currentUserId).map(this::toCommentPageDto);
	}

	@QueryMapping
	public Mono<FeedPageDto> followingFeed(@Argument(name = "cursor") String cursor,
			@Argument(name = "size") Integer size, @Argument(name = "universityId") Long universityId,
			@Argument(name = "facultyId") Long facultyId, @Argument(name = "programId") Long programId,
			@Argument(name = "topicId") Long topicId,
			@ContextValue(name = "userId", required = false) String currentUserId) {
		if (currentUserId == null && (universityId == null || universityId <= 0)) {
			return Mono.error(new CredentialException("Authentication required for following feed"));
		}
		return fetchFeed(FeedType.FOLLOWING, currentUserId, cursor, size, universityId, facultyId, programId, topicId);
	}

	@QueryMapping
	public Mono<FeedPageDto> trendingFeed(@Argument(name = "cursor") String cursor,
			@Argument(name = "size") Integer size, @Argument(name = "universityId") Long universityId,
			@Argument(name = "facultyId") Long facultyId, @Argument(name = "programId") Long programId,
			@Argument(name = "topicId") Long topicId,
			@ContextValue(name = "userId", required = false) String currentUserId) {
		return fetchFeed(FeedType.TRENDING, currentUserId, cursor, size, universityId, facultyId, programId, topicId);
	}

	private Mono<FeedPageDto> fetchFeed(FeedType type, String userId, String cursor, Integer size, Long universityId,
			Long facultyId, Long programId, Long topicId) {
		int s = size != null ? size : 20;
		Long cursorLong = parseCursor(cursor);
		Long resolvedProgramId = programId != null ? programId : topicId;
		Mono<GetFeedResponse> feedMono = universityId != null && universityId > 0
				? feedGrpcClient.getUniversityFeed(type, userId, cursorLong, s, universityId, facultyId,
						resolvedProgramId)
				: feedGrpcClient.getFeed(type, userId, cursorLong, s, topicId);
		return feedMono.flatMap(feed -> hydrateFeed(feed, userId));
	}

	@MutationMapping
	public Mono<PostDto> createPost(@Argument(name = "input") CreatePostInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUserResponse(userId).flatMap(user -> {
			Long universityId = user.hasUniversity() ? user.getUniversity().getId() : null;
			Long facultyId = user.hasFaculty() ? user.getFaculty().getId() : null;
			Long programId = user.hasProgram() ? user.getProgram().getId() : null;
			return postGrpcClient.createPost(userId, input.content(), input.mediaUrls(), universityId, facultyId,
					programId, null, null);
		}).map(this::toDto);
	}

	@MutationMapping
	public Mono<PostDto> editPost(@Argument(name = "postId") String postId,
			@Argument(name = "input") EditPostInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		boolean updateMediaUrls = Boolean.TRUE.equals(input.updateMediaUrls());
		return requireActiveUser(userId)
				.flatMap(id -> postGrpcClient.editPost(postId, id, input.content(), updateMediaUrls, input.mediaUrls()))
				.map(this::toDto);
	}

	@MutationMapping
	public Mono<DeleteResult> deletePost(@Argument(name = "postId") String postId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.deletePost(postId, id)).map(DeleteResult::new);
	}

	@MutationMapping
	public Mono<LikeResult> likePost(@Argument(name = "postId") String postId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.likePost(postId, id)).map(LikeResult::new);
	}

	@MutationMapping
	public Mono<LikeResult> unlikePost(@Argument(name = "postId") String postId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.unlikePost(postId, id)).map(LikeResult::new);
	}

	@MutationMapping
	public Mono<CommentDto> addComment(@Argument(name = "postId") String postId,
			@Argument(name = "content") String content, @Argument(name = "parentCommentId") String parentCommentId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.addComment(postId, id, content, parentCommentId))
				.map(this::toCommentDto);
	}

	@MutationMapping
	public Mono<CommentDto> editComment(@Argument(name = "commentId") String commentId,
			@Argument(name = "content") String content,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.editComment(commentId, id, content))
				.map(this::toCommentDto);
	}

	@MutationMapping
	public Mono<DeleteResult> deleteComment(@Argument(name = "commentId") String commentId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.deleteComment(commentId, id))
				.map(DeleteResult::new);
	}

	@MutationMapping
	public Mono<LikeResult> likeComment(@Argument(name = "commentId") String commentId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.likeComment(commentId, id)).map(LikeResult::new);
	}

	@MutationMapping
	public Mono<LikeResult> unlikeComment(@Argument(name = "commentId") String commentId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(id -> postGrpcClient.unlikeComment(commentId, id))
				.map(LikeResult::new);
	}

	private Mono<FeedPageDto> hydrateFeed(GetFeedResponse feed, String viewerId) {
		List<String> postIds = feed.getPostIdsList();
		String nextCursor = feed.getNextCursor() != 0 ? String.valueOf(feed.getNextCursor()) : null;

		if (postIds.isEmpty()) {
			return Mono.just(new FeedPageDto(List.of(), null, false));
		}

		return postGrpcClient.getPostsByIds(postIds, viewerId).map(posts -> {
			List<PostDto> dto = posts.getPostsList().stream().map(this::toDto).toList();
			return new FeedPageDto(dto, nextCursor, feed.getHasMore());
		});
	}

	private Mono<String> requireActiveUser(String userId) {
		return requireActiveUserResponse(userId).thenReturn(userId);
	}

	private Mono<UserResponse> requireActiveUserResponse(String userId) {
		Mono<UserResponse> userMono = userGrpcClient.getUserById(userId);
		if (userMono == null) {
			return Mono.empty();
		}
		return userMono.flatMap(user -> {
			if (user.getIsBanned()) {
				return Mono.error(new AccessDeniedException("User is banned"));
			}
			return Mono.just(user);
		});
	}

	private PostDto toDto(PostResponse r) {
		return PostDto.builder().id(r.getId()).authorId(r.getAuthorId()).content(r.getContent())
				.mediaUrls(r.getMediaUrlsList()).likesCount(r.getLikesCount()).commentsCount(r.getCommentsCount())
				.likedByMe(r.getLikedByMe()).createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
				.universityId(r.hasUniversityId() ? r.getUniversityId() : null)
				.facultyId(r.hasFacultyId() ? r.getFacultyId() : null)
				.programId(r.hasProgramId() ? r.getProgramId() : null).topicId(r.hasTopicId() ? r.getTopicId() : null)
				.build();
	}

	private PostPageDto toPageDto(PostListResponse r) {
		return new PostPageDto(r.getPostsList().stream().map(this::toDto).toList(), r.getTotal());
	}

	private CommentDto toCommentDto(CommentResponse r) {
		return new CommentDto(r.getId(), r.getPostId(), r.getAuthorId(), r.getContent(), r.getLikedByMe(),
				r.getLikesCount(), r.getCreatedAt(), r.getUpdatedAt(),
				r.hasParentCommentId() ? r.getParentCommentId() : null);
	}

	private CommentPageDto toCommentPageDto(CommentListResponse r) {
		return new CommentPageDto(r.getCommentsList().stream().map(this::toCommentDto).toList(), r.getTotal());
	}

	private UserDto toUserDto(UserResponse r) {
		return UserDto.builder().id(r.getId()).emailGoogle(r.getEmailGoogle()).username(r.getUsername())
				.name(r.getName()).surname(r.getSurname().isEmpty() ? null : r.getSurname())
				.emailUniversity(r.getEmailUniversity().isEmpty() ? null : r.getEmailUniversity())
				.avatarUrl(r.getAvatarUrl().isEmpty() ? null : r.getAvatarUrl())
				.coverUrl(r.hasCoverUrl() ? r.getCoverUrl() : null).status(
						r.getStatus().isEmpty() ? null : r.getStatus())
				.bio(r.hasBio() ? r.getBio() : null).isStudentVerified(
						r.getIsStudentVerified())
				.isEmployeeVerified(r.getIsEmployeeVerified()).createdAt(r.getCreatedAt())
				.university(r.hasUniversity()
						? UniversityDto.builder().id(Long.toString(r.getUniversity().getId()))
								.name(r.getUniversity().getName()).shortName(r.getUniversity().getShortName())
								.iconUrl(r.getUniversity().getIconUrl().isEmpty()
										? null
										: r.getUniversity().getIconUrl())
								.build()
						: null)
				.faculty(r.hasFaculty()
						? FacultyDto.builder().id(Long.toString(r.getFaculty().getId())).name(r.getFaculty().getName())
								.shortName(r.getFaculty().getShortName()).build()
						: null)
				.course(r.hasCourse() ? r.getCourse() : null)
				.educationLevel(r.hasEducationLevel() ? mapEducationLevelToDto(r.getEducationLevel()) : null)
				.graduationYear(r.hasGraduationYear() ? r.getGraduationYear() : null).isAdmin(r.getIsAdmin())
				.isBanned(r.getIsBanned()).bannedUntil(r.hasBannedUntil() ? r.getBannedUntil() : null)
				.banReason(r.hasBanReason() ? r.getBanReason() : null).build();
	}

	private static EducationLevelDto mapEducationLevelToDto(EducationLevel level) {
		return switch (level) {
			case BACHELOR -> EducationLevelDto.BACHELOR;
			case MASTER -> EducationLevelDto.MASTER;
			case PHD -> EducationLevelDto.PHD;
			case SPECIALIST -> EducationLevelDto.SPECIALIST;
			default -> null;
		};
	}

	private static Long parseCursor(String cursor) {
		if (cursor == null || cursor.isBlank())
			return null;
		try {
			return Long.parseLong(cursor);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
