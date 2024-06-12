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

    private String email;

    private String nickname;

    @Column(unique = true)
    private String phonenumber;

    private String naverid;

    private String googleemail;

    private int code;

    @CreationTimestamp
    private LocalDateTime createdate;
}

