package uni.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.user.entity.Subscription;
import uni.user.entity.User;
import uni.user.entity.University;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	UserRepository userRepository;

	@Mock
	SubscriptionRepository subscriptionRepository;

	@Mock
	UniversityFacultyRepository universityFacultyRepository;

	@Mock
	UniversityProgramRepository universityProgramRepository;

	@Mock
	OutboxService outboxService;

	@InjectMocks
	UserService userService;

	private static final UUID USER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
	private static final UUID TARGET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

	private User buildUser() {
		return User.builder().id(USER_ID).emailGoogle("ivan@gmail.com").username("ivan_petrov").name("Иван")
				.surname("Петров").isStudentVerified(false).isEmployeeVerified(false).createdAt(LocalDateTime.now())
				.build();
	}

	private User updateUsername(String username) {
		return userService.update(USER_ID, true, username, false, null, false, null, false, null, false, null, false,
				null, false, null, false, null, false, null, false, null, false, null, false, null);
	}

	private User updateName(String name) {
		return userService.update(USER_ID, false, null, true, name, false, null, false, null, false, null, false, null,
				false, null, false, null, false, null, false, null, false, null, false, null);
	}

	private User updateCourse(int course) {
		return userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null, false, null,
				true, course, false, null, false, null, false, null, false, null, false, null);
	}

	private User updateFaculty(long facultyId) {
		return userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null, true,
				facultyId, false, null, false, null, false, null, false, null, false, null, false, null);
	}

	private User updateProgram(long programId) {
		return userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null, false, null,
				false, null, false, null, false, null, false, null, false, null, true, programId);
	}

	private University buildUniversity(long id) {
		return University.builder().id(id).name("HSE").shortName("HSE").createdAt(LocalDateTime.now()).build();
	}

	@Test
	void create_or_get_returns_existing_user_if_found_by_email() {
		User existing = buildUser();
		when(userRepository.findByEmailGoogle("ivan@gmail.com")).thenReturn(Optional.of(existing));

		User result = userService.createOrGet("ivan@gmail.com", "Иван", "Петров", "");

		assertThat(result.getId()).isEqualTo(USER_ID);
		verify(userRepository, never()).save(any());
	}

	@Test
	void create_or_get_throws_when_user_is_permanently_banned() {
		User banned = buildUser();
		banned.setBannedPermanent(true);
		when(userRepository.findByEmailGoogle("banned@gmail.com")).thenReturn(Optional.of(banned));

		assertThatThrownBy(() -> userService.createOrGet("banned@gmail.com", "Иван", "Петров", ""))
				.isInstanceOf(SecurityException.class).hasMessageContaining("banned");
	}

	@Test
	void create_or_get_creates_new_user_if_not_found() {
		when(userRepository.findByEmailGoogle("new@gmail.com")).thenReturn(Optional.empty());
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.createOrGet("new@gmail.com", "Иван", "Петров", "avatar.jpg");

		assertThat(result.getEmailGoogle()).isEqualTo("new@gmail.com");
		assertThat(result.getUsername()).isNull();
		assertThat(result.getId()).isNotNull();
		verify(userRepository).save(any());
	}

	@Test
	void create_or_get_sets_null_for_blank_surname() {
		when(userRepository.findByEmailGoogle("test@gmail.com")).thenReturn(Optional.empty());
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.createOrGet("test@gmail.com", "Иван", "", "");

		assertThat(result.getSurname()).isNull();
	}

	@Test
	void create_or_get_sets_null_for_blank_avatar() {
		when(userRepository.findByEmailGoogle("test@gmail.com")).thenReturn(Optional.empty());
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.createOrGet("test@gmail.com", "Иван", "", "");

		assertThat(result.getAvatarUrl()).isNull();
	}

	@Test
	void get_by_id_returns_user_when_found() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

		User result = userService.getById(USER_ID);

		assertThat(result.getId()).isEqualTo(USER_ID);
	}

	@Test
	void get_by_id_throws_user_not_found_when_missing() {
		when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getById(USER_ID)).isInstanceOf(UserNotFoundException.class)
				.hasMessageContaining(USER_ID.toString());
	}

	@Test
	void get_by_username_returns_user_when_found() {
		when(userRepository.findByUsername("ivan_petrov")).thenReturn(Optional.of(buildUser()));

		User result = userService.getByUsername("ivan_petrov");

		assertThat(result.getUsername()).isEqualTo("ivan_petrov");
	}

	@Test
	void get_by_username_throws_user_not_found_when_missing() {
		when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getByUsername("ghost")).isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void update_changes_username_when_available() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.existsByUsername("new_name")).thenReturn(false);
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateUsername("new_name");

		assertThat(result.getUsername()).isEqualTo("new_name");
	}

	@Test
	void update_throws_when_username_already_taken_by_another_user() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.existsByUsername("taken_name")).thenReturn(true);

		assertThatThrownBy(() -> updateUsername("taken_name")).isInstanceOf(UsernameAlreadyTakenException.class)
				.hasMessageContaining("taken_name");
	}

	@Test
	void update_does_not_throw_when_username_is_same_as_current() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThatCode(() -> updateUsername("ivan_petrov")).doesNotThrowAnyException();
	}

	@Test
	void update_normalizes_username_to_lowercase_without_spaces() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.existsByUsername("newname")).thenReturn(false);
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateUsername("  New Name  ");

		assertThat(result.getUsername()).isEqualTo("newname");
	}

	@Test
	void update_throws_when_username_is_blank() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> updateUsername("")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Username cannot be empty");
	}

	@Test
	void update_only_changes_fields_that_are_flagged() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateName("Петя");

		assertThat(result.getName()).isEqualTo("Петя");
		assertThat(result.getUsername()).isEqualTo("ivan_petrov");
	}

	@Test
	void update_sets_course_when_valid() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateCourse(4);

		assertThat(result.getCourse()).isEqualTo((short) 4);
	}

	@Test
	void update_throws_when_course_is_out_of_range() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> updateCourse(9)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Course must be in range");
	}

	@Test
	void update_sets_faculty_when_it_belongs_to_users_university() {
		User user = buildUser();
		University university = buildUniversity(10L);
		user.setUniversity(university);

		UniversityFaculty faculty = UniversityFaculty.builder().id(77L).name("FKN").shortName("FKN")
				.university(university).build();

		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(universityFacultyRepository.findById(77L)).thenReturn(Optional.of(faculty));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateFaculty(77L);

		assertThat(result.getFaculty()).isEqualTo(faculty);
	}

	@Test
	void update_throws_when_faculty_belongs_to_another_university() {
		User user = buildUser();
		user.setUniversity(buildUniversity(10L));

		UniversityFaculty foreignFaculty = UniversityFaculty.builder().id(88L).name("Foreign").shortName("FRG")
				.university(buildUniversity(11L)).build();

		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(universityFacultyRepository.findById(88L)).thenReturn(Optional.of(foreignFaculty));

		assertThatThrownBy(() -> updateFaculty(88L)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Faculty does not belong");
	}

	@Test
	void update_sets_program_when_it_belongs_to_users_university_and_faculty() {
		User user = buildUser();
		University university = buildUniversity(10L);
		UniversityFaculty faculty = UniversityFaculty.builder().id(77L).name("FKN").shortName("FKN")
				.university(university).build();
		UniversityProgram program = UniversityProgram.builder().id(501L).name("SE").shortName("SE")
				.university(university).faculty(faculty).build();
		user.setUniversity(university);
		user.setFaculty(faculty);

		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(universityProgramRepository.findById(501L)).thenReturn(Optional.of(program));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = updateProgram(501L);

		assertThat(result.getProgram()).isEqualTo(program);
	}

	@Test
	void subscribe_saves_new_subscription() {
		when(userRepository.existsById(USER_ID)).thenReturn(true);
		when(userRepository.existsById(TARGET_ID)).thenReturn(true);
		when(subscriptionRepository.existsBySubscriberIdAndTargetUserId(USER_ID, TARGET_ID)).thenReturn(false);

		userService.subscribe(USER_ID, TARGET_ID);

		verify(subscriptionRepository).save(any(Subscription.class));
	}

	@Test
	void subscribe_does_not_save_duplicate() {
		when(userRepository.existsById(USER_ID)).thenReturn(true);
		when(userRepository.existsById(TARGET_ID)).thenReturn(true);
		when(subscriptionRepository.existsBySubscriberIdAndTargetUserId(USER_ID, TARGET_ID)).thenReturn(true);

		userService.subscribe(USER_ID, TARGET_ID);
		verify(subscriptionRepository, never()).save(any());
	}

	@Test
	void subscribe_throws_when_subscribing_to_yourself() {
		assertThatThrownBy(() -> userService.subscribe(USER_ID, USER_ID)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot subscribe to yourself");
	}

	@Test
	void subscribe_throws_when_subscriber_not_found() {
		when(userRepository.existsById(USER_ID)).thenReturn(false);

		assertThatThrownBy(() -> userService.subscribe(USER_ID, TARGET_ID)).isInstanceOf(UserNotFoundException.class)
				.hasMessageContaining("Subscriber not found");
	}

	@Test
	void subscribe_throws_when_target_not_found() {
		when(userRepository.existsById(USER_ID)).thenReturn(true);
		when(userRepository.existsById(TARGET_ID)).thenReturn(false);

		assertThatThrownBy(() -> userService.subscribe(USER_ID, TARGET_ID)).isInstanceOf(UserNotFoundException.class)
				.hasMessageContaining("Target user not found");
	}

	@Test
	void unsubscribe_calls_repository_delete() {
		when(subscriptionRepository.existsBySubscriberIdAndTargetUserId(USER_ID, TARGET_ID)).thenReturn(true);

		userService.unsubscribe(USER_ID, TARGET_ID);

		verify(subscriptionRepository).deleteBySubscriberIdAndTargetUserId(USER_ID, TARGET_ID);
	}

	@Test
	void unsubscribe_does_not_throw_when_subscription_not_exists() {
		when(subscriptionRepository.existsBySubscriberIdAndTargetUserId(USER_ID, TARGET_ID)).thenReturn(false);

		assertThatCode(() -> userService.unsubscribe(USER_ID, TARGET_ID)).doesNotThrowAnyException();
		verify(subscriptionRepository, never()).deleteBySubscriberIdAndTargetUserId(any(), any());
	}

	@Test
	void ban_user_permanently_clears_optional_profile_data_and_emits_cleanup_event() {
		UUID moderatorId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		User moderator = buildUser();
		moderator.setId(moderatorId);
		moderator.setAdmin(true);

		User target = buildUser();
		target.setId(TARGET_ID);
		target.setUsername("target_user");
		target.setSurname("Surname");
		target.setEmailUniversity("target@uni.edu");
		target.setAvatarUrl("avatar.png");
		target.setCoverUrl("cover.png");
		target.setStatus("status");
		target.setUniversity(buildUniversity(10L));
		target.setFaculty(
				UniversityFaculty.builder().id(11L).name("F").shortName("F").university(buildUniversity(10L)).build());
		target.setProgram(UniversityProgram.builder().id(12L).name("P").shortName("P").university(buildUniversity(10L))
				.faculty(target.getFaculty()).build());
		target.setCourse((short) 3);
		target.setEducationLevel(User.EducationLevel.BACHELOR);
		target.setGraduationYear((short) 2030);
		target.setBio("bio");

		when(userRepository.findById(moderatorId)).thenReturn(Optional.of(moderator));
		when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		userService.banUser(moderatorId, TARGET_ID, null, "Permanent ban");

		assertThat(target.isBannedPermanent()).isTrue();
		assertThat(target.getBannedUntil()).isNull();
		assertThat(target.getUsername()).isNull();
		assertThat(target.getSurname()).isNull();
		assertThat(target.getEmailUniversity()).isNull();
		assertThat(target.getAvatarUrl()).isNull();
		assertThat(target.getCoverUrl()).isNull();
		assertThat(target.getStatus()).isNull();
		assertThat(target.getUniversity()).isNull();
		assertThat(target.getFaculty()).isNull();
		assertThat(target.getProgram()).isNull();
		assertThat(target.getCourse()).isNull();
		assertThat(target.getEducationLevel()).isNull();
		assertThat(target.getGraduationYear()).isNull();
		assertThat(target.getBio()).isNull();

		verify(outboxService).enqueueUserEvent(eq("USER_PERMANENT_BANNED"), eq(TARGET_ID.toString()),
				eq(TARGET_ID.toString()), any());
		verify(userRepository).save(target);
	}

	@Test
	void delete_account_removes_user_and_emits_delete_event() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		userService.deleteAccount(USER_ID);

		verify(outboxService).enqueueUserEvent(eq("USER_DELETED"), eq(USER_ID.toString()), eq(USER_ID.toString()),
				any());
		verify(userRepository).delete(user);
	}
}
