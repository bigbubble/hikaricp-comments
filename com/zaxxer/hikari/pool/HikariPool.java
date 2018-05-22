/*
 * Copyright (C) 2013,2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleHealthChecker;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleMetricsTrackerFactory;
import com.zaxxer.hikari.util.ClockSource;
import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IBagStateListener;
import com.zaxxer.hikari.util.UtilityElf.DefaultThreadFactory;
import com.zaxxer.hikari.util.SuspendResumeLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import static com.zaxxer.hikari.pool.PoolEntry.LASTACCESS_COMPARABLE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.UtilityElf.createThreadPoolExecutor;
import static com.zaxxer.hikari.util.UtilityElf.quietlySleep;

/**
 * This is the primary connection pool class that provides the basic
 * pooling behavior for HikariCP.
 *
 * @author Brett Wooldridge
 * 连接池实现
 */
public class HikariPool extends PoolBase implements HikariPoolMXBean, IBagStateListener
{
   private final Logger LOGGER = LoggerFactory.getLogger(HikariPool.class);

   private static final ClockSource clockSource = ClockSource.INSTANCE; //时钟，时间计算

   private static final int POOL_NORMAL = 0; //正常
   private static final int POOL_SUSPENDED = 1; //中止
   private static final int POOL_SHUTDOWN = 2; //关闭

   private volatile int poolState; //连接池状态

   private final long ALIVE_BYPASS_WINDOW_MS = Long.getLong("com.zaxxer.hikari.aliveBypassWindowMs", MILLISECONDS.toMillis(500));
   private final long HOUSEKEEPING_PERIOD_MS = Long.getLong("com.zaxxer.hikari.housekeeping.periodMs", SECONDS.toMillis(30));

   private final PoolEntryCreator POOL_ENTRY_CREATOR = new PoolEntryCreator();
   private final AtomicInteger totalConnections; //总连接数
   private final ThreadPoolExecutor addConnectionExecutor;
   private final ThreadPoolExecutor closeConnectionExecutor;
   private final ScheduledThreadPoolExecutor houseKeepingExecutorService;

   private final ConcurrentBag<PoolEntry> connectionBag;

   private final ProxyLeakTask leakTask;
   private final SuspendResumeLock suspendResumeLock;

   private MetricsTrackerDelegate metricsTracker;

   /**
    * Construct a HikariPool with the specified configuration.
    *
    * @param config a HikariConfig instance
    */
   public HikariPool(final HikariConfig config)
   {
      super(config);

      this.connectionBag = new ConcurrentBag<>(this);
      this.totalConnections = new AtomicInteger();
      this.suspendResumeLock = config.isAllowPoolSuspension() ? new SuspendResumeLock() : SuspendResumeLock.FAUX_LOCK;

      if (config.getMetricsTrackerFactory() != null) {
         setMetricsTrackerFactory(config.getMetricsTrackerFactory());
      }
      else {
         setMetricRegistry(config.getMetricRegistry());
      }

      setHealthCheckRegistry(config.getHealthCheckRegistry());

      registerMBeans(this);

      checkFailFast(); //快速验证数据库连接是否正常

      ThreadFactory threadFactory = config.getThreadFactory();
	  //创建增加数据库连接的线程池
      this.addConnectionExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection adder", threadFactory, new ThreadPoolExecutor.DiscardPolicy());
	   //创建关闭数据库连接的线程池
      this.closeConnectionExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection closer", threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
      //设置定时调度任务执行器，根据配置维护连接池内数据库连接数量，空闲连接数量等
      if (config.getScheduledExecutorService() == null) {
         threadFactory = threadFactory != null ? threadFactory : new DefaultThreadFactory(poolName + " housekeeper", true);
         this.houseKeepingExecutorService = new ScheduledThreadPoolExecutor(1, threadFactory, new ThreadPoolExecutor.DiscardPolicy());
         this.houseKeepingExecutorService.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
         this.houseKeepingExecutorService.setRemoveOnCancelPolicy(true);
      }
      else {
         this.houseKeepingExecutorService = config.getScheduledExecutorService();
      }
      //设置空闲数据库连接检查，立即执行一次，然后每30s 执行一次。
      this.houseKeepingExecutorService.scheduleWithFixedDelay(new HouseKeeper(), 0L, HOUSEKEEPING_PERIOD_MS, MILLISECONDS);
      //设置连接泄漏检查，定时任务调度器
      this.leakTask = new ProxyLeakTask(config.getLeakDetectionThreshold(), houseKeepingExecutorService);
   }

   /**
    * Get a connection from the pool, or timeout after connectionTimeout milliseconds.
    *
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
	*
	* 从连接池获取一个数据库连接，如果超时抛出SQLException异常
    */
   public final Connection getConnection() throws SQLException
   {
      return getConnection(connectionTimeout);
   }

   /**
    * Get a connection from the pool, or timeout after the specified number of milliseconds.
    *
    * @param hardTimeout the maximum time to wait for a connection from the pool
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
	* 
	* 从连接池获取一个数据库连接，如果超时抛出SQLException异常
    */
   public final Connection getConnection(final long hardTimeout) throws SQLException
   {
      //控制并发线程数，最大10000，请求一个许可
      suspendResumeLock.acquire();
	  //开始时间
      final long startTime = clockSource.currentTime();

      try {
         long timeout = hardTimeout;
         do {
			//从connectionBag中获取一个数据库连接
            final PoolEntry poolEntry = connectionBag.borrow(timeout, MILLISECONDS);
            if (poolEntry == null) {
            //超时，结束循环抛出异常
			break; // We timed out... break and throw exception
            }

            final long now = clockSource.currentTime();
			//如果获取到的连接已被标记为“待清理”或（“连接的上次可以连接到数据库时间距现在已超过500毫秒”且“连接已经不能连接到数据库”），关闭这个连接
            if (poolEntry.isMarkedEvicted() || (clockSource.elapsedMillis(poolEntry.lastAccessed, now) > ALIVE_BYPASS_WINDOW_MS && !isConnectionAlive(poolEntry.connection))) {
               closeConnection(poolEntry, "(connection is evicted or dead)"); // Throw away the dead connection (passed max age or failed alive test)
               timeout = hardTimeout - clockSource.elapsedMillis(startTime);
            }
            else {
               //统计信息
               metricsTracker.recordBorrowStats(poolEntry, startTime);
			   //返回数据库连接，使用代理类包装，同时将此连接加入到溢出检测定时任务中
			   //使用代理类，重写了Connection方法，如在外部使用close()方法时，会同时处理PoolEntry对象中的信息
               return poolEntry.createProxyConnection(leakTask.schedule(poolEntry), now);
            }
         } while (timeout > 0L);
      }
      catch (InterruptedException e) {
         throw new SQLException(poolName + " - Interrupted during connection acquisition", e);
      }
      finally {
         //控制并发线程数，最大10000，释放一个许可
         suspendResumeLock.release();
      }
     
      logPoolState("Timeout failure ");
      metricsTracker.recordConnectionTimeout();
      //抛出异常 
      String sqlState = null;
      final Throwable originalException = getLastConnectionFailure();
      if (originalException instanceof SQLException) {
         sqlState = ((SQLException) originalException).getSQLState();
      }
      final SQLException connectionException = new SQLTransientConnectionException(poolName + " - Connection is not available, request timed out after " + clockSource.elapsedMillis(startTime) + "ms.", sqlState, originalException);
      if (originalException instanceof SQLException) {
         connectionException.setNextException((SQLException) originalException);
      }
      throw connectionException;
   }

   /**
    * Shutdown the pool, closing all idle connections and aborting or closing
    * active connections.
    *
    * @throws InterruptedException thrown if the thread is interrupted during shutdown
	* 关闭数据库连接池，关闭所有空闲连接，
    */
   public final synchronized void shutdown() throws InterruptedException
   {
      try {
         //状态设置为关闭
         poolState = POOL_SHUTDOWN;

         LOGGER.info("{} - Close initiated...", poolName);
         logPoolState("Before closing ");
         //将concurrentBag中所有是“未使用”的对象，从concurrentBag中移除并加入到关闭连接的线程池中，并标记所有对象“待清除”为true,
         softEvictConnections();
         
		 //停止接收新的任务并且等待已经提交的任务（包含提交正在执行和提交未执行）执行完成。当所有提交任务执行完毕，线程池即被关闭。
         addConnectionExecutor.shutdown();
		 //设置阻塞等待时间5s,5s之后才能执行后面的语句
         addConnectionExecutor.awaitTermination(5L, SECONDS);
		 //设置定时任务调度的关闭和阻塞等待时间
         if (config.getScheduledExecutorService() == null && houseKeepingExecutorService != null) {
            houseKeepingExecutorService.shutdown();
            houseKeepingExecutorService.awaitTermination(5L, SECONDS);
         }
         //关闭connectionBag
         connectionBag.close();

         final ExecutorService assassinExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection assassinator",
                                                                           config.getThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());
         try {
            final long start = clockSource.currentTime();
			//循环关闭正在使用的连接，条件：五秒内，总连接数>0
            do {
               //中止正在使用数据库连接
               abortActiveConnections(assassinExecutor);
			   //关闭不在使用的连接
               softEvictConnections();
            } while (getTotalConnections() > 0 && clockSource.elapsedMillis(start) < SECONDS.toMillis(5));
         }
         finally {
            //关闭此线程池，等待5s
            assassinExecutor.shutdown();
            assassinExecutor.awaitTermination(5L, SECONDS);
         }
         //关闭 数据库连接网络连接超时 线程池
         shutdownNetworkTimeoutExecutor();
		 //关闭 关闭连接线程池 等待5s
         closeConnectionExecutor.shutdown();
         closeConnectionExecutor.awaitTermination(5L, SECONDS);
      }
      finally {
         logPoolState("After closing ");
         unregisterMBeans();//MBean
         metricsTracker.close();//统计
         LOGGER.info("{} - Closed.", poolName);
      }
   }

   /**
    * Evict a connection from the pool.
    *
    * @param connection the connection to evict
	* 清理未使用的数据库连接
    */
   public final void evictConnection(Connection connection)
   {
      ProxyConnection proxyConnection = (ProxyConnection) connection;
	  //从溢出检测中移除
      proxyConnection.cancelLeakTask();

      try {
          //将concurrentBag中所有是“未使用”的对象，从concurrentBag中移除并加入到关闭连接的线程池中，并标记所有对象“待清除”为true,
         softEvictConnection(proxyConnection.getPoolEntry(), "(connection evicted by user)", !connection.isClosed() /* owner */);
      }
      catch (SQLException e) {
         // unreachable in HikariCP, but we're still forced to catch it
      }
   }

   public void setMetricRegistry(Object metricRegistry)
   {
      if (metricRegistry != null) {
         setMetricsTrackerFactory(new CodahaleMetricsTrackerFactory((MetricRegistry) metricRegistry));
      }
      else {
         setMetricsTrackerFactory(null);
      }
   }

   public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory)
   {
      if (metricsTrackerFactory != null) {
         this.metricsTracker = new MetricsTrackerDelegate(metricsTrackerFactory.create(config.getPoolName(), getPoolStats()));
      }
      else {
         this.metricsTracker = new NopMetricsTrackerDelegate();
      }
   }

   public void setHealthCheckRegistry(Object healthCheckRegistry)
   {
      if (healthCheckRegistry != null) {
         CodahaleHealthChecker.registerHealthChecks(this, config, (HealthCheckRegistry) healthCheckRegistry);
      }
   }

   // ***********************************************************************
   //                        IBagStateListener callback
   // ***********************************************************************

   /** {@inheritDoc} */
   //实现ConcurrentBag中IBagStateListener接口方法，创建数据库连接并加入到concurrentBag中
   @Override
   public Future<Boolean> addBagItem()
   {
      return addConnectionExecutor.submit(POOL_ENTRY_CREATOR);
   }

   // ***********************************************************************
   //                        HikariPoolMBean methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public final int getActiveConnections()
   {
      return connectionBag.getCount(STATE_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public final int getIdleConnections()
   {
      return connectionBag.getCount(STATE_NOT_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public final int getTotalConnections()
   {
      return connectionBag.size() - connectionBag.getCount(STATE_REMOVED);//STATE_REMOVED == 0?
   }

   /** {@inheritDoc} */
   @Override
   public final int getThreadsAwaitingConnection()
   {
      return connectionBag.getPendingQueue();
   }

   /** {@inheritDoc} */
   @Override
   public void softEvictConnections()
   {
      for (PoolEntry poolEntry : connectionBag.values()) {
         softEvictConnection(poolEntry, "(connection evicted)", false /* not owner */);
      }
   }

   /** {@inheritDoc} */
   @Override
   public final synchronized void suspendPool()
   {
      if (suspendResumeLock == SuspendResumeLock.FAUX_LOCK) {
         //如果没有配置 “允许中止”(config.isAllowPoolSuspension=true) 调用此方法，抛出异常
         throw new IllegalStateException(poolName + " - is not suspendable");
      }
      else if (poolState != POOL_SUSPENDED) {
         suspendResumeLock.suspend();
         poolState = POOL_SUSPENDED;
      }
   }

   /** {@inheritDoc} */
   @Override
   public final synchronized void resumePool()
   {
      if (poolState == POOL_SUSPENDED) {
         poolState = POOL_NORMAL;
         fillPool();
         suspendResumeLock.resume();
      }
   }

   // ***********************************************************************
   //                           Package methods
   // ***********************************************************************

   /**
    * Log the current pool state at debug level.
    *
    * @param prefix an optional prefix to prepend the log message
	* 记录连接池状态日志 debug级别
    */
   final void logPoolState(String... prefix)
   {
      if (LOGGER.isDebugEnabled()) {
         LOGGER.debug("{} - {}stats (total={}, active={}, idle={}, waiting={})",
                      poolName, (prefix.length > 0 ? prefix[0] : ""),
                      getTotalConnections(), getActiveConnections(), getIdleConnections(), getThreadsAwaitingConnection());
      }
   }

   /**
    * Release a connection back to the pool, or permanently close it if it is broken.
    *
    * @param poolEntry the PoolBagEntry to release back to the pool
	* 释放一个连接到连接池，如果他不能使用，永久关闭
    */
   @Override
   final void releaseConnection(final PoolEntry poolEntry)
   {
      metricsTracker.recordConnectionUsage(poolEntry);

      connectionBag.requite(poolEntry);
   }

   /**
    * Permanently close the real (underlying) connection (eat any exception).
    *
    * @param poolEntry poolEntry having the connection to close
    * @param closureReason reason to close
	* 永久关闭真是的数据库连接对象，不抛出任何异常，设置PoolEntry中Connection对象为null
    */
   final void closeConnection(final PoolEntry poolEntry, final String closureReason)
   {
      //从concurrentBag中移除
      if (connectionBag.remove(poolEntry)) {
         final int tc = totalConnections.decrementAndGet();
         if (tc < 0) {
            LOGGER.warn("{} - Unexpected value of totalConnections={}", poolName, tc, new Exception());
         }
		 //如果连接没有超过最大生存时间，从超过最长生存时间关闭的定时任务调度器中取消，并设置PoolEntry中Connection对象为null
         final Connection connection = poolEntry.close();
		 //加入关闭物理连接操作到线程池中
         closeConnectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
               quietlyCloseConnection(connection, closureReason);
            }
         });
      }
   }

   // ***********************************************************************
   //                           Private methods
   // ***********************************************************************

   /**
    * Create and add a single connection to the pool.
	* 创建一个数据库连接
    */
   private PoolEntry createPoolEntry()
   {
      try {
         //创建一个数据库连接，包装为PoolEntry对象
         final PoolEntry poolEntry = newPoolEntry();

         final long maxLifetime = config.getMaxLifetime();
         if (maxLifetime > 0) {
            // variance up to 2.5% of the maxlifetime
			//如果设置的数据库连接最大存活时间大于10000毫秒，设置真实过期时间为设置的值-设置值/40
            final long variance = maxLifetime > 10_000 ? ThreadLocalRandom.current().nextLong( maxLifetime / 40 ) : 0;
            final long lifetime = maxLifetime - variance;
			//将连接加入到超过最长生存时间关闭的定时任务调度器，并将Future结果设置到本身的endOfLife属性上
            poolEntry.setFutureEol(houseKeepingExecutorService.schedule(new Runnable() {
               @Override
               public void run() {
                  softEvictConnection(poolEntry, "(connection has passed maxLifetime)", false /* not owner */);
               }
            }, lifetime, MILLISECONDS));
         }

         LOGGER.debug("{} - Added connection {}", poolName, poolEntry.connection);
         return poolEntry;
      }
      catch (Exception e) {
         if (poolState == POOL_NORMAL) {
            LOGGER.debug("{} - Cannot acquire connection from data source", poolName, e);
         }
         return null;
      }
   }

   /**
    * Fill pool up from current idle connections (as they are perceived at the point of execution) to minimumIdle connections.
	* 填充数据库连接池
    */
   private void fillPool()
   {
      //待添加数量：min(池中允许最大数-当前数量,最小空闲连接数-当前空闲连接数)-正在创建中的数量  connectionsToAdd可能是个小于等于0的数
      final int connectionsToAdd = Math.min(config.getMaximumPoolSize() - totalConnections.get(), config.getMinimumIdle() - getIdleConnections())
                                   - addConnectionExecutor.getQueue().size();
      //填充
      for (int i = 0; i < connectionsToAdd; i++) {
         addBagItem();
      }
      //如果待添加数量>0,记录日志
      if (connectionsToAdd > 0 && LOGGER.isDebugEnabled()) {
         addConnectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
               logPoolState("After adding ");
            }
         });
      }
   }

   /**
    * Attempt to abort() active connections, or close() them.
	* 中止活动的（使用中的）连接，并关闭它 （连接池关闭时使用此方法）
    */
   private void abortActiveConnections(final ExecutorService assassinExecutor)
   {
      for (PoolEntry poolEntry : connectionBag.values(STATE_IN_USE)) {
         try {
            //中止物理连接
            poolEntry.connection.abort(assassinExecutor);
         }
         catch (Throwable e) {
            //关闭物理连接
            quietlyCloseConnection(poolEntry.connection, "(connection aborted during shutdown)");
         }
         finally {
            //如果连接没有超过最大生存时间，从超过最长生存时间关闭的定时任务调度器中取消，并设置PoolEntry中Connection对象为null
            poolEntry.close();
			//从concurrentBag中移除
            if (connectionBag.remove(poolEntry)) {
               totalConnections.decrementAndGet();
            }
         }
      }
   }

   /**
    * Fill the pool up to the minimum size.
	*
	* //快速验证数据库连接是否正常,有异常，关闭此连接池
    */
   private void checkFailFast()
   {
      if (config.isInitializationFailFast()) {
         try {
            newConnection().close();
         }
         catch (Throwable e) {
            try {
               shutdown();
            }
            catch (Throwable ex) {
               e.addSuppressed(ex);
            }

            throw new PoolInitializationException(e);
         }
      }
   }

   //将concurrentBag中所有是“未使用”的对象，从concurrentBag中移除并加入到关闭连接的线程池中，并标记所有对象“待清除”为true
   //owner 不管PoolEntry的状态如何，根据PoolEntry持有的数据库连接状态确定是否要关闭(void evictConnection(Connection connection)方法中)
   private void softEvictConnection(final PoolEntry poolEntry, final String reason, final boolean owner)
   {
      if (owner || connectionBag.reserve(poolEntry)) { //使用reserve()方法，只会处理"NOT_IN_USE"状态的
         poolEntry.markEvicted();
         closeConnection(poolEntry, reason);
      }
      else {
         poolEntry.markEvicted();
      }
   }

   private PoolStats getPoolStats()
   {
      return new PoolStats(SECONDS.toMillis(1)) {
         @Override
         protected void update() {
            this.pendingThreads = HikariPool.this.getThreadsAwaitingConnection();
            this.idleConnections = HikariPool.this.getIdleConnections();
            this.totalConnections = HikariPool.this.getTotalConnections();
            this.activeConnections = HikariPool.this.getActiveConnections();
         }
      };
   }

   // ***********************************************************************
   //                      Non-anonymous Inner-classes
   // ***********************************************************************
   
   //创建一个PoolEntry
   private class PoolEntryCreator implements Callable<Boolean>
   {
      @Override
      public Boolean call() throws Exception
      {
         long sleepBackoff = 250L;
		 //连接池正常，当前连接数小于配置的最大数，创建
         while (poolState == POOL_NORMAL && totalConnections.get() < config.getMaximumPoolSize()) {
            final PoolEntry poolEntry = createPoolEntry();
            if (poolEntry != null) {
               totalConnections.incrementAndGet();
               connectionBag.add(poolEntry);
               return Boolean.TRUE;
            }
            
            // failed to get connection from db, sleep and retry
            //创建失败，sleep(250),重试
			quietlySleep(sleepBackoff);
			//重设重试时间，重试时间逐渐增加，取connectionTimeout,10 000,sleepBackoff*1.5中的最小值
            sleepBackoff = Math.min(SECONDS.toMillis(10), Math.min(connectionTimeout, (long) (sleepBackoff * 1.5)));
         }
         // Pool is suspended or shutdown or at max size
         return Boolean.FALSE;
      }
   }

   /**
    * The house keeping task to retire idle connections.
	* 定时任务执行器执行者，空闲连接数维护者，第一次
    */
   private class HouseKeeper implements Runnable
   {
	  //三十秒之前
      private volatile long previous = clockSource.plusMillis(clockSource.currentTime(), -HOUSEKEEPING_PERIOD_MS);

      @Override
      public void run()
      {
         // refresh timeouts in case they changed via MBean
		 //更新连接池配置 BY MBean
         connectionTimeout = config.getConnectionTimeout();
         validationTimeout = config.getValidationTimeout();
         leakTask.updateLeakDetectionThreshold(config.getLeakDetectionThreshold());

         final long idleTimeout = config.getIdleTimeout();
         final long now = clockSource.currentTime();

         // Detect retrograde time, allowing +128ms as per NTP spec.
		 //时钟变慢（逆时间），允许有128ms的误差，如果大于128ms     （连接本应该失效了，但时间还没到）
         if (clockSource.plusMillis(now, 128) < clockSource.plusMillis(previous, HOUSEKEEPING_PERIOD_MS)) {
            LOGGER.warn("{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool.",
                        clockSource.elapsedDisplayString(previous, now), poolName);
            previous = now;
			//关闭所有非活动连接
            softEvictConnections();
			//填充连接池
            fillPool();
            return;
         }
         else if (now > clockSource.plusMillis(previous, (3 * HOUSEKEEPING_PERIOD_MS) / 2)) {
			//如果当前时间 > 上次执行时间的3/2 无需调整，这样的时钟会加速空闲连接的失效 （时钟快）
            // No point evicting for forward clock motion, this merely accelerates connection retirement anyway
            LOGGER.warn("{} - Thread starvation or clock leap detected (housekeeper delta={}).", clockSource.elapsedDisplayString(previous, now), poolName);
         }

         previous = now;

         String afterPrefix = "Pool ";
         if (idleTimeout > 0L) {
            //维持配置的最小空闲连接数量，将超过超时时间的空闲连接去掉
            final List<PoolEntry> idleList = connectionBag.values(STATE_NOT_IN_USE);
            int removable = idleList.size() - config.getMinimumIdle();
            if (removable > 0) {
               logPoolState("Before cleanup ");
               afterPrefix = "After cleanup  ";

               // Sort pool entries on lastAccessed
			   //按最后连接到数据库的时间排序,保证移除的都是空闲最久的
               Collections.sort(idleList, LASTACCESS_COMPARABLE);
               for (PoolEntry poolEntry : idleList) {
                  //大于空闲时间的，从concurrentBag中移除（因为关闭数据库连接需要时间，先更改PoolEntry状态不可用，以免在关闭物理连接时被引用）
                  if (clockSource.elapsedMillis(poolEntry.lastAccessed, now) > idleTimeout && connectionBag.reserve(poolEntry)) {
                     closeConnection(poolEntry, "(connection has passed idleTimeout)");
                     if (--removable == 0) {
                        break; // keep min idle cons
                     }
                  }
               }
            }
         }

         logPoolState(afterPrefix);
         //维持配置最小连接数，填充连接
         fillPool(); // Try to maintain minimum connections
      }
   }

   //自定义连接池初始化异常
   public static class PoolInitializationException extends RuntimeException
   {
      private static final long serialVersionUID = 929872118275916520L;

      /**
       * Construct an exception, possibly wrapping the provided Throwable as the cause.
       * @param t the Throwable to wrap
       */
      public PoolInitializationException(Throwable t)
      {
         super("Failed to initialize pool: " + t.getMessage(), t);
      }
   }
}
