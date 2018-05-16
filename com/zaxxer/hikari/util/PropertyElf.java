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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;

/**
 * A class that reflectively sets bean properties on a target object.
 *
 * @author Brett Wooldridge
 *
 * 通过反射向目标对象设置属性值工具类
 */
public final class PropertyElf
{
   private static final Logger LOGGER = LoggerFactory.getLogger(PropertyElf.class);

   private static final Pattern GETTER_PATTERN = Pattern.compile("(get|is)[A-Z].+");

   public static void setTargetFromProperties(final Object target, final Properties properties)
   {
      if (target == null || properties == null) {
         return;
      }
      //目标对象的所有方法
      List<Method> methods = Arrays.asList(target.getClass().getMethods());
      //所有属性的键
      Enumeration<?> propertyNames = properties.propertyNames();
      //遍历所有属性的键
      while (propertyNames.hasMoreElements()) {
         Object key = propertyNames.nextElement();
         String propName = key.toString();
        
         Object propValue = properties.getProperty(propName);
         /*
          * Properties继承HashTable
          * public String getProperty(String key) {
	  *     Object oval = super.get(key); //HashTable.get(Object key)
	  *     String sval = (oval instanceof String) ? (String)oval : null;
	  *     return ((sval == null) && (defaults != null)) ? defaults.getProperty(key) : sval;
	  * }
	  * String getProperty(String key)方法中，如果HashTable中存储的值不是String类型会返回null，使用get(Objeject key)方法获取其他类型的值(使用Properties.put(k,v)设置的值)
          */
         if (propValue == null) {
            propValue = properties.get(key);
         }
	 //如果目标对象是HikariConfig实例并且键依"dataSource."开头，键去掉"dataSource.",使用HikariConfig的方法设置数据源属性
         if (target instanceof HikariConfig && propName.startsWith("dataSource.")) {
            ((HikariConfig) target).addDataSourceProperty(propName.substring("dataSource.".length()), propValue);
         }
         //否则，使用反射的方法设置目标对象的属性
         else {
            setProperty(target, propName, propValue, methods);
         }
      }
   }

   /**
    * Get the bean-style property names for the specified object.
    *
    * @param targetClass the target object
    * @return a set of property names
    *
    * 获取目标类的bean-style的属性值
    */
   public static Set<String> getPropertyNames(final Class<?> targetClass)
   {
      HashSet<String> set = new HashSet<>();
      Matcher matcher = GETTER_PATTERN.matcher("");
      //遍历类所有方法
      for (Method method : targetClass.getMethods()) {
         String name = method.getName();
         //条件：参数个数：0，匹配正则 (get|is)[A-Z].+
         if (method.getParameterTypes().length == 0 && matcher.reset(name).matches()) {
            //去掉get 或 is
            name = name.replaceFirst("(get|is)", "");
            try {
               //属性的set方法存在
               if (targetClass.getMethod("set" + name, method.getReturnType()) != null) {
                  //将第一个字母小写，加入到结果集合中
                  name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
                  set.add(name);
               }
            }
            catch (Exception e) {
               continue;
            }
         }
      }

      return set;
   }
   /**
    * 获取目标对象的指定的属性名称的值
    * @param propName
    * @param target
    * @return Object
    */
   public static Object getProperty(final String propName, final Object target)
   {
      try {
         //根据属性名称拼接get方法，用反射方法调用
         String capitalized = "get" + propName.substring(0, 1).toUpperCase() + propName.substring(1);
         Method method = target.getClass().getMethod(capitalized);
         return method.invoke(target);
      }
      catch (Exception e) {
         try {
            //如果get方法没有，拼接is方法，用反射方法调用
            String capitalized = "is" + propName.substring(0, 1).toUpperCase() + propName.substring(1);
            Method method = target.getClass().getMethod(capitalized);
            return method.invoke(target);
         }
         catch (Exception e2) {
            return null;
         }
      }
   }

   /**
    * 复制一个新的Properties对象
    */
   public static Properties copyProperties(final Properties props)
   {
      Properties copy = new Properties();
      for (Map.Entry<Object, Object> entry : props.entrySet()) {
         copy.setProperty(entry.getKey().toString(), entry.getValue().toString());
      }
      return copy;
   }

   /**
    * 设置目标对象的属性值
    * @param target 目标对象
    * @param propName 属性名称
    * @param propValue 属性值
    * @param methods 目标对象的所有方法
    */
   private static void setProperty(final Object target, final String propName, final Object propValue, final List<Method> methods)
   {
      Method writeMethod = null;
      //构建set方法字符串，（set[A-Z].+）
      String methodName = "set" + propName.substring(0, 1).toUpperCase() + propName.substring(1);

      for (Method method : methods) {
         //遍历寻找参数个数是1且名称相同的方法
         if (method.getName().equals(methodName) && method.getParameterTypes().length == 1) {
            writeMethod = method;
            break;
         }
      }

      if (writeMethod == null) {
      	 //如果没有找到方法，构建set+全大写字符串
         methodName = "set" + propName.toUpperCase();
         for (Method method : methods) {
            if (method.getName().equals(methodName) && method.getParameterTypes().length == 1) {
               writeMethod = method;
               break;
            }
         }
      }

      if (writeMethod == null) {
      	 //没有找到方法，抛出异常
         LOGGER.error("Property {} does not exist on target {}", propName, target.getClass());
         throw new RuntimeException(String.format("Property %s does not exist on target %s", propName, target.getClass()));
      }

      try {
      	 //获取参数类型，按类型反射调用设置属性
         Class<?> paramClass = writeMethod.getParameterTypes()[0];
         if (paramClass == int.class) {
            writeMethod.invoke(target, Integer.parseInt(propValue.toString()));
         }
         else if (paramClass == long.class) {
            writeMethod.invoke(target, Long.parseLong(propValue.toString()));
         }
         else if (paramClass == boolean.class || paramClass == Boolean.class) {
            writeMethod.invoke(target, Boolean.parseBoolean(propValue.toString()));
         }
         else if (paramClass == String.class) {
            writeMethod.invoke(target, propValue.toString());
         }
         else {
            writeMethod.invoke(target, propValue);
         }
      }
      catch (Exception e) {
         LOGGER.error("Failed to set property {} on target {}", propName, target.getClass(), e);
         throw new RuntimeException(e);
      }
   }
}
