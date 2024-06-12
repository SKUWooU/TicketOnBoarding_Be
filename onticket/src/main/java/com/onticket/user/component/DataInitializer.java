package com.onticket.user.component;

import com.onticket.user.domain.SiteUser;
import com.onticket.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;


@RequiredArgsConstructor
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.findByUsername("admin") == null) {
            SiteUser admin = new SiteUser();
            admin.setUsername("admin");
            admin.setEmail("admin@example.com");
            admin.setNickname("Administrator");
            admin.setPhonenumber("01068549901");
            admin.setPassword(passwordEncoder.encode("adminpassword"));
            admin.setCode(3);
            userRepository.save(admin);
        }
    }
}