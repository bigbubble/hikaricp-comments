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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.zaxxer.hikari.util.FastList;

/**
 * A factory class that produces proxies around instances of the standard
 * JDBC interfaces.
 * 
 * @author Brett Wooldridge
 * 
 * 一个工厂类 生成 Connection，Statement，CallableStatement，PreparedStatement，ResultSet的代理类
 * 源码中没有实现，都是抛出异常，在release的jar包中，已经被JavassistProxyFactory.main()方法修改了方法体.
 * 反编译后的内容为：
 * package com.zaxxer.hikari.pool;

 * import com.zaxxer.hikari.util.FastList;
 * import java.sql.CallableStatement;
 * import java.sql.Connection;
 * import java.sql.PreparedStatement;
 * import java.sql.ResultSet;
 * import java.sql.Statement;
 *
 * public final class ProxyFactory
 * {
 *  static ProxyConnection getProxyConnection(PoolEntry paramPoolEntry, Connection paramConnection, FastList<Statement> paramFastList, ProxyLeakTask paramProxyLeakTask, long paramLong, boolean paramBoolean1, boolean paramBoolean2)
 *  {
 *    return new HikariProxyConnection(paramPoolEntry, paramConnection, paramFastList, paramProxyLeakTask, paramLong, paramBoolean1, paramBoolean2);
 *  }
  
 * static Statement getProxyStatement(ProxyConnection paramProxyConnection, Statement paramStatement)
 *  {
 *   return new HikariProxyStatement(paramProxyConnection, paramStatement);
 *  }
  
 * static CallableStatement getProxyCallableStatement(ProxyConnection paramProxyConnection, CallableStatement paramCallableStatement)
 *  {
 *   return new HikariProxyCallableStatement(paramProxyConnection, paramCallableStatement);
 *  }
 * 
 * static PreparedStatement getProxyPreparedStatement(ProxyConnection paramProxyConnection, PreparedStatement paramPreparedStatement)
 *  {
 *   return new HikariProxyPreparedStatement(paramProxyConnection, paramPreparedStatement);
 *  }
 * 
 * static ResultSet getProxyResultSet(ProxyConnection paramProxyConnection, ProxyStatement paramProxyStatement, ResultSet paramResultSet)
 *  {
 *   return new HikariProxyResultSet(paramProxyConnection, paramProxyStatement, paramResultSet);
 *  }
 * }
 * 在HikariProxy***.class的代理类中没有其他的操作，都是调用super.delegate.methodXXX(),包裹了try ... catch() throw SQLException
 */
public final class ProxyFactory
{
   private ProxyFactory()
   {
      // unconstructable
   }

   /**
    * Create a proxy for the specified {@link Connection} instance.
    * @param poolEntry
    * @param connection
    * @param openStatements
    * @param leakTask
    * @param now
    * @param isReadOnly
    * @param isAutoCommit
    * @return a proxy that wraps the specified {@link Connection}
    */
   static ProxyConnection getProxyConnection(final PoolEntry poolEntry, final Connection connection, final FastList<Statement> openStatements, final ProxyLeakTask leakTask, final long now, final boolean isReadOnly, final boolean isAutoCommit)
   {
      // Body is replaced (injected) by JavassistProxyFactory
      throw new IllegalStateException("You need to run the CLI build and you need target/classes in your classpath to run.");
   }

   static Statement getProxyStatement(final ProxyConnection connection, final Statement statement)
   {
      // Body is replaced (injected) by JavassistProxyFactory
      throw new IllegalStateException("You need to run the CLI build and you need target/classes in your classpath to run.");
   }

   static CallableStatement getProxyCallableStatement(final ProxyConnection connection, final CallableStatement statement)
   {
      // Body is replaced (injected) by JavassistProxyFactory
      throw new IllegalStateException("You need to run the CLI build and you need target/classes in your classpath to run.");
   }

   static PreparedStatement getProxyPreparedStatement(final ProxyConnection connection, final PreparedStatement statement)
   {
      // Body is replaced (injected) by JavassistProxyFactory
      throw new IllegalStateException("You need to run the CLI build and you need target/classes in your classpath to run.");
   }

   static ResultSet getProxyResultSet(final ProxyConnection connection, final ProxyStatement statement, final ResultSet resultSet)
   {
      // Body is replaced (injected) by JavassistProxyFactory
      throw new IllegalStateException("You need to run the CLI build and you need target/classes in your classpath to run.");
   }
}
