package com.thorben.janssen.talk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class ChessGame {

    @Id
    private Long id;

    private int round;

    @ManyToOne(fetch = FetchType.LAZY)
    private ChessTournament tournament;

    public Long getId() {
        return id;
    }

    public int getRound() {
        return round;
    }

    public void nextRound() {
        round++;
    }

    public ChessTournament getTournament() {
        return tournament;
    }
}
