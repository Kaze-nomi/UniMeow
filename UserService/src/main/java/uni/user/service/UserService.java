package uni.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.Subscription;
import uni.user.entity.User;
import uni.user.entity.UniversityFaculty;
import uni.user.entity.UniversityProgram;
import uni.user.exception.UserNotFoundException;
import uni.user.exception.UsernameAlreadyTakenException;
import uni.user.outbox.OutboxService;
import uni.user.repository.SubscriptionRepository;
import uni.user.repository.UniversityFacultyRepository;
import uni.user.repository.UniversityProgramRepository;
import uni.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final UniversityFacultyRepository universityFacultyRepository;
	private final UniversityProgramRepository universityProgramRepository;
	private final OutboxService outboxService;

	@Transactional
	public User createOrGet(String emailGoogle, String name, String surname, String avatarUrl) {
		User user = userRepository.findByEmailGoogle(emailGoogle).orElseGet(() -> {
			log.info("Creating new user for email {}", emailGoogle);
			return userRepository.save(User.builder().id(UUID.randomUUID()).emailGoogle(emailGoogle).username(null)
					.name(name).surname(surname.isBlank() ? null : surname)
					.avatarUrl(avatarUrl.isBlank() ? null : avatarUrl).isStudentVerified(false)
					.isEmployeeVerified(false).createdAt(LocalDateTime.now()).build());
		});

		if (isBanned(user)) {
			throw new SecurityException("User is banned");
		}

		return user;
	}

	@Transactional(readOnly = true)
	public User getById(UUID id) {
		return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException("User not found: " + id));
	}

	@Transactional(readOnly = true)
	public User getByUsername(String username) {
		return userRepository.findByUsername(username)
				.orElseThrow(() -> new UserNotFoundException("User not found: @" + username));
	}

	@Transactional
	public User update(UUID id, boolean hasUsername, String username, boolean hasName, String name, boolean hasSurname,
			String surname, boolean hasStatus, String status, boolean hasAvatarUrl, String avatarUrl,
			boolean hasFacultyId, Long facultyId, boolean hasCourse, Integer course, boolean hasEducationLevel,
			User.EducationLevel educationLevel, boolean hasGraduationYear, Integer graduationYear, boolean hasBio,
			String bio, boolean hasCoverUrl, String coverUrl, boolean hasProgramId, Long programId) {

		User user = getById(id);

		if (hasUsername) {
			if (username == null || username.isBlank()) {
				throw new IllegalArgumentException("Username cannot be empty");
			}
			String normalizedUsername = username.strip().toLowerCase().replaceAll("\\s+", "");
			if (!normalizedUsername.equals(user.getUsername()) && userRepository.existsByUsername(normalizedUsername)) {
				throw new UsernameAlreadyTakenException("Username already taken: " + normalizedUsername);
			}
			user.setUsername(normalizedUsername);
		}
		if (hasName)
			user.setName(name);
		if (hasSurname)
			user.setSurname(surname);
		if (hasStatus)
			user.setStatus(status);
		if (hasAvatarUrl)
			user.setAvatarUrl(avatarUrl);

		if (hasFacultyId) {
			if (facultyId == null) {
				user.setFaculty(null);
				user.setProgram(null);
			} else {
				UniversityFaculty faculty = universityFacultyRepository.findById(facultyId)
						.orElseThrow(() -> new IllegalArgumentException("Faculty not found: " + facultyId));

				if (user.getUniversity() == null) {
					throw new IllegalArgumentException("Cannot set faculty without university");
				}
				if (!faculty.getUniversity().getId().equals(user.getUniversity().getId())) {
					throw new IllegalArgumentException("Faculty does not belong to user's university");
				}

				user.setFaculty(faculty);
				if (user.getProgram() != null && !user.getProgram().getFaculty().getId().equals(faculty.getId())) {
					user.setProgram(null);
				}
			}
		}

		if (hasProgramId) {
			if (programId == null) {
				user.setProgram(null);
			} else {
				UniversityProgram program = universityProgramRepository.findById(programId)
						.orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
				if (user.getUniversity() == null || user.getFaculty() == null) {
					throw new IllegalArgumentException("Cannot set program without university and faculty");
				}
				if (!program.getUniversity().getId().equals(user.getUniversity().getId())
						|| !program.getFaculty().getId().equals(user.getFaculty().getId())) {
					throw new IllegalArgumentException("Program does not belong to user's university/faculty");
				}
				user.setProgram(program);
			}
		}

		if (hasCourse) {
			if (course == null) {
				user.setCourse(null);
			} else {
				if (course < 1 || course > 8) {
					throw new IllegalArgumentException("Course must be in range [1, 8]");
				}
				user.setCourse(course.shortValue());
			}
		}

		if (hasEducationLevel) {
			user.setEducationLevel(educationLevel);
		}

		if (hasGraduationYear) {
			if (graduationYear == null) {
				user.setGraduationYear(null);
			} else {
				int currentYear = LocalDateTime.now().getYear();
				if (graduationYear < currentYear - 10 || graduationYear > currentYear + 10) {
					throw new IllegalArgumentException("Graduation year is out of reasonable range");
				}
				user.setGraduationYear(graduationYear.shortValue());
			}
		}

		if (hasBio) {
			if (bio != null && bio.length() > 500) {
				throw new IllegalArgumentException("Bio must be at most 500 characters");
			}
			user.setBio(bio);
		}

		if (hasCoverUrl)
			user.setCoverUrl(coverUrl);

		log.info("Updated profile for user {}", id);
		return userRepository.save(user);
	}

	@Transactional
	public void subscribe(UUID subscriberId, UUID targetUserId) {
		if (subscriberId.equals(targetUserId)) {
			throw new IllegalArgumentException("Cannot subscribe to yourself");
		}

		if (!userRepository.existsById(subscriberId)) {
			throw new UserNotFoundException("Subscriber not found: " + subscriberId);
		}
		if (!userRepository.existsById(targetUserId)) {
			throw new UserNotFoundException("Target user not found: " + targetUserId);
		}

		boolean exists = subscriptionRepository.existsBySubscriberIdAndTargetUserId(subscriberId, targetUserId);
		if (!exists) {
			log.info("User {} subscribed to {}", subscriberId, targetUserId);
			LocalDateTime now = LocalDateTime.now();
			subscriptionRepository.save(new Subscription(subscriberId, targetUserId, now));

			outboxService.enqueueUserEvent("USER_FOLLOWED", targetUserId.toString(), subscriberId + "->" + targetUserId,
					Map.of("subscriberId", subscriberId.toString(), "targetUserId", targetUserId.toString(),
							"createdAt", now.toString()));
		}
	}

	@Transactional
	public void unsubscribe(UUID subscriberId, UUID targetUserId) {
		boolean exists = subscriptionRepository.existsBySubscriberIdAndTargetUserId(subscriberId, targetUserId);
		if (!exists) {
			return;
		}

		log.info("User {} unsubscribed from {}", subscriberId, targetUserId);
		subscriptionRepository.deleteBySubscriberIdAndTargetUserId(subscriberId, targetUserId);

		outboxService.enqueueUserEvent("USER_UNFOLLOWED", targetUserId.toString(), subscriberId + "->" + targetUserId,
				Map.of("subscriberId", subscriberId.toString(), "targetUserId", targetUserId.toString(), "unfollowedAt",
						LocalDateTime.now().toString()));
	}

	@Transactional(readOnly = true)
	public boolean isSubscribed(UUID subscriberId, UUID targetUserId) {
		if (subscriberId == null || targetUserId == null || subscriberId.equals(targetUserId)) {
			return false;
		}
		return subscriptionRepository.existsBySubscriberIdAndTargetUserId(subscriberId, targetUserId);
	}

	@Transactional(readOnly = true)
	public boolean isAdmin(UUID userId) {
		return getById(userId).isAdmin();
	}

	@Transactional
	public void grantAdmin(UUID granterId, UUID targetUserId) {
		User granter = getById(granterId);
		if (granter.getUsername() == null || !"kazenomi".equalsIgnoreCase(granter.getUsername())) {
			throw new SecurityException("Only kazenomi can grant admin privileges");
		}
		User target = getById(targetUserId);
		target.setAdmin(true);
		userRepository.save(target);
		log.warn("Admin granted to user {} by {}", targetUserId, granterId);

		outboxService.enqueueUserEvent("ADMIN_GRANTED", targetUserId.toString(), targetUserId.toString(),
				Map.of("targetUserId", targetUserId.toString(), "granterId", granterId.toString(), "grantedAt",
						LocalDateTime.now().toString()));
	}

	@Transactional
	public void banUser(UUID moderatorId, UUID targetUserId, LocalDateTime bannedUntil, String reason) {
		User moderator = getById(moderatorId);
		if (!moderator.isAdmin()) {
			throw new SecurityException("Admin privileges required");
		}

		User target = getById(targetUserId);
		if (target.isAdmin()) {
			throw new IllegalArgumentException("Cannot ban an admin user");
		}
		if ("kazenomi".equalsIgnoreCase(target.getUsername())) {
			throw new IllegalArgumentException("Cannot ban the root user");
		}

		boolean permanentBan = bannedUntil == null;
		target.setBannedUntil(bannedUntil);
		target.setBannedPermanent(permanentBan);
		target.setBanReason(reason == null || reason.isBlank() ? null : reason.trim());

		if (permanentBan) {
			clearOptionalProfileData(target);
			outboxService.enqueueUserEvent("USER_PERMANENT_BANNED", targetUserId.toString(), targetUserId.toString(),
					Map.of("userId", targetUserId.toString(), "moderatorId", moderatorId.toString(), "reason",
							target.getBanReason() == null ? "" : target.getBanReason()));
		}

		outboxService.enqueueUserEvent("USER_BANNED", targetUserId.toString(), targetUserId.toString(),
				Map.of("targetUserId", targetUserId.toString(), "moderatorId", moderatorId.toString(), "bannedAt",
						LocalDateTime.now().toString()));

		userRepository.save(target);
		log.warn("User {} banned by admin {}; until={}, reason={}", targetUserId, moderatorId, bannedUntil, reason);
	}

	@Transactional
	public void deleteAccount(UUID userId) {
		User user = getById(userId);
		outboxService.enqueueUserEvent("USER_DELETED", userId.toString(), userId.toString(),
				Map.of("userId", userId.toString(), "deletedAt", LocalDateTime.now().toString()));
		userRepository.delete(user);
		log.warn("User {} deleted their account", userId);
	}

	private static boolean isBanned(User user) {
		LocalDateTime bannedUntil = user.getBannedUntil();
		return user.isBannedPermanent() || (bannedUntil != null && bannedUntil.isAfter(LocalDateTime.now()));
	}

	private static void clearOptionalProfileData(User target) {
		target.setUsername(null);
		target.setSurname(null);
		target.setEmailUniversity(null);
		target.setAvatarUrl(null);
		target.setCoverUrl(null);
		target.setStatus(null);
		target.setUniversity(null);
		target.setFaculty(null);
		target.setProgram(null);
		target.setCourse(null);
		target.setEducationLevel(null);
		target.setGraduationYear(null);
		target.setBio(null);
	}

}
