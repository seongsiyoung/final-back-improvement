package com.example.finalproject.user.repository;

import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findWithLockByEmail(String email);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    List<User> findAllByDeletedAtIsNull();

    Page<User>findAllBy(Pageable pageable);

    Page<User>findAllByDeletedAtIsNull(Pageable pageable);

    Page<User>findAllByDeletedAtIsNotNull(Pageable pageable);

    long countByDeletedAtIsNull();

    long countByDeletedAtIsNullAndStatus(UserStatus status);

    long countByDeletedAtIsNullAndCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("""
            SELECT u
            FROM User u
            WHERE u.deletedAt IS NULL
              AND (:status IS NULL OR u.status = :status)
              AND (
                    :keyword IS NULL OR :keyword = ''
                    OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR REPLACE(u.phone, '-', '') LIKE CONCAT('%', REPLACE(:keyword, '-', ''), '%')
              )
            """)
    Page<User> searchUsersForAdmin(@Param("keyword") String keyword,
                                   @Param("status") UserStatus status,
                                   Pageable pageable);

    @Query("""
            SELECT DISTINCT u
            FROM User u
            JOIN u.userRoles ur
            JOIN ur.role r
            WHERE u.deletedAt IS NULL
              AND r.roleName = 'CUSTOMER'
              AND NOT EXISTS (
                    SELECT 1
                    FROM UserRole urx
                    JOIN urx.role rx
                    WHERE urx.user = u
                      AND rx.roleName IN ('STORE', 'RIDER', 'ADMIN')
              )
              AND (:status IS NULL OR u.status = :status)
              AND (
                    :keyword IS NULL OR :keyword = ''
                    OR LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR REPLACE(u.phone, '-', '') LIKE CONCAT('%', REPLACE(:keyword, '-', ''), '%')
              )
            """)
    Page<User> searchCustomerUsersForAdmin(@Param("keyword") String keyword,
                                           @Param("status") UserStatus status,
                                           Pageable pageable);

    @Query("""
            SELECT COUNT(DISTINCT u.id)
            FROM User u
            JOIN u.userRoles ur
            JOIN ur.role r
            WHERE u.deletedAt IS NULL
              AND r.roleName = 'CUSTOMER'
              AND NOT EXISTS (
                    SELECT 1
                    FROM UserRole urx
                    JOIN urx.role rx
                    WHERE urx.user = u
                      AND rx.roleName IN ('STORE', 'RIDER', 'ADMIN')
              )
            """)
    long countCustomerUsers();

    @Query("""
            SELECT COUNT(DISTINCT u.id)
            FROM User u
            JOIN u.userRoles ur
            JOIN ur.role r
            WHERE u.deletedAt IS NULL
              AND r.roleName = 'CUSTOMER'
              AND NOT EXISTS (
                    SELECT 1
                    FROM UserRole urx
                    JOIN urx.role rx
                    WHERE urx.user = u
                      AND rx.roleName IN ('STORE', 'RIDER', 'ADMIN')
              )
              AND u.status = :status
            """)
    long countCustomerUsersByStatus(@Param("status") UserStatus status);

    @Query("""
            SELECT COUNT(DISTINCT u.id)
            FROM User u
            JOIN u.userRoles ur
            JOIN ur.role r
            WHERE u.deletedAt IS NULL
              AND r.roleName = 'CUSTOMER'
              AND NOT EXISTS (
                    SELECT 1
                    FROM UserRole urx
                    JOIN urx.role rx
                    WHERE urx.user = u
                      AND rx.roleName IN ('STORE', 'RIDER', 'ADMIN')
              )
              AND u.createdAt BETWEEN :from AND :to
            """)
    long countCustomerUsersByCreatedAtBetween(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    @Query("""
            SELECT DISTINCT u
            FROM User u
            JOIN u.userRoles ur
            JOIN ur.role r
            WHERE u.deletedAt IS NULL
              AND r.roleName = :roleName
            """)
    List<User> findAllActiveByRoleName(@Param("roleName") String roleName);
}
