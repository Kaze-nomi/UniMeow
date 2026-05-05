package uni.gateway.grpc;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import uni.grpc.user.CreateOrGetUserRequest;
import uni.grpc.user.DeleteAccountRequest;
import uni.grpc.user.GetUserByIdRequest;
import uni.grpc.user.GetUserByUsernameRequest;
import uni.grpc.user.UpdateUserRequest;
import uni.grpc.user.UserResponse;
import uni.grpc.user.UserServiceGrpc;
import uni.grpc.user.VerifyEmailCodeRequest;
import uni.grpc.user.VerifyEmailCodeResponse;
import uni.grpc.user.CreateSessionRequest;
import uni.grpc.user.RefreshSessionRequest;
import uni.grpc.user.RefreshSessionResponse;
import uni.grpc.user.RevokeRefreshTokenRequest;
import uni.grpc.user.RevokeRefreshTokenResponse;
import uni.grpc.user.SendVerificationCodeRequest;
import uni.grpc.user.SendVerificationCodeResponse;
import uni.grpc.user.SubscribeRequest;
import uni.grpc.user.SubscribeResponse;
import uni.grpc.user.UnsubscribeRequest;
import uni.grpc.user.UnsubscribeResponse;
import uni.grpc.user.IsSubscribedRequest;
import uni.grpc.user.IsSubscribedResponse;
import uni.grpc.user.ListUniversitiesRequest;
import uni.grpc.user.UniversityListResponse;
import uni.grpc.user.ListFacultiesRequest;
import uni.grpc.user.FacultyListResponse;
import uni.grpc.user.ListProgramsRequest;
import uni.grpc.user.ProgramListResponse;
import uni.grpc.user.DeleteImprovementSuggestionRequest;
import uni.grpc.user.CreateProgramForUserRequest;
import uni.grpc.user.CreateProgramResponse;
import uni.grpc.user.BanUserRequest;
import uni.grpc.user.GrantAdminRequest;
import uni.grpc.user.CreateImprovementSuggestionRequest;
import uni.grpc.user.CreateFacultyProposalRequest;
import uni.grpc.user.ListFacultyProposalsRequest;
import uni.grpc.user.FacultyProposalListResponse;
import uni.grpc.user.ReviewFacultyProposalRequest;
import uni.grpc.user.CreateProgramProposalRequest;
import uni.grpc.user.ListProgramProposalsRequest;
import uni.grpc.user.ProgramProposalListResponse;
import uni.grpc.user.ReviewProgramProposalRequest;
import uni.grpc.user.ListImprovementSuggestionsRequest;
import uni.grpc.user.ImprovementSuggestionListResponse;
import uni.grpc.user.CreateUniversityProposalRequest;
import uni.grpc.user.ListUniversityProposalsRequest;
import uni.grpc.user.UniversityProposalListResponse;
import uni.grpc.user.ReviewUniversityProposalRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class UserGrpcClient {

	@GrpcClient("user-service")
	private UserServiceGrpc.UserServiceBlockingStub stub;

	public Mono<UserResponse> createOrGetUser(CreateOrGetUserRequest request) {
		return Mono.fromCallable(() -> stub.createOrGetUser(request)).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<UserResponse> getUserById(String id) {
		return Mono.fromCallable(() -> stub.getUserById(GetUserByIdRequest.newBuilder().setId(id).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<UserResponse> getUserByUsername(String username) {
		return Mono.fromCallable(
				() -> stub.getUserByUsername(GetUserByUsernameRequest.newBuilder().setUsername(username).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<UserResponse> updateUser(UpdateUserRequest request) {
		return Mono.fromCallable(() -> stub.updateUser(request)).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> deleteAccount(String userId) {
		return Mono.fromCallable(
				() -> stub.deleteAccount(DeleteAccountRequest.newBuilder().setUserId(userId).build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Void> createSession(String userId, String refreshToken) {
		return Mono
				.fromRunnable(() -> stub.createSession(CreateSessionRequest.newBuilder().setUserId(userId)
						.setRefreshToken(refreshToken).setExpiresInDays(30).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2).then();
	}

	public Mono<RefreshSessionResponse> refreshSession(String oldRefreshToken) {
		return Mono
				.fromCallable(() -> stub
						.refreshSession(RefreshSessionRequest.newBuilder().setOldRefreshToken(oldRefreshToken).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> revokeRefreshToken(String refreshToken) {
		return Mono.fromCallable(() -> {
			RevokeRefreshTokenResponse response = stub
					.revokeRefreshToken(RevokeRefreshTokenRequest.newBuilder().setRefreshToken(refreshToken).build());
			return response.getSuccess();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> sendVerificationCode(String userId, String universityEmail) {
		return Mono.fromCallable(() -> {
			SendVerificationCodeResponse response = stub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
					.setUserId(userId).setUniversityEmail(universityEmail).build());
			return response.getSuccess();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<VerifyEmailCodeResponse> verifyEmailCode(String userId, String code) {
		return Mono.fromCallable(
				() -> stub.verifyEmailCode(VerifyEmailCodeRequest.newBuilder().setUserId(userId).setCode(code).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> subscribe(String subscriberId, String targetUserId) {
		return Mono.fromCallable(() -> {
			SubscribeResponse response = stub.subscribe(
					SubscribeRequest.newBuilder().setSubscriberId(subscriberId).setTargetUserId(targetUserId).build());
			return response.getSuccess();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> unsubscribe(String subscriberId, String targetUserId) {
		return Mono.fromCallable(() -> {
			UnsubscribeResponse response = stub.unsubscribe(UnsubscribeRequest.newBuilder()
					.setSubscriberId(subscriberId).setTargetUserId(targetUserId).build());
			return response.getSuccess();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> isSubscribed(String subscriberId, String targetUserId) {
		return Mono.fromCallable(() -> {
			IsSubscribedResponse response = stub.isSubscribed(IsSubscribedRequest.newBuilder()
					.setSubscriberId(subscriberId).setTargetUserId(targetUserId).build());
			return response.getSubscribed();
		}).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<UniversityListResponse> listUniversities() {
		return Mono.fromCallable(() -> stub.listUniversities(ListUniversitiesRequest.newBuilder().build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<FacultyListResponse> listFaculties(long universityId) {
		return Mono.fromCallable(
				() -> stub.listFaculties(ListFacultiesRequest.newBuilder().setUniversityId(universityId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<ProgramListResponse> listPrograms(long facultyId) {
		return Mono
				.fromCallable(() -> stub.listPrograms(ListProgramsRequest.newBuilder().setFacultyId(facultyId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> banUser(String moderatorId, String targetUserId, String bannedUntil, String reason) {
		BanUserRequest.Builder builder = BanUserRequest.newBuilder().setModeratorId(moderatorId)
				.setTargetUserId(targetUserId);
		if (bannedUntil != null && !bannedUntil.isBlank())
			builder.setBannedUntil(bannedUntil);
		if (reason != null && !reason.isBlank())
			builder.setReason(reason);
		return Mono.fromCallable(() -> stub.banUser(builder.build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> deleteImprovementSuggestion(String adminId, long id) {
		return Mono.fromCallable(() -> stub
				.deleteImprovementSuggestion(
						DeleteImprovementSuggestionRequest.newBuilder().setAdminId(adminId).setId(id).build())
				.getSuccess()).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> createImprovementSuggestion(String authorId, String text) {
		return Mono.fromCallable(() -> stub
				.createImprovementSuggestion(
						CreateImprovementSuggestionRequest.newBuilder().setAuthorId(authorId).setText(text).build())
				.getSuccess()).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<ImprovementSuggestionListResponse> listImprovementSuggestions(String adminId) {
		return Mono
				.fromCallable(() -> stub.listImprovementSuggestions(
						ListImprovementSuggestionsRequest.newBuilder().setAdminId(adminId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> createUniversityProposal(String authorId, String name, String shortName, String subdomain,
			String studentDomain, String employeeDomain, String city, String description, String iconUrl) {
		return Mono
				.fromCallable(() -> stub.createUniversityProposal(CreateUniversityProposalRequest.newBuilder()
						.setAuthorId(authorId).setName(name).setShortName(shortName)
						.setSubdomain(subdomain == null ? "" : subdomain).setStudentDomain(studentDomain)
						.setEmployeeDomain(employeeDomain == null ? "" : employeeDomain)
						.setCity(city == null ? "" : city).setDescription(description == null ? "" : description)
						.setIconUrl(iconUrl == null ? "" : iconUrl).build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<UniversityProposalListResponse> listUniversityProposals(String adminId) {
		return Mono
				.fromCallable(() -> stub.listUniversityProposals(
						ListUniversityProposalsRequest.newBuilder().setAdminId(adminId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> createFacultyProposal(String authorId, long universityId, String name, String shortName) {
		return Mono
				.fromCallable(() -> stub
						.createFacultyProposal(CreateFacultyProposalRequest.newBuilder().setAuthorId(authorId)
								.setUniversityId(universityId).setName(name).setShortName(shortName).build())
						.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<FacultyProposalListResponse> listFacultyProposals(String adminId) {
		return Mono.fromCallable(
				() -> stub.listFacultyProposals(ListFacultyProposalsRequest.newBuilder().setAdminId(adminId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> reviewFacultyProposal(String reviewerId, long proposalId, String status) {
		return Mono
				.fromCallable(
						() -> stub
								.reviewFacultyProposal(ReviewFacultyProposalRequest.newBuilder()
										.setReviewerId(reviewerId).setProposalId(proposalId).setStatus(status).build())
								.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> createProgramProposal(String authorId, long facultyId, String name, String shortName) {
		return Mono
				.fromCallable(
						() -> stub
								.createProgramProposal(CreateProgramProposalRequest.newBuilder().setAuthorId(authorId)
										.setFacultyId(facultyId).setName(name).setShortName(shortName).build())
								.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<ProgramProposalListResponse> listProgramProposals(String adminId) {
		return Mono.fromCallable(
				() -> stub.listProgramProposals(ListProgramProposalsRequest.newBuilder().setAdminId(adminId).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> reviewProgramProposal(String reviewerId, long proposalId, String status) {
		return Mono
				.fromCallable(
						() -> stub
								.reviewProgramProposal(ReviewProgramProposalRequest.newBuilder()
										.setReviewerId(reviewerId).setProposalId(proposalId).setStatus(status).build())
								.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> reviewUniversityProposal(String reviewerId, long proposalId, String status) {
		return Mono
				.fromCallable(
						() -> stub
								.reviewUniversityProposal(ReviewUniversityProposalRequest.newBuilder()
										.setReviewerId(reviewerId).setProposalId(proposalId).setStatus(status).build())
								.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<CreateProgramResponse> createProgramForUser(String userId, long facultyId, String name,
			String shortName) {
		return Mono
				.fromCallable(() -> stub.createProgramForUser(CreateProgramForUserRequest.newBuilder().setUserId(userId)
						.setFacultyId(facultyId).setName(name).setShortName(shortName).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> grantAdmin(String granterId, String targetUserId) {
		return Mono.fromCallable(() -> stub
				.grantAdmin(
						GrantAdminRequest.newBuilder().setGranterId(granterId).setTargetUserId(targetUserId).build())
				.getSuccess()).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}
}
