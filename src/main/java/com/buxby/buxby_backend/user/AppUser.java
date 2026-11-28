package com.buxby.buxby_backend.user;

import jakarta.persistence.*;

@Entity
@Table(
        name = "app_user",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_provider_provider_id",
                        columnNames = {"provider", "provider_id"}
                )
        }
)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String password;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    protected AppUser() {
    }

    public AppUser(
            String email,
            String password,
            String nickname,
            String provider,
            String providerId
    ) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.provider = provider;
        this.providerId = providerId;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }

    /**
     * 이메일이 아직 비어 있는 소셜 회원에게만 새 이메일을 보완한다.
     * 기존에 저장된 이메일은 자동으로 덮어쓰지 않는다.
     *
     * @return 이메일 값이 실제로 변경되었으면 true
     */
    public boolean updateEmailIfMissing(String newEmail) {
        if (newEmail == null || newEmail.isBlank()) {
            return false;
        }

        if (email != null && !email.isBlank()) {
            return false;
        }

        email = newEmail.trim();
        return true;
    }
}
