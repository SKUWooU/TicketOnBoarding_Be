package com.onticket.user.repository;

import com.onticket.user.domain.SiteUser;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<SiteUser, String> {
    Optional<SiteUser> findOptionalByUsername(String username);
    SiteUser  findByUsername(String username);
    Optional<SiteUser> findSiteUserByPhonenumberAndEmail(String phonenumber, String email);
    Optional<SiteUser> findOptionalSiteUserByPhonenumber(String phonenumber);
    SiteUser findSiteUserByPhonenumber(String phonenumber);
    boolean existsByUsername(String username);

    @Modifying
    @Query(value = """
            INSERT INTO site_user
                (username, password, email, nickname, phonenumber, code, createdate)
            VALUES
                (:username, :password, :email, :nickname, :phonenumber, :code, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE username = VALUES(username)
            """, nativeQuery = true)
    int insertLoadTestFixtureUser(
            @Param("username") String username,
            @Param("password") String password,
            @Param("email") String email,
            @Param("nickname") String nickname,
            @Param("phonenumber") String phonenumber,
            @Param("code") int code
    );

    long countByUsernameStartingWith(String usernamePrefix);

    void deleteByUsername(String username);
//    boolean existsByEmail(String email);
    SiteUser findByNaverid(String naverId);
    SiteUser findByGoogleemail(String googleEmail);
}
