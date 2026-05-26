package com.ticketing.controller;

import com.ticketing.dto.auth.UserAdminResponse;
import com.ticketing.entity.enums.UserRole;
import com.ticketing.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    /**
     * 전체 사용자 목록 조회
     */
    @GetMapping
    public ResponseEntity<Page<UserAdminResponse>> getUsers(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(userService.getUsers(pageable));
    }

    /**
     * 사용자 역할 변경
     */
    @PatchMapping("/{id}/role")
    public ResponseEntity<UserAdminResponse> updateRole(
            @PathVariable Long id,
            @RequestParam UserRole role
    ) {
        return ResponseEntity.ok(userService.updateUserRole(id, role));
    }
}
