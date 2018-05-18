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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;

import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_RESERVED;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * ConcurrentBag是一个为连接池提供专用的并发<存储结构>,它比LinkedBlockingQueue和LinkedTransferQueue有更好的性能。
 * 如果可能它使用ThreadLocal进行存储来避免多线程时使用锁，如果在ThreadLocl列表中没有可用的对象，将会去遍历一个公共的集合来获取（多个线程共有的）。
 * 一个线程ThreadLocal中Not-in-use状态的对象可以被其他线程使用（其他线程自己的ThreadLocal中没有可使用的，偷别人家对像）。
 * 这是一个"无锁"并发类实现，使用AbstractQueuedLongSynchronizer来管理跨线程信号同步
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * 注意：ConcurrentBag中存储的“被借走的”对象没有从ConcurrentBag中任何集合中移除，所以当引用已经不使用时，不会触发垃圾回收
 * 所以要小心，要对“借走的”对象进行归还，否则会导致内存泄漏。只有“remove”方法可以完全的将对象从ConcurrentBag中移除。
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

   private final QueuedSequenceSynchronizer synchronizer; //线程同步器
   private final CopyOnWriteArrayList<T> sharedList; //所有线程共享的对象存储集合，CopyOnWriteArrayList保证线程安全
   private final boolean weakThreadLocals; //是否使用ThreadLocal弱引用存储借来的对象，否则使用自定义的FastList

   private final ThreadLocal<List<Object>> threadList; //本地线程列表
   private final IBagStateListener listener; //这里就是HikariConfig对象
   private final AtomicInteger waiters; //
   private volatile boolean closed; //是否关闭

   public interface IConcurrentBagEntry
   {
      int STATE_NOT_IN_USE = 0;
      int STATE_IN_USE = 1;
      int STATE_REMOVED = -1;
      int STATE_RESERVED = -2;

      boolean compareAndSet(int expectState, int newState);
      //在不需要让共享变量的修改立刻让其他线程可见的时候，以设置普通变量的方式来修改共享状态，可以减少不必要的内存屏障，从而提高程序执行的效率。
      void lazySet(int newState);
      int getState();
   }

   //HikariConfig 实现了此接口
   public interface IBagStateListener
   {
      Future<Boolean> addBagItem();
   }

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * 构造器，添加HikariConfig引用和使用的基本数据结构
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      this.listener = listener; //通过此变量，设置HikariConfig对象引用
      this.weakThreadLocals = useWeakThreadLocals();

      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      this.synchronizer = new QueuedSequenceSynchronizer();
      if (weakThreadLocals) {
         this.threadList = new ThreadLocal<>();
      }
      else {
         this.threadList = new ThreadLocal<List<Object>>() {
            @Override
            protected List<Object> initialValue()
            {
               return new FastList<>(IConcurrentBagEntry.class, 16);
            }
         };
      }
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    *
    * 从ConcurrentBag中“借”走一个对象，如果没有可使用状态的，阻塞等待指定时间，超时后返回null
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      // Try the thread-local list first
      //首先先从本地线程变量列表中寻找可以使用
      List<Object> list = threadList.get();
      if (weakThreadLocals && list == null) {
         list = new ArrayList<>(16);
         threadList.set(list);
      }

      for (int i = list.size() - 1; i >= 0; i--)
         //从后向前（减少移动数组操作）列表中移除当前对象，直到找到一个可以使用的，返回。
         //因为本线程中的对象可能被其他线程“偷走”，本线程列表中的对象有可能是不可使用的
         final Object entry = list.remove(i);
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // Otherwise, scan the shared list ... for maximum of timeout
      //本地线程变量列表中没有找到可以使用的，从共享列表中查找（因为都是引用，共享列表中的对象可能被其他线程引用，故有"偷"的说法）
      timeout = timeUnit.toNanos(timeout);//转换成毫微秒
      Future<Boolean> addItemFuture = null; //因共享列表对象不足而增加的对象Future
      final long startScan = System.nanoTime();//当前时间毫微秒
      final long originTimeout = timeout; //原始超时时间
      long startSeq;//同步器序列号
      waiters.incrementAndGet();//等待获取对象的线程数+1
      try {
         do {
            // scan the shared list
            //遍历共享列表
            do {
               //当前同步器序列号
               startSeq = synchronizer.currentSequence();
               for (T bagEntry : sharedList) {
                  //遍历如果有可用的对象，返回
                  if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                     // if we might have stolen another thread's new connection, restart the add...
                     //如果等待的线程个数超过1个，并且没有因共享列表对象不足而增加过，那么向共享列表中增加一个对象
                     //====因为此线程"偷"了其他线程的对象，可能导致其他线程会缺少，需要来共享列表来取，
                     //这时向共享队列里增加一个新的对象，那么另外等待的线程就可以尽早的获得到对象，（addBagItem（）因为有最大连接数限制，不会超过最大个数）
                     //====not sure
                     if (waiters.get() > 1 && addItemFuture == null) {
                        listener.addBagItem();
                     }

                     return bagEntry;//返回
                  }
               }
            //如果此次请求的同步器的序列号小于最新的同步器序列号，说明，ConcurrentBag中有可用的资源，继续遍历共享列表
            //(每次增加和释放对象都会调用同步器的signal()方法让同步器序列号+1)   
            } while (startSeq < synchronizer.currentSequence()); 

			//共享列表没有可用的对象（并且共享列表中没有增加或者上一此增加操作已经完成）再增加一个对象
            if (addItemFuture == null || addItemFuture.isDone()) {
               addItemFuture = listener.addBagItem();
            }

            //超时时间去掉已经用过的时间
            timeout = originTimeout - (System.nanoTime() - startScan);
         // 如果超时时间大于10,000并且在timeout时间内，和startSeq相比，同步器的序列号 >= (startSeq+1)（说明已经增加或释放了对象），继续到共享列表中“偷”
         } while (timeout > 10_000L && synchronizer.waitUntilSequenceExceeded(startSeq, timeout));
      }
      finally {
         //不管有没有获取到对象，等待线程个数 -1
         waiters.decrementAndGet();
      }

      return null;
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    *
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the requited value was not borrowed from the bag
    *
    * 归还“借走的”对象
    */
   public void requite(final T bagEntry)
   {
      //更改状态
      bagEntry.lazySet(STATE_NOT_IN_USE);
      //获取当前线程的存储集合
      final List<Object> threadLocalList = threadList.get();
      //将此对象加入到当前线程的集合中
      if (threadLocalList != null) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }
      //
      synchronizer.signal();
   }

   /**
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    * 增加一个新的对象到共享列表中
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);
      //同步器序列号 + 1
      synchronizer.signal();
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    * 从ConcurrentBag中移除一个STATE_IN_USE（正在使用）和STATE_RESERVED（保留状态）的对象
    */
   public boolean remove(final T bagEntry)
   {
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      // synchronizer.signal();
      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    * 返回一个指定状态的ConcurrentBag快照，此方法不会以任何方式加锁或reserve里面的对象，在对这些对象操作之前，你可以对里面的对象执行reserve(T)方法，或者你清楚并发对这些对象的影响
    */
   public List<T> values(final int state)
   {
      final ArrayList<T> list = new ArrayList<>(sharedList.size());
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            list.add(entry);
         }
      }

      return list;
   }

   /**
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    * 返回一个ConcurrentBag快照，此方法不会以任何方式加锁或reserve里面的对象，在对这些对象操作之前，你可以对里面的对象执行reserve(T)方法，或者你清楚并发对这些对象的影响
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    * 标记对象中的状态 STATE_NOT_IN_USE -> STATE_RESERVED,使得对象不能从ConcurrentBag中“借走”
    * 此方法主要用在调用values(int)方法时。STATE_RESERVED 状态的对象不能从ConcurrentBag中移除。
    * STATE_RESERVED 状态的对象可以通过unreserve(T)方法变成可“借”的对象
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    * 讲对象的状态由 STATE_RESERVED -> STATE_NOT_IN_USE
    */
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         synchronizer.signal();
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    * 获取有多少个线程正在阻塞中（等待对象分配中） 同步器 waitUntilSequenceExceeded()方法会操作AQS的pending Queue
    */
   public int getPendingQueue()
   {
      return synchronizer.getQueueLength();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    * 共享列表中某个状态的对象的个数
    */
   public int getCount(final int state)
   {
      int count = 0;
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            count++;
         }
      }
      return count;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    * 共享列表内对象个数
    */
   public int size()
   {
      return sharedList.size();
   }
   //打印所有对象状态
   public void dumpState()
   {
      for (T bagEntry : sharedList) {
         LOGGER.info(bagEntry.toString());
      }
   }

   /**
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    *
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    *
    * 是否使用弱引用存储  1.由系统参数指定"com.zaxxer.hikari.useWeakReferences"，2.当前类的ClassLoader与系统ClassLoader不一样->使用，一样->不使用
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
