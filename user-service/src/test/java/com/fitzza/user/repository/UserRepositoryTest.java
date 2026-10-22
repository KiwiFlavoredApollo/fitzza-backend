package com.fitzza.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.user.domain.User;
import com.fitzza.user.domain.UserBody;
import com.fitzza.user.support.PostgresModeJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@PostgresModeJpaTest
class UserRepositoryTest {

    private static final String ENCODED_PASSWORD = "{bcrypt}encoded-password";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBodyRepository userBodyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savedUserIsFoundByItsNormalizedEmailAndStartsActive() {
        User saved = userRepository.saveAndFlush(User.create("  Fit@Example.com ", ENCODED_PASSWORD, "fitzza"));
        entityManager.clear();

        User found = userRepository.findByEmail("fit@example.com").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmail()).isEqualTo("fit@example.com");
        assertThat(found.getNickname()).isEqualTo("fitzza");
        assertThat(found.isActive()).isTrue();
        assertThat(userRepository.findByEmail("Fit@Example.com")).isEmpty();
    }

    @Test
    void existenceChecksSeeSavedEmailAndNickname() {
        userRepository.saveAndFlush(User.create("fit@example.com", ENCODED_PASSWORD, "fitzza"));

        assertThat(userRepository.existsByEmail("fit@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();
        assertThat(userRepository.existsByNickname("fitzza")).isTrue();
        assertThat(userRepository.existsByNickname("newbie")).isFalse();
    }

    @Test
    void theDatabaseRejectsASecondAccountWithTheSameEmail() {
        userRepository.saveAndFlush(User.create("fit@example.com", ENCODED_PASSWORD, "fitzza"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                        User.create("FIT@example.com", ENCODED_PASSWORD, "newbie")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsASecondAccountWithTheSameNickname() {
        userRepository.saveAndFlush(User.create("fit@example.com", ENCODED_PASSWORD, "fitzza"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                        User.create("other@example.com", ENCODED_PASSWORD, "fitzza")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyBodyRowIsStoredForAUserAndReadBackWithNoValues() {
        User user = userRepository.saveAndFlush(User.create("fit@example.com", ENCODED_PASSWORD, "fitzza"));
        userBodyRepository.saveAndFlush(UserBody.emptyFor(user.getId()));
        entityManager.clear();

        UserBody body = userBodyRepository.findById(user.getId()).orElseThrow();

        assertThat(body.getHeight()).isNull();
        assertThat(body.getGender()).isNull();
        assertThat(body.getPreferredStyles()).isEmpty();
    }
}
