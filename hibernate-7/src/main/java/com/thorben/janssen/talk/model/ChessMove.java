package com.thorben.janssen.talk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;

@Entity
public class ChessMove {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "move_seq")
    @SequenceGenerator(name = "move_seq", allocationSize = 50)
    private Long id;

    private int moveNumber;

    private String notation;

    public ChessMove() {
    }

    public ChessMove(int moveNumber, String notation) {
        this.moveNumber = moveNumber;
        this.notation = notation;
    }

    public Long getId() {
        return id;
    }

    public int getMoveNumber() {
        return moveNumber;
    }

    public String getNotation() {
        return notation;
    }
}
