package uni.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.Subscription;
import uni.user.entity.User;
import uni.user.exception.UserNotFoundException;
import uni.user.exception.UsernameAlreadyTakenException;
import uni.user.repository.SubscriptionRepository;
import uni.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional
    public User createOrGet(String emailGoogle, String name, String surname, String avatarUrl) {
        return userRepository.findByEmailGoogle(emailGoogle)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .id(UUID.randomUUID())
                                .emailGoogle(emailGoogle)
                                .username(null)
                                .name(name)
                                .surname(surname.isBlank() ? null : surname)
                                .avatarUrl(avatarUrl.isBlank() ? null : avatarUrl)
                                .isStudentVerified(false)
                                .isEmployeeVerified(false)
                                .createdAt(LocalDateTime.now())
                                .build()
                ));
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found: @" + username));
    }

    @Transactional
    public User update(UUID id,
                       boolean hasUsername, String username,
                       boolean hasName, String name,
                       boolean hasSurname, String surname,
                       boolean hasPatronymic, String patronymic,
                       boolean hasStatus, String status,
                       boolean hasAvatarUrl, String avatarUrl) {

        User user = getById(id);

        if (hasUsername) {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("Username cannot be empty");
            }
            if (!username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
                throw new UsernameAlreadyTakenException("Username already taken: " + username);
            }
            user.setUsername(username);
        }
        if (hasName)       user.setName(name);
        if (hasSurname)    user.setSurname(surname);
        if (hasPatronymic) user.setPatronymic(patronymic);
        if (hasStatus)     user.setStatus(status);
        if (hasAvatarUrl)  user.setAvatarUrl(avatarUrl);

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

        boolean exists = subscriptionRepository
                .existsBySubscriberIdAndTargetUserId(subscriberId, targetUserId);
        if (!exists) {
            subscriptionRepository.save(
                    new Subscription(subscriberId, targetUserId, LocalDateTime.now())
            );
        }
    }

    @Transactional
    public void unsubscribe(UUID subscriberId, UUID targetUserId) {
        subscriptionRepository.deleteBySubscriberIdAndTargetUserId(subscriberId, targetUserId);
    }

}