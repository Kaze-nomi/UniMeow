package uni.user.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uni.user.repository.SubscriptionRepository;
import uni.user.repository.UniversityFacultyRepository;
import uni.user.repository.UniversityProgramRepository;
import uni.user.repository.UniversityRepository;
import uni.user.repository.UserRepository;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PlatformMetrics implements MeterBinder {

	private final UserRepository userRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final UniversityRepository universityRepository;
	private final UniversityFacultyRepository universityFacultyRepository;
	private final UniversityProgramRepository universityProgramRepository;

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("platform_users", userRepository, UserRepository::count)
				.description("Total number of registered users on the platform").register(registry);
		Gauge.builder("platform_users_student_verified", userRepository, UserRepository::countStudentVerified)
				.description("Total number of student-verified users").register(registry);
		Gauge.builder("platform_users_employee_verified", userRepository, UserRepository::countEmployeeVerified)
				.description("Total number of employee-verified users").register(registry);
		Gauge.builder("platform_users_admin", userRepository, UserRepository::countAdmins)
				.description("Total number of admin users").register(registry);
		Gauge.builder("platform_users_with_university", userRepository, UserRepository::countWithUniversity)
				.description("Total number of users linked to a university").register(registry);
		Gauge.builder("platform_users_active_bans", userRepository,
				repository -> repository.countActiveBans(LocalDateTime.now()))
				.description("Total number of currently active temporary bans").register(registry);
		Gauge.builder("platform_subscriptions", subscriptionRepository, SubscriptionRepository::count)
				.description("Total number of active user subscriptions").register(registry);
		Gauge.builder("platform_universities", universityRepository, UniversityRepository::count)
				.description("Total number of universities").register(registry);
		Gauge.builder("platform_faculties", universityFacultyRepository, UniversityFacultyRepository::count)
				.description("Total number of university faculties").register(registry);
		Gauge.builder("platform_programs", universityProgramRepository, UniversityProgramRepository::count)
				.description("Total number of university programs").register(registry);
	}
}
