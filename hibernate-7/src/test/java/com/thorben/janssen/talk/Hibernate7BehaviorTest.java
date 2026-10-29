package com.thorben.janssen.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.Version;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.thorben.janssen.talk.model.ChessMove;
import com.thorben.janssen.talk.model.ChessTournament;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * The examples of HibernatePerformanceTest that behave differently with Hibernate 7.3 -
 * same code, same data, same database.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Hibernate7BehaviorTest {

    private static final Logger log = LogManager.getLogger(Hibernate7BehaviorTest.class);

    private static final int MOVES = 10_000;

    private static EntityManagerFactory emf;

    /**
     * JOIN FETCH with setFirstResult/setMaxResults. Up to Hibernate 7.3, Hibernate can't paginate in SQL when fetching
     * a collection: it logs HHH90003004, selects all 200,000 rows and paginates in memory -
     * 210,000 entities in the persistence context for 10 tournaments. Since 7.4: a subquery, 210 entities.
     */
    @Test
    @Order(1)
    public void paginationWithJoinFetch() {
        List<ChessTournament> tournaments = measure("JOIN FETCH with pagination", () -> {
            EntityManager em = emf.createEntityManager();
            List<ChessTournament> page = em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.games
                    ORDER BY t.id""", ChessTournament.class)
                    .setFirstResult(100)
                    .setMaxResults(10)
                    .getResultList();
            int managedEntities = em.unwrap(Session.class).getStatistics().getEntityCount();
            log.info("{} entities in the persistence context", managedEntities);
            assertEquals(TestData.TOURNAMENTS * (1 + TestData.GAMES_PER_TOURNAMENT), managedEntities);
            em.close();
            return page;
        });

        assertEquals(10, tournaments.size());
        assertEquals(101L, tournaments.get(0).getId());
        tournaments.forEach(t -> assertEquals(TestData.GAMES_PER_TOURNAMENT, t.getGames().size()));
    }

    /**
     * Without hibernate.jdbc.batch_size, Hibernate 7.3 doesn't batch: 10,000 single inserts.
     * Hibernate 8 uses the dialect's default batch size - 15 for PostgreSQL.
     */
    @Test
    @Order(2)
    public void defaultJdbcBatchSize() {
        measure("Default JDBC batch size: " + MOVES + " inserts", () -> {
            EntityManager em = emf.createEntityManager();
            em.getTransaction().begin();
            for (int i = 1; i <= MOVES; i++) {
                em.persist(new ChessMove(i, "e4"));
            }
            em.getTransaction().commit();
            em.close();
            return null;
        });

        assertEquals(0, ExecutedStatements.batches());
    }

    /** Runs the use case twice and logs time, statements and batches of the 2nd run. */
    private static <T> T measure(String useCase, Supplier<T> run) {
        run.get();
        ExecutedStatements.reset();
        long start = System.nanoTime();
        T result = run.get();
        log.info("{}: {} ms, {} statements, {} batches", useCase, (System.nanoTime() - start) / 1_000_000,
                ExecutedStatements.statements(), ExecutedStatements.batches());
        return result;
    }

    @BeforeAll
    public static void init() {
        emf = TestData.createEntityManagerFactory();
        log.info("Hibernate {}", Version.getVersionString());
    }

    @AfterAll
    public static void close() {
        emf.close();
    }
}
