package com.model_store.model.base;

import com.model_store.model.constant.ParticipantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "participant")
public class Participant {

    @Id
    private Long id;
    private String login;
    private String mail;
    private String fullName;
    private String phoneNumber;
    private ParticipantStatus state;
    @Column("createdat")
    private Instant createdAt;

    @Transient
    private List<SocialNetwork> socialNetworks;

    @Transient
    private List<Address> address;

    @Transient
    private List<Product> products;
    @Transient
    private List<Image> images;
}