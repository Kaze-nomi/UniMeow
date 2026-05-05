package uni.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import uni.gateway.dto.admin.AdminActionResult;
import uni.gateway.dto.admin.BanUserInput;
import uni.gateway.dto.admin.FacultyProposalDto;
import uni.gateway.dto.admin.FacultyProposalInput;
import uni.gateway.dto.admin.ImprovementSuggestionDto;
import uni.gateway.dto.admin.ProgramProposalDto;
import uni.gateway.dto.admin.ProgramProposalInput;
import uni.gateway.dto.admin.UniversityProposalDto;
import uni.gateway.dto.admin.UniversityProposalInput;
import uni.gateway.dto.post.DeleteResult;
import uni.gateway.dto.user.UserDto;
import uni.gateway.grpc.PostGrpcClient;
import uni.gateway.grpc.UserGrpcClient;

import javax.security.auth.login.CredentialException;
import java.nio.file.AccessDeniedException;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class AdminController {

	private final UserGrpcClient userGrpcClient;
	private final PostGrpcClient postGrpcClient;
	private final UserMapper userMapper;

	@QueryMapping
	public Mono<List<ImprovementSuggestionDto>> adminImprovementSuggestions(
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId)
				.flatMap(
						adminId -> userGrpcClient.listImprovementSuggestions(adminId)
								.map(resp -> resp.getSuggestionsList().stream()
										.map(s -> new ImprovementSuggestionDto(Long.toString(s.getId()),
												s.getAuthorId(), s.getText(), s.getStatus(), s.getCreatedAt()))
										.toList()));
	}

	@QueryMapping
	public Mono<List<UniversityProposalDto>> adminUniversityProposals(
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(adminId -> userGrpcClient.listUniversityProposals(adminId)
				.map(resp -> resp.getProposalsList().stream()
						.map(p -> new UniversityProposalDto(Long.toString(p.getId()), p.getAuthorId(), p.getName(),
								p.getShortName(), p.getSubdomain().isBlank() ? null : p.getSubdomain(),
								p.getStudentDomain(), p.getEmployeeDomain().isBlank() ? null : p.getEmployeeDomain(),
								p.getCity().isBlank() ? null : p.getCity(),
								p.getDescription().isBlank() ? null : p.getDescription(), p.getStatus(),
								p.getCreatedAt(), p.getReviewedBy().isBlank() ? null : p.getReviewedBy(),
								p.getReviewedAt().isBlank() ? null : p.getReviewedAt(),
								p.getIconUrl().isBlank() ? null : p.getIconUrl()))
						.toList()));
	}

	@QueryMapping
	public Mono<List<FacultyProposalDto>> adminFacultyProposals(
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(adminId -> userGrpcClient.listFacultyProposals(adminId)
				.map(resp -> resp.getProposalsList().stream()
						.map(p -> new FacultyProposalDto(Long.toString(p.getId()), p.getAuthorId(),
								Long.toString(p.getUniversityId()), p.getUniversityName(), p.getUniversityShortName(),
								p.getName(), p.getShortName(), p.getStatus(), p.getCreatedAt(),
								p.getReviewedBy().isBlank() ? null : p.getReviewedBy(),
								p.getReviewedAt().isBlank() ? null : p.getReviewedAt()))
						.toList()));
	}

	@QueryMapping
	public Mono<List<ProgramProposalDto>> adminProgramProposals(
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(adminId -> userGrpcClient.listProgramProposals(adminId)
				.map(resp -> resp.getProposalsList().stream()
						.map(p -> new ProgramProposalDto(Long.toString(p.getId()), p.getAuthorId(),
								Long.toString(p.getUniversityId()), p.getUniversityName(), p.getUniversityShortName(),
								Long.toString(p.getFacultyId()), p.getFacultyName(), p.getFacultyShortName(),
								p.getName(), p.getShortName(), p.getStatus(), p.getCreatedAt(),
								p.getReviewedBy().isBlank() ? null : p.getReviewedBy(),
								p.getReviewedAt().isBlank() ? null : p.getReviewedAt()))
						.toList()));
	}

	@MutationMapping
	public Mono<DeleteResult> adminDeleteSuggestion(@Argument(name = "id") String id,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId)
				.flatMap(adminId -> userGrpcClient.deleteImprovementSuggestion(adminId, Long.parseLong(id)))
				.map(DeleteResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> createImprovementSuggestion(@Argument(name = "text") String text,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.createImprovementSuggestion(id, text))
				.map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> createUniversityProposal(@Argument(name = "input") UniversityProposalInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.createUniversityProposal(id, input.name(),
				input.shortName(), input.subdomain(), input.studentDomain(), input.employeeDomain(), input.city(),
				input.description(), input.iconUrl())).map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> createFacultyProposal(@Argument(name = "input") FacultyProposalInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.createFacultyProposal(id,
				Long.parseLong(input.universityId()), input.name(), input.shortName())).map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> createProgramProposal(@Argument(name = "input") ProgramProposalInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.createProgramProposal(id,
				Long.parseLong(input.facultyId()), input.name(), input.shortName())).map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> adminBanUser(@Argument(name = "input") BanUserInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(
				adminId -> userGrpcClient.banUser(adminId, input.targetUserId(), input.bannedUntil(), input.reason()))
				.map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<UserDto> adminGrantAdmin(@Argument(name = "targetUserId") String targetUserId,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireKazenomi(userId).flatMap(adminId -> userGrpcClient.grantAdmin(adminId, targetUserId)
				.then(userGrpcClient.getUserById(targetUserId))).map(userMapper::toDto);
	}

	@MutationMapping
	public Mono<DeleteResult> adminDeletePost(@Argument(name = "postId") String postId,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(adminId -> postGrpcClient.deletePost(postId, adminId, true))
				.map(DeleteResult::new);
	}

	@MutationMapping
	public Mono<DeleteResult> adminDeleteComment(@Argument(name = "commentId") String commentId,
			@ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId).flatMap(adminId -> postGrpcClient.deleteComment(commentId, adminId, true))
				.map(DeleteResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> adminReviewUniversityProposal(@Argument(name = "proposalId") String proposalId,
			@Argument(name = "status") String status, @ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId)
				.flatMap(
						adminId -> userGrpcClient.reviewUniversityProposal(adminId, Long.parseLong(proposalId), status))
				.map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> adminReviewFacultyProposal(@Argument(name = "proposalId") String proposalId,
			@Argument(name = "status") String status, @ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId)
				.flatMap(adminId -> userGrpcClient.reviewFacultyProposal(adminId, Long.parseLong(proposalId), status))
				.map(AdminActionResult::new);
	}

	@MutationMapping
	public Mono<AdminActionResult> adminReviewProgramProposal(@Argument(name = "proposalId") String proposalId,
			@Argument(name = "status") String status, @ContextValue(name = "userId", required = false) String userId) {
		return requireAdmin(userId)
				.flatMap(adminId -> userGrpcClient.reviewProgramProposal(adminId, Long.parseLong(proposalId), status))
				.map(AdminActionResult::new);
	}

	private Mono<String> requireActiveUser(String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.getUserById(userId).flatMap(user -> {
			if (user.getIsBanned()) {
				return Mono.error(new AccessDeniedException("User is banned"));
			}
			return Mono.just(userId);
		});
	}

	private Mono<String> requireAdmin(String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.getUserById(id).flatMap(user -> {
			if (!user.getIsAdmin()) {
				return Mono.error(new AccessDeniedException("Admin privileges required"));
			}
			return Mono.just(id);
		}));
	}

	private Mono<String> requireKazenomi(String userId) {
		return requireActiveUser(userId).flatMap(id -> userGrpcClient.getUserById(id).flatMap(user -> {
			if (!"kazenomi".equalsIgnoreCase(user.getUsername())) {
				return Mono.error(new AccessDeniedException("Only kazenomi can grant admin privileges"));
			}
			return Mono.just(id);
		}));
	}
}
