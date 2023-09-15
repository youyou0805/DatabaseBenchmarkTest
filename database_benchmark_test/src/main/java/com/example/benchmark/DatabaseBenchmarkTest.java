package com.example.benchmark;

import org.openjdk.jmh.annotations.*;

import java.sql.ResultSet;
import java.util.concurrent.*;

@Fork(1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
public class DatabaseBenchmarkTest {

    @Param({"1","2"})
    private int testOption;

    @Param({"30000"})
    private int threadCount;

    @Param({"300000"})
    private int requestCount;

    private ExecutorService dbExecutor;

    @Setup(Level.Trial)
    public void setup() {
        if (testOption == 1) {
            dbExecutor = Executors.newFixedThreadPool(threadCount);
            Thread.ofVirtual().scheduler(dbExecutor).start(() -> {
                try {
                    testDatabase();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (testOption == 2) {
            dbExecutor = new ForkJoinPool(threadCount);
            Thread.ofVirtual().scheduler(dbExecutor).start(() -> {
                try {
                    testDatabase();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            throw new IllegalArgumentException("Invalid test option: " + testOption);
        }
        ConnectionPool.initConnectionPool();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        ConnectionPool.closeConnection();
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            dbExecutor.shutdownNow();
        }
    }

    @Benchmark
    public void testDatabase() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(requestCount);
        for(int i = 0; i < requestCount; i++) {
            CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
                String result = null;
                try {
                    result = execQuery("select * from hello");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
                return result;
            });

        }
        latch.await();
    }

    public static String execQuery(String sql) {
        String queryResult = "";
        try {
            ConnectionNode node;
            do {
                node = ConnectionPool.getConnection();
            } while (node == null);
            ResultSet rs = node.stm.executeQuery(sql);

            while (rs.next()) {
                int id = rs.getInt("id");
                String hello = rs.getString("hello");
                String response = rs.getString("response");

                queryResult += "id: " + id + " hello:" + hello + " response: "+ response + "\n";
            }

            rs.close();
            ConnectionPool.releaseConnection(node);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return queryResult;
    }
}
