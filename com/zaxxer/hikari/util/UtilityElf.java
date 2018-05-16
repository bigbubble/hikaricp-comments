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

package com.zaxxer.hikari.util;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 *
 * @author Brett Wooldridge
 */
public final class UtilityElf
{
   /**
    *
    * @return null if string is null or empty
    * 去除字符串两边空白，如果是空字符串，返回null
   */
   public static String getNullIfEmpty(final String text)
   {
      return text == null ? null : text.trim().isEmpty() ? null : text.trim();
   }

   /**
    * Sleep and transform an InterruptedException into a RuntimeException.
    *
    * @param millis the number of milliseconds to sleep
    *
    * 线程按指定毫秒数休眠 不抛出异常
    */
   public static void quietlySleep(final long millis)
   {
      try {
         Thread.sleep(millis);
      }
      catch (InterruptedException e) {
         // I said be quiet!
      }
   }

   /**
    * Create and instance of the specified class using the constructor matching the specified
    * arguments.
    *
    * @param <T> the class type
    * @param className the name of the class to instantiate
    * @param clazz a class to cast the result as
    * @param args arguments to a constructor
    * @return an instance of the specified class
    * 
    * 使用指定类型的构造器构造一个指定类型和参数的实例
    */
   public static <T> T createInstance(final String className, final Class<T> clazz, final Object... args)
   {
      if (className == null) {
         return null;
      }

      try {
         Class<?> loaded = UtilityElf.class.getClassLoader().loadClass(className);
         if (args.length == 0) {
            return clazz.cast(loaded.newInstance());
         }

         Class<?>[] argClasses = new Class<?>[args.length];
         for (int i = 0; i < args.length; i++) {
            argClasses[i] = args[i].getClass();
         }
         Constructor<?> constructor = loaded.getConstructor(argClasses);
         return clazz.cast(constructor.newInstance(args));
      }
      catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Create a ThreadPoolExecutor.
    *
    * @param queueSize the queue size
    * @param threadName the thread name
    * @param threadFactory an optional ThreadFactory
    * @param policy the RejectedExecutionHandler policy
    * @return a ThreadPoolExecutor
    * 创建一个线程池
    */
   public static ThreadPoolExecutor createThreadPoolExecutor(final int queueSize, final String threadName, ThreadFactory threadFactory, final RejectedExecutionHandler policy)
   {
      if (threadFactory == null) {
      	 //线程工厂，创建出来的线程为守护线程
         threadFactory = new DefaultThreadFactory(threadName, true);
      }

      LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
      //corePoolSize = 1 核心线程数，默认情况下核心线程会一直存活，即使处于闲置状态也不会受存keepAliveTime限制。除非将allowCoreThreadTimeOut设置为true。
      //maximumPoolSize = 1 线程池所能容纳的最大线程数。超过这个数的线程将被阻塞。当任务队列为没有设置大小的LinkedBlockingDeque时，这个值无效。
      //keepAliveTime = 5 非核心线程的闲置超时时间，超过这个时间就会被回收
      //keepAliveTIme unit SECONDS
      //queue
      //threadFactory 
      //policy 当线程池中的资源已经全部使用，添加新线程被拒绝时，会调用RejectedExecutionHandler的rejectedExecution方法。
      ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, SECONDS, queue, threadFactory, policy);
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   // ***********************************************************************
   //                       Misc. public methods
   // ***********************************************************************

   /**
    * Get the int value of a transaction isolation level by name.
    *
    * @param transactionIsolationName the name of the transaction isolation level
    * @return the int value of the isolation level or -1
    *
    * 根据名称获取事务隔离级别int值,如果参数为空，返回"-1",如果未找到抛出IllegalArgumentException异常
    */
   public static int getTransactionIsolation(final String transactionIsolationName)
   {
      if (transactionIsolationName != null) {
         try {
            final String upperName = transactionIsolationName.toUpperCase();
            //如 TRANSACTION_READ_UNCOMMITTED 以 "TRANSACTION_"开头的字符串
            if (upperName.startsWith("TRANSACTION_")) {
               Field field = Connection.class.getField(upperName);
               return field.getInt(null);
            }
            //如果是int值的字符串表示
            final int level = Integer.parseInt(transactionIsolationName);
            switch (level) {
               case Connection.TRANSACTION_READ_UNCOMMITTED:
               case Connection.TRANSACTION_READ_COMMITTED:
               case Connection.TRANSACTION_REPEATABLE_READ:
               case Connection.TRANSACTION_SERIALIZABLE:
               case Connection.TRANSACTION_NONE:
                  return level;
               default:
                  throw new IllegalArgumentException();
             }
         }
         catch (Exception e) {
            throw new IllegalArgumentException("Invalid transaction isolation value: " + transactionIsolationName);
         }
      }

      return -1;
   }
   //默认线程工厂，可设置名称和是否是守护线程
   public static final class DefaultThreadFactory implements ThreadFactory {

      private final String threadName;
      private final boolean daemon;

      public DefaultThreadFactory(String threadName, boolean daemon) {
         this.threadName = threadName;
         this.daemon = daemon;
      }

      @Override
      public Thread newThread(Runnable r) {
         Thread thread = new Thread(r, threadName);
         thread.setDaemon(daemon);
         return thread;
      }
   }
}
