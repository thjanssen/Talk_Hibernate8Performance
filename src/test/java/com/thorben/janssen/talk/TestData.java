package com.thorben.janssen.talk;

import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

/**
 * Creates the schema and generates the demo data with SQL - much faster than persisting entities.
 */
public class TestData {

    public static final int TOURNAMENTS = 10_000;
    public static final int GAMES_PER_TOURNAMENT = 20;
    public static final int PLAYERS = 1_000;
    public static final int PLAYERS_PER_TOURNAMENT = 16;

    public static EntityManagerFactory createEntityManagerFactory() {
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("talk",
                Map.of("hibernate.session.events.auto", ExecutedStatements.class.getName()));
        insertData(emf);
        return emf;
    }

    static void insertData(EntityManagerFactory emf) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("""
                insert into ChessTournament (id, name)
                select t, 'Tournament ' || t from generate_series(1, %d) t""".formatted(TOURNAMENTS)).executeUpdate();
        em.createNativeQuery("""
                insert into ChessGame (id, round, tournament_id)
                select (t - 1) * %1$d + r, r, t from generate_series(1, %2$d) t, generate_series(1, %1$d) r"""
                .formatted(GAMES_PER_TOURNAMENT, TOURNAMENTS)).executeUpdate();
        em.createNativeQuery("""
                insert into ChessPlayer (id, firstName, lastName)
                select p, 'First ' || p, 'Last ' || p from generate_series(1, %d) p""".formatted(PLAYERS)).executeUpdate();
        em.createNativeQuery("""
                insert into ChessTournament_ChessPlayer (ChessTournament_id, players_id)
                select t, (t + p) %% %d + 1 from generate_series(1, %d) t, generate_series(1, %d) p"""
                .formatted(PLAYERS, TOURNAMENTS, PLAYERS_PER_TOURNAMENT)).executeUpdate();
        em.createNativeQuery("analyze").executeUpdate();
        em.getTransaction().commit();
        em.close();
    }

}
