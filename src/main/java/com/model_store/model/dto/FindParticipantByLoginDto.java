package com.model_store.model.dto;

import com.model_store.model.base.Participant;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public class FindParticipantByLoginDto extends Participant {
    private Long imageId;
}
