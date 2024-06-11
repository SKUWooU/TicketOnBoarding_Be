package com.onticket.user.service;

import com.onticket.user.domain.RefreshToken;
import com.onticket.user.repository.RefreshTokenRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public void saveRefreshToken(String token, String username) {

        //아이디로 토큰 검색 있으면 새토큰으로 수정,없으면 생성
        Optional<RefreshToken> _refreshToken = refreshTokenRepository.findByUsername(username);
        if (_refreshToken.isEmpty()) {
            RefreshToken refreshToken = new RefreshToken();
            refreshToken.setToken(token);
            refreshToken.setUsername(username);
            refreshTokenRepository.save(refreshToken);
        } else {
            RefreshToken existingToken = _refreshToken.get();
            existingToken.setToken(token);
            refreshTokenRepository.save(existingToken);
        }
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    public void deleteRefreshToken(String token) {
        refreshTokenRepository.deleteByToken(token);
    }
}
