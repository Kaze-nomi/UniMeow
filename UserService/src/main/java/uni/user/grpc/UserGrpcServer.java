package uni.user.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.grpc.user.*;
import uni.user.entity.User;
import uni.user.exception.*;
import uni.user.service.*;

import java.util.UUID;
import java.time.LocalDateTime;

@GrpcService
@RequiredArgsConstructor
public class UserGrpcServer extends UserServiceGrpc.UserServiceImplBase {

	private final UserService userService;
	private final SessionService sessionService;
	private final VerificationService verificationService;
	private final UniversityService universityService;
	private final AdministrationService administrationService;

	@Override
	public void createOrGetUser(CreateOrGetUserRequest req, StreamObserver<UserResponse> obs) {
		try {
			User user = userService.createOrGet(req.getEmailGoogle(), req.getName(), req.getSurname(),
					req.getAvatarUrl());
			obs.onNext(toProto(user));
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getUserById(GetUserByIdRequest req, StreamObserver<UserResponse> obs) {
		try {
			User user = userService.getById(UUID.fromString(req.getId()));
			obs.onNext(toProto(user));
			obs.onCompleted();
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		}
	}

	@Override
	public void getUserByUsername(GetUserByUsernameRequest req, StreamObserver<UserResponse> obs) {
		try {
			User user = userService.getByUsername(req.getUsername());
			obs.onNext(toProto(user));
			obs.onCompleted();
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void updateUser(UpdateUserRequest req, StreamObserver<UserResponse> obs) {
		try {
			Long facultyId = (req.hasFacultyId() && req.getFacultyId() != 0L) ? req.getFacultyId() : null;
			Long programId = (req.hasProgramId() && req.getProgramId() != 0L) ? req.getProgramId() : null;
			User user = userService.update(UUID.fromString(req.getId()), req.hasUsername(), req.getUsername(),
					req.hasName(), req.getName(), req.hasSurname(), req.getSurname(), req.hasStatus(), req.getStatus(),
					req.hasAvatarUrl(), req.getAvatarUrl(), req.hasFacultyId(), facultyId, req.hasCourse(),
					req.hasCourse() ? req.getCourse() : null, req.hasEducationLevel(),
					req.hasEducationLevel() ? mapEducationLevelFromProto(req.getEducationLevel()) : null,
					req.hasGraduationYear(), req.hasGraduationYear() ? req.getGraduationYear() : null, req.hasBio(),
					req.getBio(), req.hasCoverUrl(), req.hasCoverUrl() ? req.getCoverUrl() : null, req.hasProgramId(),
					programId);
			obs.onNext(toProto(user));
			obs.onCompleted();
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (UsernameAlreadyTakenException e) {
			obs.onError(Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void deleteAccount(DeleteAccountRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			userService.deleteAccount(UUID.fromString(req.getUserId()));
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createSession(CreateSessionRequest req, StreamObserver<CreateSessionResponse> obs) {
		try {
			sessionService.createSession(UUID.fromString(req.getUserId()), req.getRefreshToken(),
					req.getExpiresInDays());
			obs.onNext(CreateSessionResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void refreshSession(RefreshSessionRequest req, StreamObserver<RefreshSessionResponse> obs) {
		try {
			String[] result = sessionService.rotateRefreshToken(req.getOldRefreshToken());
			obs.onNext(RefreshSessionResponse.newBuilder().setUserId(result[0]).setNewRefreshToken(result[1]).build());
			obs.onCompleted();
		} catch (SessionExpiredException e) {
			obs.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void revokeRefreshToken(RevokeRefreshTokenRequest req, StreamObserver<RevokeRefreshTokenResponse> obs) {
		try {
			sessionService.revokeToken(req.getRefreshToken());
			obs.onNext(RevokeRefreshTokenResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void sendVerificationCode(SendVerificationCodeRequest req,
			StreamObserver<SendVerificationCodeResponse> obs) {
		try {
			verificationService.sendCode(UUID.fromString(req.getUserId()), req.getUniversityEmail());
			obs.onNext(SendVerificationCodeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (UnknownDomainException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (EmailAlreadyUsedException e) {
			obs.onError(Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (MailDeliveryException e) {
			obs.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void verifyEmailCode(VerifyEmailCodeRequest req, StreamObserver<VerifyEmailCodeResponse> obs) {
		try {
			String result = verificationService.verify(UUID.fromString(req.getUserId()), req.getCode());
			VerifyEmailCodeResponse.Builder response = VerifyEmailCodeResponse.newBuilder();
			if ("SUCCESS".equals(result)) {
				response.setSuccess(true);
			} else {
				response.setSuccess(false).setError(result);
			}
			obs.onNext(response.build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void subscribe(SubscribeRequest req, StreamObserver<SubscribeResponse> obs) {
		try {
			userService.subscribe(UUID.fromString(req.getSubscriberId()), UUID.fromString(req.getTargetUserId()));
			obs.onNext(SubscribeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (UserNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void unsubscribe(UnsubscribeRequest req, StreamObserver<UnsubscribeResponse> obs) {
		try {
			userService.unsubscribe(UUID.fromString(req.getSubscriberId()), UUID.fromString(req.getTargetUserId()));
			obs.onNext(UnsubscribeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void isSubscribed(IsSubscribedRequest req, StreamObserver<IsSubscribedResponse> obs) {
		try {
			boolean subscribed = userService.isSubscribed(UUID.fromString(req.getSubscriberId()),
					UUID.fromString(req.getTargetUserId()));
			obs.onNext(IsSubscribedResponse.newBuilder().setSubscribed(subscribed).build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void validateTopicForUniversity(ValidateTopicRequest req, StreamObserver<ValidateTopicResponse> obs) {
		try {
			var result = universityService.validateTopicForUniversity(req.getUniversityId(), req.getTopicId());
			ValidateTopicResponse.Builder builder = ValidateTopicResponse.newBuilder().setSuccess(result.success());
			if (result.parentTopicId() != null) {
				builder.setParentTopicId(result.parentTopicId());
			}
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getGeneralTopicForUniversity(GetGeneralTopicRequest req, StreamObserver<GetGeneralTopicResponse> obs) {
		try {
			Long topicId = universityService.getGeneralTopicId(req.getUniversityId());
			obs.onNext(GetGeneralTopicResponse.newBuilder().setTopicId(topicId).build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void resolvePostTarget(ResolvePostTargetRequest req, StreamObserver<ResolvePostTargetResponse> obs) {
		try {
			var result = universityService.resolvePostTarget(req.getAuthorId(),
					req.hasTopicId() ? req.getTopicId() : null);
			ResolvePostTargetResponse.Builder builder = ResolvePostTargetResponse.newBuilder()
					.setSuccess(result.success()).setError(result.error());
			if (result.success()) {
				if (result.universityId() != null) {
					builder.setUniversityId(result.universityId());
				}
				if (result.topicId() != null) {
					builder.setTopicId(result.topicId());
				}
				if (result.parentTopicId() != null) {
					builder.setParentTopicId(result.parentTopicId());
				}
				if (result.facultyId() != null) {
					builder.setFacultyId(result.facultyId());
				}
				if (result.programId() != null) {
					builder.setProgramId(result.programId());
				}
			}
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listUniversities(ListUniversitiesRequest req, StreamObserver<UniversityListResponse> obs) {
		try {
			UniversityListResponse.Builder builder = UniversityListResponse.newBuilder();
			universityService.listUniversities().forEach(u -> builder.addUniversities(toUniversityProto(u)));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listFaculties(ListFacultiesRequest req, StreamObserver<FacultyListResponse> obs) {
		try {
			FacultyListResponse.Builder builder = FacultyListResponse.newBuilder();
			universityService.listFaculties(req.getUniversityId())
					.forEach(f -> builder.addFaculties(toFacultyProto(f)));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listPrograms(ListProgramsRequest req, StreamObserver<ProgramListResponse> obs) {
		try {
			ProgramListResponse.Builder builder = ProgramListResponse.newBuilder();
			universityService.listPrograms(req.getFacultyId()).forEach(p -> builder.addPrograms(toProgramProto(p)));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void grantAdmin(GrantAdminRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			userService.grantAdmin(UUID.fromString(req.getGranterId()), UUID.fromString(req.getTargetUserId()));
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void banUser(BanUserRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			LocalDateTime bannedUntil = req.hasBannedUntil() && !req.getBannedUntil().isBlank()
					? LocalDateTime.parse(req.getBannedUntil())
					: null;
			userService.banUser(UUID.fromString(req.getModeratorId()), UUID.fromString(req.getTargetUserId()),
					bannedUntil, req.hasReason() ? req.getReason() : null);
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createImprovementSuggestion(CreateImprovementSuggestionRequest req,
			StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.createSuggestion(UUID.fromString(req.getAuthorId()), req.getText(),
					req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listImprovementSuggestions(ListImprovementSuggestionsRequest req,
			StreamObserver<ImprovementSuggestionListResponse> obs) {
		try {
			ImprovementSuggestionListResponse.Builder builder = ImprovementSuggestionListResponse.newBuilder();
			administrationService.listSuggestions(UUID.fromString(req.getAdminId()))
					.forEach(s -> builder.addSuggestions(ImprovementSuggestion.newBuilder().setId(s.getId())
							.setAuthorId(s.getAuthorId().toString()).setText(s.getText()).setStatus(s.getStatus())
							.setCreatedAt(s.getCreatedAt().toString()).build()));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void deleteImprovementSuggestion(DeleteImprovementSuggestionRequest req,
			StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.deleteSuggestion(UUID.fromString(req.getAdminId()), req.getId());
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createUniversityProposal(CreateUniversityProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.createUniversityProposal(UUID.fromString(req.getAuthorId()), req.getName(),
					req.getShortName(), req.getSubdomain(), req.getStudentDomain(), req.getEmployeeDomain(),
					req.getCity(), req.getDescription(), req.getIconUrl(),
					req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listUniversityProposals(ListUniversityProposalsRequest req,
			StreamObserver<UniversityProposalListResponse> obs) {
		try {
			UniversityProposalListResponse.Builder builder = UniversityProposalListResponse.newBuilder();
			administrationService.listUniversityProposals(UUID.fromString(req.getAdminId()))
					.forEach(p -> builder.addProposals(UniversityProposal.newBuilder().setId(p.getId())
							.setAuthorId(p.getAuthorId().toString()).setName(p.getName()).setShortName(p.getShortName())
							.setSubdomain(p.getSubdomain()).setStudentDomain(p.getStudentDomain())
							.setEmployeeDomain(p.getEmployeeDomain()).setCity(p.getCity() != null ? p.getCity() : "")
							.setDescription(p.getDescription() != null ? p.getDescription() : "")
							.setStatus(p.getStatus()).setCreatedAt(p.getCreatedAt().toString())
							.setReviewedBy(p.getReviewedBy() != null ? p.getReviewedBy().toString() : "")
							.setReviewedAt(p.getReviewedAt() != null ? p.getReviewedAt().toString() : "")
							.setIconUrl(p.getIconUrl() != null ? p.getIconUrl() : "").build()));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void reviewUniversityProposal(ReviewUniversityProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.reviewUniversityProposal(UUID.fromString(req.getReviewerId()), req.getProposalId(),
					req.getStatus());
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createFacultyProposal(CreateFacultyProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.createFacultyProposal(UUID.fromString(req.getAuthorId()), req.getUniversityId(),
					req.getName(), req.getShortName(), req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listFacultyProposals(ListFacultyProposalsRequest req, StreamObserver<FacultyProposalListResponse> obs) {
		try {
			FacultyProposalListResponse.Builder builder = FacultyProposalListResponse.newBuilder();
			administrationService.listFacultyProposals(UUID.fromString(req.getAdminId()))
					.forEach(p -> builder.addProposals(FacultyProposal.newBuilder().setId(p.getId())
							.setAuthorId(p.getAuthorId().toString()).setUniversityId(p.getUniversity().getId())
							.setUniversityName(p.getUniversity().getName())
							.setUniversityShortName(p.getUniversity().getShortName()).setName(p.getName())
							.setShortName(p.getShortName()).setStatus(p.getStatus())
							.setCreatedAt(p.getCreatedAt().toString())
							.setReviewedBy(p.getReviewedBy() != null ? p.getReviewedBy().toString() : "")
							.setReviewedAt(p.getReviewedAt() != null ? p.getReviewedAt().toString() : "").build()));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void reviewFacultyProposal(ReviewFacultyProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.reviewFacultyProposal(UUID.fromString(req.getReviewerId()), req.getProposalId(),
					req.getStatus());
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createProgramProposal(CreateProgramProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.createProgramProposal(UUID.fromString(req.getAuthorId()), req.getFacultyId(),
					req.getName(), req.getShortName(), req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void listProgramProposals(ListProgramProposalsRequest req, StreamObserver<ProgramProposalListResponse> obs) {
		try {
			ProgramProposalListResponse.Builder builder = ProgramProposalListResponse.newBuilder();
			administrationService.listProgramProposals(UUID.fromString(req.getAdminId()))
					.forEach(p -> builder.addProposals(ProgramProposal.newBuilder().setId(p.getId())
							.setAuthorId(p.getAuthorId().toString()).setUniversityId(p.getUniversity().getId())
							.setUniversityName(p.getUniversity().getName())
							.setUniversityShortName(p.getUniversity().getShortName())
							.setFacultyId(p.getFaculty().getId()).setFacultyName(p.getFacultyName())
							.setFacultyShortName(p.getFacultyShortName()).setName(p.getName())
							.setShortName(p.getShortName()).setStatus(p.getStatus())
							.setCreatedAt(p.getCreatedAt().toString())
							.setReviewedBy(p.getReviewedBy() != null ? p.getReviewedBy().toString() : "")
							.setReviewedAt(p.getReviewedAt() != null ? p.getReviewedAt().toString() : "").build()));
			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void reviewProgramProposal(ReviewProgramProposalRequest req, StreamObserver<ModerationResponse> obs) {
		try {
			administrationService.reviewProgramProposal(UUID.fromString(req.getReviewerId()), req.getProposalId(),
					req.getStatus());
			obs.onNext(ModerationResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void createProgramForUser(CreateProgramForUserRequest req, StreamObserver<CreateProgramResponse> obs) {
		try {
			uni.user.entity.UniversityProgram program = administrationService.createProgramForUser(
					UUID.fromString(req.getUserId()), req.getFacultyId(), req.getName(), req.getShortName());
			obs.onNext(CreateProgramResponse.newBuilder().setSuccess(true).setProgram(toProgramProto(program)).build());
			obs.onCompleted();
		} catch (SecurityException e) {
			obs.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	private UserResponse toProto(User u) {
		UserResponse.Builder builder = UserResponse.newBuilder().setId(u.getId().toString())
				.setEmailGoogle(u.getEmailGoogle()).setUsername(u.getUsername() != null ? u.getUsername() : "")
				.setName(u.getName()).setSurname(u.getSurname() != null ? u.getSurname() : "")
				.setEmailUniversity(u.getEmailUniversity() != null ? u.getEmailUniversity() : "")
				.setAvatarUrl(u.getAvatarUrl() != null ? u.getAvatarUrl() : "")
				.setStatus(u.getStatus() != null ? u.getStatus() : "").setIsStudentVerified(u.isStudentVerified())
				.setIsEmployeeVerified(u.isEmployeeVerified()).setIsAdmin(u.isAdmin())
				.setIsBanned(u.getBannedUntil() != null && u.getBannedUntil().isAfter(LocalDateTime.now()))
				.setCreatedAt(u.getCreatedAt().toString());

		if (u.getUniversity() != null) {
			builder.setUniversity(University.newBuilder().setId(u.getUniversity().getId())
					.setName(u.getUniversity().getName()).setShortName(u.getUniversity().getShortName())
					.setSubdomain(u.getUniversity().getSubdomain() != null ? u.getUniversity().getSubdomain() : "")
					.setIconUrl(u.getUniversity().getIconUrl() != null ? u.getUniversity().getIconUrl() : "").build());
		}

		if (u.getFaculty() != null) {
			builder.setFaculty(Faculty.newBuilder().setId(u.getFaculty().getId()).setName(u.getFaculty().getName())
					.setShortName(u.getFaculty().getShortName()).build());
		}

		if (u.getProgram() != null) {
			builder.setProgram(
					Program.newBuilder().setId(u.getProgram().getId()).setFacultyId(u.getProgram().getFaculty().getId())
							.setName(u.getProgram().getName()).setShortName(u.getProgram().getShortName()).build());
		}

		if (u.getCourse() != null) {
			builder.setCourse(u.getCourse());
		}
		if (u.getEducationLevel() != null) {
			builder.setEducationLevel(mapEducationLevelToProto(u.getEducationLevel()));
		}
		if (u.getGraduationYear() != null) {
			builder.setGraduationYear(u.getGraduationYear());
		}
		if (u.getBio() != null) {
			builder.setBio(u.getBio());
		}
		if (u.getCoverUrl() != null) {
			builder.setCoverUrl(u.getCoverUrl());
		}
		if (u.getBannedUntil() != null) {
			builder.setBannedUntil(u.getBannedUntil().toString());
		}
		if (u.getBanReason() != null) {
			builder.setBanReason(u.getBanReason());
		}

		return builder.build();
	}

	private static EducationLevel mapEducationLevelToProto(User.EducationLevel level) {
		return switch (level) {
			case BACHELOR -> EducationLevel.BACHELOR;
			case MASTER -> EducationLevel.MASTER;
			case PHD -> EducationLevel.PHD;
			case SPECIALIST -> EducationLevel.SPECIALIST;
		};
	}

	private static University toUniversityProto(uni.user.entity.University u) {
		return University.newBuilder().setId(u.getId()).setName(u.getName()).setShortName(u.getShortName())
				.setSubdomain(u.getSubdomain() != null ? u.getSubdomain() : "")
				.setIconUrl(u.getIconUrl() != null ? u.getIconUrl() : "").build();
	}

	private static Faculty toFacultyProto(uni.user.entity.UniversityFaculty f) {
		return Faculty.newBuilder().setId(f.getId()).setName(f.getName()).setShortName(f.getShortName()).build();
	}

	private static Program toProgramProto(uni.user.entity.UniversityProgram p) {
		return Program.newBuilder().setId(p.getId()).setFacultyId(p.getFaculty().getId()).setName(p.getName())
				.setShortName(p.getShortName()).build();
	}

	private static User.EducationLevel mapEducationLevelFromProto(EducationLevel level) {
		return switch (level) {
			case BACHELOR -> User.EducationLevel.BACHELOR;
			case MASTER -> User.EducationLevel.MASTER;
			case PHD -> User.EducationLevel.PHD;
			case SPECIALIST -> User.EducationLevel.SPECIALIST;
			default -> throw new IllegalArgumentException("Unsupported education level: " + level);
		};
	}
}
