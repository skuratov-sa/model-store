package com.model_store.model.base;

import com.model_store.model.constant.AddressStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "address")
public class Address {

    @Id
    private Long id;
    private String country;
    private String city;
    private String street;
    private String houseNumber;
    private String apartmentNumber;
    private Integer index;
    private AddressStatus status;


    public String getFullAddress() {
        return country + " г. " + city + " ул. " + street + " " + houseNumber + " кв. " + apartmentNumber + ", индекс: " + index;
    }
}