package com.ticketing.dto.auth;

import com.ticketing.entity.User;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class UserAdminResponse {

    private final Long id;
    private final String email;
    private final String nickname;
    private final String role;
    private final LocalDateTime createdAt;

    private UserAdminResponse(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.nickname = user.getNickname();
        this.role = user.getRole().name();
        this.createdAt = user.getCreatedAt();
    }

    public static UserAdminResponse from(User user) {
        return new UserAdminResponse(user);
    }
}
