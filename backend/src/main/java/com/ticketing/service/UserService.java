package com.ticketing.service;

import com.ticketing.dto.auth.UserAdminResponse;
import com.ticketing.entity.User;
import com.ticketing.entity.enums.UserRole;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Page<UserAdminResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserAdminResponse::from);
    }

    @Transactional
    public UserAdminResponse updateUserRole(Long userId, UserRole role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> NotFoundException.user(userId));
        user.updateRole(role);
        return UserAdminResponse.from(user);
    }
}
