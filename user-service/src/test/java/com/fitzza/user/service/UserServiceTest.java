package com.fitzza.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.user.domain.User;
import com.fitzza.user.domain.UserBody;
import com.fitzza.user.dto.DuplicateField;
import com.fitzza.user.dto.SignupRequest;
import com.fitzza.user.exception.DuplicateValueException;
import com.fitzza.user.exception.ErrorCode;
import com.fitzza.user.repository.UserBodyRepository;
import com.fitzza.user.repository.UserRepository;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final SignupRequest REQUEST = new SignupRequest(" Fit@Example.com ", "password1", "fitzza");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBodyRepository userBodyRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    /**
     * 모의 저장소와 인코더로 가입 서비스의 저장 및 오류 처리 흐름을 검증할 준비를 한다.
     */
    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userBodyRepository, passwordEncoder);
    }

    /**
     * 가입 시 이메일 정규화·비밀번호 해싱·활성 상태 및 같은 ID의 빈 프로필 저장을 검증한다.
     */
    @Test
    void signUpStoresNormalizedEmailEncodedPasswordAndEmptyBodyRow() {
        when(passwordEncoder.encode("password1")).thenReturn("ENCODED");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 7L);
            return user;
        });

        Long userId = userService.signUp(REQUEST);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<UserBody> savedBody = ArgumentCaptor.forClass(UserBody.class);
        verify(userRepository).saveAndFlush(savedUser.capture());
        verify(userBodyRepository).save(savedBody.capture());
        assertThat(userId).isEqualTo(7L);
        assertThat(savedUser.getValue().getEmail()).isEqualTo("fit@example.com");
        assertThat(savedUser.getValue().getPassword()).isEqualTo("ENCODED");
        assertThat(savedUser.getValue().getNickname()).isEqualTo("fitzza");
        assertThat(savedUser.getValue().isActive()).isTrue();
        assertThat(savedBody.getValue().getUserId()).isEqualTo(7L);
    }

    /**
     * 이메일만 중복이면 email 하나만 담아 사용자 저장 전에 거절하는지 검증한다.
     */
    @Test
    void signUpRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("fit@example.com")).thenReturn(true);

        assertDuplicates(DuplicateField.EMAIL);
    }

    /**
     * 닉네임만 중복이면 nickname 하나만 담아 사용자 저장 전에 거절하는지 검증한다.
     */
    @Test
    void signUpRejectsDuplicateNickname() {
        when(userRepository.existsByNickname("fitzza")).thenReturn(true);

        assertDuplicates(DuplicateField.NICKNAME);
    }

    /**
     * 이메일과 닉네임이 모두 중복이면 두 필드를 이메일·닉네임 순서로 함께 알리는지 검증한다.
     */
    @Test
    void signUpReportsBothDuplicatesAtOnce() {
        when(userRepository.existsByEmail("fit@example.com")).thenReturn(true);
        when(userRepository.existsByNickname("fitzza")).thenReturn(true);

        assertDuplicates(DuplicateField.EMAIL, DuplicateField.NICKNAME);
    }

    private void assertDuplicates(DuplicateField... expected) {
        assertThatThrownBy(() -> userService.signUp(REQUEST))
                .isInstanceOfSatisfying(DuplicateValueException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_VALUE);
                    assertThat(exception.getFields()).containsExactly(expected);
                });
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    /**
     * 중첩된 알려진 고유 제약 위반을 해당 필드의 중복으로 변환하고 프로필 저장을 중단하는지 검증한다.
     */
    @ParameterizedTest
    @CsvSource({"uk_users_email, email", "uk_users_nickname, nickname"})
    void signUpReportsConflictWhenUniqueConstraintFailsOnConcurrentRequest(String constraintName, String field) {
        when(passwordEncoder.encode("password1")).thenReturn("ENCODED");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key",
                        new RuntimeException(new ConstraintViolationException(
                                "duplicate key", new SQLException("duplicate key", "23505"), constraintName))));

        assertThatThrownBy(() -> userService.signUp(REQUEST))
                .isInstanceOfSatisfying(DuplicateValueException.class, exception -> assertThat(exception.getFields())
                        .extracting(DuplicateField::field)
                        .containsExactly(field));
        verify(userBodyRepository, never()).save(any(UserBody.class));
    }

    /**
     * 알 수 없거나 계정 중복과 무관한 제약 위반의 원래 예외가 유지되는지 검증한다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"users_pkey", "fk_users_account", "users_status_check", "uk_users_email_other"})
    void signUpRethrowsUnrelatedOrUnknownConstraintViolations(String constraintName) {
        DataIntegrityViolationException exception = new DataIntegrityViolationException("integrity violation",
                new ConstraintViolationException("integrity violation", new SQLException(), constraintName));
        assertSignUpRethrows(exception);
    }

    /**
     * 원인 예외가 없을 때 메시지만으로 계정 중복을 추정하지 않는지 검증한다.
     */
    @Test
    void signUpRethrowsIntegrityViolationWithoutCause() {
        assertSignUpRethrows(new DataIntegrityViolationException("duplicate key"));
    }

    /**
     * 메시지에 계정 제약 이름이 있어도 다른 종류의 원인을 중복으로 변환하지 않는지 검증한다.
     */
    @Test
    void signUpRethrowsOtherIntegrityCausesEvenWhenMessageMentionsAccountConstraint() {
        assertSignUpRethrows(new DataIntegrityViolationException("integrity violation",
                new SQLException("uk_users_email", "23502")));
    }

    /**
     * 원래 무결성 예외가 그대로 전달되고 프로필이 저장되지 않는지 확인한다.
     */
    private void assertSignUpRethrows(DataIntegrityViolationException exception) {
        when(passwordEncoder.encode("password1")).thenReturn("ENCODED");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(exception);

        assertThatThrownBy(() -> userService.signUp(REQUEST)).isSameAs(exception);
        verify(userBodyRepository, never()).save(any(UserBody.class));
    }
}
