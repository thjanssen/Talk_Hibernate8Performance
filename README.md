# Hibernate 8 - Gibt's das auch in schnell?

Demos for the talk, with Hibernate 8.0.0.Beta3. PostgreSQL 17 runs in Docker on port 5433, so it doesn't collide
with the course databases on 5432.

```bash
docker compose up -d
mvn test                                                     # all examples, in the order of the talk
mvn test -Dtest=HibernatePerformanceTest#paginationWithJoinFetch   # a single example
```

All examples are test methods of `HibernatePerformanceTest`, ordered with `@Order` in the order of the talk.
The data is generated once with SQL: 10,000 tournaments, 20 games and 16 of 1,000 players per tournament.
Use cases compared by time run twice; the log shows the 2nd run.

The log shows the SQL statements (`org.hibernate.SQL`) and Hibernate's session metrics (`org.hibernate.session.metrics`,
needs `hibernate.generate_statistics`). `ExecutedStatements` counts the executed JDBC statements and batches.

| Test method | Shows |
|---|---|
| `manyToOneFetchType` | `@ManyToOne` without `fetch` is eager: 101 statements for 100 games, `LAZY`: 1. Jakarta Persistence 4: uncomment `<default-to-one-fetch-type>LAZY` in persistence.xml - 1 statement without changing the mapping; an explicit `fetch` wins |
| `manyToManyListVsSet` | Removing 1 player: a `List` deletes all association records and inserts 15 again (16 statements), a `Set` deletes 1 |
| `primaryKeyGeneration` | Batch size 50, but `IDENTITY` executes 10,000 single inserts; `SEQUENCE` uses 200 batches |
| `nPlusOneSelect`, `joinFetch` | n+1 select issue: 101 statements without, 1 with `JOIN FETCH` |
| `paginationWithJoinFetch` | `JOIN FETCH` + `setFirstResult`/`setMaxResults`: pagination in a subquery since Hibernate 7.4 (before: HHH90003004, in memory) |
| `cartesianProduct`, `oneQueryPerCollection`, `subqueryFetchOption` | 2 collections in 1 query = 320,000 rows vs. 1 query per collection or `FetchMethod.BY_SUBQUERY` (36,000 rows) |
| `overviewWithEntities`, `overviewWithDtoProjection` | 210,000 managed entities vs. a DTO projection with `count` |
| `withoutJdbcBatching`, `defaultJdbcBatchSize`, `jdbcBatchSizeAsSessionOption` | 10,000 inserts: no batching, the dialect's default (PostgreSQL: 15), batch size 50 as session option |
| `batchFetchOption`, `subqueryFetchOptionForNPlusOne` | `BatchFetch(25)` (5 statements) and `FetchMethod.BY_SUBQUERY` (2 statements) as `FetchOption` of an entity graph |
| `updateWithSession`, `updateWithStatelessSession` | Updating 40,000 games with a `Session` vs. a `StatelessSession` |

Comments on the test methods explain where older Hibernate versions behave differently.

Examples 1-3 show a mapping change. The "before" mapping is a comment right above the active one in the entity
(`ChessGame.tournament`, `ChessTournament.players`, `ChessMove.id`) - swap the two lines to switch. The tests read
the active mapping via reflection and expect its behavior, so they pass in both states.
With `IDENTITY`, the batching examples can't batch either; with `players` as a `List`, `cartesianProduct`
shows duplicates: 320 instead of 16 players per tournament.

## Old behavior with Hibernate 7.3

`hibernate-7/` is a separate Maven project with the same model and data on Hibernate 7.3.13.Final - open it as
an additional Maven project in the IDE, so that both test classes run side by side. `Hibernate7BehaviorTest` contains
only the examples that behave differently:

| Test method | Hibernate 7.3 | Hibernate 8 (`HibernatePerformanceTest`) |
|---|---|---|
| `paginationWithJoinFetch` | HHH90003004, pagination in memory, 210,000 managed entities | pagination in a subquery, 210 entities |
| `defaultJdbcBatchSize` | no batching without `hibernate.jdbc.batch_size`: 10,000 single inserts | batches of 15 (PostgreSQL) |

```bash
cd hibernate-7 && mvn test
```

Both projects use the same database and regenerate the data before their tests. The model in `hibernate-7/` is a copy
of the final mappings (without the commented "before" states of examples 1-3).

