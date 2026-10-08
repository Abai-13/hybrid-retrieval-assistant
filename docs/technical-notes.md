# 项目技术笔记 — 校园失物招领与 AI 智能匹配平台

> 记录项目里每个设计决策的来龙去脉：为什么这样写、解决了什么问题、换成别的方案会怎样。按模块整理。

---

## 一、项目架构基础

### Q1: 用户的注册请求发到服务器，从进来到返回，经过了哪些层？每层做了什么？

**说明：**

> "请求到达后：
> 1. **拦截器层**：注册和登录接口被 WebConfig 放行，不需要 token。如果是需要登录的接口，JwtInterceptor 会从请求头取出 token 验签，把 userId 放入 request。
> 2. **Controller 层**：接收请求，用 @Valid 做参数格式校验（是否为空、长度是否够），校验通过则调用 Service。
> 3. **Service 层**：核心业务逻辑——验重名、加密密码、生成 token 等。发现问题抛出 BusinessException，由 GlobalExceptionHandler 统一拦截转为 Result 返回。
> 4. **Mapper 层**：MyBatis-Plus BaseMapper 操作数据库，LambdaQueryWrapper 构造查询条件。
> 5. **返回**：Controller 把结果包成 `Result { code, message, data }` 返回给前端。"

---

### Q2: @RestController 和 @Controller 有什么区别？

**说明：**
> "@RestController = @Controller + @ResponseBody。前者每个方法返回值自动序列化为 JSON 写入响应体，后者需要每个方法上加 @ResponseBody 才会返回 JSON，否则会去找视图模板（JSP/Thymeleaf）。前后端分离项目用 @RestController。"

---

### Q3: GET 和 POST 有什么区别？

**说明：**
> "GET 参数在 URL 里，有长度限制，适合查询操作，幂等（多次请求结果一样）。POST 参数在请求体里，无长度限制，适合创建操作，不幂等（多次请求会创建多条）。项目里注册和发布物品用 POST，查询物品列表用 GET。"

---

## 二、密码安全

### Q4: 用户密码在数据库里怎么存的？为什么不能存明文？

**要点（三句话）：**

> "用 BCrypt 加密存储。不能存明文的原因有三道防线：
>
> **第一道（防拖库）**：数据库泄露时攻击者拿到的是密文，无法直接登录。
>
> **第二道（防彩虹表）**：BCrypt 每次加密随机生成 22 位盐，同一个密码两次加密结果完全不同。攻击者预先算好的明文→密文对照表（彩虹表）直接作废。
>
> **第三道（防暴力破解）**：BCrypt 故意设计得很慢，每次验证约 0.1 秒。对正常登录无感，但攻击者跑字典爆破 10 万个密码要 3 小时，成本不可接受。"

---

### Q5: BCrypt 的盐存在哪里？验证时怎么知道原来用的什么盐？

**说明：**
> "盐嵌在密文里面。BCrypt 密文格式是 `$2a$10$[22位盐][31位哈希]`。验证时 `matches(明文, 密文)` 方法从密文中提取出盐，用同样的盐对用户输入重算一遍，比对结果。不需要单独存盐。"

---

### Q6: 登录时密码错误和用户不存在的提示信息，为什么要统一？

**说明：**
> "安全考虑。如果分别返回'用户不存在'和'密码错误'，攻击者可以先用一批用户名试探，筛选出哪些用户名是真实存在的（撞库），再集中对这些账号暴力破解。统一返回'用户名或密码错误'堵死了这个信息泄露通道。"

---

## 三、JWT 认证

### Q7: 用户登录成功后，服务器怎么知道下一个请求是同一个人？

**说明：**
> "用 JWT（JSON Web Token）。登录时服务器把 userId、username、role 等信息编码进令牌，用密钥签名后返回给前端。后续请求前端在 Authorization 头带上 `Bearer <token>`，服务端 JwtInterceptor 拦截请求，验签名，读出 userId，放入 request attribute。全程不查数据库不查 Redis，无状态认证。"

---

### Q8: JWT 和 Redis Session 方案有什么区别？为什么选 JWT？

**说明：**
> "核心区别：JWT 是无状态的，服务器不存任何东西，令牌本身就装着信息，靠签名保证不被篡改。Redis Session 需要在服务器端存储 session，每次请求查 Redis。
>
> 选 JWT 的原因：
> 1. 前后端分离架构，客户端可能是网页/小程序/App，JWT 天然跨平台
> 2. 不依赖 Redis，部署简单
> 3. 校园场景用户量有限，不需要实时踢人下线的能力
> 4. 如果要主动失效，可以在 Redis 里维护黑名单做补充"

---

### Q9: JWT 的签名有什么用？如果被人截获了 token 安全吗？

**说明：**
> "签名保证两件事：一是 token 内容没有被篡改（改了任何一个字符，签名就对不上），二是 token 确实是我生成的（别人没有我的密钥，签不出来）。
>
> 但 JWT 不防截获——如果攻击者在传输过程中偷到了 token，在 token 过期前他可以冒充用户。防护措施：HTTPS 加密传输 + token 过期时间不要太长 + 敏感操作二次验证。"

---

### Q10: 你的项目里 JwtInterceptor 是怎么工作的？为什么注册和登录接口不被拦截？

**说明：**
> "JwtInterceptor 实现了 HandlerInterceptor 接口，在 preHandle 方法里从 Authorization 请求头取出 `Bearer <token>`，调用 JwtUtil.validate(token) 验签名和过期时间，通过后把 userId 放入 request.setAttribute。
>
> 注册和登录接口在 WebConfig 的 addInterceptors 中通过 `.excludePathPatterns("/api/user/register", "/api/user/login")` 放行，因为这两个接口不需要登录就能访问。"

---

## 四、异常处理

### Q11: 项目里 Service 抛了异常，怎么变成 JSON 返回给前端的？

**说明：**
> "GlobalExceptionHandler 加了 @RestControllerAdvice 注解，在任何 Controller 方法中抛出的异常都会被它拦截：
> - BusinessException → 提取 code 和 message → `Result.fail(code, message)`
> - 参数校验异常 → 提取字段级别的错误信息 → `Result.fail(400, "字段名: 错误原因")`
> - 其他异常 → 记录日志，返回通用错误信息
>
> 好处：Controller 和 Service 不用写 try-catch，代码干净，错误处理集中管理。"

---

### Q12: 为什么要自定义业务异常 BusinessException？

**说明：**
> "Spring 自带的异常没有业务含义。自定义 BusinessException 可以携带业务错误码和中文错误信息，方便 GlobalExceptionHandler 精确区分并返回对应文案。如果直接用 RuntimeException，handler 只能返回笼统的'服务器内部错误'。"

---

## 五、数据库和 MyBatis-Plus

### Q13: 项目里用的是 MyBatis-Plus，它和 MyBatis 有什么区别？

**说明：**
> "MyBatis-Plus 是 MyBatis 的增强工具，不改变 MyBatis 本身：
> - BaseMapper 提供了通用 CRUD（insert、selectById、selectPage 等），常用操作不用写 SQL
> - LambdaQueryWrapper 用 Lambda 表达式构造查询条件，字段名有编译检查，写错了 IDE 直接标红
> - 自动分页插件，传入 Page 对象即可分页
> - 复杂查询仍然可以手写 SQL（Mapper XML 方式）"

---

### Q14: 物品分页查询是怎么实现的？

**说明：**
> "前端传 page（页码）、size（每页条数）、可选的 type/category/keyword。ItemServiceImpl 用 LambdaQueryWrapper 动态组装查询条件，调用 itemMapper.selectPage(new Page(page, size), wrapper) 返回分页结果，包含 records（当前页数据）、total（总条数）、current（当前页码）。"

---

### Q15: 你的 user 表和 item 表是什么关系？为什么不做外键？

**说明：**
> "一对多关系，一个用户可以发布多个物品。item 表里有 user_id 字段关联 user 表的 id。
>
> 不做物理外键的原因：
> 1. 性能：高并发时外键约束需要额外检查，拖慢写入
> 2. 灵活性：分库分表时物理外键会变成阻碍
> 3. 实际项目中主键关联的逻辑靠代码层面保证（Service 层校验），不在数据库层面做物理约束
>
> 索引 idx_user_id 代替了外键的查询加速作用。"

---

## 六、项目设计决策

### Q16: 为什么是单体项目而不是微服务？

**说明：**
> "校园场景用户量有限（几千到几万），单体 + 水平扩展足够。过早微服务会引入分布式事务、服务发现、网络开销等额外复杂度。项目按照包名进行了分层和模块划分（config/common/service/controller），未来如果业务增长，可以按边界拆分为微服务，但现在微服务是过度设计。"

---

### Q17: 为什么不用 Spring Security？

**说明：**
> "Spring Security 功能强大但配置复杂，学习曲线陡。项目只需要 JWT 登录认证，不需要角色权限管理、OAuth2、RememberMe 等功能。只引入 `spring-security-crypto` 做 BCrypt 加密，配合手写 JWT 拦截器，代码量少、逻辑透明、够用。"

---

### Q18: 为什么要统一返回体 Result？

**说明：**
> "前端只需要判断 code === 200，然后取 data。异常不靠 HTTP 500 返回给前端，而是 GlobalExceptionHandler 统一拦截转为 `{code: 异常码, message: 'xxx'}`。前端处理逻辑一致，不用根据不同的 HTTP 状态码写不同的错误处理。同时 Result 带 timestamp，方便排查问题时间线。"

---

### Q19: 如果 AI 接口调用超时 5 秒，用户会怎样？

**说明（当前阶段）：**
> "目前 AI 返回的是占位内容，不存在超时问题。正式接入大模型后，需要加超时控制（RestTemplate 或 HttpClient 设置 connectTimeout 和 readTimeout），超时后返回兜底回复如'AI 服务繁忙，请稍后重试'，而不是让用户干等 5 秒后白屏。这个机制叫服务降级。"

---

## 七、场景设计

### Q20: 如果用户量从 1000 涨到 10 万，现有架构哪里会先出问题？怎么优化？

**说明：**
> "瓶颈在三个地方：
> 1. **数据库**：读写压力增大。优化方向：加索引（现在只有基础索引）、读写分离（主库写从库读）、热点数据 Redis 缓存
> 2. **AI 接口**：每次请求都调大模型，费用高且慢。优化方向：常见问题缓存答案、队列异步处理
> 3. **图片存储**：目前本地存储不可扩展。优化方向：接入阿里云 OSS / 七牛云
>
> 但强调一点：现阶段不需要提前优化，先跑通业务，根据实际监控数据决定优化方向。"

---

### Q21: 两人同时点"认领"同一个物品，怎么防止超卖？

**说明：**
> "数据库层面用乐观锁：item 表加 version 字段，更新时带条件 `UPDATE item SET status='CLAIMED', version=version+1 WHERE id=? AND version=?`。如果 version 已被其他请求改过，update 影响行数为 0，说明已被别人抢先认领，提示用户即可。优点：不需要加锁，性能好，适合冲突率低的场景。"

---

## 八、分页查询 & 条件构造器（08-05 新增）

### Q22: 物品分页查询从前端到数据库是怎么走的？

**说明：**

> "GET 请求到 Controller，参数自动绑定到 ItemPageQuery（page、size、type、keyword、sortOrder）。Service 用 LambdaQueryWrapper 动态拼条件——前端传了 type 就加 eq，传了 keyword 就加 like，没传就不加。sortOrder=ASC 升序，否则默认降序。MyBatis-Plus 自动执行 COUNT + LIMIT 两条 SQL，返回 Page 对象，Controller 打包成 Result。"

---

### Q23: 什么是条件构造器？它解决了什么问题？

**说明（三个好处）：**

> "条件构造器（LambdaQueryWrapper）是用 Java 代码拼 SQL WHERE 条件的工具。三个好处：
>
> 1. **编译时检查**：用方法引用 `Item::getType` 而不是字符串 `"type"`。写错字编译器直接报错，到不了线上。传统 XML 或字符串写法写错字编译能过，上线才炸。
>
> 2. **动态拼条件优雅**：if 判断追加条件，不用手拼 SQL 字符串。前端传了 type 就加 `eq(type)`，传了 keyword 就加 `like(title, keyword)`，没传就不加。
>
> 3. **防 SQL 注入**：内部用 `?` 占位符传参，不是字符串拼接。
>
> 但它跟分页是两回事——条件构造器决定'查什么'，分页插件决定'一次查多少'。"

---

### Q24: 条件构造器怎么做到编译时检查的？

**说明：**

> "传统写法 `wrapper.eq("type", "LOST")` 传的是字符串，编译器只检查语法，不检查字段名是否真的存在。写成 `"typo"` 编译能过，运行到 MySQL 才报错。
>
> 条件构造器 `wrapper.eq(Item::getType, "LOST")` 传的是方法引用，编译器真的去 Item 类里找有没有 `getType` 这个方法。找不到就编译报错，根本到不了运行阶段。"

---

### Q25: selectPage 背后执行了几条 SQL？分别是什么？

**说明：**

> "两条 SQL。一条 `SELECT COUNT(*)` 查总数（算总页数用），一条 `SELECT * FROM item ... LIMIT ?, ?` 查当前页数据。必须两条都查——COUNT 给总数（前端用来算 128÷10=13 页），LIMIT 给当前页的数据列表。"

---

### Q26: 分页插件是必须配置的吗？不配会怎样？

**说明：**

> "必须配置。MybatisPlusConfig 里注册了 PaginationInnerInterceptor，告诉 MyBatis-Plus 这是 MySQL 数据库。不配的话 selectPage 不会自动加 LIMIT，相当于一次查出全部数据，分页失效。"

---

## 九、权限控制 & 拦截器深入（08-05 新增）

### Q27: GET 请求和 POST 请求在 JWT 拦截器里的处理有什么不同？

**说明：**

> "GET 请求 token 可选——有 token 就解析出 userId 放入 request（方便展示'我的发布'等个性化内容），没有也放行（游客也能浏览物品列表）。
>
> POST/PUT/DELETE 请求必须带有效 token——没有或过期直接返回 401。这是区分'浏览'和'写操作'的安全策略。"

---

### Q28: 修改物品状态怎么保证只有发布者本人能改？

**说明：**

> "两层防护：
> 1. **JWT 拦截器**：验 token 保证是已登录用户（确认'你是谁'）
> 2. **Service 层**：查出物品的发布者 userId，和当前用户 userId 对比，不一致抛异常（确认'这物品是你的'）
>
> 只有第一层的话，任何一个登录用户都能改别人的物品。第二层用 `.equals()` 而不是 `==` 比较 Long 类型，因为包装类型用 `==` 比较的是对象地址而不是值。"

---

## 十三、认领闭环（10-08 新增）

### Q37: 这次改了 AI 的候选池，为什么不重跑评测？

**说明：**
> "因为**这次改动在评测数据上等价于没改**。候选池从 `status = 'UNCLAIMED'` 放宽成
> `status IN ('UNCLAIMED','CLAIMING')`，而当时库里 **CLAIMING 一条都没有**
> （FOUND 共 64064 条，全是 UNCLAIMED）—— 两个条件返回的是同一批行。
> 跑 146 条只会测出模型的随机波动，测不到这个改动。
>
> 换了个能直接证伪的方式：用新写的认领接口把一条真实物品推到 CLAIMING，再调匹配接口 ——
> 它仍然出现在 50 条候选里；按旧条件它会被排除。**这才是这次改动买到的行为。**
>
> 还有一层：要横比就得控制变量。本机配的模型是 Qwen2.5-7B，而 94.5% 那个基线是 DeepSeek 跑的，
> 直接比会得出「大幅下降」的假结论。**换过模型跑出来的数字，和没换过的不是一个坐标系。**"

### Q38: 认领列表为什么不做缓存？

**说明：**
> "物品列表能缓存，是因为它读多写少、而且是全站热点的**公共**数据。
> 认领列表正相反：它是**每个人自己的私有小列表**，读得不多，数据还每动一次就变。
> 更关键的是成本 —— 加一个缓存就要在**认领和物品两侧所有写路径**上成对清缓存，
> 少清一处就是「状态变了但页面看不到」，而收益只是省掉一次几十毫秒的查询。这笔账不划算。
>
> **缓存不是越多越好，要看「读写比值」和「失效的复杂度」。**"

### Q39: 认领为什么要把联系方式藏起来？

**说明：**
> "因为**不藏的话，整套认领流程就没有存在意义** —— 失主在详情页直接看到电话就能联系，
> 谁还会去走「申请 → 填特征 → 等审核」？
>
> 规则：只有**物品发布者本人**和**认领审核已通过的人**能看到。其他所有人
> （游客、审核中的申请人、被驳回的人）都看不到。审核中的申请人能看到自己「审核中」的状态，
> 但看不到号码。
>
> 配套细节：返回体要带一个 `contactVisible` 字段。前端得能分清「被规则藏起来了」和
> 「发布者根本没填」，否则只能把两种情况都显示成"未填写"，等于把规则说反了。"

### Q40: 详情接口为什么返回视图对象，而不直接改实体字段？

**说明：**
> "因为 `itemDetail` 这个缓存里存的**就是实体对象本身** —— `itemService.getById(id)`
> 命中缓存时返回的是缓存里那一个实例，不是副本。如果为了藏联系方式去写
> `item.setContact(null)`，改的就是缓存里的对象，结果是**发布者本人之后也看不到联系方式了**，
> 而且要等到 TTL 过期才恢复。
>
> 所以做法是**从实体复制出一个视图对象再裁剪**，原始实体一个字都不动。
> 这条规则被单测钉住了（`ItemDetailViewTest` 专门断言"原实体的联系方式没被改掉"）。
>
> 可以推广成一句话：**凡是带缓存的对象，返回给外部之前都不要原地改它。**"

### Q41: 用数据库唯一键兜住业务规则之后，为什么还要专门翻译它的异常？

**说明：**
> "因为**唯一键冲突是个 500**。
>
> 认领流程里有两条规则交给数据库兜底（应用层先查一遍给"人话"，数据库负责并发那一层）：
> 同一人对同一物品只能有一条活跃申请、同一物品最多只能有一条「已通过」的申请。
> 这两条都用生成列 + 唯一索引实现。
>
> 这个设计本身没问题 —— 问题出在**异常没人接**。压测式地连发 6 次 approve，
> 全返回 `500 服务器内部错误`，日志里是
> `DuplicateKeyException: Duplicate entry '520727-1' for key 'uk_item_approved'`。
> 它一路冒到全局兜底，被当成"系统故障"报出去了。
>
> 可「这个物品已经有人通过了」明明是**客户端应该知道、也能处理**的事，
> 按约定 4 就该是 400/409。修法是在改状态那一处 catch 住 `DuplicateKeyException`，
> 翻成 409 加一句人话。
>
> 教训：**数据库约束是最后一道防线，但它的报错方式是"异常"，不是"业务结果"。
> 用了它兜底，就必须负责把它的异常翻译回来。**
> 顺带一提，这个 bug 单测抓不到 —— Mockito 里的 Mapper 永远不会抛唯一键冲突，
> 只有真连数据库跑才看得见。"

### Q42: 审核通过之后，为什么物品状态还是「认领中」？

**说明：**
> "因为**审核通过 ≠ 东西已经交出去了**。这两件事中间隔着一段现实时间：
> 双方要约时间、线下核对、见面交接，中间可能联系不上、可能临时反悔。
> 如果审核一通过就把物品标成「已认领」，全站立刻显示"已归还"，
> 但实际上两个人可能根本还没见到面 —— 这个状态是假的。
>
> 所以状态机是：
> `PENDING → APPROVED`（发布者认可这条申请）→ `COMPLETED`（申请人确认收到，物品才变 CLAIMED）。
>
> 同理，**驳回**之后如果这条物品上再没有任何活跃申请了，
> 物品要退回「待认领」—— 否则它会永远停在「认领中」，
> 下一个失主以为有人在走流程，实际上早就没人了。"

---

> 📝 **维护约定**：新增模块时补一节，按「为什么这样设计 / 怎么实现 / 边界情况」记录。

---

## 十、Spring 启动原理 & 依赖注入（08-06 新增）

### Q29: @SpringBootApplication 到底做了什么？启动流程是怎样的？

**说明：**

> "它是三个注解的合体：
> 1. @SpringBootConfiguration — 标记配置类
> 2. @ComponentScan — 扫描 @Service/@Controller/@Component 登记为 Bean
> 3. @EnableAutoConfiguration — 根据 jar 包自动创建额外 Bean（如 DataSource、SqlSessionFactory）
>
> 启动两阶段：登记阶段（扫描 + 自动装配 → 花名册，不创建对象），创建注入阶段（按花名册逐个创建，发现缺谁就先把谁造好塞进去）。
>
> ⚠️ 常见混淆点：Maven 依赖（pom.xml 下载 jar 包）≠ 依赖注入（@Autowired 串 Bean）。两个中文都叫'依赖'，完全不同。"

### Q30: 什么是依赖注入？和 @Autowired 是什么关系？

**说明：**

> "依赖注入就是 Spring 自动给 @Autowired 字段赋值。比如 UserController 声明了 `@Autowired private UserService userService`，Spring 创建 UserController 时发现它需要 UserService，就先去创建 UserService，再塞进来。全程不用 `new UserServiceImpl()`。
>
> 这不是独立的第三步，而是在创建 Bean 的过程中伴随发生的——边造边注，不是全造完再统一注。"

---

## 十一、排序功能实战（08-06 完成）

### Q31: 物品分页的排序是怎么实现的？

**说明：**

> "ItemPageQuery 新增 sortOrder 字段，前端可传 ASC（升序）或 DESC（降序）。Service 层用 if-else 判断：
> - 传 ASC → wrapper.orderByAsc(Item::getCreatedAt)
> - 否则（DESC 或没传）→ wrapper.orderByDesc(Item::getCreatedAt)，默认降序
>
> 注意点：用 `"ASC".equals(query.getSortOrder())` 而不是 `query.getSortOrder().equals("ASC")`，防止 null 抛空指针。Spring 自动把 URL 参数绑定到 DTO 字段，中文参数经 URL 编码后自动解码，全程不用手动处理。"

---

## 十二、AI 大模型接入（08-08 完成）

### Q32: 项目里怎么接入大模型的？从前端发请求到拿到 AI 回答，数据怎么流的？

**说明：**
> "前端 POST 到 /api/ai/chat，经过 JWT 拦截器验身份、限流拦截器查次数，然后到 AiController。Controller 调用 AiService，Service 用 RestTemplate 发 POST 请求到 DeepSeek API，拿到返回 JSON 后从 choices[0].message.content 取出回答文本，返回给前端。异常时返回降级文案，不抛给用户。"

### Q33: 为什么要加超时控制？

**说明：**
> "大模型 API 响应可能很慢（几十秒），不设超时的话：一是请求线程一直阻塞，占用服务器资源；二是用户浏览器一直转圈，体验很差。我设了 3 秒连接超时和 30 秒读取超时，超时后返回'AI 服务繁忙，请稍后重试'。30 秒对 AI 生成来说足够，对用户来说不会干等太久。"

### Q34: 限流是怎么实现的？为什么用内存而不是 Redis？

**说明：**
> "用 ConcurrentHashMap 存每个用户最近 1 分钟的请求时间列表。每次请求先清理超过 60 秒的旧记录，再判断剩余条数是否达到 5 次上限。达到就返回 429，不调用 AI API。选内存的原因：校园场景用户量小，内存足够，不引入额外依赖。如果未来需要分布式部署，把 Map 换成 Redis 的 incr + expire，判断逻辑不变。"

### Q35: API Key 怎么保护？为什么不写在配置文件里？

**说明：**
> "用环境变量 `${DEEPSEEK_API_KEY}` 占位，真实 Key 在本地系统环境变量中。配置文件提交到 GitHub 时只有占位符，不会被扫到。两道防线：第一道是环境变量隔离，第二道是限流（即使 Key 泄露，每个用户每分钟最多 5 次，无法批量刷）。"

### Q36: 大模型接口挂了怎么办？

**说明：**
> "Service 层做了异常降级：连接超时、读超时、或者 API 返回非 200，都 catch 住返回友好提示，不抛给全局异常处理器。这样即使 DeepSeek 服务器挂了、网络不通了，用户看到的是'AI 服务繁忙，请稍后重试'，而不是 500 错误页。这是服务降级的基本思想。"
