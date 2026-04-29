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

	private User updateOnlyUsername(String username) {
		return userService.update(USER_ID, true, username, false, null, false, null, false, null, false, null, false,
				null, false, null, false, null, false, null, false, null, false, null, false, null, false, null);
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

		User result = userService.update(USER_ID, true, "new_name", false, null, false, null, false, null, false, null,
				false, null, false, null, false, null, false, null, false, null, false, null, false, null, false, null);

		assertThat(result.getUsername()).isEqualTo("new_name");
	}

	@Test
	void update_throws_when_username_already_taken_by_another_user() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.existsByUsername("taken_name")).thenReturn(true);

		assertThatThrownBy(() -> updateOnlyUsername("taken_name")).isInstanceOf(UsernameAlreadyTakenException.class)
				.hasMessageContaining("taken_name");
	}

	@Test
	void update_does_not_throw_when_username_is_same_as_current() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThatCode(() -> updateOnlyUsername("ivan_petrov")).doesNotThrowAnyException();
	}

	@Test
	void update_throws_when_username_is_blank() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.update(USER_ID, true, "", false, null, false, null, false, null, false,
				null, false, null, false, null, false, null, false, null, false, null, false, null, false, null, false,
				null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Username cannot be empty");
	}

	@Test
	void update_only_changes_fields_that_are_flagged() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.update(USER_ID, false, null, true, "Петя", false, null, false, null, false, null,
				false, null, false, null, false, null, false, null, false, null, false, null, false, null, false, null);

		assertThat(result.getName()).isEqualTo("Петя");
		assertThat(result.getUsername()).isEqualTo("ivan_petrov");
	}

	@Test
	void update_sets_course_when_valid() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null,
				false, null, false, null, true, 4, false, null, false, null, false, null, false, null, false, null);

		assertThat(result.getCourse()).isEqualTo((short) 4);
	}

	@Test
	void update_throws_when_course_is_out_of_range() {
		User user = buildUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.update(USER_ID, false, null, false, null, false, null, false, null, false,
				null, false, null, false, null, true, 9, false, null, false, null, false, null, false, null, false,
				null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Course must be in range");
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

		User result = userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null,
				false, null, true, 77L, false, null, false, null, false, null, false, null, false, null, false, null);

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

		assertThatThrownBy(() -> userService.update(USER_ID, false, null, false, null, false, null, false, null, false,
				null, false, null, true, 88L, false, null, false, null, false, null, false, null, false, null, false,
				null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Faculty does not belong");
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

		User result = userService.update(USER_ID, false, null, false, null, false, null, false, null, false, null,
				false, null, false, null, false, null, false, null, false, null, false, null, false, null, true, 501L);

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
}
