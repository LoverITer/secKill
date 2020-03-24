本次的项目实践是使用 SpringBoot+Mybatis 对电商项目中秒杀模块的实现. 目前阶段使用4台云服务器来做分布式集群，提高并发处理性能.

## 后端技术

- SpringBoot

- Mybatis

- Redis

- Rocketmq

## 使用到的第三方工具

- Mybatis-generator

- joda-time

- lombok

- guava

## 集群拓扑图

部署环境为4台服务器，一台作为nginx反向代理服务器，两台作为WebServer服务器，一台作为数据仓库, 拓扑图如下:

![pic](https://ae01.alicdn.com/kf/Hcb1e9d3507b840dd8efad801f4fe5d67r.png)

## 架构层次图
![pic](https://ae01.alicdn.com/kf/Ha41f99ff143a42099dfc4082cddfc8425.png)


### 接入层
- 接入层模型 ViewObject: 
    - View Object与前端对接的模型, 隐藏内部实现，供展示的聚合类型
- 业务层 Model: 
    - 领域模型，贫血+调用服务来提供输出能力
- DataObject 
    - 为领域模型的具体存储形式，一个领域模型由一个或多个DataObject组成，每一个DataObject对应一张表，以ORM方式操作数据库.

- 自定义异常类属性：
    - errCode: 业务逻辑错误码 ( 如：1开头的错误码为通用错误，2开头的错误码为用户相关错误，3开头的错误码为交易信息相关错误等.. )

    - errMsg: 错误提示

### 服务层

- 用户模块:
    - 注册(使用手机获取验证码短信来注册)
    - 登陆

- 交易模块:
    - 下单操作

- 商品模块:
    - 创建商品
    - 展示商品

- 促销模块
    - 维护促销商品列表

## 优化手段:

- ### 使用多级缓存提高查询性能.
    - Redis集中式缓存:
        - 单机配置 (本次使用)
        - sentinal哨兵模式 （了解学习
        - 集群cluster模式 （了解学习
    
    - 本地热点数据缓存
        - 特点:
            - 使用的是JVM的缓存来对热点数据进行存储；因为当数据库对缓存中的数据进行修改后会造成脏读，所以本地缓存的过期时间要设的尽可能的短. 
        - 第三方库:
            - Guava:
                - 提供了这样一种可控制大小和超时时间的线程安全的Map
                - 可配置lru策略（置换算法配置）
    - nginx配合lua脚本实现第三级缓存
        - nginx lua插载点
            - `init_by_lua`: 系统启动时调用
            - `init_workder_by_lua`: worker进程启动时调用
            - `set_by_lua`: nginx变量用复杂lua return
            - `rewrite_by_lua`: 重写url规则
            - `access_by_lua`: 权限验证阶段
            - `content_by_lua`: 内容输出节点
        - 使用Openresty:
            - 共享内存字典，所有worker进程均可见，LRU淘汰. 
                - 速度快，但受内存限制，且会脏读.
            - 支持Redis
                - 会多一次网络查询开销去访问数据库服务器上的redis，好处是能够保证缓存数据一致性. （往往这里的缓存是只读操作，可以访问redis的从库，从而减小对主库的压力。
            
- ### 交易验证优化(下单环节的优化)
    - 用户风控, 活动校验等环节能用读缓存解决的就尽量不要读数据库.
    - 库存的行锁优化
        1. 将扣减库存操作放到redis中操作:
            - 预先将库存信息刷入redis中
            - 落单后在redis中减库存
            - 使用异步消息队列Rocketmq异步同步数据库内，从而保证库存数据库最终一致性保证
        2. 问题的发现与解决: 
            - 使用消息队列异步同步数据库出现问题时，仍然无法保证缓存和数据库的一致性，例如:
                - 异步消息发送失败
                - 扣减操作失败
                - 下单失败无法正确补回库存
            - 解决办法，将 “创建订单事务提交成功” 和 “消息发出” 这两个动作再次绑定成一个事务，即可解决，也就是说，发一条事务型消息。

        3. 问题的发现与解决:
            - 遇到问题:
                - 在定期checkLocalTransaction的UNKNOWN状态的消息时，仅以当前传入消息的参数还不够(仅传入了ItemId和扣减个数amount)。
            - 解决问题：引入库存流水：
                - 数据类型：
                    - 主业务数据(例如ItemModel)
                    - 操作型数据(记录某个操作，便于追踪该操作的状态，**保证异步操作的正确执行(例如具备回滚的能力等等)**), 例如配置，中间状态的记录，等等
        4. 问题的发现与解决:
            - redis缓存在某些时刻仍无法保证与数据库一致性, 例如:
                - 在缓存中扣减之后，出现异常，回滚之后，缓存里的值并没有被回滚.
                - 在消息队列中，若线程在createOrder之后挂掉了，既无法进入catch代码块，也没有跳出try块，消息的状态将用于处于UNKONWN的状态.. 
            - 问题的解决:
                - 严格的缓存, db一致性将是以时间开销为代价的，因此，如果仅仅是保证缓存与数据库的最终一致性，可以根据具体的业务场景来决定高可用技术的实现，例如在本次的秒杀场景中的原则为：宁可少卖，不能超卖。
                - 解决方法: 
                    - 因为宁可少卖，不能多卖的业务场景，可以允许redis比实际数据库中少.
                    - 超时自动释放（若订单流水在一定时间内没有从unkonwn状态转变，那么将会将此订单作废）
                    - 分布式锁（日后看
        5. 库存售罄
            - 加库存售罄标示；售罄后不去操作后续流程；售罄后通知各系统售罄；回补上新；