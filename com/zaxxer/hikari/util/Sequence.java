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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A monotonically increasing long sequence.
 *
 * 单调增加的long型序列
 * @author brettw
 */
@SuppressWarnings("serial")
public interface Sequence
{
   /**
    * Increments the current sequence by one.
    * 当前序列增加1
    */
   void increment();

   /**
    * Get the current sequence.
    *
    * @return the current sequence.
    * 获取当前序列值
    */
   long get();

   /**
    * Factory class used to create a platform-specific ClockSource.
    * 根据不同平台创建序列器
    */
   final class Factory
   {
      public static Sequence create()
      {
         try {
            //java.util.concurrent.atomic.LongAdder存在，系统变量"com.zaxxer.hikari.useAtomicLongSequence"不存在，或为false,使用java8 LongAdder
            if (Sequence.class.getClassLoader().loadClass("java.util.concurrent.atomic.LongAdder") != null && !Boolean.getBoolean("com.zaxxer.hikari.useAtomicLongSequence")) {
               return new Java8Sequence();
            }
         }
         catch (ClassNotFoundException e) {
            try {
               //异常使用 com.codahale.metrics.LongAdder
               Class<?> longAdderClass = Sequence.class.getClassLoader().loadClass("com.codahale.metrics.LongAdder");
               return new DropwizardSequence(longAdderClass);
            }
            catch (Exception e2) {
            }
         }
         //使用java 7 AtomicLong
         return new Java7Sequence();
      }

   }

   final class Java7Sequence extends AtomicLong implements Sequence {
      @Override
      public void increment() {
         this.incrementAndGet();
      }
   }

   final class Java8Sequence extends LongAdder implements Sequence {
      @Override
      public long get() {
         return this.sum();
      }
   }

   final class DropwizardSequence implements Sequence {
      private final Object longAdder;
      private final Method increment;
      private final Method sum;

      public DropwizardSequence(Class<?> longAdderClass) throws Exception {
         Constructor<?> constructor = longAdderClass.getDeclaredConstructors()[0];
         constructor.setAccessible(true);
         increment = longAdderClass.getMethod("increment");
         increment.setAccessible(true);
         sum = longAdderClass.getMethod("sum");
         sum.setAccessible(true);
         longAdder = constructor.newInstance();
      }

      @Override
      public void increment()
      {
         try {
            increment.invoke(longAdder);
         }
         catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
         }
      }

      @Override
      public long get()
      {
         try {
            return (Long) sum.invoke(longAdder);
         }
         catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
         }
      }
   }
}
