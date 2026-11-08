package com.bramhastra.baton.identity;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_group")
@Getter
@Setter
@NoArgsConstructor
public class UserGroup {

    @Id
    @GeneratedValue
    private UUID id;

    private String name;

    private String description;

    public UserGroup(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
