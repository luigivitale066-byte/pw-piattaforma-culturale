package it.tesi.cultural.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.io.Encoders;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void createsSignedTokenWithUsernameAndRole() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        JwtService jwtService = new JwtService(Encoders.BASE64.encode(secret), 3600);

        String token = jwtService.createToken("alice", "ROLE_USER");
        var claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("roles")).isEqualTo(java.util.List.of("ROLE_USER"));
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(3600);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        // Una firma creata con una chiave diversa non deve essere accettata dal parser.
        byte[] firstSecret = new byte[32];
        byte[] secondSecret = new byte[32];
        new SecureRandom().nextBytes(firstSecret);
        new SecureRandom().nextBytes(secondSecret);
        JwtService first = new JwtService(Encoders.BASE64.encode(firstSecret), 3600);
        JwtService second = new JwtService(Encoders.BASE64.encode(secondSecret), 3600);
        String token = first.createToken("alice", "ROLE_USER");
        assertThatThrownBy(() -> second.parseToken(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
