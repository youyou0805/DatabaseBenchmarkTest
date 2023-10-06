# 比较不同调度器（FixedThreadPool，ForkJoinPool）的性能

## 任务实现

**任务描述**

编写JMH测试用例，在常见应用场景下（将mysql的同步操作提交到独立线程池，让协程异步等待独立线程池执行完毕 ，可以利用CompletableFuture实现），对比不同调度器（FixedThreadPool，ForkJoinPool）的性能表现。

**进度**：目前已经完成了JHM测试代码的编写，输出了测试结果与火焰图。

- **2023年9月19日更新火焰图**
- **2023年10月7日更新新应用场景下测试用例结果分析**

**待完成**：

- 读懂火焰图

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

| Benchmark                          | (requestCount) | (testOption) | (threadCount) | Mode  | Cnt  | Score | Error  | Units |
| ---------------------------------- | -------------- | ------------ | ------------- | ----- | ---- | ----- | ------ | ----- |
| DatabaseBenchmarkTest.testDatabase | 100000         | 1            | 10000         | avgt  | 5    | 1.333 | ±0.677 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 100000         | 2            | 10000         | avgt  | 5    | 1.449 | ±0.570 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 300000         | 1            | 30000         | avgt  | 5    | 4.385 | ±1.326 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 300000         | 2            | 30000         | avgt  | 5    | 5.128 | ±0.300 | s/op  |
| DatabaseBenchmarkTest.testDatabase | 100000         | 1            | 10000         | thrpt | 5    | 0.654 | ±0.201 | ops/s |
| DatabaseBenchmarkTest.testDatabase | 100000         | 2            | 10000         | thrpt | 5    | 0.616 | ±0.128 | ops/s |
| DatabaseBenchmarkTest.testDatabase | 300000         | 1            | 30000         | thrpt | 5    | 0.200 | ±0.045 | ops/s |
| DatabaseBenchmarkTest.testDatabase | 300000         | 2            | 30000         | thrpt | 5    | 0.318 | ±0.100 | ops/s |

以下是对测试结果的分析：

- 在相同的测试条件下，使用 `FixedThreadPool` 和 `ForkJoinPool` 的性能表现略有差异。在 `avgt` 模式下，`ForkJoinPool` 的平均执行时间略长，而在 `thrpt` 模式下，两者的吞吐量基本相似。
- 随着测试规模和线程数增加,错误率error相对提高,但ForkJoinPool的error值普遍低于FixedThreadPool，表明ForkJoinPool处理任务更加稳定,在高并发场景下性能波动小于FixedThreadPool。

FixedThreadPool适用于大量短期任务，例如并发请求较多的Web服务器。它具有较低的线程创建和销毁开销，适合于处理大量短期任务。
ForkJoinPool适用于递归任务和分治算法，例如大规模数据处理或并行计算。它可以自动将任务分解为较小的子任务，并利用工作窃取算法提高任务并行性。在处理递归任务时，ForkJoinPool通常具有更好的性能。

## 火焰图

调度器：FixedThreadPool   requestCount：300000  threadCount：30000

![image-20230919134731225](https://cdn.jsdelivr.net/gh/youyou0805/pictures/2023/09/image-20230919134731225-5692fe.png)

调度器：ForkJoinPool   requestCount：300000  threadCount：30000

![image-20230919134823278](https://cdn.jsdelivr.net/gh/youyou0805/pictures/2023/09/image-20230919134823278-f2ec83.png)

------

```
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
    @Param({"1","2"})
    private int testOption;

    @Param({"100","1000"})
    private int threadCount;

    @Param({"1000","10000"})
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
```

以上代码是一个基准测试类，通过JMH框架进行性能测试。它包括了不同的基准测试选项、线程数量和任务数量，并使用线程池执行任务。其中包含了CPU密集型任务和模拟长时间运行的任务。通过运行基准测试可以评估和比较不同配置下的性能表现。

------


FixedThreadPool和ForkJoinPool作为协程调度器时的对比分析总结：

FixedThreadPool作为协程调度器的情况：

1. 并发性：FixedThreadPool作为协程调度器可以提供并发执行协程的能力，类似于多线程的并发执行模型。
2. 线程数量固定：FixedThreadPool作为协程调度器时，采用固定数量线程模型，每个线程独立执行任务，线程上下文切换开销小。
3. 资源利用：由于线程数量固定，无法根据协程的数量进行动态调整，可能存在线程资源浪费或协程等待的情况，特别是当协程数量超过线程数量时。
4. 适用性：FixedThreadPool作为协程调度器适用于需要并发执行的协程任务，但适用性相对较窄，不支持协程的轻量级和非阻塞特性。

ForkJoinPool作为协程调度器的情况：

1. 任务拆分与并行执行：ForkJoinPool作为协程调度器可以将大任务拆分为小任务并行执行，类似于协程的任务拆分和并发执行模型。
2. 动态负载平衡：ForkJoinPool使用工作窃取算法，允许线程间交换和执行任务，但上下文切换开销较大。在任务执行过程中可以动态调整线程的负载，提高并行性能。
3. 适应性：ForkJoinPool作为协程调度器适用于递归任务和可拆分的任务，能够以更细粒度的方式进行任务调度和并行计算。
4. 资源利用：由于工作窃取算法的负载平衡机制，ForkJoinPool可以更高效地利用线程资源，避免资源浪费和任务等待的情况。

总结：

- FixedThreadPool作为协程调度器提供并发执行协程的能力，但无法动态调整线程数量，可能存在资源利用问题。

- ForkJoinPool作为协程调度器具有任务拆分、并行执行和动态负载平衡的能力，适用于递归和可拆分的任务，并能更高效地利用线程资源。

- 总体性能

  CPU密集任务:FixedThreadPool更适用于CPU密集任务，利用多核高效执行，性能优良。

  IO密集任务:ForkJoinPool工作窃取能力处理横向切分的任务、IO密集任务更有效率，更好利用率。


在选择使用FixedThreadPool还是ForkJoinPool作为协程调度器时，需要考虑任务的特性、并发需求和资源利用要求。同时，还应注意协程调度器的实验性质，并在实际使用中进行测试和评估。
