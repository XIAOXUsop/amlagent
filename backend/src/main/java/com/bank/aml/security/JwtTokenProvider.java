package com.bank.aml.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long validityMs;

    public JwtTokenProvider(@Value("${aml.security.jwt-secret}") String secret,
                            @Value("${aml.security.jwt-validity-hours:24}") long validityHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validityMs = validityHours * 3600_000L;
    }

    public String createToken(String username, String role, int tokenVersion) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("ver", tokenVersion)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + validityMs))
                .signWith(key)
                .compact();
    }

    /** 读取令牌签发时的 tokenVersion；旧令牌（无 ver claim）按 -1 处理，一律视为待吊销。 */
    public int tokenVersion(Claims claims) {
        Object ver = claims.get("ver");
        return ver instanceof Number number ? number.intValue() : -1;
    }

    public boolean validate(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
