package com.ticketing.dto.auth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String email;
    private String nickname;
    private String role;
}
