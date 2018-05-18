/*
 * Copyright (C) 2015 Brett Wooldridge
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

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.AbstractQueuedLongSynchronizer;

/**
 * A specialized wait/notify class useful for resource tracking through the
 * use of a monotonically-increasing long sequence.
 * 通过使用一个单调递增的long序列器实现一个通知/等待同步器，用来对资源跟踪
 * <p>
 * When a shared resource becomes available the {@link #signal()} method should
 * be called unconditionally.
 * <p>
 * 当一个共享的资源可用，signal()方法应该被无条件调用
 * A thread wishing to acquire a shared resource should: <br>
 * <ul>
 *   <li>Obtain the current sequence from the {@link #currentSequence()} method </li>
 *   <li>Call {@link #waitUntilSequenceExceeded(long, long)} with that sequence.  </li>
 *   <li>Upon receiving a <code>true</code> result from {@link #waitUntilSequenceExceeded(long, long)},
 *       the current sequence should again be obtained from the {@link #currentSequence()} method,
 *       and an attempt to acquire the resource should be made. </li>
 *   <li>If the shared resource cannot be acquired, the thread should again call 
 *       {@link #waitUntilSequenceExceeded(long, long)} with the previously obtained sequence. </li>
 *   <li>If <code>false</code> is received from {@link #waitUntilSequenceExceeded(long, long)}
 *       then a timeout has occurred. </li>
 * </ul>
 * <p>
 * 一个线程想要获取一个共享资源应该：
 *   * 使用currentSequence()方法 获取当前序列
 *   * 调用waitUntilSequenceExceeded(long,long)方法
 *   * 直到waitUntilSequenceExceeded(long,long)方法返回true,当前序列再次由currentSequence()获得 ？？？,然后尝试获取资源
 *   * 如果waitUntilSequenceExceeded(long,long)方法返回false,则发生了超时 ？？
 * When running on Java 8 and above, this class leverages the fact that when {@link LongAdder}
 * is monotonically increasing, and only {@link LongAdder#increment()} and {@link LongAdder#sum()}
 * are used, it can be relied on to be Sequentially Consistent.
 *
 * 当运行在Java8及以上，这个类使用LongAdder的increment()和sum()方法，保证顺序一致？？
 * @see <a href="http://en.wikipedia.org/wiki/Sequential_consistency">Java Spec</a> 
 * @author Brett Wooldridge
 */
public final class QueuedSequenceSynchronizer
{
   private final Sequence sequence;
   private final Synchronizer synchronizer;

   /**
    * Default constructor
    */
   public QueuedSequenceSynchronizer()
   {
      this.synchronizer = new Synchronizer();
      //根据特定JDK平台获取序列器
      this.sequence = Sequence.Factory.create();
   }

   /**
    * 给所有等待的线程发送信号(释放资源，每调用一次，同步器的的序列号+1)
    */
   public void signal()
   {
      synchronizer.releaseShared(1);
   }

   /**
    * Get the current sequence.
    *
    * @return the current sequence
    * 获取当前同步器的序列号
    */
   public long currentSequence()
   {
      return sequence.get();
   }

   /**
    * Block the current thread until the current sequence exceeds the specified threshold, or
    * until the specified timeout is reached.
    * 
    * @param sequence the threshold the sequence must reach before this thread becomes unblocked
    * @param nanosTimeout a nanosecond timeout specifying the maximum time to wait
    * @return true if the threshold was reached, false if the wait timed out
    * @throws InterruptedException if the thread is interrupted while waiting
    * 
    * 阻塞当前线程直到序列号达到特定的阈值或者超过超时时间
    */
   public boolean waitUntilSequenceExceeded(long sequence, long nanosTimeout) throws InterruptedException
   {
      return synchronizer.tryAcquireSharedNanos(sequence, nanosTimeout);
   }

   /**
    * Queries whether any threads are waiting to for the sequence to reach a particular threshold.
    *
    * @return true if there may be other threads waiting for a sequence threshold to be reached
    *
    * 同步器中是否有等待获取资源的线程
    */
   public boolean hasQueuedThreads()
   {
      return synchronizer.hasQueuedThreads();
   }

   /**
    * Returns an estimate of the number of threads waiting for a sequence threshold to be reached. The
    * value is only an estimate because the number of threads may change dynamically while this method
    * traverses internal data structures. This method is designed for use in monitoring system state,
    * not for synchronization control.
    *
    * @return the estimated number of threads waiting for a sequence threshold to be reached
    *
    * 获取同步器中等待资源的估计的线程数。因为在遍历同步器的内部数据结构时，可能会发生动态变化，所以是个估计值。这个方法设计用来监控系统状态，而不是同步控制
    */
   public int getQueueLength()
   {
      return synchronizer.getQueueLength();
   }

   //同步器 AQS实现
   private final class Synchronizer extends AbstractQueuedLongSynchronizer
   {
      private static final long serialVersionUID = 104753538004341218L;

      /** {@inheritDoc} */
      //本类waitUntilSequenceExceeded(long,long)方法最终会使用此方法
      @Override
      protected long tryAcquireShared(final long seq)
      {
         return sequence.get() - (seq + 1);
      }

      /** {@inheritDoc} */
      //本类signal()方法最终会使用本方法
      @Override
      protected boolean tryReleaseShared(final long unused)
      {
         //每次释放资源，序列号+1
         sequence.increment();
         return true;
      }
   }
}
