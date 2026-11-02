package com.buxby.buxby_backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository
        extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByProviderAndProviderId(
            String provider,
            String providerId
    );

    boolean existsByProviderAndProviderId(
            String provider,
            String providerId
    );
}
