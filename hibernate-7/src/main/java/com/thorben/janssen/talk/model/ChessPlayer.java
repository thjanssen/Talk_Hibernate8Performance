package com.thorben.janssen.talk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ChessPlayer {

    @Id
    private Long id;

    private String firstName;

    private String lastName;

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }
}
