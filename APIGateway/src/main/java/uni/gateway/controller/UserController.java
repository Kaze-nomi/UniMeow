package uni.gateway.controller;

import lombok.RequiredArgsConstructor;

import javax.security.auth.login.CredentialException;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
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
import uni.gateway.dto.user.TopicDto;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.EducationLevel;
import uni.grpc.user.Topic;
import uni.grpc.user.UpdateUserRequest;
import uni.grpc.user.UserResponse;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class UserController {

	private final UserGrpcClient userGrpcClient;

	@QueryMapping
	public Mono<UserDto> me(@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.getUserById(userId).map(this::toDto);
	}

	@QueryMapping
	public Mono<UserDto> getUser(@Argument(name = "id") String id) {
		return userGrpcClient.getUserById(id).map(this::toDto);
	}

	@QueryMapping
	public Mono<UserDto> getUserByUsername(@Argument(name = "username") String username) {
		return userGrpcClient.getUserByUsername(username).map(this::toDto);
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

	@QueryMapping
	public Mono<List<TopicDto>> listTopics(@Argument(name = "universityId") String universityId) {
		long universityIdLong = parseId(universityId, "universityId");
		return userGrpcClient.listTopics(universityIdLong).map(resp -> toTopicTree(resp.getTopicsList()));
	}

	@MutationMapping
	public Mono<UserDto> updateProfile(@Argument(name = "input") UpdateProfileInput input,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}

		UpdateUserRequest.Builder builder = UpdateUserRequest.newBuilder().setId(userId);

		if (input.username() != null)
			builder.setUsername(input.username());
		if (input.name() != null)
			builder.setName(input.name());
		if (input.surname() != null)
			builder.setSurname(input.surname());
		if (input.patronymic() != null)
			builder.setPatronymic(input.patronymic());
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
				throw new IllegalArgumentException("facultyId cannot be blank");
			}
			builder.setFacultyId(Long.parseLong(input.facultyId()));
		}
		if (input.programId() != null) {
			if (input.programId().isBlank()) {
				throw new IllegalArgumentException("programId cannot be blank");
			}
			builder.setProgramId(Long.parseLong(input.programId()));
		}
		if (input.course() != null)
			builder.setCourse(input.course());
		if (input.educationLevel() != null)
			builder.setEducationLevel(mapEducationLevelToProto(input.educationLevel()));
		if (input.graduationYear() != null)
			builder.setGraduationYear(input.graduationYear());

		return userGrpcClient.updateUser(builder.build()).map(this::toDto);
	}

	@MutationMapping
	public Mono<ProgramDto> createProgram(@Argument(name = "facultyId") String facultyId,
			@Argument(name = "name") String name, @Argument(name = "shortName") String shortName,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		long facultyIdLong = parseId(facultyId, "facultyId");
		return userGrpcClient.createProgramForUser(userId, facultyIdLong, name, shortName)
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
		return userGrpcClient.sendVerificationCode(userId, universityEmail).map(VerificationResult::new);
	}

	@MutationMapping
	public Mono<VerifyResult> verifyEmailCode(@Argument(name = "code") String code,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.verifyEmailCode(userId, code)
				.map(resp -> new VerifyResult(resp.getSuccess(), resp.getError().isBlank() ? null : resp.getError()));
	}

	@MutationMapping
	public Mono<SubscribeResult> subscribe(@Argument(name = "targetUserId") String targetUserId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.subscribe(userId, targetUserId).map(SubscribeResult::new);
	}

	@MutationMapping
	public Mono<SubscribeResult> unsubscribe(@Argument(name = "targetUserId") String targetUserId,
			@ContextValue(name = "userId", required = false) String userId) {
		if (userId == null) {
			return Mono.error(new CredentialException("Authentication required"));
		}
		return userGrpcClient.unsubscribe(userId, targetUserId).map(SubscribeResult::new);
	}

	private UserDto toDto(UserResponse r) {
		return UserDto.builder().id(r.getId()).emailGoogle(r.getEmailGoogle()).username(r.getUsername())
				.name(r.getName()).surname(r.getSurname().isEmpty() ? null : r.getSurname())
				.patronymic(r.getPatronymic().isEmpty() ? null : r.getPatronymic())
				.emailUniversity(r.getEmailUniversity().isEmpty() ? null : r.getEmailUniversity())
				.avatarUrl(r.getAvatarUrl().isEmpty() ? null : r.getAvatarUrl())
				.coverUrl(r.hasCoverUrl() ? r.getCoverUrl() : null).status(
						r.getStatus().isEmpty() ? null : r.getStatus())
				.bio(r.hasBio() ? r.getBio() : null).isStudentVerified(
						r.getIsStudentVerified())
				.isEmployeeVerified(r.getIsEmployeeVerified()).createdAt(
						r.getCreatedAt())
				.university(
						r.hasUniversity()
								? UniversityDto.builder().id(Long.toString(r.getUniversity().getId()))
										.name(r.getUniversity().getName()).shortName(r.getUniversity().getShortName())
										.subdomain(r.getUniversity().getSubdomain().isEmpty()
												? null
												: r.getUniversity().getSubdomain())
										.iconUrl(r.getUniversity().getIconUrl().isEmpty()
												? null
												: r.getUniversity().getIconUrl())
										.build()
								: null)
				.faculty(r.hasFaculty()
						? FacultyDto.builder().id(Long.toString(r.getFaculty().getId())).name(r.getFaculty().getName())
								.shortName(r.getFaculty().getShortName()).build()
						: null)
				.program(r.hasProgram()
						? ProgramDto.builder().id(Long.toString(r.getProgram().getId()))
								.facultyId(Long.toString(r.getProgram().getFacultyId())).name(r.getProgram().getName())
								.shortName(r.getProgram().getShortName()).build()
						: null)
				.course(r.hasCourse() ? r.getCourse() : null)
				.educationLevel(r.hasEducationLevel() ? mapEducationLevelToDto(r.getEducationLevel()) : null)
				.graduationYear(r.hasGraduationYear() ? r.getGraduationYear() : null).isAdmin(r.getIsAdmin())
				.isBanned(r.getIsBanned()).bannedUntil(r.hasBannedUntil() ? r.getBannedUntil() : null)
				.banReason(r.hasBanReason() ? r.getBanReason() : null).build();
	}

	private static EducationLevel mapEducationLevelToProto(EducationLevelDto level) {
		return switch (level) {
			case BACHELOR -> EducationLevel.BACHELOR;
			case MASTER -> EducationLevel.MASTER;
			case PHD -> EducationLevel.PHD;
			case SPECIALIST -> EducationLevel.SPECIALIST;
		};
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

	private static List<TopicDto> toTopicTree(List<Topic> topics) {
		Map<Long, List<Topic>> childrenByParent = new HashMap<>();

		for (Topic topic : topics) {
			if (topic.hasParentId()) {
				childrenByParent.computeIfAbsent(topic.getParentId(), k -> new ArrayList<>()).add(topic);
			}
		}

		return topics.stream().filter(topic -> !topic.hasParentId()).map(topic -> toTopicDto(topic, childrenByParent))
				.toList();
	}

	private static TopicDto toTopicDto(Topic topic, Map<Long, List<Topic>> childrenByParent) {
		List<TopicDto> subtopics = childrenByParent.getOrDefault(topic.getId(), List.of()).stream()
				.map(child -> toTopicDto(child, childrenByParent)).toList();

		return new TopicDto(Long.toString(topic.getId()), topic.getSlug(), topic.getName(), topic.getIsSystem(),
				topic.hasFacultyId() ? Long.toString(topic.getFacultyId()) : null, subtopics);
	}
}
