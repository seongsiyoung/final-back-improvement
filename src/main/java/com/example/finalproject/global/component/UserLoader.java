package com.example.finalproject.global.component;

import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserLoader {
    private final UserRepository userRepository;

    /**
     유저 불러오기(이메일)
     */
    public User loadUserByUsername(String username) {
        return userRepository.findByEmail(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User loadUserByUsernameWithLock(String username) {
        return userRepository.findWithLockByEmail(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 유저 불러오기(ID)
     */
    public User loadUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 삭제되지 않은 유저 불러오기(이메일)
     */
    public User loadNatDeleteUserByUserName(String username) {
        return userRepository.findByEmailAndDeletedAtIsNull(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * 모든 유저 불러오기(삭제된 유저 포함)
     */
    public Page<User> loadAllUsers(Pageable pageable){
        return userRepository.findAllBy(pageable);
    }

    /**
     * 모든 유저 불러오기(삭제된 유저 포함)
     */
    public Page<User> loadAllNatDeleteUsers(Pageable pageable){
        return userRepository.findAllByDeletedAtIsNull(pageable);
    }

    /**
     * 모든 유저 불러오기(삭제된 유저만)
     */
    public Page<User> loadAllDeleteUsers(Pageable pageable){
        return userRepository.findAllByDeletedAtIsNotNull(pageable);
    }
}
