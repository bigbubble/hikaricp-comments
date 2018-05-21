/*
 * Copyright (C) 2014 Brett Wooldridge
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
import java.sql.Statement;
import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ClockSource;
import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import com.zaxxer.hikari.util.FastList;

/**
 * Entry used in the ConcurrentBag to track Connection instances.
 *
 * @author Brett Wooldridge
 * ConcurrentBag中的存储的类，包含了具体的数据库连接对象Connection,并记录了数据库连接状态和相关信息
 */
final class PoolEntry implements IConcurrentBagEntry
{
   private static final Logger LOGGER = LoggerFactory.getLogger(PoolEntry.class);

   static final Comparator<PoolEntry> LASTACCESS_COMPARABLE;

   Connection connection; //具体连接
   long lastAccessed; //最近一次连接数据库的时间
   long lastBorrowed; //最近被“借”的时间
   private volatile boolean evict; //是否被标记“待清理”

   private volatile ScheduledFuture<?> endOfLife; //最大生存时间定时处理的处理结果

   private final FastList<Statement> openStatements; //执行的sql语句
   private final HikariPool hikariPool; //连接池引用
   private final AtomicInteger state; //状态

   private final boolean isReadOnly; //是否只读
   private final boolean isAutoCommit; //是否自动提交

   static
   {
      //初始化比较器，最近一次连接数据库的时间 越小排在前面
      LASTACCESS_COMPARABLE = new Comparator<PoolEntry>() {
         @Override
         public int compare(final PoolEntry entryOne, final PoolEntry entryTwo) {
            return Long.compare(entryOne.lastAccessed, entryTwo.lastAccessed);
         }
      };
   }

   PoolEntry(final Connection connection, final PoolBase pool, final boolean isReadOnly, final boolean isAutoCommit)
   {
      this.connection = connection;
      this.hikariPool = (HikariPool) pool;
      this.isReadOnly = isReadOnly;
      this.isAutoCommit = isAutoCommit;
      this.state = new AtomicInteger();
      this.lastAccessed = ClockSource.INSTANCE.currentTime();
      this.openStatements = new FastList<>(Statement.class, 16);
   }

   /**
    * Release this entry back to the pool.
    *
    * @param lastAccessed last access time-stamp
	* 从"使用状态"变成"未使用状态",释放到连接池中，最终调用ConcurrentBag.requite(PoolEntry)方法
    */
   void recycle(final long lastAccessed)
   {
      this.lastAccessed = lastAccessed;
      hikariPool.releaseConnection(this);
   }

   /**
    * @param endOfLife
	* 设置连接 超过最大生存时间的Future对象
    */
   void setFutureEol(final ScheduledFuture<?> endOfLife)
   {
      this.endOfLife = endOfLife;
   }

   //创建代理类
   Connection createProxyConnection(final ProxyLeakTask leakTask, final long now)
   {
      return ProxyFactory.getProxyConnection(this, connection, openStatements, leakTask, now, isReadOnly, isAutoCommit);
   }

   //重设连接状态
   void resetConnectionState(final ProxyConnection proxyConnection, final int dirtyBits) throws SQLException
   {
      hikariPool.resetConnectionState(connection, proxyConnection, dirtyBits);
   }

   String getPoolName()
   {
      return hikariPool.toString();
   }

   boolean isMarkedEvicted()
   {
      return evict;
   }

   void markEvicted()
   {
      this.evict = true;
   }

   //清除此连接
   void evict(final String closureReason)
   {
      hikariPool.closeConnection(this, closureReason);
   }

   /** Returns millis since lastBorrowed */
   //上次“借”走到现在的时间
   long getMillisSinceBorrowed()
   {
      return ClockSource.INSTANCE.elapsedMillis(lastBorrowed);
   }

   /** {@inheritDoc} */
   @Override
   public String toString()
   {
      final long now = ClockSource.INSTANCE.currentTime();
      return connection
         + ", accessed " + ClockSource.INSTANCE.elapsedDisplayString(lastAccessed, now) + " ago, "
         + stateToString();
   }

   // ***********************************************************************
   //                      IConcurrentBagEntry methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public int getState()
   {
      return state.get();
   }

   /** {@inheritDoc} */
   @Override
   public boolean compareAndSet(int expect, int update)
   {
      return state.compareAndSet(expect, update);
   }

   /** {@inheritDoc} */
   @Override
   public void lazySet(int update)
   {
      state.lazySet(update);
   }
   //关闭连接（并没有关闭Connection连接,返回处理）
   Connection close()
   {
      ScheduledFuture<?> eol = endOfLife;
	  //最大生存时间超时任务超，如果没有完成，取消此任务
      if (eol != null && !eol.isDone() && !eol.cancel(false)) {
         LOGGER.warn("{} - maxLifeTime expiration task cancellation unexpectedly returned false for connection {}", getPoolName(), connection);
      }
      //清除引用
      Connection con = connection;
      connection = null;
      endOfLife = null;
      return con;
   }

   //状态的字符串表示
   private String stateToString()
   {
      switch (state.get()) {
      case STATE_IN_USE:
         return "IN_USE";
      case STATE_NOT_IN_USE:
         return "NOT_IN_USE";
      case STATE_REMOVED:
         return "REMOVED";
      case STATE_RESERVED:
         return "RESERVED";
      default:
         return "Invalid";
      }
   }
}
