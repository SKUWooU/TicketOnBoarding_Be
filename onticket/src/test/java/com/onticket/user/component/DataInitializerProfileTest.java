package com.onticket.user.component;

import com.onticket.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DataInitializerProfileTest {

    @Test
    void loadtestProfileDoesNotRegisterAdminInitializer() {
        assertEquals(0, beanCountForProfile("loadtest"));
    }

    @Test
    void localProfileKeepsExistingAdminInitializer() {
        assertEquals(1, beanCountForProfile("local"));
    }

    private int beanCountForProfile(String profile) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(UserRepository.class, () -> mock(UserRepository.class));
            context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
            context.register(DataInitializer.class);
            context.refresh();
            return context.getBeansOfType(DataInitializer.class).size();
        }
    }
}
