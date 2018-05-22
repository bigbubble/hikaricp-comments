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

package com.zaxxer.hikari.util;

import java.util.concurrent.Semaphore;

/**
 * This class implements a lock that can be used to suspend and resume the pool.  It
 * also provides a faux implementation that is used when the feature is disabled that
 * hopefully gets fully "optimized away" by the JIT.
 *
 * @author Brett Wooldridge
 *
 * 实现了一个对连接池进行中止和恢复的锁。当功能禁用时，提供了一个假的实现，hopefully gets fully "optimized away" by the JIT.
 */
public class SuspendResumeLock
{
   //假的实现，不做任何操作
   public static final SuspendResumeLock FAUX_LOCK = new SuspendResumeLock(false) {
      @Override
      public void acquire() {}

      @Override
      public void release() {}
      
      @Override
      public void suspend() {}

      @Override
      public void resume() {}
   };
   //允许10000个线程，同时获取数据库连接
   private static final int MAX_PERMITS = 10000;
   //Semaphore是计数信号量。Semaphore管理一系列许可证。
   //每个acquire方法阻塞，直到有一个许可证可以获得然后拿走一个许可证；每个release方法增加一个许可证，这可能会释放一个阻塞的acquire方法。
   //然而，其实并没有实际的许可证这个对象，Semaphore只是维持了一个可获得许可证的数量。 
   private final Semaphore acquisitionSemaphore;

   /**
    * Default constructor
    */
   public SuspendResumeLock()
   {
      this(true);
   }

   private SuspendResumeLock(final boolean createSemaphore)
   {
	  //使用公平获取资源策略，先到先获得
      acquisitionSemaphore = (createSemaphore ? new Semaphore(MAX_PERMITS, true) : null);
   }

   //获取一个
   public void acquire()
   {
      acquisitionSemaphore.acquireUninterruptibly();
   }

   //释放一个
   public void release()
   {
      acquisitionSemaphore.release();
   }
   //获取所有，没有可用的，导致中止
   public void suspend()
   {
      acquisitionSemaphore.acquireUninterruptibly(MAX_PERMITS);
   }
   //释放所有，恢复状态
   public void resume()
   {
      acquisitionSemaphore.release(MAX_PERMITS);
   }
}
