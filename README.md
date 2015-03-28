# 基于 Jedis 的简单客户端 ha 方案

jedis client side load-balance simple implementation, based on spring-data-redis framework.

通常 redis 的部署方式为一个或多个 master，master 后面挂 N 个 slave，slave 只读，master 可读写，为了提高 redis 节点的读取效率，可以将读取请求分配到多个 slave 节点上（即负载均衡）
常见的 redis 负载均衡方案有
* twemproxy
* haproxy
这两个方案的优点是对应用友好，并且也比较容易实现 shard 方案，缺点是需要额外维护一个高可用的代理

我们的场景
* 一个 master，多个 slave，master 可读写，slave 只读
* 没有使用 shard
* 读取请求比写入请求高非常多（每天千万级别的读取）

实现原理
实现方式参考了 spymemcached 的实现。

假设有 N 个 redis 节点
* 同时创建 N 个到 redis 节点的连接池列表
* 需要连接的时候，随机（实际实现为轮询）选择一个连接池获取连接
* 如果从该连接池获取连接失败，将该连接池从连接池列表中移除，添加到重连队列，尝试获取下一个连接池
* 启动一个监控线程监控重连队列中的连接池，如果连接池恢复正常（ping-pong测试），则加回到连接池列表中

很简单，吧?

