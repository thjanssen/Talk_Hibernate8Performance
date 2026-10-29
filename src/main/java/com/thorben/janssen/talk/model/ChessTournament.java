package com.thorben.janssen.talk.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;

@Entity
public class ChessTournament {

    @Id
    private Long id;

    private String name;

    @OneToMany(mappedBy = "tournament")
    private Set<ChessGame> games = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "ChessTournament_ChessPlayer")
    // Example 2 - before: a List removes all association records and inserts the remaining ones again
    // private List<ChessPlayer> players = new ArrayList<>();
    private Set<ChessPlayer> players = new HashSet<>();

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<ChessGame> getGames() {
        return games;
    }

    public Collection<ChessPlayer> getPlayers() {
        return players;
    }
}
