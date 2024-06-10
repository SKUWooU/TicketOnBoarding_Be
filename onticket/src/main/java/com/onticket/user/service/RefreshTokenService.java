package com.onticket.user.service;

import com.onticket.user.domain.RefreshToken;
import com.onticket.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;

    public void saveRefreshToken(String token, String username) {
        Optional<RefreshToken> _refreshToken = refreshTokenRepository.findByToken(token);
        if (_refreshToken.isEmpty()) {
            RefreshToken refreshToken = new RefreshToken();
            refreshToken.setToken(token);
            refreshToken.setUsername(username);
            refreshTokenRepository.save(refreshToken);
        } else {
            RefreshToken existingToken = _refreshToken.get();
            existingToken.setUsername(username);
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
