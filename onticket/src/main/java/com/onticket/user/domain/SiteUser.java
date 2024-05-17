package com.onticket.user.domain;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Setter
@Getter
@Entity
public class SiteUser {


//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;

    @Id
    private String username;

    private String password;

    @Column(unique = true)
    private String email;

    private String nickname;

    @Column(unique = true)
    private String phonenumber;

    @CreationTimestamp
    private LocalDateTime createdat;
}

