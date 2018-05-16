# hikari 连接池 注释 V2.4.5

##1. HikariPoolMXBean HikariConfigMXBean
MBean：是Managed Bean的简称，可以翻译为“管理构件”。  
在JMX中MBean代表一个被管理的资源实例，通过MBean中暴露的方法和属性，外界可以获取被管理的资源的状态和操纵MBean的行为。  
MBean可以看作是JavaBean的一种特殊形式，其定义是符合JavaBean的规范的。
但是MBean在定义是，首先需要定义一个名字结尾为“MBean”的接口，例如：HelloMBean，然后这个接口的实现类的名字必须叫做Hello。 
而MXBean与MBean的区别主要是在于在接口中会引用到一些其他类型的类时，其表现方式的不一样。
在MXBean中，如果一个MXBean的接口定义了一个属性是一个自定义类型，如MemoryMXBean中定义了heapMemoryUsage属性，这个属性是MemoryUsage类型的，
当JMX使用这个MXBean时，这个MemoryUsage就会被转换成一种标准的类型，这些类型被称为开放类型，是定义在javax.management.openmbean包中的。
而这个转换的规则是，如果是原生类型，如int或者是String，则不会有变化，但如果是其他自定义类型，则被转换成CompositeDataSupport类。

MBean中的除基本类型外，其他类型属性值无法获取，MXBean中都可以