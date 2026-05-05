package uni.gateway.controller;

import lombok.RequiredArgsConstructor;

import javax.security.auth.login.CredentialException;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import uni.gateway.dto.user.SubscribeResult;
import uni.gateway.dto.user.EducationLevelDto;
import uni.gateway.dto.user.FacultyDto;
import uni.gateway.dto.user.ProgramDto;
import uni.gateway.dto.user.UpdateProfileInput;
import uni.gateway.dto.user.UniversityDto;
import uni.gateway.dto.user.UserDto;
import uni.gateway.dto.user.VerificationResult;
import uni.gateway.dto.user.VerifyResult;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.EducationLevel;
import uni.grpc.user.UpdateUserRequest;
import reactor.core.publisher.Mono;
import uni.gateway.dto.post.DeleteResult;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class UserController {

	private final UserGrpcClient userGrpcClient;
	private final UserMapper userMapper;

	@QueryMapping
	public Mono<UserDto> me(@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.getUserById(userId).map(userMapper::toDto);
	}

	@QueryMapping
	public Mono<UserDto> getUser(@Argument(name = "id") String id) {
		return userGrpcClient.getUserById(id).map(userMapper::toDto);
	}

	@QueryMapping
	public Mono<UserDto> getUserByUsername(@Argument(name = "username") String username) {
		return userGrpcClient.getUserByUsername(username).map(userMapper::toDto);
	}

	@SchemaMapping(typeName = "User", field = "isFollowedByMe")
	public Mono<Boolean> isFollowedByMe(UserDto user,
			@ContextValue(name = "userId", required = false) String viewerId) {
		if (viewerId == null || viewerId.equals(user.id())) {
			return Mono.just(false);
		}
		return userGrpcClient.isSubscribed(viewerId, user.id());
	}

	@QueryMapping
	public Mono<List<UniversityDto>> listUniversities() {
		return userGrpcClient.listUniversities().map(resp -> resp.getUniversitiesList().stream()
				.map(u -> UniversityDto.builder().id(Long.toString(u.getId())).name(u.getName())
						.shortName(u.getShortName()).subdomain(u.getSubdomain().isEmpty() ? null : u.getSubdomain())
						.iconUrl(u.getIconUrl().isEmpty() ? null : u.getIconUrl()).build())
				.toList());
	}

	@QueryMapping
	public Mono<List<FacultyDto>> listFaculties(@Argument(name = "universityId") String universityId) {
		long universityIdLong = parseId(universityId, "universityId");
		return userGrpcClient.listFaculties(universityIdLong)
				.map(resp -> resp.getFacultiesList().stream().map(f -> FacultyDto.builder().id(Long.toString(f.getId()))
						.name(f.getName()).shortName(f.getShortName()).build()).toList());
	}

	@QueryMapping
	public Mono<List<ProgramDto>> listPrograms(@Argument(name = "facultyId") String facultyId) {
		long facultyIdLong = parseId(facultyId, "facultyId");
		return userGrpcClient.listPrograms(facultyIdLong)
				.map(resp -> resp.getProgramsList().stream()
						.map(p -> ProgramDto.builder().id(Long.toString(p.getId()))
								.facultyId(Long.toString(p.getFacultyId())).name(p.getName())
								.shortName(p.getShortName()).build())
						.toList());
	}

	@MutationMapping
	public Mono<UserDto> updateProfile(@Argument(name = "input") UpdateProfileInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}

		return requireActiveUser(userId).flatMap(activeId -> doUpdateProfile(activeId, input));
	}

	private Mono<UserDto> doUpdateProfile(String userId, UpdateProfileInput input) {
		UpdateUserRequest.Builder builder = UpdateUserRequest.newBuilder().setId(userId);

		if (input.username() != null)
			builder.setUsername(input.username());
		if (input.name() != null)
			builder.setName(input.name());
		if (input.surname() != null)
			builder.setSurname(input.surname());
		if (input.status() != null)
			builder.setStatus(input.status());
		if (input.avatarUrl() != null)
			builder.setAvatarUrl(input.avatarUrl());
		if (input.coverUrl() != null)
			builder.setCoverUrl(input.coverUrl());
		if (input.bio() != null)
			builder.setBio(input.bio());
		if (input.facultyId() != null) {
			if (input.facultyId().isBlank()) {
				builder.setFacultyId(0L);
			} else {
				builder.setFacultyId(Long.parseLong(input.facultyId()));
			}
		}
		if (input.programId() != null) {
			if (input.programId().isBlank()) {
				builder.setProgramId(0L);
			} else {
				builder.setProgramId(Long.parseLong(input.programId()));
			}
		}
		if (input.course() != null)
			builder.setCourse(input.course());
		if (input.educationLevel() != null)
			builder.setEducationLevel(mapEducationLevelToProto(input.educationLevel()));
		if (input.graduationYear() != null)
			builder.setGraduationYear(input.graduationYear());

		return userGrpcClient.updateUser(builder.build()).map(userMapper::toDto);
	}

	@MutationMapping
	public Mono<ProgramDto> createProgram(@Argument(name = "facultyId") String facultyId,
			@Argument(name = "name") String name, @Argument(name = "shortName") String shortName,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		long facultyIdLong = parseId(facultyId, "facultyId");
		return requireActiveUser(userId)
				.flatMap(activeId -> userGrpcClient.createProgramForUser(activeId, facultyIdLong, name, shortName))
				.map(resp -> ProgramDto.builder().id(Long.toString(resp.getProgram().getId()))
						.facultyId(Long.toString(resp.getProgram().getFacultyId())).name(resp.getProgram().getName())
						.shortName(resp.getProgram().getShortName()).build());
	}

	@MutationMapping
	public Mono<VerificationResult> sendVerificationCode(@Argument(name = "universityEmail") String universityEmail,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId)
				.flatMap(activeId -> userGrpcClient.sendVerificationCode(activeId, universityEmail))
				.map(VerificationResult::new);
	}

	@MutationMapping
	public Mono<VerifyResult> verifyEmailCode(@Argument(name = "code") String code,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(activeId -> userGrpcClient.verifyEmailCode(activeId, code))
				.map(resp -> new VerifyResult(resp.getSuccess(), resp.getError().isBlank() ? null : resp.getError()));
	}

	@MutationMapping
	public Mono<SubscribeResult> subscribe(@Argument(name = "targetUserId") String targetUserId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(activeId -> userGrpcClient.subscribe(activeId, targetUserId))
				.map(SubscribeResult::new);
	}

	@MutationMapping
	public Mono<SubscribeResult> unsubscribe(@Argument(name = "targetUserId") String targetUserId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return requireActiveUser(userId).flatMap(activeId -> userGrpcClient.unsubscribe(activeId, targetUserId))
				.map(SubscribeResult::new);
	}

	@MutationMapping
	public Mono<DeleteResult> deleteAccount(@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.deleteAccount(userId).map(DeleteResult::new);
	}

	private Mono<String> requireActiveUser(String userId) {
		return userGrpcClient.getUserById(userId).flatMap(user -> {
			if (user.getIsBanned()) {
				return Mono.error(new AccessDeniedException("User is banned"));
			}
			return Mono.just(userId);
		});
	}

	private static EducationLevel mapEducationLevelToProto(EducationLevelDto level) {
		return switch (level) {
			case BACHELOR -> EducationLevel.BACHELOR;
			case MASTER -> EducationLevel.MASTER;
			case PHD -> EducationLevel.PHD;
			case SPECIALIST -> EducationLevel.SPECIALIST;
		};
	}

	private static long parseId(String raw, String fieldName) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException(fieldName + " cannot be blank");
		}
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(fieldName + " must be a number");
		}
	}

}
