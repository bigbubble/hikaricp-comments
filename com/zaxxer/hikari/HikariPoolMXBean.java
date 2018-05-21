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
 * The javax.management MBean for a Hikari pool instance.
 *
 * @author Brett Wooldridge
 *
 * 连接池的MXBean
 */
public interface HikariPoolMXBean 
{
   //获取空闲的连接数.
   int getIdleConnections();
   //获取活动的连接数
   int getActiveConnections();
   //获取总连接数
   int getTotalConnections();
   //等待获取数据库连接的线程数
   int getThreadsAwaitingConnection();
   //将concurrentBag中所有是“未使用”的对象，从concurrentBag中移除并加入到关闭连接的线程池中，并标记所有对象“待清除”为true,
   void softEvictConnections();
   //中止
   void suspendPool();
   //恢复
   void resumePool();
}
