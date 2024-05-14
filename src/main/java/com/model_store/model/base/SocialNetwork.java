package com.model_store.model.base;

import com.model_store.model.constant.SocialNetworkType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "social_network")
public class SocialNetwork {

    @Id
    private Long id;
    private SocialNetworkType type;
    private String login;
    private Long participantId;
}