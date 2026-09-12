package com.bank.aml.security;

import com.bank.aml.config.AmlProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验。
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;

    private final long validityMs;

    private final Clock clock;

    public JwtTokenProvider(AmlProperties properties, Clock clock) {
        this(properties.security().jwtSecret(), properties.security().jwtValidityHours(), clock);
    }

    JwtTokenProvider(String secret, long validityHours, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validityMs = validityHours * 3600_000L;
        this.clock = clock;
    }

    public String createToken(String username, String role, int tokenVersion) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
            .subject(username)
            .claim("role", role)
            .claim("ver", tokenVersion)
            // JJWT 的边界 API 仍接收 Date；业务时间在进入库前始终使用注入 Clock/Instant。
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(Duration.ofMillis(validityMs))))
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
        }
        catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

}
