package com.thorben.janssen.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.FetchMethod;
import org.hibernate.Session;
import org.hibernate.SessionCreationOption;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.w3c.dom.NodeList;

import com.thorben.janssen.talk.model.ChessGame;
import com.thorben.janssen.talk.model.ChessMove;
import com.thorben.janssen.talk.model.ChessPlayer;
import com.thorben.janssen.talk.model.ChessTournament;
import com.thorben.janssen.talk.model.TournamentSummary;

import jakarta.persistence.BatchFetch;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ManyToOne;

/**
 * All examples of the talk, in the order of the talk.
 *
 * Data (generated once): 10,000 tournaments, 20 games and 16 of 1,000 players per tournament.
 * Use cases that are compared by time run twice; the 1st run warms up the JVM and the database,
 * the log shows the 2nd one.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HibernatePerformanceTest {

    private static final Logger log = LogManager.getLogger(HibernatePerformanceTest.class);

    private static final int MOVES = 10_000;
    private static final long MAX_TOURNAMENT_ID = 1_000;

    private static EntityManagerFactory emf;

    // ---------------------------------------------------------------------------------------------
    // Wrong annotations
    // ---------------------------------------------------------------------------------------------

    /**
     * @ManyToOne uses FetchType.EAGER by default - 1 additional query per tournament.
     * Switch ChessGame.tournament between both mappings; the test expects the statements of the active one.
     *
     * New in Jakarta Persistence 4 / Hibernate 8: uncomment <default-to-one-fetch-type>LAZY</default-to-one-fetch-type>
     * in persistence.xml to make all to-one associations without a fetch attribute lazy - an explicit fetch attribute wins.
     */
    @Test
    @Order(1)
    public void manyToOneFetchType() throws NoSuchFieldException {
        // without a fetch attribute: FetchType.EAGER up to Jakarta Persistence 3.2,
        // FetchType.DEFAULT in 4.0 (Hibernate 8) - which still means eager for to-one associations
        FetchType fetchType = ChessGame.class.getDeclaredField("tournament").getAnnotation(ManyToOne.class).fetch();
        FetchType defaultToOneFetchType = defaultToOneFetchType();
        boolean lazy = fetchType == FetchType.LAZY || fetchType == FetchType.DEFAULT && defaultToOneFetchType == FetchType.LAZY;
        EntityManager em = emf.createEntityManager();
        ExecutedStatements.reset();

        // the 1st round of the first 100 tournaments
        List<ChessGame> games = em.createQuery("""
                SELECT g
                FROM ChessGame g
                WHERE g.round = 1
                    AND g.tournament.id <= 100""", ChessGame.class)
                .getResultList();

        log.info("FetchType.{}, default-to-one-fetch-type {}: {} games, {} statements", fetchType,
                defaultToOneFetchType == null ? "not set" : defaultToOneFetchType, games.size(), ExecutedStatements.statements());
        assertEquals(100, games.size());
        assertEquals(lazy ? 1 : 101, ExecutedStatements.statements());
        em.close();
    }

    /**
     * Removing 1 player from a tournament: a List removes all association records and inserts the
     * remaining ones again, a Set removes just 1 record.
     * Switch ChessTournament.players between List and Set; the test expects the statements of the active one.
     */
    @Test
    @Order(2)
    public void manyToManyListVsSet() throws NoSuchFieldException {
        Class<?> type = ChessTournament.class.getDeclaredField("players").getType();
        // no JDBC batching, so that every statement gets counted
        EntityManager em = emf.createEntityManager(new SessionCreationOption.JdbcBatchSize(0));
        em.getTransaction().begin();
        // the last tournament - the other examples don't use it
        ChessTournament tournament = em.find(ChessTournament.class, (long) TestData.TOURNAMENTS);
        ChessPlayer player = tournament.getPlayers().iterator().next();

        ExecutedStatements.reset();
        tournament.getPlayers().remove(player);
        em.getTransaction().commit();

        log.info("{}: {} statements to remove 1 player", type.getSimpleName(), ExecutedStatements.statements());
        assertEquals(type == List.class ? 1 + TestData.PLAYERS_PER_TOURNAMENT - 1 : 1, ExecutedStatements.statements());
        em.close();
    }

    /**
     * JDBC batching is activated (batch size 50), but GenerationType.IDENTITY prevents it - Hibernate has to
     * execute every insert immediately to get the primary key.
     * Switch ChessMove.id between IDENTITY and SEQUENCE; the test expects the behavior of the active one.
     */
    @Test
    @Order(3)
    public void primaryKeyGeneration() {
        GenerationType strategy = primaryKeyStrategy();

        persistMoves("GenerationType." + strategy, 50);

        if (strategy == GenerationType.IDENTITY) {
            assertEquals(MOVES, ExecutedStatements.statements());
            assertEquals(0, ExecutedStatements.batches());
        } else {
            assertTrue(ExecutedStatements.batches() > 0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Missing keywords in queries
    // ---------------------------------------------------------------------------------------------

    /** n+1 select issue: 1 query for the tournaments, 1 per tournament for its games. */
    @Test
    @Order(4)
    public void nPlusOneSelect() {
        EntityManager em = emf.createEntityManager();
        ExecutedStatements.reset();

        List<ChessTournament> tournaments = em.createQuery("""
                SELECT t
                FROM ChessTournament t
                WHERE t.id <= 100""", ChessTournament.class)
                .getResultList();
        tournaments.forEach(t -> log.debug("{}: {} games", t.getName(), t.getGames().size()));

        log.info("Without JOIN FETCH: {} tournaments, {} statements", tournaments.size(), ExecutedStatements.statements());
        assertEquals(101, ExecutedStatements.statements());
        em.close();
    }

    /** JOIN FETCH loads the tournaments and their games with 1 query. */
    @Test
    @Order(5)
    public void joinFetch() {
        EntityManager em = emf.createEntityManager();
        ExecutedStatements.reset();

        List<ChessTournament> tournaments = em.createQuery("""
                SELECT t
                FROM ChessTournament t
                    JOIN FETCH t.games
                WHERE t.id <= 100""", ChessTournament.class)
                .getResultList();
        tournaments.forEach(t -> log.debug("{}: {} games", t.getName(), t.getGames().size()));

        log.info("With JOIN FETCH: {} tournaments, {} statements", tournaments.size(), ExecutedStatements.statements());
        assertEquals(1, ExecutedStatements.statements());
        em.close();
    }

    /**
     * JOIN FETCH with setFirstResult/setMaxResults.
     *
     * Up to Hibernate 7.3, Hibernate couldn't paginate in SQL when fetching a collection: it logged
     * HHH90003004 ("firstResult/maxResults specified with collection fetch; applying in memory"), selected
     * all 200,000 rows and paginated in memory - 210,000 entities in the persistence context for 10 tournaments.
     * Since Hibernate 7.4, it paginates the tournaments in a subquery and joins their games afterwards:
     * 210 entities. Old behavior: hibernate-7/Hibernate7BehaviorTest#paginationWithJoinFetch
     */
    @Test
    @Order(6)
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
            logManagedEntities(em);
            em.close();
            return page;
        });

        assertEquals(10, tournaments.size());
        assertEquals(101L, tournaments.get(0).getId());
        tournaments.forEach(t -> assertEquals(TestData.GAMES_PER_TOURNAMENT, t.getGames().size()));
    }

    /**
     * Cartesian product: fetching 2 collections in 1 query returns games x players rows per tournament.
     * With Sets, Hibernate doesn't throw a MultipleBagFetchException - the query just gets slow.
     */
    @Test
    @Order(7)
    public void cartesianProduct() {
        log.info("Rows returned by the database: {}", countRows("""
                select count(*)
                from ChessTournament t
                    join ChessGame g on g.tournament_id = t.id
                    join ChessTournament_ChessPlayer tp on tp.ChessTournament_id = t.id
                where t.id <= :maxId"""));

        List<ChessTournament> tournaments = measure("1 query for 2 collections", () -> {
            EntityManager em = emf.createEntityManager();
            List<ChessTournament> result = em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.games
                        JOIN FETCH t.players
                    WHERE t.id <= :maxId""", ChessTournament.class)
                    .setParameter("maxId", MAX_TOURNAMENT_ID)
                    .getResultList();
            em.close();
            return result;
        });

        // players as List (before state of manyToManyListVsSet): every row of the cartesian product adds a player
        assertTournaments(tournaments, playersMappedAsList()
                ? TestData.GAMES_PER_TOURNAMENT * TestData.PLAYERS_PER_TOURNAMENT
                : TestData.PLAYERS_PER_TOURNAMENT);
    }

    /** Cartesian product, solution 1: 1 query per collection in the same persistence context. */
    @Test
    @Order(8)
    public void oneQueryPerCollection() {
        log.info("Rows returned by the database: {}", countRows("""
                select (select count(*) from ChessGame g where g.tournament_id <= :maxId)
                     + (select count(*) from ChessTournament_ChessPlayer tp where tp.ChessTournament_id <= :maxId)"""));

        List<ChessTournament> tournaments = measure("1 query per collection", () -> {
            EntityManager em = emf.createEntityManager();
            List<ChessTournament> result = em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.games
                    WHERE t.id <= :maxId""", ChessTournament.class)
                    .setParameter("maxId", MAX_TOURNAMENT_ID)
                    .getResultList();
            // initializes the players of the tournaments loaded above
            em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.players
                    WHERE t.id <= :maxId""", ChessTournament.class)
                    .setParameter("maxId", MAX_TOURNAMENT_ID)
                    .getResultList();
            em.close();
            return result;
        });

        assertTournaments(tournaments, TestData.PLAYERS_PER_TOURNAMENT);
    }

    /**
     * Cartesian product, solution 2 (new in Hibernate 8 / Jakarta Persistence 4): JOIN FETCH the games and
     * let an entity graph load the players with a subquery - FetchMethod.BY_SUBQUERY as FetchOption.
     */
    @Test
    @Order(9)
    public void subqueryFetchOption() {
        List<ChessTournament> tournaments = measure("JOIN FETCH + FetchMethod.BY_SUBQUERY", () -> {
            EntityManager em = emf.createEntityManager();
            EntityGraph<ChessTournament> graph = em.createEntityGraph(ChessTournament.class);
            graph.addAttributeNode("players").addOption(FetchMethod.BY_SUBQUERY);

            List<ChessTournament> result = em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.games
                    WHERE t.id <= :maxId""", ChessTournament.class)
                    .setParameter("maxId", MAX_TOURNAMENT_ID)
                    .setHint("jakarta.persistence.loadgraph", graph)
                    .getResultList();
            em.close();
            return result;
        });

        assertTournaments(tournaments, TestData.PLAYERS_PER_TOURNAMENT);
    }

    /** Overview of all tournaments with their number of games, using entities: 210,000 managed objects. */
    @Test
    @Order(10)
    public void overviewWithEntities() {
        List<TournamentSummary> summaries = measure("Overview with entities", () -> {
            EntityManager em = emf.createEntityManager();
            List<TournamentSummary> result = em.createQuery("""
                    SELECT t
                    FROM ChessTournament t
                        JOIN FETCH t.games""", ChessTournament.class)
                    .getResultList().stream()
                    .map(t -> new TournamentSummary(t.getName(), t.getGames().size()))
                    .toList();
            logManagedEntities(em);
            em.close();
            return result;
        });

        assertSummaries(summaries);
    }

    /** The same overview with a DTO projection: the database counts, no managed objects. */
    @Test
    @Order(11)
    public void overviewWithDtoProjection() {
        List<TournamentSummary> summaries = measure("Overview with DTO projection", () -> {
            EntityManager em = emf.createEntityManager();
            List<TournamentSummary> result = em.createQuery("""
                    SELECT new com.thorben.janssen.talk.model.TournamentSummary(t.name, count(g))
                    FROM ChessTournament t
                        JOIN t.games g
                    GROUP BY t.id, t.name""", TournamentSummary.class)
                    .getResultList();
            logManagedEntities(em);
            em.close();
            return result;
        });

        assertSummaries(summaries);
    }

    // ---------------------------------------------------------------------------------------------
    // Hibernate's optimizations
    // ---------------------------------------------------------------------------------------------

    /** JDBC batching deactivated: 10,000 single inserts. */
    @Test
    @Order(12)
    public void withoutJdbcBatching() {
        persistMoves("Without JDBC batching", 0);
        assertEquals(0, ExecutedStatements.batches());
    }

    /**
     * Without any configuration, Hibernate 8 uses the dialect's default batch size - 15 for PostgreSQL.
     * Hibernate 7.3 didn't batch without hibernate.jdbc.batch_size: 10,000 single inserts.
     * Old behavior: hibernate-7/Hibernate7BehaviorTest#defaultJdbcBatchSize
     */
    @Test
    @Order(13)
    public void defaultJdbcBatchSize() {
        persistMoves("Default JDBC batch size", null);
    }

    /**
     * Batch size 50 for 1 session. Globally, use the property hibernate.jdbc.batch_size.
     * New in Hibernate 8: the batch size as option when creating the EntityManager
     * (SessionCreationOption.JdbcBatchSize) - before, only Session#setJdbcBatchSize after creating it.
     */
    @Test
    @Order(14)
    public void jdbcBatchSizeAsSessionOption() {
        persistMoves("JDBC batch size 50", 50);
        // ChessMove with GenerationType.IDENTITY (before state of primaryKeyGeneration) prevents batching
        assertEquals(primaryKeyStrategy() != GenerationType.IDENTITY, ExecutedStatements.batches() > 0);
    }

    /**
     * Batch fetching as FetchOption of an entity graph (new in Hibernate 8 / Jakarta Persistence 4, where
     * BatchSize became BatchFetch): per use case instead of globally with @BatchSize on the association.
     * Compare with nPlusOneSelect (101 statements): 1 + 100 / 25 = 5 statements.
     */
    @Test
    @Order(15)
    public void batchFetchOption() {
        EntityManager em = emf.createEntityManager();
        ExecutedStatements.reset();

        EntityGraph<ChessTournament> graph = em.createEntityGraph(ChessTournament.class);
        graph.addAttributeNode("games").addOption(new BatchFetch(25));

        List<ChessTournament> tournaments = em.createQuery("""
                SELECT t
                FROM ChessTournament t
                WHERE t.id <= 100""", ChessTournament.class)
                .setHint("jakarta.persistence.loadgraph", graph)
                .getResultList();
        tournaments.forEach(t -> log.debug("{}: {} games", t.getName(), t.getGames().size()));

        log.info("BatchFetch(25): {} tournaments, {} statements", tournaments.size(), ExecutedStatements.statements());
        assertEquals(5, ExecutedStatements.statements());
        em.close();
    }

    /**
     * Subquery fetching as FetchOption of an entity graph (new in Hibernate 8): instead of globally with
     * @Fetch(FetchMode.SUBSELECT) on the association. 1 query for the tournaments, 1 for all their games.
     */
    @Test
    @Order(16)
    public void subqueryFetchOptionForNPlusOne() {
        EntityManager em = emf.createEntityManager();
        ExecutedStatements.reset();

        EntityGraph<ChessTournament> graph = em.createEntityGraph(ChessTournament.class);
        graph.addAttributeNode("games").addOption(FetchMethod.BY_SUBQUERY);

        List<ChessTournament> tournaments = em.createQuery("""
                SELECT t
                FROM ChessTournament t
                WHERE t.id <= 100""", ChessTournament.class)
                .setHint("jakarta.persistence.loadgraph", graph)
                .getResultList();
        tournaments.forEach(t -> log.debug("{}: {} games", t.getName(), t.getGames().size()));

        log.info("FetchMethod.BY_SUBQUERY: {} tournaments, {} statements", tournaments.size(), ExecutedStatements.statements());
        assertEquals(2, ExecutedStatements.statements());
        em.close();
    }


    // ---------------------------------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------------------------------

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

    /** @param batchSize JDBC batch size of the session, null for the default */
    private static void persistMoves(String useCase, Integer batchSize) {
        measure(useCase + ": " + MOVES + " inserts", () -> {
            EntityManager em = batchSize == null
                    ? emf.createEntityManager()
                    : emf.createEntityManager(new SessionCreationOption.JdbcBatchSize(batchSize));
            em.getTransaction().begin();
            for (int i = 1; i <= MOVES; i++) {
                em.persist(new ChessMove(i, "e4"));
            }
            em.getTransaction().commit();
            em.close();
            return null;
        });
    }

    private static void logManagedEntities(EntityManager em) {
        log.info("{} entities in the persistence context", em.unwrap(Session.class).getStatistics().getEntityCount());
    }

    private static void assertTournaments(List<ChessTournament> tournaments, int playersPerTournament) {
        assertEquals(MAX_TOURNAMENT_ID, tournaments.size());
        tournaments.forEach(t -> {
            assertEquals(TestData.GAMES_PER_TOURNAMENT, t.getGames().size());
            assertEquals(playersPerTournament, t.getPlayers().size());
        });
    }

    private static void assertSummaries(List<TournamentSummary> summaries) {
        assertEquals(TestData.TOURNAMENTS, summaries.size());
        summaries.forEach(s -> assertEquals(TestData.GAMES_PER_TOURNAMENT, s.games()));
    }

    /** default-to-one-fetch-type of persistence.xml, null if not set (commented out) */
    private static FetchType defaultToOneFetchType() {
        try (InputStream xml = HibernatePerformanceTest.class.getResourceAsStream("/META-INF/persistence.xml")) {
            NodeList elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
                    .getElementsByTagName("default-to-one-fetch-type");
            return elements.getLength() == 0 ? null : FetchType.valueOf(elements.item(0).getTextContent().trim());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GenerationType primaryKeyStrategy() {
        try {
            return ChessMove.class.getDeclaredField("id").getAnnotation(GeneratedValue.class).strategy();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean playersMappedAsList() {
        try {
            return ChessTournament.class.getDeclaredField("players").getType() == List.class;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object countRows(String sql) {
        EntityManager em = emf.createEntityManager();
        Object rows = em.createNativeQuery(sql).setParameter("maxId", MAX_TOURNAMENT_ID).getSingleResult();
        em.close();
        return rows;
    }

    @BeforeAll
    public static void init() {
        emf = TestData.createEntityManagerFactory();
    }

    @AfterAll
    public static void close() {
        emf.close();
    }
}
