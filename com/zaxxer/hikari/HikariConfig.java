/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.util.PropertyElf;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import static com.zaxxer.hikari.util.UtilityElf.getNullIfEmpty;
/**
 * 
 */
public class HikariConfig implements HikariConfigMXBean
{
   private static final Logger LOGGER = LoggerFactory.getLogger(HikariConfig.class);

   private static final long CONNECTION_TIMEOUT = SECONDS.toMillis(30);//30秒
   private static final long VALIDATION_TIMEOUT = SECONDS.toMillis(5);//5秒
   private static final long IDLE_TIMEOUT = MINUTES.toMillis(10);//10分钟
   private static final long MAX_LIFETIME = MINUTES.toMillis(30);//30分钟

   private static boolean unitTest;

   // Properties changeable at runtime through the MBean 可以在运行时通过MBean修改
   //
   //获取一个客户端从连接池获取一个数据库连接的最大等待时间（毫秒）
   private volatile long connectionTimeout;
   //连接池验证一个连接是存活状态的最大等待时间
   private volatile long validationTimeout;
   //连接空闲时间
   private volatile long idleTimeout;
   //在有记录表明可能有连接溢出之前，连接可以退出池的时间
   private volatile long leakDetectionThreshold;
   //连接最大存活时间
   private volatile long maxLifetime;
   //最大连接池数量
   private volatile int maxPoolSize;
   //最小空闲数
   private volatile int minIdle;

   // Properties NOT changeable at runtime 不可以在运行时通过MBean修改
   //
   private String catalog;
   private String connectionInitSql;//连接创建后，调用的sql,如果sql执行失败则连接不加入到连接池中
   private String connectionTestQuery;//测试连接可用性sql
   private String dataSourceClassName;
   private String dataSourceJndiName;
   private String driverClassName;
   private String jdbcUrl;
   private String password;
   private String poolName;
   private String transactionIsolationName;
   private String username;
   private boolean isAutoCommit;
   private boolean isReadOnly;
   private boolean isInitializationFailFast;
   private boolean isIsolateInternalQueries;
   private boolean isRegisterMbeans;
   private boolean isAllowPoolSuspension;
   private DataSource dataSource;
   private Properties dataSourceProperties;
   private ThreadFactory threadFactory;
   private ScheduledThreadPoolExecutor scheduledExecutor;
   private MetricsTrackerFactory metricsTrackerFactory;
   private Object metricRegistry;
   private Object healthCheckRegistry;
   private Properties healthCheckProperties;

   /**
    * Default constructor
    */
   public HikariConfig()
   {
      dataSourceProperties = new Properties();
      healthCheckProperties = new Properties();

      minIdle = -1;
      maxPoolSize = -1;
      maxLifetime = MAX_LIFETIME;//30min
      connectionTimeout = CONNECTION_TIMEOUT;//30s
      validationTimeout = VALIDATION_TIMEOUT;//5s
      idleTimeout = IDLE_TIMEOUT;//10min

      isAutoCommit = true;
      isInitializationFailFast = true;
      //系统属性指定配置文件
      String systemProp = System.getProperty("hikaricp.configurationFile");
      if (systemProp != null) {
         loadProperties(systemProp);
      }
   }

   /**
    * Construct a HikariConfig from the specified properties object.
    *
    * @param properties the name of the property file
    * 从特定的属性对象构建对象
    */
   public HikariConfig(Properties properties)
   {
      this();
      PropertyElf.setTargetFromProperties(this, properties);
   }

   /**
    * Construct a HikariConfig from the specified property file name.  <code>propertyFileName</code>
    * will first be treated as a path in the file-system, and if that fails the
    * Class.getResourceAsStream(propertyFileName) will be tried.
    *
    * @param propertyFileName the name of the property file
    * 从指定文件名构建对象，如果从文件系统获取失败，会尝试Class.getResourceAsStream(propertyFileName)
    */
   public HikariConfig(String propertyFileName)
   {
      this();

      loadProperties(propertyFileName);
   }

   /**
    * Get the default catalog name to be set on connections.
    *
    * @return the default catalog name
    */
   public String getCatalog()
   {
      return catalog;
   }

   /**
    * Set the default catalog name to be set on connections.
    *
    * @param catalog the catalog name, or null
    */
   public void setCatalog(String catalog)
   {
      this.catalog = catalog;
   }

   /**
    * Get the SQL query to be executed to test the validity of connections.
    *
    * @return the SQL query string, or null
    */
   public String getConnectionTestQuery()
   {
      return connectionTestQuery;
   }

   /**
    * Set the SQL query to be executed to test the validity of connections. Using
    * the JDBC4 <code>Connection.isValid()</code> method to test connection validity can
    * be more efficient on some databases and is recommended.  See
    * {@link HikariConfig#setJdbc4ConnectionTest(boolean)}.
    *
    * @param connectionTestQuery a SQL query string
    */
   public void setConnectionTestQuery(String connectionTestQuery)
   {
      this.connectionTestQuery = connectionTestQuery;
   }

   /**
    * Get the SQL string that will be executed on all new connections when they are
    * created, before they are added to the pool.
    *
    * @return the SQL to execute on new connections, or null
    */
   public String getConnectionInitSql()
   {
      return connectionInitSql;
   }

   /**
    * Set the SQL string that will be executed on all new connections when they are
    * created, before they are added to the pool.  If this query fails, it will be
    * treated as a failed connection attempt.
    *
    * @param connectionInitSql the SQL to execute on new connections
    */
   public void setConnectionInitSql(String connectionInitSql)
   {
      this.connectionInitSql = connectionInitSql;
   }

   /** {@inheritDoc} */
   @Override
   public long getConnectionTimeout()
   {
      return connectionTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setConnectionTimeout(long connectionTimeoutMs)
   {
      if (connectionTimeoutMs == 0) {
         this.connectionTimeout = Integer.MAX_VALUE;
      }
      else if (connectionTimeoutMs < 250) {
         throw new IllegalArgumentException("connectionTimeout cannot be less than 250ms");
      }
      else {
         this.connectionTimeout = connectionTimeoutMs;
      }
   }

   /** {@inheritDoc} */
   @Override
   public long getValidationTimeout()
   {
      return validationTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setValidationTimeout(long validationTimeoutMs)
   {
      if (validationTimeoutMs < 250) {
         throw new IllegalArgumentException("validationTimeout cannot be less than 250ms");
      }

      this.validationTimeout = validationTimeoutMs;
   }

   /**
    * Get the {@link DataSource} that has been explicitly specified to be wrapped by the
    * pool.
    *
    * @return the {@link DataSource} instance, or null
    */
   public DataSource getDataSource()
   {
      return dataSource;
   }

   /**
    * Set a {@link DataSource} for the pool to explicitly wrap.  This setter is not
    * available through property file based initialization.
    *
    * @param dataSource a specific {@link DataSource} to be wrapped by the pool
    */
   public void setDataSource(DataSource dataSource)
   {
      this.dataSource = dataSource;
   }

   public String getDataSourceClassName()
   {
      return dataSourceClassName;
   }

   public void setDataSourceClassName(String className)
   {
      this.dataSourceClassName = className;
   }

   public void addDataSourceProperty(String propertyName, Object value)
   {
      dataSourceProperties.put(propertyName, value);
   }

   public String getDataSourceJNDI()
   {
      return this.dataSourceJndiName;
   }

   public void setDataSourceJNDI(String jndiDataSource)
   {
      this.dataSourceJndiName = jndiDataSource;
   }

   public Properties getDataSourceProperties()
   {
      return dataSourceProperties;
   }

   public void setDataSourceProperties(Properties dsProperties)
   {
      dataSourceProperties.putAll(dsProperties);
   }

   public String getDriverClassName()
   {
      return driverClassName;
   }

   public void setDriverClassName(String driverClassName)
   {
      try {
         Class<?> driverClass = this.getClass().getClassLoader().loadClass(driverClassName);
         driverClass.newInstance();
         this.driverClassName = driverClassName;
      }
      catch (Exception e) {
         throw new RuntimeException("Failed to load class of driverClassName " + driverClassName, e);
      }
   }

   /** {@inheritDoc} */
   @Override
   public long getIdleTimeout()
   {
      return idleTimeout;
   }

   /** {@inheritDoc} */
   @Override
   public void setIdleTimeout(long idleTimeoutMs)
   {
      if (idleTimeoutMs < 0) {
         throw new IllegalArgumentException("idleTimeout cannot be negative");
      }
      this.idleTimeout = idleTimeoutMs;
   }

   public String getJdbcUrl()
   {
      return jdbcUrl;
   }

   public void setJdbcUrl(String jdbcUrl)
   {
      this.jdbcUrl = jdbcUrl;
   }

   /**
    * Get the default auto-commit behavior of connections in the pool.
    *
    * @return the default auto-commit behavior of connections
    */
   public boolean isAutoCommit()
   {
      return isAutoCommit;
   }

   /**
    * Set the default auto-commit behavior of connections in the pool.
    *
    * @param isAutoCommit the desired auto-commit default for connections
    */
   public void setAutoCommit(boolean isAutoCommit)
   {
      this.isAutoCommit = isAutoCommit;
   }

   /**
    * Get the pool suspension behavior (allowed or disallowed).
    *
    * @return the pool suspension behavior
    */
   public boolean isAllowPoolSuspension()
   {
      return isAllowPoolSuspension;
   }

   /**
    * Set whether or not pool suspension is allowed.  There is a performance
    * impact when pool suspension is enabled.  Unless you need it (for a
    * redundancy system for example) do not enable it.
    *
    * @param isAllowPoolSuspension the desired pool suspension allowance
    */
   public void setAllowPoolSuspension(boolean isAllowPoolSuspension)
   {
      this.isAllowPoolSuspension = isAllowPoolSuspension;
   }

   /**
    * Get whether or not the construction of the pool should throw an exception
    * if the minimum number of connections cannot be created.
    *
    * @return whether or not initialization should fail on error immediately
    */
   public boolean isInitializationFailFast()
   {
      return isInitializationFailFast;
   }

   /**
    * Set whether or not the construction of the pool should throw an exception
    * if the minimum number of connections cannot be created.
    *
    * @param failFast true if the pool should fail if the minimum connections cannot be created
    */
   public void setInitializationFailFast(boolean failFast)
   {
      isInitializationFailFast = failFast;
   }

   public boolean isIsolateInternalQueries()
   {
      return isIsolateInternalQueries;
   }

   public void setIsolateInternalQueries(boolean isolate)
   {
      this.isIsolateInternalQueries = isolate;
   }

   @Deprecated
   public boolean isJdbc4ConnectionTest()
   {
      return false;
   }

   @Deprecated
   public void setJdbc4ConnectionTest(boolean useIsValid)
   {
      LOGGER.warn("The jdbcConnectionTest property is now deprecated, see the documentation for connectionTestQuery");
   }

   public MetricsTrackerFactory getMetricsTrackerFactory()
   {
      return metricsTrackerFactory;
   }

   public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory)
   {
      if (metricRegistry != null) {
         throw new IllegalStateException("cannot use setMetricsTrackerFactory() and setMetricRegistry() together");
      }

      this.metricsTrackerFactory = metricsTrackerFactory;
   }

   /**
    * Get the Codahale MetricRegistry, could be null.
    *
    * @return the codahale MetricRegistry instance
    */
   public Object getMetricRegistry()
   {
      return metricRegistry;
   }

   /**
    * Set a Codahale MetricRegistry to use for HikariCP.
    *
    * @param metricRegistry the Codahale MetricRegistry to set
    */
   public void setMetricRegistry(Object metricRegistry)
   {
      if (metricsTrackerFactory != null) {
         throw new IllegalStateException("cannot use setMetricRegistry() and setMetricsTrackerFactory() together");
      }

      if (metricRegistry != null) {
         if (metricRegistry instanceof String) {
            try {
               InitialContext initCtx = new InitialContext();
               metricRegistry = initCtx.lookup((String) metricRegistry);
            }
            catch (NamingException e) {
               throw new IllegalArgumentException(e);
            }
         }

         if (!(metricRegistry instanceof MetricRegistry)) {
            throw new IllegalArgumentException("Class must be an instance of com.codahale.metrics.MetricRegistry");
         }
      }

      this.metricRegistry = metricRegistry;
   }

   /**
    * Get the Codahale HealthCheckRegistry, could be null.
    *
    * @return the Codahale HealthCheckRegistry instance
    */
   public Object getHealthCheckRegistry()
   {
      return healthCheckRegistry;
   }

   /**
    * Set a Codahale HealthCheckRegistry to use for HikariCP.
    *
    * @param healthCheckRegistry the Codahale HealthCheckRegistry to set
    */
   public void setHealthCheckRegistry(Object healthCheckRegistry)
   {
      if (healthCheckRegistry != null) {
         if (healthCheckRegistry instanceof String) {
            try {
               InitialContext initCtx = new InitialContext();
               healthCheckRegistry = initCtx.lookup((String) healthCheckRegistry);
            }
            catch (NamingException e) {
               throw new IllegalArgumentException(e);
            }
         }

         if (!(healthCheckRegistry instanceof HealthCheckRegistry)) {
            throw new IllegalArgumentException("Class must be an instance of com.codahale.metrics.health.HealthCheckRegistry");
         }
      }

      this.healthCheckRegistry = healthCheckRegistry;
   }

   public Properties getHealthCheckProperties()
   {
      return healthCheckProperties;
   }

   public void setHealthCheckProperties(Properties healthCheckProperties)
   {
      this.healthCheckProperties.putAll(healthCheckProperties);
   }

   public void addHealthCheckProperty(String key, String value)
   {
      healthCheckProperties.setProperty(key, value);
   }

   public boolean isReadOnly()
   {
      return isReadOnly;
   }

   public void setReadOnly(boolean readOnly)
   {
      this.isReadOnly = readOnly;
   }

   public boolean isRegisterMbeans()
   {
      return isRegisterMbeans;
   }

   public void setRegisterMbeans(boolean register)
   {
      this.isRegisterMbeans = register;
   }

   /** {@inheritDoc} */
   @Override
   public long getLeakDetectionThreshold()
   {
      return leakDetectionThreshold;
   }

   /** {@inheritDoc} */
   @Override
   public void setLeakDetectionThreshold(long leakDetectionThresholdMs)
   {
      this.leakDetectionThreshold = leakDetectionThresholdMs;
   }

   /** {@inheritDoc} */
   @Override
   public long getMaxLifetime()
   {
      return maxLifetime;
   }

   /** {@inheritDoc} */
   @Override
   public void setMaxLifetime(long maxLifetimeMs)
   {
      this.maxLifetime = maxLifetimeMs;
   }

   /** {@inheritDoc} */
   @Override
   public int getMaximumPoolSize()
   {
      return maxPoolSize;
   }

   /** {@inheritDoc} */
   @Override
   public void setMaximumPoolSize(int maxPoolSize)
   {
      if (maxPoolSize < 1) {
         throw new IllegalArgumentException("maxPoolSize cannot be less than 1");
      }
      this.maxPoolSize = maxPoolSize;
   }

   /** {@inheritDoc} */
   @Override
   public int getMinimumIdle()
   {
      return minIdle;
   }

   /** {@inheritDoc} */
   @Override
   public void setMinimumIdle(int minIdle)
   {
      if (minIdle < 0) {
         throw new IllegalArgumentException("minimumIdle cannot be negative");
      }
      this.minIdle = minIdle;
   }

   /**
    * Get the default password to use for DataSource.getConnection(username, password) calls.
    * @return the password
    */
   public String getPassword()
   {
      return password;
   }

   /**
    * Set the default password to use for DataSource.getConnection(username, password) calls.
    * @param password the password
    */
   @Override
   public void setPassword(String password)
   {
      this.password = password;
   }

   /** {@inheritDoc} */
   @Override
   public String getPoolName()
   {
      return poolName;
   }

   /**
    * Set the name of the connection pool.  This is primarily used for the MBean
    * to uniquely identify the pool configuration.
    *
    * @param poolName the name of the connection pool to use
    */
   public void setPoolName(String poolName)
   {
      this.poolName = poolName;
   }

   /**
    * Get the ScheduledExecutorService used for housekeeping.
    *
    * @return the executor
    */
   public ScheduledThreadPoolExecutor getScheduledExecutorService()
   {
      return scheduledExecutor;
   }

   /**
    * Set the ScheduledExecutorService used for housekeeping.
    *
    * @param executor the ScheduledExecutorService
    */
   public void setScheduledExecutorService(ScheduledThreadPoolExecutor executor)
   {
      this.scheduledExecutor = executor;
   }

   public String getTransactionIsolation()
   {
      return transactionIsolationName;
   }

   /**
    * Set the default transaction isolation level.  The specified value is the
    * constant name from the <code>Connection</code> class, eg.
    * <code>TRANSACTION_REPEATABLE_READ</code>.
    *
    * @param isolationLevel the name of the isolation level
    */
   public void setTransactionIsolation(String isolationLevel)
   {
      this.transactionIsolationName = isolationLevel;
   }

   /**
    * Get the default username used for DataSource.getConnection(username, password) calls.
    *
    * @return the username
    */
   public String getUsername()
   {
      return username;
   }

   /**
    * Set the default username used for DataSource.getConnection(username, password) calls.
    *
    * @param username the username
    */
   @Override
   public void setUsername(String username)
   {
      this.username = username;
   }

   /**
    * Get the thread factory used to create threads.
    *
    * @return the thread factory (may be null, in which case the default thread factory is used)
    */
   public ThreadFactory getThreadFactory()
   {
      return threadFactory;
   }

   /**
    * Set the thread factory to be used to create threads.
    *
    * @param threadFactory the thread factory (setting to null causes the default thread factory to be used)
    */
   public void setThreadFactory(ThreadFactory threadFactory)
   {
      this.threadFactory = threadFactory;
   }

   public void validate()
   {
      if (poolName == null) {
         poolName = "HikariPool-" + generatePoolNumber();
      }
      else if (isRegisterMbeans && poolName.contains(":")) {
         throw new IllegalArgumentException("poolName cannot contain ':' when used with JMX");
      }

      // treat empty property as null
      catalog = getNullIfEmpty(catalog);
      connectionInitSql = getNullIfEmpty(connectionInitSql);
      connectionTestQuery = getNullIfEmpty(connectionTestQuery);
      transactionIsolationName = getNullIfEmpty(transactionIsolationName);
      dataSourceClassName = getNullIfEmpty(dataSourceClassName);
      dataSourceJndiName = getNullIfEmpty(dataSourceJndiName);
      driverClassName = getNullIfEmpty(driverClassName);
      jdbcUrl = getNullIfEmpty(jdbcUrl);

      // Check Data Source Options
      if (dataSource != null) {
         if (dataSourceClassName != null) {
            LOGGER.warn("{} - using dataSource and ignoring dataSourceClassName.", poolName);
         }
      }
      else if (dataSourceClassName != null) {
         if (driverClassName != null) {
            LOGGER.error("{} - cannot use driverClassName and dataSourceClassName together.", poolName);
            throw new IllegalArgumentException("cannot use driverClassName and dataSourceClassName together.");
         }
         else if (jdbcUrl != null) {
            LOGGER.warn("{} - using dataSourceClassName and ignoring jdbcUrl.", poolName);
         }
      }
      else if (jdbcUrl != null) {
      }
      else if (driverClassName != null) {
         LOGGER.error("{} - jdbcUrl is required with driverClassName.", poolName);
         throw new IllegalArgumentException("jdbcUrl is required with driverClassName.");
      }
      else {
         LOGGER.error("{} - dataSource or dataSourceClassName or jdbcUrl is required.", poolName);
         throw new IllegalArgumentException("dataSource or dataSourceClassName or jdbcUrl is required.");
      }

      validateNumerics();

      if (LOGGER.isDebugEnabled() || unitTest) {
         logConfiguration();
      }
   }

   private void validateNumerics()
   {
      //最小“最大连接存活时间不能小于30秒，否则设置为默认值
      if (maxLifetime != 0 && maxLifetime < SECONDS.toMillis(30)) {
         LOGGER.warn("{} - maxLifetime is less than 30000ms, setting to default {}ms.", poolName, MAX_LIFETIME);
         maxLifetime = MAX_LIFETIME;
      }

      //空闲超时时间接近或大于“最大连接超时时间”，则忽略此参数
      if (idleTimeout + SECONDS.toMillis(1) > maxLifetime && maxLifetime > 0) {
         LOGGER.warn("{} - idleTimeout is close to or more than maxLifetime, disabling it.", poolName);
         idleTimeout = 0;
      }
      //空闲超时时间不能小于10秒，否则设置为默认
      if (idleTimeout != 0 && idleTimeout < SECONDS.toMillis(10)) {
         LOGGER.warn("{} - idleTimeout is less than 10000ms, setting to default {}ms.", poolName, IDLE_TIMEOUT);
         idleTimeout = IDLE_TIMEOUT;
      }

      if (leakDetectionThreshold > 0 && !unitTest) {
         if (leakDetectionThreshold < SECONDS.toMillis(2) || (leakDetectionThreshold > maxLifetime && maxLifetime > 0)) {
            LOGGER.warn("{} - leakDetectionThreshold is less than 2000ms or more than maxLifetime, disabling it.", poolName);
            leakDetectionThreshold = 0;
         }
      }
      //从连接池中获取连接的超时时间不能小于250毫秒，否则设置为默认值
      if (connectionTimeout < 250) {
         LOGGER.warn("{} - connectionTimeout is less than 250ms, setting to {}ms.", poolName, CONNECTION_TIMEOUT);
         connectionTimeout = CONNECTION_TIMEOUT;
      }
      //从连接验证时间不能小于250毫秒，否则设置为默认值
      if (validationTimeout < 250) {
         LOGGER.warn("{} - validationTimeout is less than 250ms, setting to {}ms.", poolName, VALIDATION_TIMEOUT);
         validationTimeout = VALIDATION_TIMEOUT;
      }
      //最大连接数如果小于1，最小空闲连接数<=0,将maxPoolSize设置为10，否则设置为minIdle
      if (maxPoolSize < 1) {
         maxPoolSize = (minIdle <= 0) ? 10 : minIdle;
      }
      //最小空闲连接数<0或大于连接池中总连接数，则设置为连接池中总连接数
      if (minIdle < 0 || minIdle > maxPoolSize) {
         minIdle = maxPoolSize;
      }
   }
   //记录配置日志
   private void logConfiguration()
   {
      LOGGER.debug("{} - configuration:", poolName);
      final Set<String> propertyNames = new TreeSet<>(PropertyElf.getPropertyNames(HikariConfig.class));
      for (String prop : propertyNames) {
         try {
            Object value = PropertyElf.getProperty(prop, this);
            if ("dataSourceProperties".equals(prop)) {
               Properties dsProps = PropertyElf.copyProperties(dataSourceProperties);
               dsProps.setProperty("password", "<masked>");
               value = dsProps;
            }
            if (prop.contains("password")) {
               value = "<masked>";
            }
            else if (value instanceof String) {
               value = "\"" + value + "\""; // quote to see lead/trailing spaces is any
            }
            LOGGER.debug((prop + "................................................").substring(0, 32) + value);
         }
         catch (Exception e) {
            continue;
         }
      }
   }
   //加载属性文件，先寻找文件系统路径，没有再在类路径下寻找
   protected void loadProperties(String propertyFileName)
   {
      final File propFile = new File(propertyFileName);
      try (final InputStream is = propFile.isFile() ? new FileInputStream(propFile) : this.getClass().getResourceAsStream(propertyFileName)) {
         if (is != null) {
            Properties props = new Properties();
            props.load(is);
            PropertyElf.setTargetFromProperties(this, props);
         }
         else {
            throw new IllegalArgumentException("Cannot find property file: " + propertyFileName);
         }
      }
      catch (IOException io) {
         throw new RuntimeException("Failed to read property file", io);
      }
   }
   //生成连接池名称序号
   private int generatePoolNumber()
   {
      // Pool number is global to the VM to avoid overlapping pool numbers in classloader scoped environments
      // VM中全局唯一，以避免在类加载器作用域环境中重叠
      synchronized (System.getProperties()) {
         final int next = Integer.getInteger("com.zaxxer.hikari.pool_number", 0) + 1;
         System.setProperty("com.zaxxer.hikari.pool_number", String.valueOf(next));
         return next;
      }
   }
   //拷贝非final的属性值
   public void copyState(HikariConfig other)
   {
      for (Field field : HikariConfig.class.getDeclaredFields()) {
         if (!Modifier.isFinal(field.getModifiers())) {
            field.setAccessible(true);
            try {
               field.set(other, field.get(this));
            }
            catch (Exception e) {
               throw new RuntimeException("Failed to copy HikariConfig state: " + e.getMessage(), e);
            }
         }
      }
   }
}
