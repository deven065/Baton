package com.bramhastra.baton.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.tenant.Tenant;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Duration expiry;

    public JwtService(JwtEncoder encoder, @Value("${baton.jwt.expiry-hours:8}") long expiryHours) {
        this.encoder = encoder;
        this.expiry = Duration.ofHours(expiryHours);
    }

    public IssuedToken issue(AppUser user, Tenant tenant) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiry);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("baton")
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", user.getDisplayName())
                // The workspace is part of the signed token: every request is routed to this company's database.
                .claim("tid", tenant.id().toString())
                .claim("ws", tenant.slug())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
