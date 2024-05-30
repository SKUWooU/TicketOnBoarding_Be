package com.onticket.user.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.issuer}")
    private String issuer;

    @Value("${jwt.secret_key}")
    private String secretKey;

    private static final long ACCESS_TOKEN_EXPIRATION_TIME = 900 * 1000; // 1 day
    private static final long REFRESH_TOKEN_EXPIRATION_TIME = 2592000 * 1000; // 30 days

    //access 토큰 생성
    public String generateAccessToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setExpiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION_TIME))
                .signWith(SignatureAlgorithm.HS512, secretKey)
                .compact();
    }
    //리프레시 토큰 생성
    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setExpiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION_TIME))
                .signWith(SignatureAlgorithm.HS512, secretKey)
                .compact();
    }

    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .setSigningKey(secretKey)
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean validateToken(String token, String username) {
        Claims claims = getClaimsFromToken(token);
        return claims.getSubject().equals(username) && !claims.getExpiration().before(new Date());
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}