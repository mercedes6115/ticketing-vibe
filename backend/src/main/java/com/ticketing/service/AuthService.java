package com.ticketing.service;

import com.ticketing.config.JwtTokenProvider;
import com.ticketing.dto.auth.LoginRequest;
import com.ticketing.dto.auth.ReissueRequest;
import com.ticketing.dto.auth.SignupRequest;
import com.ticketing.dto.auth.TokenResponse;
import com.ticketing.entity.User;
import com.ticketing.entity.enums.UserRole;
import com.ticketing.exception.NotFoundException;
import com.ticketing.exception.UnauthorizedException;
import com.ticketing.repository.UserRepository;
import com.ticketing.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private static final String AUTH_FAIL_PREFIX = "auth:fail:";
    private static final int MAX_FAIL_COUNT = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalStateException("이미 사용 중인 이메일입니다.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .role(UserRole.USER)
                .build();
        userRepository.save(user);

        log.info("User signed up: email={}", user.getEmail());

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        String tokenKey = REFRESH_TOKEN_PREFIX + user.getId();

        // DB 커밋 성공 후 저장 — 커밋 실패 시 7일 토큰 잔류 방지
        TransactionUtils.afterCommit(() -> redisTemplate.opsForValue().set(tokenKey, refreshToken, REFRESH_TOKEN_TTL));

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole().name())
                .build();
    }

    public TokenResponse login(LoginRequest request) {
        String failKey = AUTH_FAIL_PREFIX + request.getEmail();

        Object failCount = redisTemplate.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount.toString()) >= MAX_FAIL_COUNT) {
            throw new UnauthorizedException("로그인 시도 횟수를 초과했습니다. 15분 후 다시 시도해주세요.");
        }

        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1) {
                // 첫 실패 시에만 TTL 설정 — 이후 실패는 같은 창 안에서 누적
                redisTemplate.expire(failKey, LOCKOUT_DURATION);
            }
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        redisTemplate.delete(failKey);

        log.info("User logged in: email={}", user.getEmail());
        return issueTokens(user);
    }

    public TokenResponse reissue(ReissueRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new UnauthorizedException("유효하지 않은 리프레시 토큰입니다.");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String tokenKey = REFRESH_TOKEN_PREFIX + userId;
        Object stored = redisTemplate.opsForValue().get(tokenKey);

        if (stored == null || !refreshToken.equals(stored.toString())) {
            throw new UnauthorizedException("만료되었거나 유효하지 않은 리프레시 토큰입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> NotFoundException.user(userId));

        redisTemplate.delete(tokenKey);
        return issueTokens(user);
    }

    public void logout(Long userId) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
        log.info("User logged out: userId={}", userId);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                REFRESH_TOKEN_TTL
        );

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole().name())
                .build();
    }
}
