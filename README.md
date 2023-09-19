# 比较不同调度器（FixedThreadPool，ForkJoinPool）的性能

## 任务实现

**任务描述**

编写JMH测试用例，在常见应用场景下（将mysql的同步操作提交到独立线程池，让协程异步等待独立线程池执行完毕 ，可以利用CompletableFuture实现），对比不同调度器（FixedThreadPool，ForkJoinPool）的性能表现。

**进度**：目前已经完成了JHM测试代码的编写，输出了测试结果与火焰图。

- **2023年9月19日更新火焰图**

**待完成**：

1. 设计测试用例，探究（FixedThreadPool，ForkJoinPool）在什么实际场景下具有区别于另一调度器的的性能，并探究原因。
2. 尝试分析火焰图。

**代码实现**

```java
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
```

## **JMH测试结果**

| Benchmark                          | (requestCount) | (testOption) | (threadCount) | Mode  | Cnt  | Score | Error   | Units |
| ---------------------------------- | -------------- | ------------ | ------------- | ----- | ---- | ----- | ------- | ----- |
| DatabaseBenchmarkTest.testDatabase | 100000         | 1            | 10000         | avgt  | 5    | 1.333 | ± 0.677 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 100000         | 2            | 10000         | avgt  | 5    | 1.449 | ± 0.570 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 300000         | 1            | 30000         | avgt  | 5    | 4.385 | ± 1.326 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 300000         | 2            | 30000         | avgt  | 5    | 5.128 | ± 0.300 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 100000         | 1            | 10000         | thrpt | 5    | 0.654 | ± 0.201 | ops/s |
| DatabaseBenchmarkTest.testDatabase | 100000         | 2            | 10000         | thrpt | 5    | 0.616 | ± 0.128 | ops/s |

以下是对测试结果的分析：

- 在相同的测试条件下，使用 `FixedThreadPool` 和 `ForkJoinPool` 的性能表现略有差异。在 `avgt` 模式下，`ForkJoinPool` 的平均执行时间略长，而在 `thrpt` 模式下，两者的吞吐量基本相似。
- 随着测试规模和线程数增加,错误率error相对提高,但ForkJoinPool的error值普遍低于FixedThreadPool，表明ForkJoinPool处理任务更加稳定,在高并发场景下性能波动小于FixedThreadPool。

## 火焰图

调度器：FixedThreadPool   requestCount：300000  threadCount：30000

![image-20230919134731225](https://cdn.jsdelivr.net/gh/youyou0805/pictures/2023/09/image-20230919134731225-5692fe.png)

调度器：ForkJoinPool   requestCount：300000  threadCount：30000

![image-20230919134823278](https://cdn.jsdelivr.net/gh/youyou0805/pictures/2023/09/image-20230919134823278-f2ec83.png)

