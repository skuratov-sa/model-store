package com.model_store.model;

import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

@Builder
@Data
@AllArgsConstructor
public class CustomUserDetails implements UserDetails {
    private Long id;
    private String login;
    private String password;
    private String role;  // Роль пользователя
    private String email;
    private String fullName;
    private ParticipantStatus status;
    private Long imageId;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Возвращаем роль как GrantedAuthority
        return Collections.singletonList(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return login;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status == ParticipantStatus.ACTIVE;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public static CustomUserDetails fromUser(Participant participant, Long imageId) {
        return CustomUserDetails.builder()
                .id(participant.getId())
                .login(participant.getLogin())
                .email(participant.getMail())
                .fullName(participant.getFullName())
                .role(participant.getRole().name())
                .password(participant.getPassword())
                .imageId(imageId)
                .status(participant.getStatus())
                .build();
    }
}