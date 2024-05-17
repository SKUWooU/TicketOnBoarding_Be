package com.onticket.user.service;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.role.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


//로그인 데이터 처리
//UserDetailsService->스프링 시큐리티가 제공
@RequiredArgsConstructor
@Service
public class UserSecurityService implements UserDetailsService {
    private final UserRepository userRepository;

    // loadUserByUsername 메서드는 사용자명으로 스프링 시큐리티의 사용자 객체를 조회하여 리턴하는 메서드
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException{
        Optional<SiteUser> _siteUser=this.userRepository.findOptionalByUsername(username);
        List<GrantedAuthority> authorities = new ArrayList<>();

        //사용자명으로 객체를 조회하고 데이터가 없으면 오류발생시킴
        if(_siteUser.isEmpty()){
            throw new UsernameNotFoundException("사용자를 찾을수 없습니다.");
        }

        SiteUser siteUser = _siteUser.get();

        //계정 이름이 admin이면 관리자권한 부여
        if("admin".equals(username)){
            authorities.add(new SimpleGrantedAuthority(UserRole.ADMIN.getValue()));

        //아니면 유저권한 부여
        } else{
            authorities.add(new SimpleGrantedAuthority(UserRole.USER.getValue()));
        }
        return new User(siteUser.getUsername(),siteUser.getPassword(), authorities);
    }

}