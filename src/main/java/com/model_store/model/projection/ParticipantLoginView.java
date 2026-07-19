package com.model_store.model.projection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantLoginView {
    @Column("participant_id")
    private Long participantId;
    private String login;
}
