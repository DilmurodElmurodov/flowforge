package com.flowforge.core.security.auth;

import com.flowforge.core.security.AccountDirectory;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.core.security.auth.AuthDtos.TokenResponse;
import com.flowforge.core.security.jwt.JwtTokenProvider;
import com.flowforge.core.security.token.RefreshTokenService;
import com.flowforge.core.tenant.TenantContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Application service orchestrating login, refresh-token rotation and logout.
 * Methods are deliberately <em>not</em> transactional: each collaborator owns its own transaction, so a
 * {@code BadCredentialsException} raised after a reuse-detection revocation cannot roll that revocation back.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokens;
    private final AccountDirectory accounts;

    public AuthService(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokens, AccountDirectory accounts) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
    }

    public TokenResponse login(String username, String password) {
        TenantContext.require();   // fail fast with 400 when X-Tenant-ID is missing
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        UserPrincipal principal = ((UserPrincipal) authentication.getPrincipal()).eraseCredentials();
        return mint(principal);
    }

    public TokenResponse refresh(String rawRefreshToken) {
        RefreshTokenService.IssuedToken rotated = refreshTokens.rotate(rawRefreshToken);
        UserPrincipal principal = accounts.findById(rotated.record().getTenantId(), rotated.record().getUserId())
                .filter(UserPrincipal::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Account is no longer active"));
        return TokenResponse.bearer(tokenProvider.createAccessToken(principal), rotated.rawToken(),
                tokenProvider.accessTokenTtlSeconds());
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    private TokenResponse mint(UserPrincipal principal) {
        RefreshTokenService.IssuedToken refresh = refreshTokens.issue(TenantContext.require(), principal.getId());
        return TokenResponse.bearer(tokenProvider.createAccessToken(principal), refresh.rawToken(),
                tokenProvider.accessTokenTtlSeconds());
    }
}
