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

package com.zaxxer.hikari.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ClockSource;
import com.zaxxer.hikari.util.FastList;

/**
 * This is the proxy class for java.sql.Connection.
 *
 * @author Brett Wooldridge
 * 连接池中获取到Connection的代理类  此类和同级别的Proxy开头的类作为最终的代理类的父类，方法中没有try..catch() throw，只是声明了抛出SQL异常，方法体没有异常检查代码 
 */
public abstract class ProxyConnection implements Connection
{
   static final int DIRTY_BIT_READONLY   = 0b00001;
   static final int DIRTY_BIT_AUTOCOMMIT = 0b00010;
   static final int DIRTY_BIT_ISOLATION  = 0b00100;
   static final int DIRTY_BIT_CATALOG    = 0b01000;
   static final int DIRTY_BIT_NETTIMEOUT = 0b10000;

   private static final Logger LOGGER;
   private static final Set<String> SQL_ERRORS;
   private static final ClockSource clockSource;

   protected Connection delegate; //被代理的数据库连接

   private final PoolEntry poolEntry;  //PoolEntry
   private final ProxyLeakTask leakTask; //泄漏检测
   private final FastList<Statement> openStatements; // 执行的语句

   private int dirtyBits; //
   private long lastAccess; // 上次结束与数据库联系的时间
   private boolean isCommitStateDirty;

   private boolean isReadOnly;
   private boolean isAutoCommit;
   private int networkTimeout;
   private int transactionIsolation;
   private String dbcatalog;

   // static initializer
   static {
      LOGGER = LoggerFactory.getLogger(ProxyConnection.class);
      clockSource = ClockSource.INSTANCE;

      SQL_ERRORS = new HashSet<>();
      SQL_ERRORS.add("57P01"); // ADMIN SHUTDOWN
      SQL_ERRORS.add("57P02"); // CRASH SHUTDOWN
      SQL_ERRORS.add("57P03"); // CANNOT CONNECT NOW
      SQL_ERRORS.add("01002"); // SQL92 disconnect error
      SQL_ERRORS.add("JZ0C0"); // Sybase disconnect error
      SQL_ERRORS.add("JZ0C1"); // Sybase disconnect error
   }

   protected ProxyConnection(final PoolEntry poolEntry, final Connection connection, final FastList<Statement> openStatements, final ProxyLeakTask leakTask, final long now, final boolean isReadOnly, final boolean isAutoCommit) {
      this.poolEntry = poolEntry;
      this.delegate = connection;
      this.openStatements = openStatements;
      this.leakTask = leakTask;
      this.lastAccess = now;
      this.isReadOnly = isReadOnly;
      this.isAutoCommit = isAutoCommit;
   }

   /** {@inheritDoc} */
   @Override
   public final String toString()
   {
      return new StringBuilder(64)
         .append(this.getClass().getSimpleName()).append('@').append(System.identityHashCode(this))
         .append(" wrapping ")
         .append(delegate).toString();
   }

   // ***********************************************************************
   //                     Connection State Accessors
   // ***********************************************************************

   final boolean getAutoCommitState()
   {
      return isAutoCommit;
   }

   final String getCatalogState()
   {
      return dbcatalog;
   }

   final int getTransactionIsolationState()
   {
      return transactionIsolation;
   }

   final boolean getReadOnlyState()
   {
      return isReadOnly;
   }

   final int getNetworkTimeoutState()
   {
      return networkTimeout;
   }

   // ***********************************************************************
   //                          Internal methods
   // ***********************************************************************

   final PoolEntry getPoolEntry()
   {
      return poolEntry;
   }

   //异常检查
   final SQLException checkException(final SQLException sqle)
   {
      final String sqlState = sqle.getSQLState();
	  //被检查的数据库连接不是“已关闭的数据库连接”
      if (sqlState != null && delegate != ClosedConnection.CLOSED_CONNECTION) {
         if (sqlState.startsWith("08") || SQL_ERRORS.contains(sqlState)) { // broken connection 连接损坏
            LOGGER.warn("{} - Connection {} marked as broken because of SQLSTATE({}), ErrorCode({})",
                        poolEntry.getPoolName(), delegate, sqlState, sqle.getErrorCode(), sqle);
            leakTask.cancel();
			//清理掉连接
            poolEntry.evict("(connection is broken)");
            delegate = ClosedConnection.CLOSED_CONNECTION;
         }
         else {
            final SQLException nse = sqle.getNextException();
            if (nse != null && nse != sqle) {
               checkException(nse);
            }
         }
      }
      return sqle;
   }

   final synchronized void untrackStatement(final Statement statement)
   {
      openStatements.remove(statement);
   }

   final void markCommitStateDirty()
   {
      if (isAutoCommit) {
         lastAccess = clockSource.currentTime();
      }
      else {
         isCommitStateDirty = true;
      }
   }

   void cancelLeakTask()
   {
      leakTask.cancel();
   }

   //由代理对象统一管理Statement，关闭连接时统一对openStatements里的对象close()
   private final synchronized <T extends Statement> T trackStatement(final T statement)
   {
      openStatements.add(statement);

      return statement;
   }

   //关闭Statement对象
   private final void closeStatements()
   {
      final int size = openStatements.size();
      if (size > 0) {
         for (int i = 0; i < size && delegate != ClosedConnection.CLOSED_CONNECTION; i++) {
            try {
               final Statement statement = openStatements.get(i);
               if (statement != null) {
                  statement.close();
               }
            }
            catch (SQLException e) {
               checkException(e);
            }
         }

         synchronized (this) {
            openStatements.clear();
         }
      }
   }

   // **********************************************************************
   //              "Overridden" java.sql.Connection Methods
   // **********************************************************************

   /** {@inheritDoc} */
   //重写close()方法，因为要在连接池中复用，不能真的关闭连接
   @Override
   public final void close() throws SQLException
   {
      // Closing statements can cause connection eviction, so this must run before the conditional below
	  //关闭Statement对象可能导致连接关闭清除，先执行
      closeStatements();

      if (delegate != ClosedConnection.CLOSED_CONNECTION) {
         leakTask.cancel();

         try {
            //不是自动提交，提交状态被修改，不是只读
            if (isCommitStateDirty && !isAutoCommit && !isReadOnly) {
               //回滚
               delegate.rollback();
			   //更新与数据库最后联系时间
               lastAccess = clockSource.currentTime();
               LOGGER.debug("{} - Executed rollback on connection {} due to dirty commit state on close().", poolEntry.getPoolName(), delegate);
            }

            if (dirtyBits != 0) {
               //连接原始值，在连接使用过程中被修改，重置数据库连接的初始值
               poolEntry.resetConnectionState(this, dirtyBits);
			   //更新与数据库最后联系时间
               lastAccess = clockSource.currentTime();
            }
            //清除警告信息
            delegate.clearWarnings();
         }
         catch (SQLException e) {
            // when connections are aborted, exceptions are often thrown that should not reach the application
            if (!poolEntry.isMarkedEvicted()) {
               throw checkException(e);
            }
         }
         finally {
            //设置代理类为 “已关闭的连接” (PoolEntry中的connection对象的引用没有变化，使用代理类方法将会看到ClosedConnection.CLOSED_CONNECTION类的表现)
            delegate = ClosedConnection.CLOSED_CONNECTION;
			//释放PoolEntry到连接池中
            poolEntry.recycle(lastAccess);
         }
      }
   }
   //以下是对其他方法的重写，修改statement获取、isClose()、状态修改相关的方法和数据变更：
   /** {@inheritDoc} */
   @Override
   public boolean isClosed() throws SQLException
   {
      return (delegate == ClosedConnection.CLOSED_CONNECTION);
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement() throws SQLException
   {
      return ProxyFactory.getProxyStatement(this, trackStatement(delegate.createStatement()));
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement(int resultSetType, int concurrency) throws SQLException
   {
      return ProxyFactory.getProxyStatement(this, trackStatement(delegate.createStatement(resultSetType, concurrency)));
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement(int resultSetType, int concurrency, int holdability) throws SQLException
   {
      return ProxyFactory.getProxyStatement(this, trackStatement(delegate.createStatement(resultSetType, concurrency, holdability)));
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql) throws SQLException
   {
      return ProxyFactory.getProxyCallableStatement(this, trackStatement(delegate.prepareCall(sql)));
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql, int resultSetType, int concurrency) throws SQLException
   {
      return ProxyFactory.getProxyCallableStatement(this, trackStatement(delegate.prepareCall(sql, resultSetType, concurrency)));
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql, int resultSetType, int concurrency, int holdability) throws SQLException
   {
      return ProxyFactory.getProxyCallableStatement(this, trackStatement(delegate.prepareCall(sql, resultSetType, concurrency, holdability)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql, autoGeneratedKeys)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int resultSetType, int concurrency) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql, resultSetType, concurrency)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int resultSetType, int concurrency, int holdability) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql, resultSetType, concurrency, holdability)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql, columnIndexes)));
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException
   {
      return ProxyFactory.getProxyPreparedStatement(this, trackStatement(delegate.prepareStatement(sql, columnNames)));
   }

   /** {@inheritDoc} */
   @Override
   public void commit() throws SQLException
   {
      delegate.commit();
      isCommitStateDirty = false;
      lastAccess = clockSource.currentTime();
   }

   /** {@inheritDoc} */
   @Override
   public void rollback() throws SQLException
   {
      delegate.rollback();
      isCommitStateDirty = false;
      lastAccess = clockSource.currentTime();
   }

   /** {@inheritDoc} */
   @Override
   public void rollback(Savepoint savepoint) throws SQLException
   {
      delegate.rollback(savepoint);
      isCommitStateDirty = false;
      lastAccess = clockSource.currentTime();
   }

   /** {@inheritDoc} */
   @Override
   public void setAutoCommit(boolean autoCommit) throws SQLException
   {
      delegate.setAutoCommit(autoCommit);
      isAutoCommit = autoCommit;
      dirtyBits |= DIRTY_BIT_AUTOCOMMIT;
   }

   /** {@inheritDoc} */
   @Override
   public void setReadOnly(boolean readOnly) throws SQLException
   {
      delegate.setReadOnly(readOnly);
      isReadOnly = readOnly;
      isCommitStateDirty = false;
      dirtyBits |= DIRTY_BIT_READONLY;
   }

   /** {@inheritDoc} */
   @Override
   public void setTransactionIsolation(int level) throws SQLException
   {
      delegate.setTransactionIsolation(level);
      transactionIsolation = level;
      dirtyBits |= DIRTY_BIT_ISOLATION;
   }

   /** {@inheritDoc} */
   @Override
   public void setCatalog(String catalog) throws SQLException
   {
      delegate.setCatalog(catalog);
      dbcatalog = catalog;
      dirtyBits |= DIRTY_BIT_CATALOG;
   }

   /** {@inheritDoc} */
   @Override
   public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException
   {
      delegate.setNetworkTimeout(executor, milliseconds);
      networkTimeout = milliseconds;
      dirtyBits |= DIRTY_BIT_NETTIMEOUT;
   }

   /** {@inheritDoc} */
   @Override
   public final boolean isWrapperFor(Class<?> iface) throws SQLException
   {
      return iface.isInstance(delegate) || (delegate instanceof Wrapper && delegate.isWrapperFor(iface));
   }

   /** {@inheritDoc} */
   @Override
   @SuppressWarnings("unchecked")
   public final <T> T unwrap(Class<T> iface) throws SQLException
   {
      if (iface.isInstance(delegate)) {
         return (T) delegate;
      }
      else if (delegate instanceof Wrapper) {
          return delegate.unwrap(iface);
      }

      throw new SQLException("Wrapped connection is not an instance of " + iface);
   }

   // **********************************************************************
   //                         Private classes
   // **********************************************************************

   //获取一个固定对象代表“已关闭”的Connection，使用JDK代理修改abort,isValid,toString方法快速返回，其他都抛异常
   //所有的代理对象关闭后的delegate代理都使用CLOSED_CONNECTION这个固定的引用，为连接关闭后外界代理连接的引用调用提供处理，同时唯一类减少了内存消耗和比对代价
   private static final class ClosedConnection
   {
      static final Connection CLOSED_CONNECTION = getClosedConnection();

      private static Connection getClosedConnection()
      {
         InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
            {
               final String methodName = method.getName();
               if ("abort".equals(methodName)) {
                  return Void.TYPE;
               }
               else if ("isValid".equals(methodName)) {
                  return Boolean.FALSE;
               }
               else if ("toString".equals(methodName)) {
                  return ClosedConnection.class.getCanonicalName();
               }

               throw new SQLException("Connection is closed");
            }
         };

         return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[] { Connection.class }, handler);
      }
   }
}
