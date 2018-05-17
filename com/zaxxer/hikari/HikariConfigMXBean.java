/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.hikari;

/**
 * The javax.management MBean for a Hikari pool configuration.
 *
 * @author Brett Wooldridge
 *
 * ConfigMXBean
 */
public interface HikariConfigMXBean
{
   /**
    * Get the maximum number of milliseconds that a client will wait for a connection from the pool. If this 
    * time is exceeded without a connection becoming available, a SQLException will be thrown from
    * {@link javax.sql.DataSource#getConnection()}.
    *
    * @return the connection timeout in milliseconds
    * 
    * 获取一个客户端从连接池获取一个数据库连接的最大等待时间（毫秒）。
    * DataSource#getConnection()操作时，如果超过这个时间没有获得数据库连接，会抛出一个SQLException
    */
   long getConnectionTimeout();

   /**
    * Set the maximum number of milliseconds that a client will wait for a connection from the pool. If this
    * time is exceeded without a connection becoming available, a SQLException will be thrown from
    * {@link javax.sql.DataSource#getConnection()}.
    *
    * @param connectionTimeoutMs the connection timeout in milliseconds
    * 设置一个客户端从连接池获取一个数据库连接的最大等待时间（毫秒）。
    * DataSource#getConnection()操作时，如果超过这个时间没有获得数据库连接，会抛出一个SQLException
    */
   void setConnectionTimeout(long connectionTimeoutMs);

   /**
    * Get the maximum number of milliseconds that the pool will wait for a connection to be validated as
    * alive.
    *
    * @return the validation timeout in milliseconds
    *
    * 获取 连接池验证一个连接是存活状态的最大等待时间
    */
   long getValidationTimeout();

   /**
    * Sets the maximum number of milliseconds that the pool will wait for a connection to be validated as
    * alive.
    *
    * @param validationTimeoutMs the validation timeout in milliseconds
    *
    * 设置 连接池验证一个连接是存活状态的最大等待时间
    */
   void setValidationTimeout(long validationTimeoutMs);

   /**
    * This property controls the maximum amount of time (in milliseconds) that a connection is allowed to sit 
    * idle in the pool. Whether a connection is retired as idle or not is subject to a maximum variation of +30
    * seconds, and average variation of +15 seconds. A connection will never be retired as idle before this timeout.
    * A value of 0 means that idle connections are never removed from the pool.
    *
    * @return the idle timeout in milliseconds
    *
    * 一个连接在连接池中最大空闲时间。
    * 无论连接是否是因为超过闲置时间而断开连接，都会受到最大+30s,平均15s的影响
    * 在这个值之前，连接不会因为空闲而弃用
    * 0表示空闲的连接永远不会从连接池中移除
    */
   long getIdleTimeout();

   /**
    * This property controls the maximum amount of time (in milliseconds) that a connection is allowed to sit 
    * idle in the pool. Whether a connection is retired as idle or not is subject to a maximum variation of +30
    * seconds, and average variation of +15 seconds. A connection will never be retired as idle before this timeout.
    * A value of 0 means that idle connections are never removed from the pool.
    *
    * @param idleTimeoutMs the idle timeout in milliseconds
    * 
    * 设置一个连接在连接池中最大空闲时间
    */
   void setIdleTimeout(long idleTimeoutMs);

   /**
    * This property controls the amount of time that a connection can be out of the pool before a message is
    * logged indicating a possible connection leak. A value of 0 means leak detection is disabled.
    *
    * @return the connection leak detection threshold in milliseconds
    * 
    * 在有记录表明可能有连接溢出之前，连接可以退出池的时间
    * 0表示禁用连接溢出检测
    */
   long getLeakDetectionThreshold();

   /**
    * This property controls the amount of time that a connection can be out of the pool before a message is
    * logged indicating a possible connection leak. A value of 0 means leak detection is disabled.
    *
    * @param leakDetectionThresholdMs the connection leak detection threshold in milliseconds
    * 设置 在有记录表明可能有连接溢出之前，连接可以退出池的时间
    */
   void setLeakDetectionThreshold(long leakDetectionThresholdMs);

   /**
    * This property controls the maximum lifetime of a connection in the pool. When a connection reaches this
    * timeout, even if recently used, it will be retired from the pool. An in-use connection will never be
    * retired, only when it is idle will it be removed.
    *
    * @return the maximum connection lifetime in milliseconds
    *
    * 连接在连接池中存在的最长时间。如果一个连接达到了超时时间，尽管它最近被使用过，依然会被移除。如果一个连接正在被使用，不会被移除，当它空闲时才会被移除
    */
   long getMaxLifetime();

   /**
    * This property controls the maximum lifetime of a connection in the pool. When a connection reaches this
    * timeout, even if recently used, it will be retired from the pool. An in-use connection will never be
    * retired, only when it is idle will it be removed.
    *
    * @param maxLifetimeMs the maximum connection lifetime in milliseconds
    * 设置 连接在连接池中存在的最长时间
    */
   void setMaxLifetime(long maxLifetimeMs);

   /**
    * The property controls the maximum size that the pool is allowed to reach, including both idle and in-use
    * connections. Basically this value will determine the maximum number of actual connections to the database
    * backend.
    * <p>
    * When the pool reaches this size, and no idle connections are available, calls to getConnection() will
    * block for up to connectionTimeout milliseconds before timing out.
    *
    * @return the minimum number of connections in the pool
    * 源码注释错误 ？
    */
   int getMinimumIdle();

   /**
    * The property controls the minimum number of idle connections that HikariCP tries to maintain in the pool,
    * including both idle and in-use connections. If the idle connections dip below this value, HikariCP will
    * make a best effort to restore them quickly and efficiently.
    *
    * @param minIdle the minimum number of idle connections in the pool to maintain
    *
    * 设置 连接池最小空闲连接数，如果连接池中空闲连接小于这个值，小于MaxPoolSize，连接池会尽可能的高效快速的创建这个连接
    */
   void setMinimumIdle(int minIdle);

   /**
    * The property controls the maximum number of connections that HikariCP will keep in the pool,
    * including both idle and in-use connections. 
    *
    * @return the maximum number of connections in the pool
    * 
    * 连接池最大连接数，包括空闲和使用的连接
    */
   int getMaximumPoolSize();

   /**
    * The property controls the maximum size that the pool is allowed to reach, including both idle and in-use
    * connections. Basically this value will determine the maximum number of actual connections to the database
    * backend.
    * <p>
    * When the pool reaches this size, and no idle connections are available, calls to getConnection() will
    * block for up to connectionTimeout milliseconds before timing out.
    *
    * @param maxPoolSize the maximum number of connections in the pool
    * 设置 连接池最大连接数.基本上，这个值将决定到数据库后端的实际连接的最大数量。
    * 当连接池中连接数量达到了这个值，并且没有空闲的连接可供使用，getConnect()在超时之前会阻塞
    */
   void setMaximumPoolSize(int maxPoolSize);

   /**
    * Set the password used for authentication. Changing this at runtime will apply to new connections only.
    * Altering this at runtime only works for DataSource-based connections, not Driver-class or JDBC URL-based
    * connections.
    *
    * @param password the database password
    *
    * 运行时更改数据库连接密码，只对新DataSource-based连接有效，对Driver-class或JDBC-based连接无效
    */
   void setPassword(String password);

   /**
    * Set the username used for authentication. Changing this at runtime will apply to new connections only.
    * Altering this at runtime only works for DataSource-based connections, not Driver-class or JDBC URL-based
    * connections.
    *
    * @param username the database username
    *
    * 运行时更改数据库连接用户名，只对新DataSource-based连接有效，对Driver-class或JDBC-based连接无效
    */
   void setUsername(String username);

  
   /**
    * The name of the connection pool.
    *
    * @return the name of the connection pool
    *
    * 获取连接池名称
    */
   String getPoolName();
}