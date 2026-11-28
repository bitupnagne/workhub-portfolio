package com.portfolio.worksystem.security;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserMapper userMapper;
    private final LoginAttemptService loginAttemptService;

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount user = userMapper.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다");
        }
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new DisabledException("비활성화된 계정입니다");
        }
        List<String> roles = userMapper.findRolesByUserId(user.getId());
        user.setRoles(roles);
        boolean accountNonLocked = !loginAttemptService.isBlocked(username);
        return new CustomUserDetails(user, accountNonLocked);
    }
}
