package com.onticket.user.repository;

import com.onticket.user.domain.SiteUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<SiteUser, String> {
    Optional<SiteUser> findOptionalByUsername(String username);
    SiteUser  findByUsername(String username);
    Optional<SiteUser> findSiteUserByPhonenumberAndEmail(String phonenumber, String email);
    Optional<SiteUser> findOptionalSiteUserByPhonenumber(String phonenumber);
    SiteUser findSiteUserByPhonenumber(String phonenumber);
    boolean existsByUsername(String username);
    void deleteByUsername(String username);
//    boolean existsByEmail(String email);
    SiteUser findByNaverid(String naverId);
    SiteUser findByGoogleemail(String googleEmail);
}