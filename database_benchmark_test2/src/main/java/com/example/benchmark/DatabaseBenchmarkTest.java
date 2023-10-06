package com.example.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.*;

@Fork(1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatabaseBenchmarkTest {
    @Param({"2"})
    private int testOption;

    @Param({"100"})
    private int threadCount;

    @Param({ "1000"})
    private int taskCount;

    private ExecutorService dbExecutor;

    @Setup(Level.Trial)
    public void setup() {
        if (testOption == 1) {
            dbExecutor = Executors.newFixedThreadPool(threadCount);
        } else if (testOption == 2) {
            dbExecutor = new ForkJoinPool(threadCount);
        } else {
            throw new IllegalArgumentException("Invalid test option: " + testOption);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
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
    public void testHeavyCpuTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(taskCount);
        for(int i = 0; i < taskCount; i++) {
            CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                try {
                    Thread.ofVirtual().scheduler(dbExecutor).start(() -> {
                        longRunningTask();
                        latch.countDown();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        //等待所有任务完成
        latch.await();
    }

    public void heavyCpuTask() {
        // 执行计算密集型任务
        for (int i = 0; i < 1000000; i++) {
            Math.sqrt(i);
        }
    }

    public void longRunningTask() {
        // 模拟长时间运行的任务，例如睡眠5秒
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}