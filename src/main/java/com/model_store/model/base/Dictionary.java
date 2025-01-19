package com.model_store.model.base;

import com.model_store.model.constant.DictionaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "dictionary")
public class Dictionary {
    private DictionaryType type;
    private String value;
    private String description;
}
